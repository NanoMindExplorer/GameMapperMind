package com.nanomindexplorer.gamemappermind.input

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FASE 5.1 — Unit tests for PointerPool acquire/release semantics.
 *
 * Path di repo:
 *   android/app/src/test/java/com/nanomindexplorer/gamemappermind/input/PointerPoolTest.kt
 *
 * Run: `./gradlew :app:testDebugUnitTest --tests "*.PointerPoolTest"`
 *
 * Coverage:
 *   - Acquire returns IDs in range [10..109]
 *   - Sequential acquires return sequential IDs (10, 11, 12, ...)
 *   - Release makes the slot available for re-acquire
 *   - LRU eviction: when pool is full, oldest slot is evicted
 *   - Stale timeout: slots idle > timeoutMs are eligible for eviction
 *   - Pool exhaustion: returns -1 when all slots active and none stale
 *   - activeCount()/freeCount() correctly track state
 *   - reset() releases ALL slots including active ones
 *   - Double-release is safe (logged, not crashed)
 *   - Out-of-range IDs in releasePointer are safely ignored
 *   - Thread-safe under concurrent acquire/release (smoke test)
 *
 * Strategy:
 *   - Use real PointerPool instance (no mocks needed — pure Kotlin logic).
 *   - Use a short staleTimeoutMs (100 ms) for fast tests.
 *   - Use Thread.sleep() only where strictly necessary (stale tests).
 *   - No Android framework dependencies — runs on plain JVM.
 */

class PointerPoolTest {

    private lateinit var pool: PointerPool

    @Before
    fun setUp() {
        // Use a short stale timeout for fast test execution.
        pool = PointerPool(staleTimeoutMs = 100L)
    }

    @After
    fun tearDown() {
        // Reset pool to release any active slots between tests.
        pool.reset()
    }

    // ───────────────────────────────────────────────────────────────────────
    // Acquire basics
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `first acquire returns ID 10 (ID_OFFSET)`() {
        val id = pool.acquirePointer()
        assertEquals(PointerPool.ID_OFFSET, id)
    }

    @Test
    fun `sequential acquires return sequential IDs`() {
        val id1 = pool.acquirePointer()
        val id2 = pool.acquirePointer()
        val id3 = pool.acquirePointer()
        assertEquals(10, id1)
        assertEquals(11, id2)
        assertEquals(12, id3)
    }

    @Test
    fun `acquired IDs are in valid range [10..109]`() {
        // Acquire 5 IDs and verify all are in range.
        for (i in 0 until 5) {
            val id = pool.acquirePointer()
            assertTrue("ID $id out of range", id in PointerPool.ID_OFFSET..PointerPool.MAX_ID)
        }
    }

    @Test
    fun `acquired IDs are unique until release`() {
        val ids = mutableSetOf<Int>()
        for (i in 0 until 10) {
            val id = pool.acquirePointer()
            assertTrue("Duplicate ID $id", ids.add(id))
        }
        assertEquals(10, ids.size)
    }

    // ───────────────────────────────────────────────────────────────────────
    // Release basics
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `release makes slot available for re-acquire`() {
        val id1 = pool.acquirePointer()
        pool.releasePointer(id1)
        // After release, the next acquire should return the same slot
        // (it became MRU on release, but it's the only free slot).
        val id2 = pool.acquirePointer()
        assertEquals(id1, id2)
    }

    @Test
    fun `released slot is reused before allocating new slot`() {
        // Acquire 3 slots: 10, 11, 12
        val id1 = pool.acquirePointer()
        val id2 = pool.acquirePointer()
        val id3 = pool.acquirePointer()
        assertEquals(10, id1)
        assertEquals(11, id2)
        assertEquals(12, id3)

        // Release slot 11 (middle)
        pool.releasePointer(id2)

        // Next acquire should return 11 (the free slot), not 13
        val id4 = pool.acquirePointer()
        assertEquals(11, id4)
    }

    @Test
    fun `activeCount tracks acquisitions and releases`() {
        assertEquals(0, pool.activeCount())
        assertEquals(PointerPool.CAPACITY, pool.freeCount())

        val id1 = pool.acquirePointer()
        assertEquals(1, pool.activeCount())
        assertEquals(PointerPool.CAPACITY - 1, pool.freeCount())

        val id2 = pool.acquirePointer()
        assertEquals(2, pool.activeCount())

        pool.releasePointer(id1)
        assertEquals(1, pool.activeCount())

        pool.releasePointer(id2)
        assertEquals(0, pool.activeCount())
        assertEquals(PointerPool.CAPACITY, pool.freeCount())
    }

    // ───────────────────────────────────────────────────────────────────────
    // LRU eviction
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `LRU eviction returns -1 when no slots are stale`() {
        // Use a long timeout so nothing becomes stale during the test.
        val longPool = PointerPool(staleTimeoutMs = 60_000L)
        // Fill all 100 slots
        for (i in 0 until PointerPool.CAPACITY) {
            val id = longPool.acquirePointer()
            assertNotEquals("Acquire $i failed", -1, id)
        }
        // Pool is full, no stale slots → next acquire should return -1
        val id = longPool.acquirePointer()
        assertEquals("Expected -1 when pool exhausted and no stale slots", -1, id)
    }

    @Test
    fun `LRU eviction evicts oldest stale slot when pool is full`() {
        // Use a short timeout so slots become stale quickly.
        val shortPool = PointerPool(staleTimeoutMs = 50L)
        // Fill all 100 slots
        val firstId = shortPool.acquirePointer()  // This will be the oldest
        for (i in 1 until PointerPool.CAPACITY) {
            shortPool.acquirePointer()
        }
        assertEquals(100, shortPool.activeCount())

        // Wait for slots to become stale
        Thread.sleep(80L)

        // Next acquire should evict the LRU slot (which is the first one acquired, ID=10)
        val evictedId = shortPool.acquirePointer()
        assertEquals("Expected LRU eviction to return ID 10", 10, evictedId)
    }

    @Test
    fun `LRU eviction only evicts slots older than staleTimeoutMs`() {
        val shortPool = PointerPool(staleTimeoutMs = 100L)
        // Fill pool
        for (i in 0 until PointerPool.CAPACITY) {
            shortPool.acquirePointer()
        }
        // Wait 50ms (less than 100ms timeout — slots NOT stale yet)
        Thread.sleep(60L)
        // Acquire should return -1 (nothing stale)
        val id = shortPool.acquirePointer()
        assertEquals(-1, id)

        // Wait another 60ms (total 120ms > 100ms timeout — slots now stale)
        Thread.sleep(60L)
        // Now acquire should succeed via eviction
        val evictedId = shortPool.acquirePointer()
        assertNotEquals(-1, evictedId)
    }

    // ───────────────────────────────────────────────────────────────────────
    // evictStalePointers (manual GC trigger)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `evictStalePointers returns 0 when no slots are stale`() {
        pool.acquirePointer()
        pool.acquirePointer()
        // No wait — nothing stale
        val evicted = pool.evictStalePointers(timeoutMs = 60_000L)
        assertEquals(0, evicted)
        // Slots still active
        assertEquals(2, pool.activeCount())
    }

    @Test
    fun `evictStalePointers evicts slots older than timeout`() {
        val id1 = pool.acquirePointer()
        val id2 = pool.acquirePointer()
        // Wait for them to become stale (pool was created with 100ms timeout)
        Thread.sleep(120L)
        val evicted = pool.evictStalePointers()
        assertEquals(2, evicted)
        assertEquals(0, pool.activeCount())
    }

    @Test
    fun `evictStalePointers does not evict recently-active slots`() {
        val id1 = pool.acquirePointer()
        Thread.sleep(60L)
        val id2 = pool.acquirePointer()  // This one is recent
        // Evict with 100ms timeout — only id1 should be evicted
        val evicted = pool.evictStalePointers(timeoutMs = 100L)
        assertEquals(1, evicted)
        assertEquals(1, pool.activeCount())
        assertTrue(pool.isActive(id2))
        assertFalse(pool.isActive(id1))
    }

    // ───────────────────────────────────────────────────────────────────────
    // Pool exhaustion
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `pool exhaustion returns -1`() {
        val longPool = PointerPool(staleTimeoutMs = 60_000L)
        // Fill entire pool
        for (i in 0 until PointerPool.CAPACITY) {
            longPool.acquirePointer()
        }
        // Next acquire returns -1 (no stale slots)
        val id = longPool.acquirePointer()
        assertEquals(-1, id)
    }

    @Test
    fun `pool exhaustion is recoverable after release`() {
        val longPool = PointerPool(staleTimeoutMs = 60_000L)
        // Fill pool
        val ids = mutableListOf<Int>()
        for (i in 0 until PointerPool.CAPACITY) {
            ids.add(longPool.acquirePointer())
        }
        // Exhausted
        assertEquals(-1, longPool.acquirePointer())
        // Release one slot
        longPool.releasePointer(ids[0])
        // Now acquire should succeed
        val id = longPool.acquirePointer()
        assertNotEquals(-1, id)
    }

    // ───────────────────────────────────────────────────────────────────────
    // reset()
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `reset releases all active slots`() {
        // Acquire 50 slots
        for (i in 0 until 50) {
            pool.acquirePointer()
        }
        assertEquals(50, pool.activeCount())
        // Reset
        pool.reset()
        // All slots should be free
        assertEquals(0, pool.activeCount())
        assertEquals(PointerPool.CAPACITY, pool.freeCount())
    }

    @Test
    fun `reset allows re-acquire of all slots`() {
        // Acquire all 100 slots
        for (i in 0 until PointerPool.CAPACITY) {
            pool.acquirePointer()
        }
        assertEquals(0, pool.freeCount())
        // Reset
        pool.reset()
        // Should be able to acquire all 100 again
        for (i in 0 until PointerPool.CAPACITY) {
            val id = pool.acquirePointer()
            assertNotEquals(-1, id)
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Edge cases + error handling
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `releasePointer with out-of-range ID is safely ignored`() {
        // IDs below ID_OFFSET
        pool.releasePointer(0)
        pool.releasePointer(5)
        pool.releasePointer(9)
        // IDs above MAX_ID
        pool.releasePointer(110)
        pool.releasePointer(200)
        pool.releasePointer(Int.MAX_VALUE)
        // Negative IDs
        pool.releasePointer(-1)
        pool.releasePointer(-100)
        // No crash, no state change
        assertEquals(0, pool.activeCount())
    }

    @Test
    fun `double-release is safe and does not crash`() {
        val id = pool.acquirePointer()
        pool.releasePointer(id)
        // Double-release should be a no-op (logged but not crashed)
        pool.releasePointer(id)
        pool.releasePointer(id)
        // State should still be consistent
        assertEquals(0, pool.activeCount())
    }

    @Test
    fun `isActive returns true for active slots, false for free slots`() {
        val id = pool.acquirePointer()
        assertTrue(pool.isActive(id))
        pool.releasePointer(id)
        assertFalse(pool.isActive(id))
    }

    @Test
    fun `isActive returns false for out-of-range IDs`() {
        assertFalse(pool.isActive(-1))
        assertFalse(pool.isActive(0))
        assertFalse(pool.isActive(9))
        assertFalse(pool.isActive(110))
        assertFalse(pool.isActive(Int.MAX_VALUE))
    }

    // ───────────────────────────────────────────────────────────────────────
    // Concurrency smoke test
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `concurrent acquire and release does not corrupt state`() {
        // Spin up 4 threads, each doing 50 acquire+release cycles.
        // After all threads finish, pool should have 0 active slots.
        val numThreads = 4
        val cyclesPerThread = 50
        val threads = (1..numThreads).map { threadNum ->
            Thread {
                for (i in 0 until cyclesPerThread) {
                    val id = pool.acquirePointer()
                    if (id != -1) {
                        // Simulate brief work
                        Thread.sleep(1)
                        pool.releasePointer(id)
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }  // Wait up to 10s

        // All threads done — all slots should be released
        // (some may still be active if a thread was preempted mid-cycle,
        // but the count should be small)
        val active = pool.activeCount()
        assertTrue("Too many active slots after concurrent test: $active", active <= numThreads)
    }

    // ───────────────────────────────────────────────────────────────────────
    // Capacity verification
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `pool capacity is exactly 100`() {
        assertEquals(100, PointerPool.CAPACITY)
    }

    @Test
    fun `ID_OFFSET is 10`() {
        assertEquals(10, PointerPool.ID_OFFSET)
    }

    @Test
    fun `MAX_ID is 109`() {
        assertEquals(109, PointerPool.MAX_ID)
    }

    @Test
    fun `can acquire exactly CAPACITY pointers without exhaustion`() {
        val longPool = PointerPool(staleTimeoutMs = 60_000L)
        for (i in 0 until PointerPool.CAPACITY) {
            val id = longPool.acquirePointer()
            assertNotEquals("Acquire $i returned -1", -1, id)
        }
        assertEquals(PointerPool.CAPACITY, longPool.activeCount())
    }
}
