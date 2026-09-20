package com.example.dominocounter.detect

import java.nio.FloatBuffer

/**
 * Lays RGB pixels out the way the model's input tensor wants them. Exporters disagree: the
 * TensorFlow route gives height x width x 3 (interleaved), the PyTorch route gives
 * 3 x height x width (one full plane per colour). The model says which; this just obeys.
 */
object InputPacker {

    /**
     * @param pixels ARGB ints, row by row, exactly [width] x [height] of them
     * @param out    positioned at its start; on return holds `3 * width * height` floats in 0..1
     */
    fun pack(pixels: IntArray, width: Int, height: Int, channelsFirst: Boolean, out: FloatBuffer) {
        require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}" }
        if (channelsFirst) {
            for (shift in intArrayOf(16, 8, 0)) { // R plane, then G, then B
                for (p in pixels) out.put(((p shr shift) and 0xFF) / 255f)
            }
        } else {
            for (p in pixels) {
                out.put(((p shr 16) and 0xFF) / 255f)
                out.put(((p shr 8) and 0xFF) / 255f)
                out.put((p and 0xFF) / 255f)
            }
        }
    }
}
