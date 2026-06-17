package com.nanomindexplorer.gamemappermind.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FASE 3.4 — JVM unit tests for ProfileValidator.
 *
 * Path di repo:
 *   android/app/src/test/java/com.nanomindexplorer.gamemappermind/model/ProfileValidatorTest.kt
 *
 * Run with: `./gradlew :app:testDebugUnitTest --tests "*.ProfileValidatorTest"`
 *
 * Each test mirrors a case in src/schemas/__tests__/gameProfile.test.ts.
 * If a behavior changes on one side, change both.
 */

class ProfileValidatorTest {

    private val baseProfileJson = """
        {
          "schemaVersion": "1.0.0",
          "profileId": "test-profile",
          "gameName": "Test Game",
          "packageName": "com.example.test",
          "screenSize": { "width": 2800, "height": 1840 },
          "orientation": "landscape",
          "deadzone":   { "leftStick": 0.10, "rightStick": 0.10 },
          "sensitivity":{ "leftStick": 1.0,  "rightStick": 1.0 },
          "mappings": [
            {
              "id": 0,
              "buttonCode": 304,
              "buttonName": "A",
              "action": "tap",
              "xPercent": 0.5,
              "yPercent": 0.5,
              "durationMs": 80,
              "pressure": 1.0,
              "antiBan": { "jitterPx": 1.0, "timingJitterMs": 4.0, "pressureVar": 0.05 }
            }
          ],
          "swipeTriggers": [
            { "buttonCode": 314, "direction": "left", "durationMs": 120, "span": 0.33 }
          ],
          "metadata": {
            "author": "tester",
            "version": "1.0.0",
            "createdAt": "2026-06-01T00:00:00Z",
            "updatedAt": "2026-06-17T00:00:00Z",
            "notes": "",
            "tags": []
          }
        }
    """.trimIndent()

    @Test
    fun `accepts minimal valid profile`() {
        val result = ProfileValidator.parseAndValidate(baseProfileJson)
        assertTrue("Expected Ok, got $result", result is ProfileValidator.ValidationResult.Ok)
    }

    @Test
    fun `applies defaults for optional fields`() {
        val minimal = """
            {
              "schemaVersion": "1.0.0",
              "profileId": "minimal",
              "gameName": "Min",
              "packageName": "com.example.min",
              "screenSize": { "width": 1080, "height": 1920 },
              "orientation": "portrait",
              "mappings": [],
              "metadata": {
                "author": "a",
                "version": "1.0.0",
                "createdAt": "2026-01-01T00:00:00Z",
                "updatedAt": "2026-01-01T00:00:00Z"
              }
            }
        """.trimIndent()
        val result = ProfileValidator.parseAndValidate(minimal)
        assertTrue(result is ProfileValidator.ValidationResult.Ok)
        val profile = (result as ProfileValidator.ValidationResult.Ok).profile
        assertEquals(0.10f, profile.deadzone.leftStick, 0.001f)
        assertEquals(1.0f, profile.sensitivity.rightStick, 0.001f)
        assertEquals(0, profile.swipeTriggers.size)
        assertEquals("", profile.metadata.notes)
    }

    @Test
    fun `rejects non-semver schemaVersion`() {
        val json = baseProfileJson.replace("\"1.0.0\"", "\"v1.0\"")
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
        val errs = (result as ProfileValidator.ValidationResult.Err).joined()
        assertTrue("Expected semver error, got: $errs", errs.contains("semver", ignoreCase = true))
    }

    @Test
    fun `rejects uppercase profileId`() {
        val json = baseProfileJson.replace("\"test-profile\"", "\"PUBGM\"")
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
    }

    @Test
    fun `rejects invalid packageName`() {
        val json = baseProfileJson.replace("\"com.example.test\"", "\"not-a-package\"")
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
    }

    @Test
    fun `rejects xPercent > 1_0`() {
        val json = baseProfileJson.replace("\"xPercent\": 0.5", "\"xPercent\": 1.5")
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
    }

    @Test
    fun `rejects mapping id > 89`() {
        val json = baseProfileJson.replace("\"id\": 0,", "\"id\": 90,")
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
    }

    @Test
    fun `rejects swipe with zero end coordinates`() {
        val json = baseProfileJson
            .replace("\"action\": \"tap\"", "\"action\": \"swipe\"")
            .replace("\"xPercent\": 0.5,\n              \"yPercent\": 0.5,",
                     "\"xPercent\": 0.5,\n              \"yPercent\": 0.5,\n              \"endXPercent\": 0.0,\n              \"endYPercent\": 0.0,")
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
        val errs = (result as ProfileValidator.ValidationResult.Err).joined()
        assertTrue("Expected swipe error, got: $errs", errs.contains("swipe", ignoreCase = true))
    }

    @Test
    fun `rejects duplicate mapping ids`() {
        val json = baseProfileJson.replace(
            "\"buttonCode\": 304,",
            "\"id\": 0,\n              \"buttonCode\": 305,",
        )
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
        val errs = (result as ProfileValidator.ValidationResult.Err).joined()
        assertTrue("Expected duplicate id error, got: $errs", errs.contains("duplicate id", ignoreCase = true))
    }

    @Test
    fun `rejects duplicate mapping buttonCodes`() {
        val json = baseProfileJson.replace(
            "\"buttonCode\": 304,",
            "\"id\": 1,\n              \"buttonCode\": 304,",
        )
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
        val errs = (result as ProfileValidator.ValidationResult.Err).joined()
        assertTrue("Expected duplicate buttonCode error, got: $errs", errs.contains("duplicate buttonCode", ignoreCase = true))
    }

    @Test
    fun `rejects swipe trigger buttonCode colliding with mapping`() {
        val json = baseProfileJson.replace(
            "\"buttonCode\": 314, \"direction\": \"left\"",
            "\"buttonCode\": 304, \"direction\": \"left\""
        )
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
        val errs = (result as ProfileValidator.ValidationResult.Err).joined()
        assertTrue("Expected collide error, got: $errs", errs.contains("collide", ignoreCase = true))
    }

    @Test
    fun `rejects unsupported schemaVersion`() {
        val json = baseProfileJson.replace("\"1.0.0\"", "\"2.0.0\"", 1)
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue(result is ProfileValidator.ValidationResult.Err)
        val errs = (result as ProfileValidator.ValidationResult.Err).joined()
        assertTrue("Expected unsupported error, got: $errs", errs.contains("unsupported", ignoreCase = true))
    }

    @Test
    fun `ignores unknown fields for forward compat`() {
        val json = baseProfileJson.replace(
            "\"orientation\": \"landscape\",",
            "\"orientation\": \"landscape\",\n  \"unknownFutureField\": 42,"
        )
        val result = ProfileValidator.parseAndValidate(json)
        assertTrue("Expected Ok despite unknown field, got $result",
            result is ProfileValidator.ValidationResult.Ok)
    }

    @Test
    fun `round-trip serialize preserves data`() {
        val result = ProfileValidator.parseAndValidate(baseProfileJson)
        assertTrue(result is ProfileValidator.ValidationResult.Ok)
        val profile = (result as ProfileValidator.ValidationResult.Ok).profile
        val reserialized = ProfileValidator.serialize(profile)
        val result2 = ProfileValidator.parseAndValidate(reserialized)
        assertTrue(result2 is ProfileValidator.ValidationResult.Ok)
    }
}
