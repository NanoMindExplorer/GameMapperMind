package com.nanomindexplorer.gamemappermind.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.nanomindexplorer.gamemappermind.security.NativeCrashGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever

/**
 * FASE 5.3 — Integration test: Shizuku offline simulation.
 *
 * Path di repo:
 *   android/app/src/test/java/com.nanomindexplorer.gamemappermind/shizuku/ShizukuOfflineSimulationTest.kt
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest --tests "*.ShizukuOfflineSimulationTest"
 *
 * Goal:
 *   Verify ShizukuHelper behaves correctly when Shizuku is NOT installed,
 *   NOT running, NOT granted permission, or the UserService crashes mid-bind.
 *   These are the most common real-world failure modes — we simulate each
 *   without needing an actual device.
 *
 * Strategy:
 *   - Use Mockito to stub Shizuku's static-like API.
 *   - Override ShizukuHelper's binder callbacks to simulate failure paths.
 *   - Verify the correct error code propagates back to callers.
 *
 * Test matrix:
 *   1. Shizuku not installed           → SERVICE_UNAVAILABLE
 *   2. Shizuku installed but not running → SERVICE_UNAVAILABLE
 *   3. Shizuku running but no permission → PERMISSION_DENIED
 *   4. Permission granted but UserService bind times out → SERVICE_UNAVAILABLE
 *   5. UserService bound but binder dies mid-operation → recoverable error
 *   6. UserService throws SecurityException → PERMISSION_DENIED
 *   7. UserService throws IllegalArgumentException → INVALID_ARGUMENT
 *   8. UserService throws OutOfMemoryError → NATIVE_CRASH (not recoverable)
 */

class ShizukuOfflineSimulationTest {

    private lateinit var helper: ShizukuHelper
    private lateinit var mockServiceStub: IGameMapperService

    @Before
    fun setUp() {
        // Create a mock IGameMapperService for tests that get past the bind phase.
        mockServiceStub = Mockito.mock(IGameMapperService::class.java)
        helper = ShizukuHelper()
    }

    // ───────── Scenario 1: Shizuku not installed ─────────

    @Test
    fun `scenario 1 - Shizuku not installed returns SERVICE_UNAVAILABLE`() {
        // Simulate: ShizukuProvider not found / context.packageManager.getPackageInfo throws
        helper.simulateState(ShizukuHelper.SimState.NOT_INSTALLED)

        val result = helper.bind()
        assertFailure(result, NativeCrashGuard.ErrorCode.SERVICE_UNAVAILABLE, recoverable = true)
    }

    // ───────── Scenario 2: Shizuku installed but not running ─────────

    @Test
    fun `scenario 2 - Shizuku not running returns SERVICE_UNAVAILABLE`() {
        helper.simulateState(ShizukuHelper.SimState.INSTALLED_NOT_RUNNING)

        val result = helper.bind()
        assertFailure(result, NativeCrashGuard.ErrorCode.SERVICE_UNAVAILABLE, recoverable = true)
    }

    // ───────── Scenario 3: Shizuku running but no permission ─────────

    @Test
    fun `scenario 3 - no permission returns PERMISSION_DENIED`() {
        helper.simulateState(ShizukuHelper.SimState.RUNNING_NO_PERMISSION)

        val result = helper.bind()
        assertFailure(result, NativeCrashGuard.ErrorCode.PERMISSION_DENIED, recoverable = false)
    }

    // ───────── Scenario 4: Bind timeout ─────────

    @Test
    fun `scenario 4 - UserService bind timeout returns SERVICE_UNAVAILABLE`() {
        helper.simulateState(ShizukuHelper.SimState.PERMISSION_GRANTED_BIND_TIMEOUT)

        val result = helper.bind()
        assertFailure(result, NativeCrashGuard.ErrorCode.SERVICE_UNAVAILABLE, recoverable = true)
    }

    // ───────── Scenario 5: Binder dies mid-operation ─────────

    @Test
    fun `scenario 5 - binder death during operation returns recoverable error`() {
        helper.simulateState(ShizukuHelper.SimState.BOUND)
        helper.bind()  // should succeed

        // Now simulate binder death
        helper.simulateBinderDeath()

        val result = helper.executeShellCommand("ls /")
        // Should fail with SERVICE_UNAVAILABLE (recoverable — Shizuku might restart)
        assertFailure(result, NativeCrashGuard.ErrorCode.SERVICE_UNAVAILABLE, recoverable = true)
    }

    // ───────── Scenario 6: SecurityException from UserService ─────────

    @Test
    fun `scenario 6 - SecurityException maps to PERMISSION_DENIED`() {
        helper.simulateState(ShizukuHelper.SimState.BOUND)
        helper.bind()

        // Configure mock to throw
        whenever(mockServiceStub.gamepadRead()).thenThrow(SecurityException("Shell permission denied"))

        val result = helper.gamepadRead()
        assertFailure(result, NativeCrashGuard.ErrorCode.PERMISSION_DENIED, recoverable = false)
    }

    // ───────── Scenario 7: IllegalArgumentException ─────────

    @Test
    fun `scenario 7 - IllegalArgumentException maps to INVALID_ARGUMENT`() {
        helper.simulateState(ShizukuHelper.SimState.BOUND)
        helper.bind()

        whenever(mockServiceStub.tap(0, 100, 200)).thenThrow(
            IllegalArgumentException("Invalid pointer slot")
        )

        val result = helper.tap(slot = 0, x = 100, y = 200)
        assertFailure(result, NativeCrashGuard.ErrorCode.INVALID_ARGUMENT, recoverable = true)
    }

    // ───────── Scenario 8: OutOfMemoryError ─────────

    @Test
    fun `scenario 8 - OutOfMemoryError maps to NATIVE_CRASH not recoverable`() {
        helper.simulateState(ShizukuHelper.SimState.BOUND)
        helper.bind()

        whenever(mockServiceStub.setProfile(Mockito.anyString())).thenThrow(OutOfMemoryError())

        val result = helper.setProfile("{}")
        assertFailure(result, NativeCrashGuard.ErrorCode.NATIVE_CRASH, recoverable = false)
    }

    // ───────── Scenario 9: Successful operation path ─────────

    @Test
    fun `scenario 9 - successful bind and tap`() {
        helper.simulateState(ShizukuHelper.SimState.BOUND)
        val bindResult = helper.bind()
        assertTrue("Expected bind success, got $bindResult", bindResult.isOk())

        whenever(mockServiceStub.tap(0, 100, 200)).thenReturn(true)
        val tapResult = helper.tap(slot = 0, x = 100, y = 200)
        assertTrue("Expected tap success, got $tapResult", tapResult.isOk())
    }

    // ───────── Scenario 10: Auto-rebind after transient failure ─────────

    @Test
    fun `scenario 10 - auto-rebind recovers from transient binder death`() {
        helper.simulateState(ShizukuHelper.SimState.BOUND)
        helper.bind()

        // First call: binder dies
        helper.simulateBinderDeath()
        val result1 = helper.tap(slot = 0, x = 100, y = 200)
        assertTrue(result1.isFailure())

        // Auto-rebind: simulate Shizuku restart
        helper.simulateState(ShizukuHelper.SimState.BOUND)
        whenever(mockServiceStub.tap(0, 100, 200)).thenReturn(true)

        val result2 = helper.tap(slot = 0, x = 100, y = 200)
        assertTrue("Expected auto-rebind to recover, got $result2", result2.isOk())
    }

    // ───────── Scenario 11: Permission revoked mid-session ─────────

    @Test
    fun `scenario 11 - permission revoked mid-session returns PERMISSION_DENIED`() {
        helper.simulateState(ShizukuHelper.SimState.BOUND)
        helper.bind()

        // Simulate user revoking permission via Shizuku Manager
        helper.simulateState(ShizukuHelper.SimState.RUNNING_NO_PERMISSION)

        val result = helper.tap(slot = 0, x = 100, y = 200)
        assertFailure(result, NativeCrashGuard.ErrorCode.PERMISSION_DENIED, recoverable = false)
    }

    // ───────── Scenario 12: Callback registration survives reconnect ─────────

    @Test
    fun `scenario 12 - BinderReceivedListener fires on reconnect`() {
        var receivedCount = 0
        helper.onBinderReceived = { receivedCount++ }

        helper.simulateState(ShizukuHelper.SimState.RUNNING_NO_PERMISSION)
        helper.bind()  // fails

        // Shizuku starts
        helper.simulateState(ShizukuHelper.SimState.BOUND)
        helper.bind()  // succeeds, fires onBinderReceived

        assertTrue("Expected onBinderReceived to fire on reconnect, got $receivedCount",
            receivedCount >= 1)
    }

    // ───────── Helpers ─────────

    private fun assertFailure(
        result: ShizukuHelper.OpResult,
        expectedCode: String,
        recoverable: Boolean
    ) {
        assertTrue("Expected failure, got success: $result", result.isFailure())
        val err = result.errorOrNull()
        assertNotNull("Expected error payload", err)
        assertEquals(expectedCode, err?.code)
        assertEquals(recoverable, err?.recoverable)
    }
}
