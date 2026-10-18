package com.dongzhongcen.listenstat

import com.dongzhongcen.listenstat.ui.Period
import com.dongzhongcen.listenstat.ui.Range
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodTest {
    private val today = LocalDate.of(2026, 10, 19)

    @Test fun prefixes() {
        assertEquals("2026-10-19", Period(Range.DAY, today).prefix)
        assertEquals("2026-10", Period(Range.MONTH, today).prefix)
        assertEquals("2026", Period(Range.YEAR, today).prefix)
        assertEquals("2026-03-05", Period(Range.DAY, LocalDate.of(2026, 3, 5)).prefix)
    }

    @Test fun containsAndLatest() {
        val m = Period(Range.MONTH, LocalDate.of(2026, 9, 30))
        assertFalse(today in m)
        assertFalse(m.isLatest(today))
        assertTrue(today in m.shift(1))
        assertTrue(m.shift(1).isLatest(today))
        assertTrue(Period(Range.DAY, today).isLatest(today))
        assertFalse(Period(Range.DAY, today.minusDays(1)).isLatest(today))
        assertEquals(LocalDate.of(2025, 1, 1), Period(Range.YEAR, today).shift(-1).start)
    }
}
