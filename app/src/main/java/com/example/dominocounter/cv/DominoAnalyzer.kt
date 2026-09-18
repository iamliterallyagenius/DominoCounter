package com.example.dominocounter.cv

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the overlay draws. Cycle it by tapping the screen.
 */
enum class DebugMode {
    /** Normal operation, no overlay, no extra cost. */
    OFF,

    /** The binary mask exactly as `findContours` receives it. */
    MASK,

    /** The analysis frame with every contour colour-coded by why it passed or failed. */
    DETECTIONS;

    fun next(): DebugMode {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }
}

/**
 * What the UI gets after each analysed frame.
 *
 * @param stableTotal  the filtered value to display
 * @param rawTotal     this frame's unfiltered measurement (diagnostics)
 * @param tileCount    how many valid domino tiles were segmented
 * @param frameTimeMs  wall-clock cost of the whole pipeline
 * @param changed      true when [stableTotal] just changed
 * @param debugBitmap  overlay frame, or null when [DebugMode.OFF]. REUSED between
 *                     frames — draw it, don't retain it.
 */
data class AnalysisResult(
    val stableTotal: Int,
    val rawTotal: Int,
    val tileCount: Int,
    val frameTimeMs: Long,
    val changed: Boolean,
    val debugBitmap: Bitmap? = null
)

/**
 * Pure geometric domino scorer. No models, no inference, no GPU delegates —
 * segment → contours → shape filter → photometric gate → warp → blob count.
 *
 * Runs entirely on the single-threaded analysis executor supplied by CameraX.
 * [onResult] is invoked on that same background thread; marshal to the main
 * thread yourself at the call site.
 */
class DominoAnalyzer(
    private val config: DetectionConfig = DetectionConfig(),
    private val onResult: (AnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    /** Written from the UI thread, read on the analysis thread. */
    @Volatile
    var debugMode: DebugMode = DebugMode.OFF

    private val stabilizer = StableCounter(config.stableFrames)
    private val pipDetector = PipDetector(config)

    /** Reused across frames so the overlay doesn't churn the heap at 30 fps. */
    private var debugBitmap: Bitmap? = null

    private data class FrameStats(
        val tileCount: Int,
        val totalPips: Int,
        val debugBitmap: Bitmap?
    )

    private data class TileCandidate(val rect: RotatedRect, val area: Double)

    /**
     * One candidate, warped upright with a margin of its surroundings included.
     *
     * @param face        the tile itself, canonically sized — this is what gets counted
     * @param faceMean    mean brightness of the tile face
     * @param surroundMean mean brightness of the band around it (== faceMean if disabled)
     * @param brightRatio fraction of face pixels that are genuinely white
     * @param inverse     face-space → frame-space mapping, for debug drawing only
     */
    private class TileSample(
        val face: Mat,
        val faceMean: Double,
        val surroundMean: Double,
        val brightRatio: Double,
        val inverse: Mat?
    )

    override fun analyze(image: ImageProxy) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val stats = process(image)
            val changed = stabilizer.submit(stats.totalPips)
            onResult(
                AnalysisResult(
                    stableTotal = stabilizer.stable,
                    rawTotal = stats.totalPips,
                    tileCount = stats.tileCount,
                    frameTimeMs = SystemClock.elapsedRealtime() - startedAt,
                    changed = changed,
                    debugBitmap = stats.debugBitmap
                )
            )
        } catch (t: Throwable) {
            // Never let one bad frame kill the analyzer thread.
            Log.e(TAG, "Frame analysis failed", t)
        } finally {
            // Non-negotiable: without close() the STRATEGY_KEEP_ONLY_LATEST queue
            // fills up and the camera stops delivering frames entirely.
            image.close()
        }
    }

    // =====================================================================
    //  Pipeline
    // =====================================================================
    private fun process(image: ImageProxy): FrameStats = matScope {

        val mode = debugMode                       // snapshot: it can change mid-frame
        val rotation = image.imageInfo.rotationDegrees

        // 1. Frame → grayscale (Y plane, zero colour conversion).
        val source = image.toGrayscaleMat().managed()

        val oriented = if (config.rotateFrameUpright) {
            source.rotatedBy(rotation).managed()
        } else {
            source
        }

        // 2. Downscale so cost is bounded regardless of the sensor resolution.
        val work = downscaled(oriented)

        // 3. Gaussian blur — suppresses sensor noise so the segmenter doesn't
        //    shatter flat surfaces into speckle.
        val blurred = Mat().managed()
        val blurK = oddAtLeast(config.blurKernel, 3).toDouble()
        Imgproc.GaussianBlur(work, blurred, Size(blurK, blurK), 0.0)

        // 4. One Otsu pass defines the scene's own light/dark split. Everything that
        //    needs a brightness reference is derived from it, so the whole detector
        //    re-tunes itself to the lighting on every single frame.
        val otsu = otsuLevel(blurred)
        val whiteLevel = min(250.0, otsu + config.brightAboveOtsu)

        // 5. Segment the tiles out of the background.
        val mask = buildTileMask(blurred, otsu, whiteLevel)

        // Debug canvas: the grayscale frame promoted to RGB so we can draw in colour.
        val canvas = if (mode == DebugMode.DETECTIONS) {
            Mat().managed().also { Imgproc.cvtColor(work, it, Imgproc.COLOR_GRAY2RGB) }
        } else {
            null
        }

        // 6. Contours — RETR_EXTERNAL because we only want tile outlines,
        //    not the pip holes inside them.
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat().managed()
        Imgproc.findContours(
            mask, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        contours.forEach { it.managed() }

        // 7. Shape filtering: area, aspect ratio, solidity, corner count.
        //    In DETECTIONS mode every rejection is drawn in the colour of its reason,
        //    so you can see WHICH filter threw your domino away.
        val frameArea = (work.rows() * work.cols()).toDouble()
        val minArea = frameArea * config.minTileAreaRatio
        val maxArea = frameArea * config.maxTileAreaRatio

        val candidates = ArrayList<TileCandidate>(16)
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minArea || area > maxArea) {
                // Only draw near-misses; sub-pixel specks would bury the screen.
                if (canvas != null && area > minArea * 0.25) {
                    outline(canvas, contour, COLOR_REJECT_AREA)
                }
                continue
            }

            val curve = MatOfPoint2f(*contour.toArray()).managed()
            val rect = Imgproc.minAreaRect(curve)

            val longSide = max(rect.size.width, rect.size.height)
            val shortSide = min(rect.size.width, rect.size.height)
            if (shortSide < 8.0) continue

            val aspect = longSide / shortSide
            if (aspect < config.minAspectRatio || aspect > config.maxAspectRatio) {
                canvas?.let { outline(it, contour, COLOR_REJECT_ASPECT) }
                continue
            }

            // Solidity: how much of its own bounding box the contour actually fills.
            // A domino ≈ 1.0; hands, shadows and merged blobs fall well below.
            val solidity = area / (longSide * shortSide)
            if (solidity < config.minSolidity) {
                canvas?.let { outline(it, contour, COLOR_REJECT_SOLIDITY) }
                continue
            }

            // Corner count: a tile outline simplifies to ~4 vertices. Table texture,
            // cables and wood grain simplify to many more.
            if (config.requireQuadrilateral && !looksRectangular(curve)) {
                canvas?.let { outline(it, contour, COLOR_REJECT_CORNERS) }
                continue
            }

            candidates.add(TileCandidate(rect, area))
        }

        // Biggest first, then hard-capped: if the mask exploded into noise we
        // still do a bounded amount of work per frame.
        candidates.sortByDescending { it.area }
        val considered = if (candidates.size > config.maxTilesPerFrame) {
            candidates.subList(0, config.maxTilesPerFrame)
        } else {
            candidates
        }

        // 8. Warp upright (with surroundings), photometric gate, then count pips.
        var tiles = 0
        var pips = 0
        for (candidate in considered) {
            val sample = sampleTile(work, candidate.rect, whiteLevel, wantInverse = canvas != null)
                ?: continue

            // A domino is white AND brighter than whatever it lies on. Wood grain and
            // stitching pass every SHAPE test but fail both of these.
            if (config.requireWhiteFace && !isWhiteTile(sample)) {
                canvas?.let { drawRect(it, candidate.rect, COLOR_REJECT_BRIGHTNESS, 2) }
                continue
            }

            val result = pipDetector.countPips(sample.face, collectCenters = canvas != null)
            if (result.count < 0) {
                // Survived everything but produced an impossible pip count.
                canvas?.let { drawRect(it, candidate.rect, COLOR_REJECT_PIPS, 2) }
                continue
            }
            tiles++
            pips += result.count

            if (canvas != null) {
                drawRect(canvas, candidate.rect, COLOR_ACCEPT, 3)
                label(canvas, candidate.rect, result.count)
                sample.inverse?.let { inv ->
                    for (centre in mapToFrame(result.centers, inv)) {
                        Imgproc.circle(canvas, centre, 4, COLOR_PIP, -1)
                    }
                }
            }
        }

        // 9. Hand the overlay to the UI. Rotated here (display only) because the
        //    pipeline itself runs in sensor orientation.
        val bitmap = when (mode) {
            DebugMode.OFF -> null
            DebugMode.MASK -> toBitmap(mask.rotatedBy(rotation).managed())
            DebugMode.DETECTIONS -> toBitmap(canvas!!.rotatedBy(rotation).managed())
        }

        FrameStats(tileCount = tiles, totalPips = pips, debugBitmap = bitmap)
    }

    // =====================================================================
    //  Segmentation
    // =====================================================================

    /** Otsu's optimal light/dark split for this frame, in grey levels. */
    private fun MatScope.otsuLevel(src: Mat): Double {
        val scratch = Mat().managed()
        return Imgproc.threshold(
            src, scratch, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU
        )
    }

    /**
     * Produces the binary mask that `findContours` walks.
     *
     * EDGE mode is background-independent: it keys on the *contrast boundary* at the
     * tile border instead of assuming the tile is the brightest object in frame. The
     * brightness gate then throws away every edge that isn't adjacent to something
     * white, which is what keeps grain and fabric out of the contour list entirely.
     */
    private fun MatScope.buildTileMask(blurred: Mat, otsu: Double, whiteLevel: Double): Mat {
        val mask = Mat().managed()

        when (config.segmentationMode) {

            SegmentationMode.EDGE -> {
                Imgproc.Canny(
                    blurred, mask,
                    otsu * config.cannyLowRatio * config.cannyHighScale,
                    otsu * config.cannyHighScale
                )

                // Gate BEFORE closing: an edge in a mid-tone region can't then be
                // welded back into a loop by the morphology step.
                if (config.gateEdgesByBrightness) {
                    val white = Mat().managed()
                    Imgproc.threshold(blurred, white, whiteLevel, 255.0, Imgproc.THRESH_BINARY)

                    // The tile's own border sits just OUTSIDE the white area, so the
                    // white region has to be grown before it can act as a stencil.
                    val gateK = oddAtLeast(config.brightGateDilate, 3).toDouble()
                    val gateKernel = Imgproc.getStructuringElement(
                        Imgproc.MORPH_ELLIPSE, Size(gateK, gateK)
                    ).managed()
                    Imgproc.dilate(white, white, gateKernel)
                    Core.bitwise_and(mask, white, mask)
                }

                // Close + dilate so the tile outline becomes one unbroken loop.
                // findContours then traces that loop and returns the tile boundary;
                // the pips inside are interior edges and RETR_EXTERNAL ignores them.
                val edgeK = oddAtLeast(config.edgeCloseKernel, 3).toDouble()
                val kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT, Size(edgeK, edgeK)
                ).managed()
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
                if (config.edgeDilateIterations > 0) {
                    Imgproc.dilate(
                        mask, mask, kernel,
                        Point(-1.0, -1.0), config.edgeDilateIterations
                    )
                }
            }

            SegmentationMode.ADAPTIVE -> {
                Imgproc.adaptiveThreshold(
                    blurred,
                    mask,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY,
                    adaptiveBlockSize(blurred),
                    config.adaptiveConstant
                )
                closeMask(mask)
            }

            SegmentationMode.OTSU -> {
                Imgproc.threshold(
                    blurred, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU
                )
                closeMask(mask)
            }
        }
        return mask
    }

    /** Seals pip holes and hairline gaps so each tile is one solid blob. */
    private fun MatScope.closeMask(mask: Mat) {
        val closeK = oddAtLeast(config.morphCloseKernel, 3).toDouble()
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(closeK, closeK)
        ).managed()
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
    }

    /**
     * The "it's a white tile, not a plank" test.
     *
     * Two independent conditions, because each catches what the other misses:
     *  - most of the face must actually be white (grain is mid-tone throughout)
     *  - the face must be clearly brighter than the band around it (a grain rectangle
     *    is exactly as bright as the wood surrounding it; a domino never is)
     */
    private fun isWhiteTile(sample: TileSample): Boolean {
        if (sample.brightRatio < config.minBrightPixelRatio) return false
        val contrast = sample.faceMean - sample.surroundMean
        return contrast >= config.minSurroundContrast
    }

    // =====================================================================
    //  Debug drawing
    // =====================================================================

    private fun outline(canvas: Mat, contour: MatOfPoint, color: Scalar) {
        Imgproc.drawContours(canvas, listOf(contour), -1, color, 2)
    }

    private fun drawRect(canvas: Mat, rect: RotatedRect, color: Scalar, thickness: Int) {
        val corners = Array(4) { Point() }
        rect.points(corners)
        for (i in 0 until 4) {
            Imgproc.line(canvas, corners[i], corners[(i + 1) % 4], color, thickness)
        }
    }

    private fun label(canvas: Mat, rect: RotatedRect, pips: Int) {
        Imgproc.putText(
            canvas, pips.toString(),
            Point(rect.center.x - 12.0, rect.center.y + 10.0),
            Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, COLOR_ACCEPT, 3
        )
    }

    /** Maps pip centres from warped-face space back onto the analysis frame. */
    private fun MatScope.mapToFrame(centers: List<Point>, inverse: Mat): List<Point> {
        if (centers.isEmpty()) return emptyList()
        val src = MatOfPoint2f(*centers.toTypedArray()).managed()
        val dst = MatOfPoint2f().managed()
        Core.perspectiveTransform(src, dst, inverse)
        return dst.toList()
    }

    /** Reuses one ARGB_8888 buffer; only reallocates when the frame size changes. */
    private fun toBitmap(mat: Mat): Bitmap {
        val existing = debugBitmap
        val target = if (existing == null || existing.width != mat.cols() || existing.height != mat.rows()) {
            Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
                .also { debugBitmap = it }
        } else {
            existing
        }
        Utils.matToBitmap(mat, target)
        return target
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    /** Returns [src] itself when no downscale is needed, otherwise a managed resized copy. */
    private fun MatScope.downscaled(src: Mat): Mat {
        val longest = max(src.cols(), src.rows())
        if (longest <= config.maxAnalysisDimension) return src

        val scale = config.maxAnalysisDimension.toDouble() / longest
        val dst = Mat().managed()
        Imgproc.resize(
            src, dst,
            Size((src.cols() * scale).roundToInt().toDouble(), (src.rows() * scale).roundToInt().toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA
        )
        return dst
    }

    private fun adaptiveBlockSize(mat: Mat): Int {
        val base = min(mat.rows(), mat.cols()) / config.adaptiveBlockDivisor
        return oddAtLeast(min(base, config.adaptiveBlockMax), 15)
    }

    /** Douglas-Peucker simplification: a domino collapses to ~4 vertices, clutter doesn't. */
    private fun MatScope.looksRectangular(curve: MatOfPoint2f): Boolean {
        val perimeter = Imgproc.arcLength(curve, true)
        if (perimeter <= 0.0) return false

        val approx = MatOfPoint2f().managed()
        Imgproc.approxPolyDP(curve, approx, config.approxEpsilonRatio * perimeter, true)
        val corners = approx.rows()
        return corners in 4..config.maxCorners
    }

    /**
     * Perspective-warps a candidate into a canonical upright rectangle, padded with a
     * margin of the surface around it, and measures its photometry.
     *
     * Warping the tile PLUS its surroundings in one pass is what makes the contrast
     * test nearly free: the tile face is the centre crop, the surround is the border
     * band, and both means come from the same buffer.
     *
     * After this, a pip is always the same number of pixels across whatever the angle,
     * distance or tilt of the phone — which turns pip detection into a fixed-threshold
     * problem.
     */
    private fun MatScope.sampleTile(
        src: Mat,
        rect: RotatedRect,
        whiteLevel: Double,
        wantInverse: Boolean
    ): TileSample? {
        // minAreaRect corners come back in cyclic order (0→1→2→3 walks the rectangle).
        val corners = Array(4) { Point() }
        rect.points(corners)

        val edgeA = distance(corners[0], corners[1])
        val edgeB = distance(corners[1], corners[2])
        if (edgeA < 2.0 || edgeB < 2.0) return null

        val ordered: Array<Point>
        val longLen: Double
        val shortLen: Double
        if (edgeA >= edgeB) {
            ordered = arrayOf(corners[0], corners[1], corners[2], corners[3])
            longLen = edgeA
            shortLen = edgeB
        } else {
            // Rotate the cycle by one so the long edge maps to the output width.
            ordered = arrayOf(corners[1], corners[2], corners[3], corners[0])
            longLen = edgeB
            shortLen = edgeA
        }

        val faceW = config.normalizedTileLongSide
        val faceH = max(24, (faceW * (shortLen / longLen)).roundToInt())

        val padX = (faceW * config.surroundMarginRatio).roundToInt()
        val padY = (faceH * config.surroundMarginRatio).roundToInt()
        val canvasW = faceW + 2 * padX
        val canvasH = faceH + 2 * padY

        val srcPoints = MatOfPoint2f(*ordered).managed()

        // The tile maps to the CENTRE of the canvas; the band left over is its surround.
        val canvasPoints = MatOfPoint2f(
            Point(padX.toDouble(), padY.toDouble()),
            Point((padX + faceW - 1).toDouble(), padY.toDouble()),
            Point((padX + faceW - 1).toDouble(), (padY + faceH - 1).toDouble()),
            Point(padX.toDouble(), (padY + faceH - 1).toDouble())
        ).managed()

        val transform = Imgproc.getPerspectiveTransform(srcPoints, canvasPoints).managed()
        val canvas = Mat().managed()
        Imgproc.warpPerspective(
            src, canvas, transform,
            Size(canvasW.toDouble(), canvasH.toDouble()),
            Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE          // tiles near the frame edge don't get a fake black surround
        )

        val face = Mat(canvas, Rect(padX, padY, faceW, faceH)).managed()

        val faceMean = Core.mean(face).`val`[0]
        val faceArea = (faceW * faceH).toDouble()

        val surroundMean = if (padX >= 2 && padY >= 2) {
            val canvasArea = (canvasW * canvasH).toDouble()
            val canvasMean = Core.mean(canvas).`val`[0]
            // Ring total = whole total − face total.
            (canvasMean * canvasArea - faceMean * faceArea) / (canvasArea - faceArea)
        } else {
            faceMean                      // margin disabled → contrast test is a no-op
        }

        val whiteBinary = Mat().managed()
        Imgproc.threshold(face, whiteBinary, whiteLevel, 255.0, Imgproc.THRESH_BINARY)
        val brightRatio = Core.countNonZero(whiteBinary) / faceArea

        // Built in FACE space so pip centres map straight back without an offset.
        val inverse = if (wantInverse) {
            val facePoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(faceW - 1.0, 0.0),
                Point(faceW - 1.0, faceH - 1.0),
                Point(0.0, faceH - 1.0)
            ).managed()
            Imgproc.getPerspectiveTransform(facePoints, srcPoints).managed()
        } else {
            null
        }

        return TileSample(face, faceMean, surroundMean, brightRatio, inverse)
    }

    companion object {
        private const val TAG = "DominoAnalyzer"

        // Utils.matToBitmap treats 3-channel input as RGB, so these are R,G,B.
        private val COLOR_ACCEPT = Scalar(60.0, 255.0, 60.0)            // green  — counted
        private val COLOR_PIP = Scalar(0.0, 210.0, 255.0)               // cyan   — pip found
        private val COLOR_REJECT_AREA = Scalar(120.0, 120.0, 120.0)     // grey   — wrong size
        private val COLOR_REJECT_ASPECT = Scalar(255.0, 40.0, 40.0)     // red    — wrong shape
        private val COLOR_REJECT_SOLIDITY = Scalar(255.0, 200.0, 0.0)   // yellow — not solid
        private val COLOR_REJECT_CORNERS = Scalar(255.0, 0.0, 255.0)    // magenta— too many corners
        private val COLOR_REJECT_BRIGHTNESS = Scalar(40.0, 140.0, 255.0)// blue   — not white enough
        private val COLOR_REJECT_PIPS = Scalar(255.0, 120.0, 0.0)       // orange — impossible pips
    }
}
