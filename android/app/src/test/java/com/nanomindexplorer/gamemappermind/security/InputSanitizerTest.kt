package com.nanomindexplorer.gamemappermind.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito

/**
 * FASE 4.4 — Unit tests for InputSanitizer.
 *
 * Path di repo:
 *   android/app/src/test/java/com.nanomindexplorer.gamemappermind/security/InputSanitizerTest.kt
 *
 * Run: `./gradlew :app:testDebugUnitTest --tests "*.InputSanitizerTest"`
 */

class InputSanitizerTest {

    private fun makeCall(vararg pairs: Pair<String, Any>): com.getcapacitor.PluginCall {
        val jsObj = com.getcapacitor.JSObject()
        for ((k, v) in pairs) {
            jsObj.put(k, v)
        }
        // PluginCall has package-private constructors — use Mockito to stub the getters.
        val call = Mockito.mock(com.getcapacitor.PluginCall::class.java)
        for ((k, v) in pairs) {
            when (v) {
                is String  -> Mockito.`when`(call.getString(k)).thenReturn(v)
                is Int     -> Mockito.`when`(call.getInt(k)).thenReturn(v)
                is Double  -> Mockito.`when`(call.getDouble(k)).thenReturn(v)
                is Boolean -> Mockito.`when`(call.getBoolean(k)).thenReturn(v)
            }
        }
        return call
    }

    // ───── String validation ─────

    @Test
    fun `requireString accepts normal input`() {
        val call = makeCall("name" to "GameMapper")
        val result = InputSanitizer.requireString(call, "name")
        assertEquals("GameMapper", result)
    }

    @Test
    fun `requireString rejects null`() {
        val call = makeCall()
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireString(call, "missing")
        }
    }

    @Test
    fun `requireString rejects too long input`() {
        val long = "a".repeat(70_000)
        val call = makeCall("name" to long)
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireString(call, "name", maxLen = 65_536)
        }
    }

    @Test
    fun `requireString strips control chars but preserves newlines`() {
        val call = makeCall("name" to "line1\nline2\ttab\u0000null")
        val result = InputSanitizer.requireString(call, "name")
        // \n and \t preserved, \u0000 stripped
        assertEquals("line1\nline2\ttabnull", result)
    }

    @Test
    fun `requireString rejects path traversal`() {
        val call = makeCall("name" to "../../etc/passwd")
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireString(call, "name")
        }
    }

    @Test
    fun `requireString rejects file scheme`() {
        val call = makeCall("name" to "file:///data/data/com.gamemappermind")
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireString(call, "name")
        }
    }

    @Test
    fun `requireString rejects content scheme`() {
        val call = makeCall("name" to "content://contacts/1")
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireString(call, "name")
        }
    }

    // ───── Int validation ─────

    @Test
    fun `requireInt accepts in-range value`() {
        val call = makeCall("slot" to 42)
        assertEquals(42, InputSanitizer.requireInt(call, "slot", 0, 99))
    }

    @Test
    fun `requireInt rejects below range`() {
        val call = makeCall("slot" to -1)
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireInt(call, "slot", 0, 99)
        }
    }

    @Test
    fun `requireInt rejects above range`() {
        val call = makeCall("slot" to 100)
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireInt(call, "slot", 0, 99)
        }
    }

    @Test
    fun `requireInt rejects missing`() {
        val call = makeCall()
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireInt(call, "slot", 0, 99)
        }
    }

    // ───── Double validation ─────

    @Test
    fun `requireDouble rejects NaN`() {
        val call = makeCall("frac" to Double.NaN)
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireDouble(call, "frac", 0.0, 1.0)
        }
    }

    @Test
    fun `requireDouble rejects Infinity`() {
        val call = makeCall("frac" to Double.POSITIVE_INFINITY)
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireDouble(call, "frac", 0.0, 1.0)
        }
    }

    @Test
    fun `requireDouble accepts valid fraction`() {
        val call = makeCall("frac" to 0.5)
        assertEquals(0.5, InputSanitizer.requireDouble(call, "frac", 0.0, 1.0), 0.0001)
    }

    // ───── Pointer slot ─────

    @Test
    fun `requirePointerSlot accepts 0 and 99`() {
        assertEquals(0, InputSanitizer.requirePointerSlot(makeCall("slot" to 0)))
        assertEquals(99, InputSanitizer.requirePointerSlot(makeCall("slot" to 99)))
    }

    @Test
    fun `requirePointerSlot rejects 100`() {
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requirePointerSlot(makeCall("slot" to 100))
        }
    }

    // ───── Package name ─────

    @Test
    fun `requirePackageName accepts valid`() {
        val call = makeCall("pkg" to "com.tencent.ig")
        assertEquals("com.tencent.ig", InputSanitizer.requirePackageName(call, "pkg"))
    }

    @Test
    fun `requirePackageName rejects uppercase`() {
        val call = makeCall("pkg" to "Com.Tencent.IG")
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requirePackageName(call, "pkg")
        }
    }

    @Test
    fun `requirePackageName rejects single segment`() {
        val call = makeCall("pkg" to "tencent")
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requirePackageName(call, "pkg")
        }
    }

    // ───── JSON ─────

    @Test
    fun `requireJsonString accepts valid JSON`() {
        val json = """{"a":1,"b":[1,2,3]}"""
        val call = makeCall("data" to json)
        val result = InputSanitizer.requireJsonString(call, "data")
        assertEquals(json, result)
    }

    @Test
    fun `requireJsonString rejects too deep nesting`() {
        val deep = "[".repeat(40) + "]".repeat(40)
        val call = makeCall("data" to deep)
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireJsonString(call, "data")
        }
    }

    @Test
    fun `requireJsonString rejects oversized`() {
        val big = "x".repeat(300_000)
        val call = makeCall("data" to big)
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireJsonString(call, "data")
        }
    }

    @Test
    fun `requireJsonObject parses valid JSON`() {
        val call = makeCall("data" to """{"k":"v"}""")
        val obj = InputSanitizer.requireJsonObject(call, "data")
        assertEquals("v", obj.getString("k"))
    }

    @Test
    fun `requireJsonObject rejects malformed JSON`() {
        val call = makeCall("data" to """{not valid""")
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireJsonObject(call, "data")
        }
    }

    // ───── URL ─────

    @Test
    fun `requireAllowedUrl accepts https`() {
        val call = makeCall("url" to "https://example.com/path")
        val url = InputSanitizer.requireAllowedUrl(call, "url")
        assertNotNull(url)
    }

    @Test
    fun `requireAllowedUrl rejects file scheme`() {
        val call = makeCall("url" to "file:///etc/passwd")
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireAllowedUrl(call, "url")
        }
    }

    @Test
    fun `requireAllowedUrl rejects javascript scheme`() {
        val call = makeCall("url" to "javascript:alert(1)")
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireAllowedUrl(call, "url")
        }
    }

    // ───── Int array ─────

    @Test
    fun `requireIntArray accepts valid array`() {
        val call = makeCall("slots" to "[0, 1, 2, 3]")
        val result = InputSanitizer.requireIntArray(call, "slots") { it in 0..99 }
        assertEquals(listOf(0, 1, 2, 3), result)
    }

    @Test
    fun `requireIntArray rejects invalid element`() {
        val call = makeCall("slots" to "[0, 1, 200]")
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireIntArray(call, "slots") { it in 0..99 }
        }
    }

    @Test
    fun `requireIntArray rejects oversized array`() {
        val arr = (1..300).joinToString(",", "[", "]")
        val call = makeCall("slots" to arr)
        assertThrows(IllegalArgumentException::class.java) {
            InputSanitizer.requireIntArray(call, "slots", maxItems = 256) { true }
        }
    }
}
