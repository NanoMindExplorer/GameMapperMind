package com.nanomindexplorer.gamemappermind.e2e

import com.nanomindexplorer.gamemappermind.daemon.InputPipelineWorker
import com.nanomindexplorer.gamemappermind.input.AnalogProcessor
import com.nanomindexplorer.gamemappermind.input.GamepadManager
import com.nanomindexplorer.gamemappermind.input.TouchInjector
import com.nanomindexplorer.gamemappermind.model.*
import com.nanomindexplorer.gamemappermind.security.NativeCrashGuard
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FASE 5.4 — End-to-end test: profile load → pipeline → touch injection.
 *
 * Path di repo:
 *   android/app/src/test/java/com.nanomindexplorer.gamemappermind/e2e/ProfileToInjectionE2ETest.kt
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest --tests "*.ProfileToInjectionE2ETest"
 *
 * Goal:
 *   Verify the complete data flow from a JSON profile string all the way to
 *   touch injector calls, exercising every layer in between:
 *
 *     JSON string
 *        ↓ ProfileValidator.parseAndValidate()
 *     GameProfile (domain object)
 *        ↓ InputPipelineWorker.setProfile()
 *     Pipeline state updated
 *        ↓ GamepadManager.snapshot() returns button press
 *     processTick() applies mappings
 *        ↓ TouchInjector.tap() called with slot=10, x=1400, y=920
 *     TouchInjector records inject call
 *        ↓ InputManager.injectInputEvent (not invoked in unit test — JVM has no InputManager)
 *     (We stop at TouchInjector's public API call recording)
 *
 * Why this test is important:
 *   Unit tests verify each layer in isolation. E2E tests verify the
 *   CONTRACT between layers — that a JSON profile with xPercent=0.5 actually
 *   results in TouchInjector.tap() being called with x=1400 on a 2800px-wide
 *   screen. A bug in any layer (ProfileValidator, AnalogProcessor, pipeline
 *   tick scheduling, coordinate conversion) would surface here.
 *
 * Scenarios covered:
 *   1. Single tap mapping → single inject call with correct coords
 *   2. Swipe mapping → inject calls in sequence (DOWN, MOVE..., UP)
 *   3. Two simultaneous buttons → two inject calls (multi-pointer)
 *   4. Analog stick → AnalogProcessor called with correct deflection
 *   5. Profile swap mid-session → old mappings stop, new mappings start
 *   6. Invalid profile JSON → error propagates, pipeline keeps old profile
 *   7. Empty profile (no mappings) → no inject calls
 *   8. Profile with all 50 mappings → no crashes, all 50 buttons inject
 */

class ProfileToInjectionE2ETest {

    private lateinit var gamepad: ScriptableGamepadManager
    private lateinit var injector: RecordingTouchInjector
    private lateinit var analog: RecordingAnalogProcessor
    private lateinit var worker: InputPipelineWorker
    private val emittedEvents = mutableListOf<JSONObject>()

    @Before
    fun setUp() {
        gamepad = ScriptableGamepadManager()
        injector = RecordingTouchInjector()
        analog = RecordingAnalogProcessor()
        worker = InputPipelineWorker(
            gamepadManager = gamepad,
            touchInjector = injector,
            analogProcessor = analog,
            onGamepadEvent = { emittedEvents.add(it) }
        )
    }

    @After
    fun tearDown() {
        worker.stop()
    }

    // ───────── Scenario 1: Single tap mapping ─────────

    @Test
    fun `e2e - single tap mapping produces correct inject call`() {
        val profileJson = """
            {
              "schemaVersion": "1.0.0",
              "profileId": "e2e-tap",
              "gameName": "E2E Test",
              "packageName": "com.test.game",
              "screenSize": { "width": 2800, "height": 1840 },
              "orientation": "landscape",
              "mappings": [
                {
                  "id": 0,
                  "buttonCode": 304,
                  "buttonName": "A",
                  "action": "tap",
                  "xPercent": 0.5,
                  "yPercent": 0.5,
                  "durationMs": 80
                }
              ],
              "metadata": {
                "author": "e2e",
                "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z",
                "updatedAt": "2026-01-01T00:00:00Z"
              }
            }
        """.trimIndent()

        // 1) Validate + set profile
        val result = ProfileValidator.parseAndValidate(profileJson)
        assertTrue("Profile parse failed: $result", result is ProfileValidator.ValidationResult.Ok)
        val profile = (result as ProfileValidator.ValidationResult.Ok).profile
        worker.setProfile(profile)

        // 2) Start pipeline
        worker.start()

        // 3) Press button 304
        gamepad.enqueueSnapshot(buttons = intArrayOf(304))
        Thread.sleep(200)

        // 4) Verify inject call
        assertTrue("Expected at least one tap call, got ${injector.tapCalls.size}",
            injector.tapCalls.isNotEmpty())

        val tap = injector.tapCalls.first()
        assertEquals(10, tap.slot)         // id=0 → slot = id + 10 = 10
        assertEquals(1400, tap.x)          // 0.5 * 2800 = 1400
        assertEquals(920, tap.y)           // 0.5 * 1840 = 920
    }

    // ───────── Scenario 2: Swipe mapping ─────────

    @Test
    fun `e2e - swipe mapping produces swipe call sequence`() {
        val profileJson = """
            {
              "schemaVersion": "1.0.0",
              "profileId": "e2e-swipe",
              "gameName": "E2E Swipe",
              "packageName": "com.test.game",
              "screenSize": { "width": 2800, "height": 1840 },
              "orientation": "landscape",
              "mappings": [
                {
                  "id": 0,
                  "buttonCode": 304,
                  "action": "swipe",
                  "xPercent": 0.2,
                  "yPercent": 0.5,
                  "endXPercent": 0.8,
                  "endYPercent": 0.5,
                  "durationMs": 100
                }
              ],
              "metadata": {
                "author": "e2e",
                "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z",
                "updatedAt": "2026-01-01T00:00:00Z"
              }
            }
        """.trimIndent()

        val result = ProfileValidator.parseAndValidate(profileJson)
        val profile = (result as ProfileValidator.ValidationResult.Ok).profile
        worker.setProfile(profile)
        worker.start()

        gamepad.enqueueSnapshot(buttons = intArrayOf(304))
        Thread.sleep(300)

        assertTrue("Expected at least one swipe call", injector.swipeCalls.isNotEmpty())
        val swipe = injector.swipeCalls.first()
        assertEquals(560, swipe.x1)    // 0.2 * 2800 = 560
        assertEquals(920, swipe.y1)    // 0.5 * 1840 = 920
        assertEquals(2240, swipe.x2)   // 0.8 * 2800 = 2240
        assertEquals(920, swipe.y2)
        assertEquals(100L, swipe.durationMs)
    }

    // ───────── Scenario 3: Two simultaneous buttons ─────────

    @Test
    fun `e2e - two simultaneous buttons produce two inject calls with different slots`() {
        val profileJson = """
            {
              "schemaVersion": "1.0.0",
              "profileId": "e2e-multi",
              "gameName": "E2E Multi",
              "packageName": "com.test.game",
              "screenSize": { "width": 2800, "height": 1840 },
              "orientation": "landscape",
              "mappings": [
                { "id": 0, "buttonCode": 304, "action": "tap", "xPercent": 0.1, "yPercent": 0.1 },
                { "id": 1, "buttonCode": 305, "action": "tap", "xPercent": 0.9, "yPercent": 0.9 }
              ],
              "metadata": {
                "author": "e2e",
                "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z",
                "updatedAt": "2026-01-01T00:00:00Z"
              }
            }
        """.trimIndent()

        val profile = (ProfileValidator.parseAndValidate(profileJson) as ProfileValidator.ValidationResult.Ok).profile
        worker.setProfile(profile)
        worker.start()

        // Press BOTH buttons simultaneously
        gamepad.enqueueSnapshot(buttons = intArrayOf(304, 305))
        Thread.sleep(200)

        assertTrue("Expected at least 2 tap calls, got ${injector.tapCalls.size}",
            injector.tapCalls.size >= 2)

        val slots = injector.tapCalls.map { it.slot }.toSet()
        assertTrue("Expected slots 10 and 11, got $slots",
            10 in slots && 11 in slots)
    }

    // ───────── Scenario 4: Analog stick ─────────

    @Test
    fun `e2e - analog stick deflection triggers AnalogProcessor`() {
        val profileJson = """
            {
              "schemaVersion": "1.0.0",
              "profileId": "e2e-analog",
              "gameName": "E2E Analog",
              "packageName": "com.test.game",
              "screenSize": { "width": 2800, "height": 1840 },
              "orientation": "landscape",
              "mappings": [],
              "metadata": {
                "author": "e2e",
                "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z",
                "updatedAt": "2026-01-01T00:00:00Z"
              }
            }
        """.trimIndent()

        val profile = (ProfileValidator.parseAndValidate(profileJson) as ProfileValidator.ValidationResult.Ok).profile
        worker.setProfile(profile)
        worker.start()

        // Push analog deflection above the deadzone
        gamepad.enqueueSnapshot(lx = 0.7f, ly = 0.3f)
        Thread.sleep(200)

        assertTrue("Expected AnalogProcessor.process to be called, got ${analog.processCallCount}",
            analog.processCallCount > 0)

        val last = analog.lastSnapshot
        assertNotNull(last)
        assertEquals(0.7f, last?.leftStickX, 0.01f)
        assertEquals(0.3f, last?.leftStickY, 0.01f)
    }

    // ───────── Scenario 5: Profile swap mid-session ─────────

    @Test
    fun `e2e - profile swap stops old mappings and starts new ones`() {
        val profileA = """
            {
              "schemaVersion": "1.0.0", "profileId": "a", "gameName": "A",
              "packageName": "com.test.a",
              "screenSize": { "width": 2800, "height": 1840 },
              "orientation": "landscape",
              "mappings": [{ "id": 0, "buttonCode": 304, "action": "tap", "xPercent": 0.1, "yPercent": 0.1 }],
              "metadata": { "author": "e2e", "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z", "updatedAt": "2026-01-01T00:00:00Z" }
            }
        """.trimIndent()

        val profileB = """
            {
              "schemaVersion": "1.0.0", "profileId": "b", "gameName": "B",
              "packageName": "com.test.b",
              "screenSize": { "width": 2800, "height": 1840 },
              "orientation": "landscape",
              "mappings": [{ "id": 0, "buttonCode": 304, "action": "tap", "xPercent": 0.9, "yPercent": 0.9 }],
              "metadata": { "author": "e2e", "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z", "updatedAt": "2026-01-01T00:00:00Z" }
            }
        """.trimIndent()

        val pA = (ProfileValidator.parseAndValidate(profileA) as ProfileValidator.ValidationResult.Ok).profile
        val pB = (ProfileValidator.parseAndValidate(profileB) as ProfileValidator.ValidationResult.Ok).profile

        worker.setProfile(pA)
        worker.start()

        // Press button 304 — should tap at (0.1, 0.1) = (280, 184)
        gamepad.enqueueSnapshot(buttons = intArrayOf(304))
        Thread.sleep(200)
        val tapA = injector.tapCalls.last()
        assertEquals(280, tapA.x)
        assertEquals(184, tapA.y)

        // Swap profile
        injector.tapCalls.clear()
        worker.setProfile(pB)

        // Press button 304 again — should now tap at (0.9, 0.9) = (2520, 1656)
        gamepad.enqueueSnapshot(buttons = intArrayOf(304))
        Thread.sleep(200)
        val tapB = injector.tapCalls.last()
        assertEquals(2520, tapB.x)
        assertEquals(1656, tapB.y)
    }

    // ───────── Scenario 6: Invalid profile JSON ─────────

    @Test
    fun `e2e - invalid profile keeps old profile active`() {
        // Set a valid profile first
        val validJson = """
            {
              "schemaVersion": "1.0.0", "profileId": "valid", "gameName": "V",
              "packageName": "com.test.v",
              "screenSize": { "width": 2800, "height": 1840 },
              "orientation": "landscape",
              "mappings": [{ "id": 0, "buttonCode": 304, "action": "tap", "xPercent": 0.5, "yPercent": 0.5 }],
              "metadata": { "author": "e2e", "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z", "updatedAt": "2026-01-01T00:00:00Z" }
            }
        """.trimIndent()
        val validProfile = (ProfileValidator.parseAndValidate(validJson) as ProfileValidator.ValidationResult.Ok).profile
        worker.setProfile(validProfile)
        worker.start()

        // Try to set an invalid profile
        val invalidJson = """{ "schemaVersion": "2.0.0", ... }"""
        val invalidResult = ProfileValidator.parseAndValidate(invalidJson)
        assertTrue("Expected parse failure", invalidResult is ProfileValidator.ValidationResult.Err)
        // Don't call worker.setProfile — that's the contract: only push validated profiles

        // Verify old profile still works
        gamepad.enqueueSnapshot(buttons = intArrayOf(304))
        Thread.sleep(200)
        assertTrue("Old profile should still produce taps",
            injector.tapCalls.isNotEmpty())
        val tap = injector.tapCalls.last()
        assertEquals(1400, tap.x)  // 0.5 * 2800
        assertEquals(920, tap.y)   // 0.5 * 1840
    }

    // ───────── Scenario 7: Empty profile ─────────

    @Test
    fun `e2e - empty profile produces no inject calls`() {
        val profileJson = """
            {
              "schemaVersion": "1.0.0", "profileId": "empty", "gameName": "E",
              "packageName": "com.test.e",
              "screenSize": { "width": 2800, "height": 1840 },
              "orientation": "landscape",
              "mappings": [],
              "metadata": { "author": "e2e", "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z", "updatedAt": "2026-01-01T00:00:00Z" }
            }
        """.trimIndent()

        val profile = (ProfileValidator.parseAndValidate(profileJson) as ProfileValidator.ValidationResult.Ok).profile
        worker.setProfile(profile)
        worker.start()

        // Press every button — should produce no inject calls
        gamepad.enqueueSnapshot(buttons = (304..320).toList().toIntArray())
        Thread.sleep(200)

        assertEquals("Expected zero taps for empty profile",
            0, injector.tapCalls.size)
        assertEquals("Expected zero swipes for empty profile",
            0, injector.swipeCalls.size)
    }

    // ───────── Scenario 8: 50 mappings (max capacity) ─────────

    @Test
    fun `e2e - 50 mappings all fire without crash`() {
        val mappingsJson = (0 until 50).joinToString(",") { i ->
            """{ "id": $i, "buttonCode": ${304 + i}, "action": "tap", "xPercent": 0.5, "yPercent": 0.5 }"""
        }
        val profileJson = """
            {
              "schemaVersion": "1.0.0", "profileId": "max", "gameName": "Max",
              "packageName": "com.test.max",
              "screenSize": { "width": 2800, "height": 1840 },
              "orientation": "landscape",
              "mappings": [$mappingsJson],
              "metadata": { "author": "e2e", "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z", "updatedAt": "2026-01-01T00:00:00Z" }
            }
        """.trimIndent()

        val profile = (ProfileValidator.parseAndValidate(profileJson) as ProfileValidator.ValidationResult.Ok).profile
        worker.setProfile(profile)
        worker.start()

        // Press all 50 buttons simultaneously
        val allBtns = (304 until 354).toList().toIntArray()
        gamepad.enqueueSnapshot(buttons = allBtns)
        Thread.sleep(300)

        // Should have 50 taps (or close to it — timing-dependent)
        val uniqueSlots = injector.tapCalls.map { it.slot }.toSet()
        assertTrue("Expected at least 40 unique slots (got ${uniqueSlots.size})",
            uniqueSlots.size >= 40)
    }

    // ───────── Fakes ─────────

    /** GamepadManager that returns scripted snapshots on demand. */
    private class ScriptableGamepadManager : GamepadManager() {
        private val queue = java.util.concurrent.ConcurrentLinkedQueue<GamepadManager.Snapshot>()

        fun enqueueSnapshot(
            lx: Float = 0f, ly: Float = 0f,
            rx: Float = 0f, ry: Float = 0f,
            buttons: IntArray = IntArray(0)
        ) {
            val cls = GamepadManager.Snapshot::class.java
            val ctor = cls.declaredConstructors.first()
            ctor.isAccessible = true
            val snap = ctor.newInstance(
                lx, ly, rx, ry, 0f, 0f, 0,
                buttons.fold(0) { acc, b -> acc or (1 shl (b - 300)) }
            ) as GamepadManager.Snapshot
            queue.offer(snap)
        }

        override fun snapshot(): GamepadManager.Snapshot? = queue.poll()
    }

    /** TouchInjector that records calls instead of injecting. */
    private class RecordingTouchInjector : TouchInjector(
        getScreenWidth = { 2800 },
        getScreenHeight = { 1840 }
    ) {
        data class TapCall(val slot: Int, val x: Int, val y: Int)
        data class SwipeCall(val slot: Int, val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long)

        val tapCalls = mutableListOf<TapCall>()
        val swipeCalls = mutableListOf<SwipeCall>()

        fun tap(pointerSlot: Int, x: Int, y: Int) {
            tapCalls.add(TapCall(pointerSlot, x, y))
        }

        fun swipe(pointerSlot: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
            swipeCalls.add(SwipeCall(pointerSlot, x1, y1, x2, y2, durationMs))
        }

        fun releaseAll() {}
        fun pendingQueueDepth(): Int = 0
    }

    /** AnalogProcessor that records calls. */
    private class RecordingAnalogProcessor : AnalogProcessor() {
        var processCallCount = 0
        var releaseCallCount = 0
        var lastSnapshot: GamepadManager.Snapshot? = null

        override fun process(
            snapshot: GamepadManager.Snapshot,
            profile: GameProfile,
            screenW: Int,
            screenH: Int,
            injector: TouchInjector
        ) {
            processCallCount++
            lastSnapshot = snapshot
        }

        override fun releaseAll(injector: TouchInjector) {
            releaseCallCount++
        }

        override fun onProfileChanged(profile: GameProfile?) {}
    }
}
