package com.nanomindexplorer.gamemappermind.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * FASE 3.4 — Kotlin-side canonical models for the game profile.
 *
 * Path di repo:
 *   android/app/src/main/java/com.nanomindexplorer.gamemappermind/model/ProfileModels.kt
 *
 * These @Serializable classes are the Kotlin mirror of:
 *   - schemas/game_profile.schema.json
 *   - src/schemas/gameProfile.ts
 *
 * All three MUST stay in sync. The Kotlin side uses kotlinx.serialization
 * with `explicitNulls = false` and `ignoreUnknownKeys = true` so future
 * schema additions don't break deserialization on older builds.
 *
 * Field naming convention:
 *   - Kotlin uses camelCase.
 *   - JSON uses snake_case for hyphenated concepts is NOT used here; this
 *     schema uses camelCase JSON keys to match the TypeScript mirror exactly.
 *     This avoids any naming translation layer at the JS bridge boundary.
 */

@Serializable
data class AntiBan(
    @SerialName("jitterPx")       val jitterPx: Float = 1.0f,
    @SerialName("timingJitterMs") val timingJitterMs: Float = 4.0f,
    @SerialName("pressureVar")    val pressureVar: Float = 0.05f
)

@Serializable
data class Mapping(
    @SerialName("id")           val id: Int,
    @SerialName("buttonCode")   val buttonCode: Int,
    @SerialName("buttonName")   val buttonName: String? = null,
    @SerialName("action")       val action: String,         // "tap" | "swipe" | "hold"
    @SerialName("xPercent")     val xPercent: Float,
    @SerialName("yPercent")     val yPercent: Float,
    @SerialName("endXPercent")  val endXPercent: Float = 0.0f,
    @SerialName("endYPercent")  val endYPercent: Float = 0.0f,
    @SerialName("durationMs")   val durationMs: Int = 80,
    @SerialName("pressure")     val pressure: Float = 1.0f,
    @SerialName("antiBan")      val antiBan: AntiBan = AntiBan()
) {
    companion object {
        const val ACTION_TAP   = "tap"
        const val ACTION_SWIPE = "swipe"
        const val ACTION_HOLD  = "hold"
    }
}

@Serializable
data class SwipeTrigger(
    @SerialName("buttonCode") val buttonCode: Int,
    @SerialName("direction")  val direction: String,   // "up" | "down" | "left" | "right"
    @SerialName("durationMs") val durationMs: Int = 120,
    @SerialName("span")       val span: Float = 0.33f
) {
    companion object {
        const val DIR_UP    = "up"
        const val DIR_DOWN  = "down"
        const val DIR_LEFT  = "left"
        const val DIR_RIGHT = "right"
    }
}

@Serializable
data class ScreenSize(
    @SerialName("width")  val width: Int,
    @SerialName("height") val height: Int
)

@Serializable
data class Deadzone(
    @SerialName("leftStick")  val leftStick: Float = 0.10f,
    @SerialName("rightStick") val rightStick: Float = 0.10f
)

@Serializable
data class Sensitivity(
    @SerialName("leftStick")  val leftStick: Float = 1.0f,
    @SerialName("rightStick") val rightStick: Float = 1.0f
)

@Serializable
data class ProfileMetadata(
    @SerialName("author")    val author: String,
    @SerialName("version")   val version: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("updatedAt") val updatedAt: String,
    @SerialName("notes")     val notes: String = "",
    @SerialName("tags")      val tags: List<String> = emptyList()
)

@Serializable
data class GameProfile(
    @SerialName("schemaVersion") val schemaVersion: String,
    @SerialName("profileId")     val profileId: String,
    @SerialName("gameName")      val gameName: String,
    @SerialName("packageName")   val packageName: String,
    @SerialName("screenSize")    val screenSize: ScreenSize,
    @SerialName("orientation")   val orientation: String,    // "landscape" | "portrait" | "auto"
    @SerialName("deadzone")      val deadzone: Deadzone = Deadzone(),
    @SerialName("sensitivity")   val sensitivity: Sensitivity = Sensitivity(),
    @SerialName("mappings")      val mappings: List<Mapping> = emptyList(),
    @SerialName("swipeTriggers") val swipeTriggers: List<SwipeTrigger> = emptyList(),
    @SerialName("metadata")      val metadata: ProfileMetadata
) {
    companion object {
        const val ORIENTATION_LANDSCAPE = "landscape"
        const val ORIENTATION_PORTRAIT  = "portrait"
        const val ORIENTATION_AUTO      = "auto"

        /** Supported schema versions — bump when adding breaking changes. */
        val SUPPORTED_VERSIONS = setOf("1.0.0")
    }
}
