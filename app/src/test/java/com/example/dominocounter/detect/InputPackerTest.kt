package com.example.dominocounter.detect

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.FloatBuffer

class InputPackerTest {

    // A 2 x 2 image: red, green / blue, white.
    private val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())

    private fun pack(channelsFirst: Boolean): FloatArray {
        val buffer = FloatBuffer.allocate(12)
        InputPacker.pack(pixels, width = 2, height = 2, channelsFirst = channelsFirst, out = buffer)
        assertThat(buffer.position()).isEqualTo(12)
        return buffer.array()
    }

    @Test
    fun `channels-last interleaves red green blue per pixel`() {
        assertThat(pack(channelsFirst = false)).usingTolerance(1e-6).containsExactly(
            1f, 0f, 0f, /**/ 0f, 1f, 0f, /**/ 0f, 0f, 1f, /**/ 1f, 1f, 1f
        ).inOrder()
    }

    @Test
    fun `channels-first writes a whole red plane, then green, then blue`() {
        assertThat(pack(channelsFirst = true)).usingTolerance(1e-6).containsExactly(
            1f, 0f, 0f, 1f, /* R */
            0f, 1f, 0f, 1f, /* G */
            0f, 0f, 1f, 1f  /* B */
        ).inOrder()
    }

    @Test
    fun `values are scaled into 0 to 1`() {
        val buffer = FloatBuffer.allocate(3)
        InputPacker.pack(intArrayOf(0xFF336699.toInt()), 1, 1, false, buffer)

        assertThat(buffer.array()[0]).isWithin(1e-6f).of(0x33 / 255f)
        assertThat(buffer.array()[1]).isWithin(1e-6f).of(0x66 / 255f)
        assertThat(buffer.array()[2]).isWithin(1e-6f).of(0x99 / 255f)
    }
}
