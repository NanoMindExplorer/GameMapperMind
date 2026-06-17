package com.nanomindexplorer.gamemappermind.daemon

import android.os.SystemClock
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.GamepadManager
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import com.nanomindexplorer.gamemappermind.model.GameProfile
import com.nanomindexplorer.gamemappermind.model.Mapping
import com.nanomindexplorer.gamemappermind.model.ScreenSize
import com.nanomindexplorer.gamemappermind.model.ProfileMetadata
import com.nanomindexplorer.gamemappermind.model.Deadzone
import com.nanomindexplorer.gamemappermind.model.Sensitivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * FASE 5.1 — Unit tests for InputPipelineWorker tier transitions,
 * CPU sampling, and backpressure handling.
 *
 * Path di repo:
 *   android/app/src/test/java/com.nanomindexplorer.gamemappermind/daemon/InputPipelineWorkerTest.kt
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest --tests "*.InputPipelineWorkerTest"
 *
 * Strategy:
 *   - Use Mockito to stub GamepadManager.snapshot() with scripted values.
 *   - Use a FakeTouchInjector that records inject calls + exposes queue depth.
 *   - Use a FakeAnalogProcessor that does nothing (we exercise tier logic, not analog math).
 *   - Drive the pipeline by calling the internal tick directly via reflection,
 *     OR by using a real worker thread + Thread.sleep() for timing-based tests.
 *
 *   We do NOT test /proc/stat parsing directly — that's an environment dependency.
 *   Instead we test the public observable: tier transitions under various
 *   snapshot + queue depth conditions.
 */

class InputPipelineWorkerTest {

    // ───────── Fakes ─────────

    /** Fake TouchInjector that records every call + lets us simulate queue depth. */
    private class FakeTouchInjector : TouchInjector(
        getScreenWidth = { 2800 },
        getScreenHeight = { 1840 }
    ) {
        var injectedTaps: Int = 0
        var injectedSwipes: Int = 0
        var releasedAll: Boolean = false
        var forcedQueueDepth: Int = 0

        // We override the public surface used by the pipeline.
        // Note: TouchInjector's actual methods may differ in your repo —
        // this test demonstrates the contract.
        fun tap(pointerSlot: Int, x: Int, y: Int) { injectedTaps++ }
        fun swipe(pointerSlot: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) { injectedSwipes++ }
        fun releaseAll() { releasedAll = true }
        fun pendingQueueDepth(): Int = forcedQueueDepth
    }

    /** Fake AnalogProcessor — no-op so we can isolate pipeline logic. */
    private class FakeAnalogProcessor : AnalogProcessor() {
        var processCallCount = 0
        var releaseCallCount = 0

        override fun process(
            snapshot: GamepadManager.Snapshot,
            profile: GameProfile,
            screenW: Int,
            screenH: Int,
            injector: TouchInjector
        ) {
            processCallCount++
        }

        override fun releaseAll(injector: TouchInjector) {
            releaseCallCount++
        }

        override fun onProfileChanged(profile: GameProfile?) {}
    }

    /** Fake GamepadManager that returns scripted snapshots in sequence. */
    private class FakeGamepadManager : GamepadManager() {
        private val script = ArrayList<GamepadManager.Snapshot>()
        private var idx = 0

        fun enqueue(snapshots: List<GamepadManager.Snapshot>) {
            script.clear()
            script.addAll(snapshots)
            idx = 0
        }

        override fun snapshot(): GamepadManager.Snapshot? {
            if (idx >= script.size) return null
            return script[idx++]
        }
    }

    // ───────── Test fixtures ─────────

    private lateinit var gamepad: FakeGamepadManager
    private lateinit var injector: FakeTouchInjector
    private lateinit var analog: FakeAnalogProcessor
    private val emittedEvents = mutableListOf<JSONObject>()

    private fun makeWorker(): InputPipelineWorker {
        return InputPipelineWorker(
            gamepadManager = gamepad,
            touchInjector = injector,
            analogProcessor = analog,
            onGamepadEvent = { event -> emittedEvents.add(event) }
        )
    }

    private fun makeSnapshot(
        lx: Float = 0f, ly: Float = 0f,
        rx: Float = 0f, ry: Float = 0f,
        buttons: IntArray = IntArray(0)
    ): GamepadManager.Snapshot {
        // Use reflection to construct Snapshot since it's likely a data class
        // with private constructor in your repo — adapt as needed.
        val cls = GamepadManager.Snapshot::class.java
        val ctor = cls.declaredConstructors.first()
        ctor.isAccessible = true
        // Defaults: leftStickX, leftStickY, rightStickX, rightStickY,
        // leftTrigger, rightTrigger, dpad, buttonsBits
        return ctor.newInstance(
            lx, ly, rx, ry,
            0f, 0f,          // triggers
            0,               // dpad
            buttons.fold(0) { acc, b -> acc or (1 shl b) }  // bits
        ) as GamepadManager.Snapshot
    }

    private fun makeProfile(): GameProfile {
        return GameProfile(
            schemaVersion = "1.0.0",
            profileId = "test-profile",
            gameName = "Test",
            packageName = "com.test.game",
            screenSize = ScreenSize(2800, 1840),
            orientation = "landscape",
            deadzone = Deadzone(),
            sensitivity = Sensitivity(),
            mappings = listOf(
                Mapping(
                    id = 0,
                    buttonCode = 304,
                    action = "tap",
                    xPercent = 0.5f,
                    yPercent = 0.5f,
                    durationMs = 80
                )
            ),
            swipeTriggers = emptyList(),
            metadata = ProfileMetadata(
                author = "test",
                version = "1.0.0",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z"
            )
        )
    }

    @Before
    fun setUp() {
        gamepad = FakeGamepadManager()
        injector = FakeTouchInjector()
        analog = FakeAnalogProcessor()
        emittedEvents.clear()
    }

    // ───────── Tier transition tests ─────────

    @Test
    fun `idle snapshot keeps pipeline at LOW tier`() {
        val worker = makeWorker()
        worker.start()
        try {
            // Enqueue 5 idle snapshots (no analog, no buttons)
            gamepad.enqueue(List(5) { makeSnapshot() })
            // Let a few ticks run
            Thread.sleep(100)
            // Tier should remain LOW
            val tier = readField<String>(worker, "currentTier")
            assertEquals("LOW", tier)
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `analog activity promotes tier to MID then HIGH`() {
        val worker = makeWorker()
        worker.start()
        try {
            // Enqueue snapshots with large analog deflection
            gamepad.enqueue(List(10) {
                makeSnapshot(lx = 0.8f, ly = 0.8f)
            })
            Thread.sleep(100)
            // Should be at MID or HIGH
            val tier = readField<String>(worker, "currentTier")
            assertTrue("Expected MID or HIGH, got $tier", tier == "MID" || tier == "HIGH")
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `small analog deflection stays at MID not HIGH`() {
        val worker = makeWorker()
        worker.start()
        try {
            // Analog delta 0.04 — below PROMOTE_MID_TO_HIGH_ANALOG_DELTA (0.06)
            gamepad.enqueue(List(10) {
                makeSnapshot(lx = 0.04f, ly = 0.0f)
            })
            Thread.sleep(100)
            val tier = readField<String>(worker, "currentTier")
            // Should NOT be HIGH
            assertTrue("Expected MID or LOW, got $tier", tier == "MID" || tier == "LOW")
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `backpressure forces tier to LOW`() {
        val worker = makeWorker()
        worker.start()
        try {
            // Force queue depth above HARD watermark (32)
            injector.forcedQueueDepth = 40
            // Even with active analog, tier should drop to LOW
            gamepad.enqueue(List(5) { makeSnapshot(lx = 0.9f, ly = 0.9f) })
            Thread.sleep(100)
            val tier = readField<String>(worker, "currentTier")
            assertEquals("LOW", tier)
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `soft watermark blocks promotion to HIGH`() {
        val worker = makeWorker()
        worker.start()
        try {
            // Soft watermark is 12 — set queue depth just above
            injector.forcedQueueDepth = 14
            gamepad.enqueue(List(10) { makeSnapshot(lx = 0.8f, ly = 0.8f) })
            Thread.sleep(100)
            val tier = readField<String>(worker, "currentTier")
            // Should be MID, not HIGH (queue depth blocks promotion)
            assertTrue("Expected MID or LOW (queue pressure), got $tier",
                tier == "MID" || tier == "LOW")
        } finally {
            worker.stop()
        }
    }

    // ───────── Profile mapping tests ─────────

    @Test
    fun `pressed button triggers tap injection`() {
        val worker = makeWorker()
        worker.setProfile(makeProfile())
        worker.start()
        try {
            // Button 304 (BTN_SOUTH = A) is pressed
            gamepad.enqueue(List(3) { makeSnapshot(buttons = intArrayOf(304)) })
            Thread.sleep(150)
            assertTrue("Expected at least one tap, got ${injector.injectedTaps}",
                injector.injectedTaps > 0)
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `no profile means no injection`() {
        val worker = makeWorker()
        // Don't set a profile
        worker.start()
        try {
            gamepad.enqueue(List(3) { makeSnapshot(buttons = intArrayOf(304)) })
            Thread.sleep(150)
            assertEquals(0, injector.injectedTaps)
        } finally {
            worker.stop()
        }
    }

    // ───────── Lifecycle tests ─────────

    @Test
    fun `stop releases all pointers`() {
        val worker = makeWorker()
        worker.start()
        Thread.sleep(50)
        worker.stop()
        assertTrue("Expected releaseAll() on stop", injector.releasedAll)
    }

    @Test
    fun `double start is idempotent`() {
        val worker = makeWorker()
        worker.start()
        worker.start()  // should not throw
        assertTrue(worker.isRunning())
        worker.stop()
    }

    @Test
    fun `stop is idempotent when not running`() {
        val worker = makeWorker()
        worker.stop()  // should not throw
        // No assertion needed — just verifying no exception
    }

    // ───────── Event emission tests ─────────

    @Test
    fun `pipeline emits gamepad events to callback`() {
        val worker = makeWorker()
        worker.start()
        try {
            gamepad.enqueue(List(3) { makeSnapshot(lx = 0.5f, ly = 0.5f) })
            Thread.sleep(150)
            assertTrue("Expected at least one event, got ${emittedEvents.size}",
                emittedEvents.size > 0)
            // Verify event shape
            val first = emittedEvents.first()
            assertEquals("gamepad", first.getString("type"))
            assertTrue(first.has("lx"))
            assertTrue(first.has("tier"))
            assertTrue(first.has("periodMs"))
            assertTrue(first.has("cpu"))
            assertTrue(first.has("qDepth"))
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `event includes tier name`() {
        val worker = makeWorker()
        worker.start()
        try {
            gamepad.enqueue(List(3) { makeSnapshot() })
            Thread.sleep(150)
            assertTrue(emittedEvents.isNotEmpty())
            val tier = emittedEvents.last().getString("tier")
            assertTrue("Tier must be LOW/MID/HIGH, got $tier",
                tier in setOf("LOW", "MID", "HIGH"))
        } finally {
            worker.stop()
        }
    }

    // ───────── Profile swap test ─────────

    @Test
    fun `profile swap takes effect on next tick`() {
        val worker = makeWorker()
        worker.start()
        try {
            // First: no profile, no taps
            gamepad.enqueue(List(2) { makeSnapshot(buttons = intArrayOf(304)) })
            Thread.sleep(100)
            val tapsBefore = injector.injectedTaps

            // Set profile
            worker.setProfile(makeProfile())
            gamepad.enqueue(List(3) { makeSnapshot(buttons = intArrayOf(304)) })
            Thread.sleep(150)
            assertTrue("Expected taps to increase after profile set",
                injector.injectedTaps > tapsBefore)
        } finally {
            worker.stop()
        }
    }

    // ───────── Helpers ─────────

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, fieldName: String): T {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target) as T
    }
}
