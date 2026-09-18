package com.example.dominocounter.util

import java.text.Normalizer
import java.util.Locale

/**
 * Produces the dedupe key stored in `players.normalizedName`.
 *
 * Without this, "Wael", "wael " and "WAEL" become three players with three separate
 * win records. Accent folding matters here specifically because names get typed in
 * French and Arabic as well as English: "Moh" / "Mohâ" and harakat-bearing Arabic
 * spellings must collapse to one key.
 */
object NameNormalizer {

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .replace(WHITESPACE, " ")
            .lowercase(Locale.ROOT)

    /** Trimmed and whitespace-collapsed, but otherwise exactly as the user typed it. */
    fun displayForm(raw: String): String = raw.trim().replace(WHITESPACE, " ")
}
