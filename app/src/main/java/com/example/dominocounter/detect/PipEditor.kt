package com.example.dominocounter.detect

import kotlin.math.hypot
import kotlin.math.max

/**
 * One marker on the review screen: a detected pip (or pip cluster) or one the player added.
 * Coordinates are in the captured photo's pixels.
 *
 * @param value how many pips it stands for — 1 for a single pip
 */
data class ReviewPip(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val value: Int,
    val manual: Boolean = false
)

/** The tap-to-fix rules of the review screen, kept free of Android so they can be tested. */
object PipEditor {

    /**
     * A tap on a marker removes it; a tap on bare table adds a single pip there.
     *
     * @param hitRadius how far from a marker's centre still counts as touching it, in photo
     *                  pixels. Callers derive it from a finger-sized target so tiny pips stay
     *                  tappable; a marker's own half-size is used when that is larger.
     */
    fun toggleAt(pips: List<ReviewPip>, x: Float, y: Float, hitRadius: Float): List<ReviewPip> {
        val hitIndex = pips.indices
            .filter { distance(pips[it], x, y) <= max(hitRadius, max(pips[it].w, pips[it].h) / 2f) }
            .minByOrNull { distance(pips[it], x, y) }

        if (hitIndex != null) return pips.filterIndexed { i, _ -> i != hitIndex }

        val size = typicalSize(pips) ?: (hitRadius * 1.2f)
        return pips + ReviewPip(x, y, size, size, value = 1, manual = true)
    }

    fun total(pips: List<ReviewPip>): Int = pips.sumOf { it.value }

    /** Median single-pip width, so a hand-added pip is drawn the same size as the found ones. */
    private fun typicalSize(pips: List<ReviewPip>): Float? {
        val sizes = pips.filter { it.value == 1 }.map { max(it.w, it.h) }.sorted()
        return if (sizes.isEmpty()) null else sizes[sizes.size / 2]
    }

    private fun distance(p: ReviewPip, x: Float, y: Float): Float = hypot(p.x - x, p.y - y)
}
