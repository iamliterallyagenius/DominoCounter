package com.example.dominocounter.util

/**
 * Injected everywhere a timestamp is written, so repository tests can pin time
 * instead of asserting on `System.currentTimeMillis()`.
 */
fun interface AppClock {
    fun now(): Long

    companion object {
        val System = AppClock { java.lang.System.currentTimeMillis() }
    }
}
