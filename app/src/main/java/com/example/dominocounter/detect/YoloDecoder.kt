package com.example.dominocounter.detect

import kotlin.math.max
import kotlin.math.min

/** One raw detection; centre and size are in whichever pixel space the producing function documents. */
data class Detection(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val classId: Int,
    val score: Float
)

/**
 * How a source photo was scaled and centred inside the model's fixed-size input, so boxes
 * found in model space can be mapped back onto the photo. Aspect ratio is preserved; the
 * leftover border is padding.
 */
class Letterbox(val srcW: Int, val srcH: Int, val dstW: Int, val dstH: Int) {
    val scale: Float = min(dstW / srcW.toFloat(), dstH / srcH.toFloat())
    val padX: Float = (dstW - srcW * scale) / 2f
    val padY: Float = (dstH - srcH * scale) / 2f

    fun toSourceX(x: Float): Float = (x - padX) / scale
    fun toSourceY(y: Float): Float = (y - padY) / scale
    fun toSourceLength(length: Float): Float = length / scale
}

/**
 * Turns the raw output tensor of an Ultralytics-style YOLO detector into boxes.
 *
 * Pure Kotlin on purpose: this is the part that has to be right for the total to be right,
 * and it can be unit-tested without a phone or a model file.
 */
object YoloDecoder {

    /**
     * @param raw       one image's output, flat, exactly as the interpreter wrote it
     * @param dimA      first non-batch dimension of the output tensor
     * @param dimB      second non-batch dimension. One of the two is `4 + classes` (tiny), the
     *                  other is the anchor count (thousands); which comes first depends on the
     *                  exporter, so the layout is inferred rather than assumed.
     * @return detections scoring at least [confThreshold], in model INPUT pixels
     */
    fun decode(
        raw: FloatArray,
        dimA: Int,
        dimB: Int,
        inputW: Int,
        inputH: Int,
        confThreshold: Float
    ): List<Detection> {
        require(raw.size == dimA * dimB) { "Output has ${raw.size} values, expected ${dimA * dimB}" }
        val featuresFirst = dimA < dimB
        val features = min(dimA, dimB)
        val anchors = max(dimA, dimB)
        require(features > 4) { "Output has $features features per box; need 4 box values plus class scores" }
        val classes = features - 4

        fun at(feature: Int, anchor: Int): Float =
            if (featuresFirst) raw[feature * anchors + anchor] else raw[anchor * features + feature]

        val kept = ArrayList<Detection>()
        for (a in 0 until anchors) {
            var bestClass = 0
            var bestScore = at(4, a)
            for (c in 1 until classes) {
                val s = at(4 + c, a)
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < confThreshold) continue
            kept.add(Detection(at(0, a), at(1, a), at(2, a), at(3, a), bestClass, bestScore))
        }
        if (kept.isEmpty()) return kept

        // TFLite exports normalise boxes to 0..1; ONNX-style exports use input pixels. A real
        // pixel box can't have every extent under 1.5, so the two are easy to tell apart.
        val normalised = kept.maxOf { max(it.cx + it.w / 2f, it.cy + it.h / 2f) } <= 1.5f
        if (!normalised) return kept
        return kept.map {
            it.copy(cx = it.cx * inputW, cy = it.cy * inputH, w = it.w * inputW, h = it.h * inputH)
        }
    }

    /**
     * Greedy non-maximum suppression, class-agnostic: a pip cluster must not be counted once
     * for every class that happens to fire on it.
     */
    fun nms(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val kept = ArrayList<Detection>()
        for (candidate in detections.sortedByDescending { it.score }) {
            if (kept.none { iou(it, candidate) > iouThreshold }) kept.add(candidate)
        }
        return kept
    }

    fun iou(a: Detection, b: Detection): Float {
        val overlapW = min(a.cx + a.w / 2f, b.cx + b.w / 2f) - max(a.cx - a.w / 2f, b.cx - b.w / 2f)
        val overlapH = min(a.cy + a.h / 2f, b.cy + b.h / 2f) - max(a.cy - a.h / 2f, b.cy - b.h / 2f)
        if (overlapW <= 0f || overlapH <= 0f) return 0f
        val intersection = overlapW * overlapH
        val union = a.w * a.h + b.w * b.h - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
