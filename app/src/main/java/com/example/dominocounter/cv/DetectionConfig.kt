package com.example.dominocounter.cv

/**
 * Every magic number in the pipeline lives here so you can tune the detector
 * without touching the algorithm. All size-related values are expressed as
 * *ratios* of the frame (or of the tile), never as absolute pixels — that is
 * what makes the same config work on a 720p budget phone and a 4K flagship.
 */
data class DetectionConfig(

    // ---------------- Frame pre-processing ----------------

    /** Analysis is downscaled so the longest side never exceeds this. Lower = faster. */
    val maxAnalysisDimension: Int = 960,

    /** Gaussian blur kernel (must be odd). Kills sensor noise before segmentation. */
    val blurKernel: Int = 5,

    /**
     * The frame is rotation-invariant for counting purposes (minAreaRect and pip
     * blobs don't care which way is up), so rotating costs a full-frame memcpy for
     * nothing. Turn it on only if you add a debug overlay aligned to the preview.
     */
    val rotateFrameUpright: Boolean = false,

    // ---------------- Segmentation (finding the tiles) ----------------

    /** EDGE works on any background. See the enum docs below. */
    val segmentationMode: SegmentationMode = SegmentationMode.EDGE,

    /**
     * Canny low threshold as a fraction of the high threshold. The high threshold is
     * derived per-frame from Otsu, so it needs no manual tuning across lighting.
     * Lower it (0.33) to catch fainter tile borders on low-contrast surfaces.
     */
    val cannyLowRatio: Double = 0.50,

    /**
     * Multiplier on the Otsu-derived Canny high threshold. Raise it (1.3–1.6) to make
     * the edge detector deaf to faint texture: wood grain, fabric weave, table seams.
     */
    val cannyHighScale: Double = 1.0,

    /** Closes gaps in the tile outline so it becomes one traceable loop (odd). */
    val edgeCloseKernel: Int = 7,

    /** Extra thickening of the edge ring. Raise to 2 if outlines keep breaking. */
    val edgeDilateIterations: Int = 1,

    // ---------------- Brightness gate: tiles are WHITE ----------------

    /**
     * Discards every edge that isn't near a bright region BEFORE contours are traced.
     * Wood grain, stitching and shadow lines live in mid-tones, so they vanish here
     * and never cost a contour, a warp or a pip search.
     *
     * Harmless on a white tabletop: the gate simply passes everything and the shape
     * and surround checks below do the work instead.
     */
    val gateEdgesByBrightness: Boolean = true,

    /**
     * How far above the frame's Otsu split a pixel must sit to count as "white",
     * in grey levels. Raise it if dark-ish surfaces still leak through; lower it
     * (4–8) if tiles disappear in dim light.
     */
    val brightAboveOtsu: Double = 12.0,

    /**
     * Dilation of the bright region before gating (odd). Must be wide enough to cover
     * the tile's own border, which sits just OUTSIDE the white area.
     */
    val brightGateDilate: Int = 9,

    // ---------------- Photometric validation, per candidate ----------------

    /** Enables the face-brightness and surround-contrast checks below. */
    val requireWhiteFace: Boolean = true,

    /**
     * How much context to sample around each candidate, as a fraction of its size.
     * The band outside the tile is its "surround"; 0 disables the contrast check.
     */
    val surroundMarginRatio: Double = 0.22,

    /**
     * The tile face must be at least this many grey levels brighter than the surface
     * it sits on. This is the single most effective filter against grain: a grain
     * "rectangle" is the same brightness as the wood around it, a domino never is.
     */
    val minSurroundContrast: Double = 16.0,

    /**
     * Fraction of the tile face that must be genuinely white. A double-six (12 pips)
     * still leaves ~75% white, so 0.45 is generous. Wood grain scores far lower.
     */
    val minBrightPixelRatio: Double = 0.45,

    // -- only used by SegmentationMode.ADAPTIVE --

    /**
     * Adaptive block size = min(frameSide) / divisor, forced odd and clamped.
     * The block MUST be bigger than a tile, or the tile's own interior becomes the
     * local mean and the mask comes out hollow. Divisor 2 ≈ half the frame.
     */
    val adaptiveBlockDivisor: Int = 2,
    val adaptiveBlockMax: Int = 301,

    /** Subtracted from the local mean. Raise it if the background bleeds into the mask. */
    val adaptiveConstant: Double = 7.0,

    /** Morphological close (odd) that seals pip holes and small gaps in the tile edge. */
    val morphCloseKernel: Int = 7,

    // ---------------- Tile (contour) filtering ----------------

    /** Contour area as a fraction of total frame area. */
    val minTileAreaRatio: Double = 0.004,
    val maxTileAreaRatio: Double = 0.45,

    /** long side / short side of the minAreaRect. A standard domino is 2:1. */
    val minAspectRatio: Double = 1.25,
    val maxAspectRatio: Double = 3.40,

    /** contourArea / minAreaRect area. Rejects L-shapes, hands, shadows, merged blobs. */
    val minSolidity: Double = 0.72,

    /**
     * Polygon-approximation guard: a domino outline simplifies to roughly 4 corners.
     * Very effective at rejecting wood grain, table seams and cable clutter in EDGE
     * mode. Turn it off if valid tiles start disappearing on rounded-corner sets.
     */
    val requireQuadrilateral: Boolean = true,
    val approxEpsilonRatio: Double = 0.03,
    val maxCorners: Int = 8,

    /** Hard cap — if more "tiles" than this survive, the mask is noise, not dominoes. */
    val maxTilesPerFrame: Int = 12,

    // ---------------- ROI normalisation ----------------

    /**
     * Each tile is perspective-warped to a canonical upright rectangle with this
     * long side. Fixing the scale is what lets pip radii be constants.
     */
    val normalizedTileLongSide: Int = 256,

    /** Inset applied to the warped tile before pip search, to drop edge/shadow pixels. */
    val tileInsetRatio: Double = 0.06,

    // ---------------- Pip detection ----------------

    val pipMethod: PipMethod = PipMethod.CONTOUR_BLOB,

    /** Pip diameter as a fraction of the tile's SHORT side. */
    val minPipDiameterRatio: Double = 0.09,
    val maxPipDiameterRatio: Double = 0.34,

    /** 4·π·area / perimeter² — 1.0 is a perfect circle. Rejects the centre divider line. */
    val minPipCircularity: Double = 0.62,

    /** Bounding-box squareness guard for a pip. */
    val minPipBoxRatio: Double = 0.62,

    /**
     * If the warped tile has almost no contrast it's a blank (0-0) tile; Otsu on a flat
     * patch would hallucinate blobs out of noise, so bail out early.
     */
    val minTileStdDev: Double = 9.0,

    /** Double-six = 12. Anything above this means the tile detection was wrong → discard. */
    val maxPipsPerTile: Int = 12,

    // ---------------- Stabilisation ----------------

    /** Consecutive identical frames required before the big number is allowed to change. */
    val stableFrames: Int = 5
)

enum class SegmentationMode {
    /**
     * Canny edges, self-tuned from Otsu, gated by brightness and closed into solid
     * outlines. Works on ANY background — wood, felt, white, cluttered — because it
     * keys on the contrast boundary at the tile border rather than on the tile being
     * the brightest thing in frame. This is the default.
     */
    EDGE,

    /**
     * Local brightness threshold. Only valid when tiles are clearly brighter than the
     * surface AND the block size exceeds the tile. Handles uneven lighting well.
     */
    ADAPTIVE,

    /**
     * Global Otsu. Fastest and cleanest when the scene is strongly bimodal
     * (light tiles on a uniformly darker mat) and the lighting is flat.
     */
    OTSU
}

enum class PipMethod {
    /** Otsu + contour circularity. Default: more robust and ~2× faster than Hough. */
    CONTOUR_BLOB,

    /** Classic HoughCircles on the inverted ROI. */
    HOUGH_CIRCLES
}
