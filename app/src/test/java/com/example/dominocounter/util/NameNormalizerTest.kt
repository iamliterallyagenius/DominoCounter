package com.example.dominocounter.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NameNormalizerTest {

    @Test
    fun `case and surrounding whitespace collapse to one key`() {
        val keys = listOf("Wael", "wael ", " WAEL", "wAeL").map { NameNormalizer.normalize(it) }
        assertThat(keys.toSet()).hasSize(1)
    }

    @Test
    fun `internal whitespace is collapsed`() {
        assertThat(NameNormalizer.normalize("Moh   Amine")).isEqualTo("moh amine")
    }

    @Test
    fun `french accents fold to their base letters`() {
        assertThat(NameNormalizer.normalize("Amélie")).isEqualTo(NameNormalizer.normalize("Amelie"))
        assertThat(NameNormalizer.normalize("Chloé")).isEqualTo("chloe")
    }

    @Test
    fun `arabic diacritics fold away`() {
        // Same consonantal skeleton, one written with harakat.
        assertThat(NameNormalizer.normalize("وَائِل")).isEqualTo(NameNormalizer.normalize("وائل"))
    }

    @Test
    fun `display form keeps the original casing but tidies spacing`() {
        assertThat(NameNormalizer.displayForm("  Wael   Ben  ")).isEqualTo("Wael Ben")
    }

    @Test
    fun `distinct names stay distinct`() {
        assertThat(NameNormalizer.normalize("Bilal")).isNotEqualTo(NameNormalizer.normalize("Bilel"))
    }
}
