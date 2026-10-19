package com.dongzhongcen.listenstat

import com.dongzhongcen.listenstat.sync.SyncApi
import com.dongzhongcen.listenstat.sync.SyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRecordIdTest {
    @Test
    fun recordId_isStableForSameTriple() {
        val a = SyncApi.recordId("com.example.music", "Song A", "2026-10-19")
        val b = SyncApi.recordId("com.example.music", "Song A", "2026-10-19")
        assertEquals(a, b)
        assertTrue(a.length >= 32)
    }

    @Test
    fun recordId_differsWhenFieldChanges() {
        val base = SyncApi.recordId("com.example.music", "Song A", "2026-10-19")
        assertNotEquals(base, SyncApi.recordId("com.other", "Song A", "2026-10-19"))
        assertNotEquals(base, SyncApi.recordId("com.example.music", "Song B", "2026-10-19"))
        assertNotEquals(base, SyncApi.recordId("com.example.music", "Song A", "2026-10-18"))
    }

    @Test
    fun syntheticStart_isDeterministicAndPositive() {
        val id = SyncApi.recordId("pkg", "t", "2026-01-01")
        assertEquals(SyncManager.syntheticStartMs(id), SyncManager.syntheticStartMs(id))
        assertTrue(SyncManager.syntheticStartMs(id) > 0L)
    }
}
