package com.example.dominocounter.detect

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScanPhotoSaverTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, minute, second)
        }.timeInMillis

    @Test
    fun `file name carries the timestamp, the confirmed total and the model total`() {
        val now = millis(2026, Calendar.SEPTEMBER, 19, 14, 30, 12)

        assertThat(ScanPhotoSaver.fileName(now, confirmedTotal = 14, modelTotal = 12, timeZone = utc))
            .isEqualTo("scan_20260919_143012_total14_model12")
    }

    @Test
    fun `an uncorrected scan shows the same total twice`() {
        val now = millis(2026, Calendar.JANUARY, 2, 3, 4, 5)

        assertThat(ScanPhotoSaver.fileName(now, 7, 7, utc)).isEqualTo("scan_20260102_030405_total7_model7")
    }

    @Test
    fun `file names sort chronologically`() {
        val earlier = ScanPhotoSaver.fileName(millis(2026, Calendar.MARCH, 1, 9, 0, 0), 5, 5, utc)
        val later = ScanPhotoSaver.fileName(millis(2026, Calendar.MARCH, 1, 18, 0, 0), 5, 5, utc)

        assertThat(earlier).isLessThan(later)
    }
}
