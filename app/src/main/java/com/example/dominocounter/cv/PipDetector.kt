package com.example.dominocounter.cv

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * @param count   pips found, or -1 when the ROI should be rejected as a bad tile
 * @param centers pip centres in the WARPED tile's coordinate space (debug only; empty
 *                unless `collectCenters` was requested)
 */
data class PipResult(val count: Int, val centers: List<Point>) {
    companion object {
        val REJECTED = PipResult(-1, emptyList())
        val EMPTY = PipResult(0, emptyList())
    }
}

/**
 * Counts the dark pips inside one perspective-normalised domino tile.
 *
 * Input is always a grayscale Mat whose long side == [DetectionConfig.normalizedTileLongSide],
 * which is what lets pip radius bounds be plain constants instead of per-frame guesses.
 */
class PipDetector(private val config: DetectionConfig) {

    private class Inset(val mat: Mat, val dx: Int, val dy: Int)

    fun countPips(tile: Mat, collectCenters: Boolean = false): PipResult {
        if (tile.rows() < 24 || tile.cols() < 24) return PipResult.REJECTED

        val result = when (config.pipMethod) {
            PipMethod.CONTOUR_BLOB -> countByContours(tile, collectCenters)
            PipMethod.HOUGH_CIRCLES -> countByHoughCircles(tile, collectCenters)
        }
        // More pips than a tile can physically carry ⇒ we didn't segment a domino.
        return if (result.count > config.maxPipsPerTile) PipResult.REJECTED else result
    }

    // ------------------------------------------------------------------
    // Default: Otsu + circular-blob contours. Robust, deterministic, fast.
    // ------------------------------------------------------------------
    private fun countByContours(tile: Mat, collectCenters: Boolean): PipResult = matScope {
        val inset = insetCopy(tile)
        val roi = inset.mat.managed()
        val shortSide = min(roi.rows(), roi.cols()).toDouble()

        // Blank (0-0) guard: a flat patch has no contrast, and Otsu on a flat patch
        // amplifies sensor noise into fake pips.
        val mean = MatOfDouble().managed()
        val stdDev = MatOfDouble().managed()
        Core.meanStdDev(roi, mean, stdDev)
        if (stdDev.toArray()[0] < config.minTileStdDev) return@matScope PipResult.EMPTY

        val blurred = Mat().managed()
        Imgproc.GaussianBlur(roi, blurred, Size(3.0, 3.0), 0.0)

        // Pips are the dark minority → THRESH_BINARY_INV makes them white blobs.
        val binary = Mat().managed()
        Imgproc.threshold(
            blurred, binary, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU
        )

        // Opening erases the centre divider line and salt noise while leaving pips intact.
        val kernelSize = oddAtLeast((shortSide * 0.035).roundToInt(), 3).toDouble()
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(kernelSize, kernelSize)
        ).managed()
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat().managed()
        Imgproc.findContours(
            binary, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        contours.forEach { it.managed() }

        val minDiameter = shortSide * config.minPipDiameterRatio
        val maxDiameter = shortSide * config.maxPipDiameterRatio
        val minArea = PI / 4.0 * minDiameter * minDiameter
        val maxArea = PI / 4.0 * maxDiameter * maxDiameter

        var pips = 0
        val centers = if (collectCenters) ArrayList<Point>(12) else null

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minArea || area > maxArea) continue

            val box = Imgproc.boundingRect(contour)
            val boxRatio = min(box.width, box.height).toDouble() / max(box.width, box.height)
            if (boxRatio < config.minPipBoxRatio) continue
            if (box.width > maxDiameter * 1.3 || box.height > maxDiameter * 1.3) continue

            val curve = MatOfPoint2f(*contour.toArray()).managed()
            val perimeter = Imgproc.arcLength(curve, true)
            if (perimeter <= 0.0) continue

            val circularity = 4.0 * PI * area / (perimeter * perimeter)
            if (circularity < config.minPipCircularity) continue

            pips++
            // Undo the inset so the centre is valid in the warped tile's own space.
            centers?.add(
                Point(
                    box.x + box.width / 2.0 + inset.dx,
                    box.y + box.height / 2.0 + inset.dy
                )
            )
        }
        PipResult(pips, centers ?: emptyList())
    }

    // ------------------------------------------------------------------
    // Alternative: HoughCircles on the inverted ROI (config.pipMethod).
    // ------------------------------------------------------------------
    private fun countByHoughCircles(tile: Mat, collectCenters: Boolean): PipResult = matScope {
        val inset = insetCopy(tile)
        val roi = inset.mat.managed()
        val shortSide = min(roi.rows(), roi.cols()).toDouble()

        val mean = MatOfDouble().managed()
        val stdDev = MatOfDouble().managed()
        Core.meanStdDev(roi, mean, stdDev)
        if (stdDev.toArray()[0] < config.minTileStdDev) return@matScope PipResult.EMPTY

        // Invert so the pips become the bright features, then blur — Hough's internal
        // Canny stage is extremely noise-sensitive.
        val inverted = Mat().managed()
        Core.bitwise_not(roi, inverted)
        Imgproc.GaussianBlur(inverted, inverted, Size(5.0, 5.0), 1.5)

        val circles = Mat().managed()
        Imgproc.HoughCircles(
            inverted,
            circles,
            Imgproc.HOUGH_GRADIENT,
            1.0,                                                  // dp (accumulator = image res)
            shortSide * 0.16,                                     // minDist between pip centres
            110.0,                                                // param1: Canny high threshold
            12.0,                                                 // param2: accumulator threshold
            (shortSide * config.minPipDiameterRatio / 2.0).roundToInt(),
            (shortSide * config.maxPipDiameterRatio / 2.0).roundToInt()
        )
        if (circles.empty()) return@matScope PipResult.EMPTY

        // HoughCircles returns 1 row, N columns of (x, y, radius).
        val count = circles.cols()
        val centers = if (collectCenters) {
            val list = ArrayList<Point>(count)
            val data = FloatArray(3)
            for (i in 0 until count) {
                circles.get(0, i, data)
                list.add(Point((data[0] + inset.dx).toDouble(), (data[1] + inset.dy).toDouble()))
            }
            list
        } else {
            emptyList()
        }
        PipResult(count, centers)
    }

    /**
     * Crops the warped tile inwards so background bleed and the drop shadow along the
     * tile border can't be mistaken for a pip. Returns a standalone (cloned) Mat plus
     * the offset needed to map coordinates back to the uncropped tile.
     */
    private fun insetCopy(tile: Mat): Inset {
        val insetX = (tile.cols() * config.tileInsetRatio).roundToInt()
        val insetY = (tile.rows() * config.tileInsetRatio).roundToInt()
        val w = tile.cols() - 2 * insetX
        val h = tile.rows() - 2 * insetY
        if (w < 16 || h < 16) return Inset(tile.clone(), 0, 0)

        val header = Mat(tile, Rect(insetX, insetY, w, h))
        val copy = header.clone()
        header.release()   // header is a view; releasing it does not touch `tile`
        return Inset(copy, insetX, insetY)
    }
}
