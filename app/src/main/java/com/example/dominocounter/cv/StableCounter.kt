package com.example.dominocounter.cv

/**
 * Temporal filter for the displayed total.
 *
 * A per-frame geometric detector will always flicker by a pip or two on motion blur,
 * a passing shadow, or a finger crossing the tile. A value is only promoted to the UI
 * after it has been observed [requiredStreak] frames in a row — cheap, allocation-free,
 * and far more predictable than a rolling average (an average of 6 and 7 is 6.5, which
 * is not a legal domino score).
 *
 * Not thread-safe by design: it is only ever touched from the single analysis thread.
 */
class StableCounter(private val requiredStreak: Int) {

    private var candidate = Int.MIN_VALUE
    private var streak = 0

    /** Last value that survived the streak test. */
    var stable: Int = 0
        private set

    /**
     * Feeds one raw frame measurement.
     * @return true if [stable] just changed (i.e. the UI needs repainting).
     */
    fun submit(rawCount: Int): Boolean {
        if (rawCount == candidate) {
            streak++
        } else {
            candidate = rawCount
            streak = 1
        }

        if (streak >= requiredStreak && rawCount != stable) {
            stable = rawCount
            return true
        }
        return false
    }

    fun reset() {
        candidate = Int.MIN_VALUE
        streak = 0
        stable = 0
    }
}
