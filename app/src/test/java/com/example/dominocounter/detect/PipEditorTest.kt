package com.example.dominocounter.detect

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PipEditorTest {

    private fun pip(x: Float, y: Float, size: Float = 20f, value: Int = 1) =
        ReviewPip(x, y, size, size, value)

    @Test
    fun `tapping a pip removes it`() {
        val pips = listOf(pip(100f, 100f), pip(300f, 300f))

        val result = PipEditor.toggleAt(pips, 102f, 99f, hitRadius = 30f)

        assertThat(result).containsExactly(pips[1])
    }

    @Test
    fun `tapping bare table adds a manual pip at that spot`() {
        val pips = listOf(pip(100f, 100f))

        val result = PipEditor.toggleAt(pips, 500f, 500f, hitRadius = 30f)

        assertThat(result).hasSize(2)
        val added = result.last()
        assertThat(added.manual).isTrue()
        assertThat(added.value).isEqualTo(1)
        assertThat(added.x).isEqualTo(500f)
        assertThat(added.y).isEqualTo(500f)
    }

    @Test
    fun `a manual pip is drawn the same size as the typical detected one`() {
        val pips = listOf(pip(0f, 0f, 10f), pip(100f, 0f, 12f), pip(200f, 0f, 40f))

        val added = PipEditor.toggleAt(pips, 500f, 500f, hitRadius = 30f).last()

        assertThat(added.w).isEqualTo(12f)
    }

    @Test
    fun `overlapping targets remove only the nearest pip`() {
        val near = pip(100f, 100f)
        val far = pip(110f, 100f)

        val result = PipEditor.toggleAt(listOf(near, far), 104f, 100f, hitRadius = 30f)

        assertThat(result).containsExactly(far)
    }

    @Test
    fun `a large pip is hit by its own size even with a small hit radius`() {
        val cluster = pip(100f, 100f, size = 80f, value = 7)

        val result = PipEditor.toggleAt(listOf(cluster), 135f, 100f, hitRadius = 5f)

        assertThat(result).isEmpty()
    }

    @Test
    fun `total sums pip values`() {
        assertThat(PipEditor.total(listOf(pip(0f, 0f), pip(1f, 1f, value = 7)))).isEqualTo(8)
        assertThat(PipEditor.total(emptyList())).isEqualTo(0)
    }
}
