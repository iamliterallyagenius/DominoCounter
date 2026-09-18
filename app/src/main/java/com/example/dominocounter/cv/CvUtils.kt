package com.example.dominocounter.cv

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic `Mat` lifetime management.
 *
 * `Mat` holds native memory that the GC neither sees nor hurries to free. At 30 fps a
 * single leaked Mat per frame will OOM the process in under a minute. Registering every
 * Mat with a scope guarantees `release()` runs exactly once, even when the frame throws.
 */
class MatScope {
    private val mats = ArrayList<Mat>(24)

    /** Registers this Mat for release when the scope ends. */
    fun <T : Mat> T.managed(): T {
        mats.add(this)
        return this
    }

    fun releaseAll() {
        // Reverse order: submats/headers die before the buffers they point at.
        for (i in mats.indices.reversed()) {
            mats[i].release()
        }
        mats.clear()
    }
}

/** Runs [block] and releases every Mat registered inside it — success or exception. */
inline fun <R> matScope(block: MatScope.() -> R): R {
    val scope = MatScope()
    try {
        return scope.block()
    } finally {
        scope.releaseAll()
    }
}

/**
 * Converts an `ImageProxy` straight into a single-channel grayscale `Mat`.
 *
 * Key optimisation: for `YUV_420_888` the first plane **is** the luminance channel, so
 * there is no YUV→RGB→GRAY conversion to do. We copy Y and we're done — roughly 3× less
 * work than the usual `Imgproc.cvtColor(yuvMat, dst, COLOR_YUV2GRAY_NV21)` dance and
 * zero risk of NV21/I420 plane-order bugs across vendors.
 *
 * Handles `rowStride != width` (padded rows are the norm, not the exception) and the rare
 * `pixelStride > 1` Y plane.
 */
fun ImageProxy.toGrayscaleMat(): Mat {
    check(format == ImageFormat.YUV_420_888) {
        "Expected YUV_420_888 but got format=$format. " +
            "Set ImageAnalysis.setOutputImageFormat(OUTPUT_IMAGE_FORMAT_YUV_420_888)."
    }

    val plane = planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val w = width
    val h = height

    val out = Mat(h, w, CvType.CV_8UC1)
    val origin = buffer.position()

    if (pixelStride == 1 && rowStride == w) {
        // Tightly packed: one bulk copy.
        val data = ByteArray(w * h)
        buffer.get(data)
        buffer.position(origin)
        out.put(0, 0, data)
    } else {
        val row = ByteArray(w)
        val strided = if (pixelStride > 1) ByteArray((w - 1) * pixelStride + 1) else ByteArray(0)
        for (y in 0 until h) {
            buffer.position(origin + y * rowStride)
            if (pixelStride == 1) {
                buffer.get(row, 0, w)
            } else {
                val len = min(strided.size, buffer.remaining())
                buffer.get(strided, 0, len)
                var src = 0
                for (x in 0 until w) {
                    row[x] = strided[src]
                    src += pixelStride
                }
            }
            out.put(y, 0, row)
        }
        buffer.position(origin)
    }
    return out
}

/** Returns a NEW Mat rotated by the sensor rotation. Caller owns it. */
fun Mat.rotatedBy(rotationDegrees: Int): Mat {
    val dst = Mat()
    when (((rotationDegrees % 360) + 360) % 360) {
        90 -> Core.rotate(this, dst, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(this, dst, Core.ROTATE_180)
        270 -> Core.rotate(this, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> copyTo(dst)
    }
    return dst
}

fun distance(a: Point, b: Point): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/** Forces a kernel/block size to be odd and at least [floor]. */
fun oddAtLeast(value: Int, floor: Int): Int {
    var v = if (value < floor) floor else value
    if (v % 2 == 0) v += 1
    return v
}
