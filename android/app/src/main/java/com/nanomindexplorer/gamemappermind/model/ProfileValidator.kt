package com.nanomindexplorer.gamemappermind.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonException

/**
 * FASE 3.4 — Profile parser + cross-field validator.
 *
 * Path di repo:
 *   android/app/src/main/java/com.nanomindexplorer.gamemappermind/model/ProfileValidator.kt
 *
 * Responsibilities:
 *   1) Parse a JSON string into a GameProfile using kotlinx.serialization.
 *   2) Validate semantic invariants that the @Serializable classes can't express.
 *   3) Return a structured ValidationResult (never throws).
 *
 * The validation rules here mirror the Zod `.superRefine()` checks on the
 * TypeScript side. If you add a rule there, add it here too.
 *
 * Usage:
 *   val result = ProfileValidator.parseAndValidate(jsonString)
 *   if (result is ValidationResult.Ok) {
 *       val profile = result.profile
 *       pipeline.setProfile(profile)
 *   } else {
 *       Log.e(TAG, result.errors.joinToString("; "))
 *   }
 */

object ProfileValidator {

    /**
     * Configured JSON parser.
     * - `ignoreUnknownKeys = true`  : forward-compat — new JSON fields don't break old builds.
     * - `explicitNulls = false`     : missing fields use defaults from @Serializable.
     * - `isLenient = true`          : allow trailing commas, unquoted strings where unambiguous.
     * - `coerceInputValues = true`  : coerce numeric strings to numbers where the target is numeric.
     */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    sealed class ValidationResult {
        data class Ok(val profile: GameProfile) : ValidationResult()
        data class Err(val errors: List<String>) : ValidationResult() {
            fun joined(): String = errors.joinToString("; ")
        }
    }

    /**
     * Parse a JSON string into a GameProfile, then run all semantic validators.
     * Returns Ok(profile) or Err(list of error messages). Never throws.
     */
    fun parseAndValidate(jsonStr: String): ValidationResult {
        val parseErrors = mutableListOf<String>()

        // 1) Deserialize.
        val profile: GameProfile = try {
            json.decodeFromString(GameProfile.serializer(), jsonStr)
        } catch (e: JsonException) {
            return ValidationResult.Err(listOf("JSON parse error: ${e.message}"))
        } catch (e: Throwable) {
            return ValidationResult.Err(listOf("Unexpected parse error: ${e.message}"))
        }

        // 2) Run all validators.
        val errors = mutableListOf<String>()
        errors += validateSchemaVersion(profile)
        errors += validateProfileId(profile)
        errors += validatePackageName(profile)
        errors += validateScreenSize(profile)
        errors += validateOrientation(profile)
        errors += validateDeadzone(profile)
        errors += validateSensitivity(profile)
        errors += validateMappingIds(profile)
        errors += validateMappingButtonCodes(profile)
        errors += validateMappingActions(profile)
        errors += validateSwipeTriggers(profile)
        errors += validateSwipeTriggerCollisions(profile)
        errors += validateMetadata(profile)

        return if (errors.isEmpty()) ValidationResult.Ok(profile)
               else ValidationResult.Err(errors)
    }

    /**
     * Convenience: validate an already-parsed GameProfile (e.g., one built in memory).
     */
    fun validate(profile: GameProfile): ValidationResult {
        val errors = mutableListOf<String>()
        errors += validateSchemaVersion(profile)
        errors += validateProfileId(profile)
        errors += validatePackageName(profile)
        errors += validateScreenSize(profile)
        errors += validateOrientation(profile)
        errors += validateDeadzone(profile)
        errors += validateSensitivity(profile)
        errors += validateMappingIds(profile)
        errors += validateMappingButtonCodes(profile)
        errors += validateMappingActions(profile)
        errors += validateSwipeTriggers(profile)
        errors += validateSwipeTriggerCollisions(profile)
        errors += validateMetadata(profile)
        return if (errors.isEmpty()) ValidationResult.Ok(profile)
               else ValidationResult.Err(errors)
    }

    /** Serialize a GameProfile back to canonical JSON. */
    fun serialize(profile: GameProfile): String {
        return json.encodeToString(GameProfile.serializer(), profile)
    }

    // ───────────────────────────────────────────────────────────────────────
    // Individual validators
    // ───────────────────────────────────────────────────────────────────────

    private val SCHEMA_VERSION_RE = Regex("""^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$""")
    private val PROFILE_ID_RE     = Regex("""^[a-z0-9]+(-[a-z0-9]+)*$""")
    private val PACKAGE_NAME_RE   = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")
    private val SEMVER_RE         = Regex("""^\d+\.\d+\.\d+$""")
    private val ISO_DATE_RE       = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$""")

    private fun validateSchemaVersion(p: GameProfile): List<String> {
        val errs = mutableListOf<String>()
        if (!SCHEMA_VERSION_RE.matches(p.schemaVersion)) {
            errs += "schemaVersion: invalid semver format '${p.schemaVersion}'"
        }
        if (p.schemaVersion !in GameProfile.SUPPORTED_VERSIONS) {
            errs += "schemaVersion: unsupported '${p.schemaVersion}'. " +
                    "Supported: ${GameProfile.SUPPORTED_VERSIONS.joinToString(", ")}"
        }
        return errs
    }

    private fun validateProfileId(p: GameProfile): List<String> {
        val errs = mutableListOf<String>()
        if (!PROFILE_ID_RE.matches(p.profileId)) {
            errs += "profileId: must be lowercase kebab-case, got '${p.profileId}'"
        }
        if (p.profileId.length > 64) {
            errs += "profileId: max length 64, got ${p.profileId.length}"
        }
        return errs
    }

    private fun validatePackageName(p: GameProfile): List<String> {
        return if (!PACKAGE_NAME_RE.matches(p.packageName)) {
            listOf("packageName: invalid Android package name '${p.packageName}'")
        } else emptyList()
    }

    private fun validateScreenSize(p: GameProfile): List<String> {
        val errs = mutableListOf<String>()
        val (w, h) = p.screenSize.width to p.screenSize.height
        if (w !in 1..7680) errs += "screenSize.width: must be 1..7680, got $w"
        if (h !in 1..4320) errs += "screenSize.height: must be 1..4320, got $h"
        return errs
    }

    private fun validateOrientation(p: GameProfile): List<String> {
        val allowed = setOf(
            GameProfile.ORIENTATION_LANDSCAPE,
            GameProfile.ORIENTATION_PORTRAIT,
            GameProfile.ORIENTATION_AUTO
        )
        return if (p.orientation !in allowed)
            listOf("orientation: must be one of $allowed, got '${p.orientation}'")
        else emptyList()
    }

    private fun validateDeadzone(p: GameProfile): List<String> {
        val errs = mutableListOf<String>()
        if (p.deadzone.leftStick !in 0.0f..0.5f)
            errs += "deadzone.leftStick: must be 0.0..0.5, got ${p.deadzone.leftStick}"
        if (p.deadzone.rightStick !in 0.0f..0.5f)
            errs += "deadzone.rightStick: must be 0.0..0.5, got ${p.deadzone.rightStick}"
        return errs
    }

    private fun validateSensitivity(p: GameProfile): List<String> {
        val errs = mutableListOf<String>()
        if (p.sensitivity.leftStick !in 0.1f..5.0f)
            errs += "sensitivity.leftStick: must be 0.1..5.0, got ${p.sensitivity.leftStick}"
        if (p.sensitivity.rightStick !in 0.1f..5.0f)
            errs += "sensitivity.rightStick: must be 0.1..5.0, got ${p.sensitivity.rightStick}"
        return errs
    }

    private fun validateMappingIds(p: GameProfile): List<String> {
        val errs = mutableListOf<String>()
        if (p.mappings.size > 50) {
            errs += "mappings: max 50 entries, got ${p.mappings.size}"
        }
        // Range check.
        p.mappings.forEachIndexed { i, m ->
            if (m.id !in 0..89) {
                errs += "mappings[$i].id: must be 0..89, got ${m.id}"
            }
            if (m.buttonCode !in 0..1023) {
                errs += "mappings[$i].buttonCode: must be 0..1023, got ${m.buttonCode}"
            }
            if (m.xPercent !in 0.0f..1.0f) {
                errs += "mappings[$i].xPercent: must be 0.0..1.0, got ${m.xPercent}"
            }
            if (m.yPercent !in 0.0f..1.0f) {
                errs += "mappings[$i].yPercent: must be 0.0..1.0, got ${m.yPercent}"
            }
            if (m.durationMs !in 16..5000) {
                errs += "mappings[$i].durationMs: must be 16..5000, got ${m.durationMs}"
            }
            if (m.pressure !in 0.0f..1.0f) {
                errs += "mappings[$i].pressure: must be 0.0..1.0, got ${m.pressure}"
            }
        }
        // Duplicate ids.
        val ids = p.mappings.map { it.id }
        val dupIds = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (dupIds.isNotEmpty()) {
            errs += "mappings: duplicate id(s) ${dupIds.sorted()}"
        }
        return errs
    }

    private fun validateMappingButtonCodes(p: GameProfile): List<String> {
        val btns = p.mappings.map { it.buttonCode }
        val dup = btns.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        return if (dup.isNotEmpty()) {
            listOf("mappings: duplicate buttonCode(s) ${dup.sorted()}")
        } else emptyList()
    }

    private fun validateMappingActions(p: GameProfile): List<String> {
        val errs = mutableListOf<String>()
        val allowed = setOf(Mapping.ACTION_TAP, Mapping.ACTION_SWIPE, Mapping.ACTION_HOLD)
        p.mappings.forEachIndexed { i, m ->
            if (m.action !in allowed) {
                errs += "mappings[$i].action: must be one of $allowed, got '${m.action}'"
            }
            if (m.action == Mapping.ACTION_SWIPE) {
                if (m.endXPercent == 0.0f && m.endYPercent == 0.0f) {
                    errs += "mappings[$i]: swipe action requires endXPercent/endYPercent"
                }
                if (m.endXPercent !in 0.0f..1.0f) {
                    errs += "mappings[$i].endXPercent: must be 0.0..1.0, got ${m.endXPercent}"
                }
                if (m.endYPercent !in 0.0f..1.0f) {
                    errs += "mappings[$i].endYPercent: must be 0.0..1.0, got ${m.endYPercent}"
                }
            }
        }
        return errs
    }

    private fun validateSwipeTriggers(p: GameProfile): List<String> {
        val errs = mutableListOf<String>()
        if (p.swipeTriggers.size > 20) {
            errs += "swipeTriggers: max 20 entries, got ${p.swipeTriggers.size}"
        }
        val allowedDirs = setOf(
            SwipeTrigger.DIR_UP, SwipeTrigger.DIR_DOWN,
            SwipeTrigger.DIR_LEFT, SwipeTrigger.DIR_RIGHT
        )
        p.swipeTriggers.forEachIndexed { i, t ->
            if (t.buttonCode !in 0..1023) {
                errs += "swipeTriggers[$i].buttonCode: must be 0..1023, got ${t.buttonCode}"
            }
            if (t.direction !in allowedDirs) {
                errs += "swipeTriggers[$i].direction: must be one of $allowedDirs, got '${t.direction}'"
            }
            if (t.durationMs !in 16..2000) {
                errs += "swipeTriggers[$i].durationMs: must be 16..2000, got ${t.durationMs}"
            }
            if (t.span !in 0.1f..1.0f) {
                errs += "swipeTriggers[$i].span: must be 0.1..1.0, got ${t.span}"
            }
        }
        return errs
    }

    private fun validateSwipeTriggerCollisions(p: GameProfile): List<String> {
        val mappingBtns = p.mappings.map { it.buttonCode }.toSet()
        val colliding = p.swipeTriggers
            .map { it.buttonCode }
            .filter { it in mappingBtns }
            .distinct()
        return if (colliding.isNotEmpty()) {
            listOf("swipeTriggers: buttonCode(s) $colliding collide with mappings")
        } else emptyList()
    }

    private fun validateMetadata(p: GameProfile): List<String> {
        val errs = mutableListOf<String>()
        val m = p.metadata
        if (m.author.isBlank()) errs += "metadata.author: must not be blank"
        if (m.author.length > 64) errs += "metadata.author: max length 64, got ${m.author.length}"
        if (!SEMVER_RE.matches(m.version)) errs += "metadata.version: invalid semver '${m.version}'"
        if (!ISO_DATE_RE.matches(m.createdAt)) {
            errs += "metadata.createdAt: invalid ISO-8601 datetime '${m.createdAt}'"
        }
        if (!ISO_DATE_RE.matches(m.updatedAt)) {
            errs += "metadata.updatedAt: invalid ISO-8601 datetime '${m.updatedAt}'"
        }
        if (m.notes.length > 512) errs += "metadata.notes: max length 512, got ${m.notes.length}"
        if (m.tags.size > 10) errs += "metadata.tags: max 10 entries, got ${m.tags.size}"
        m.tags.forEachIndexed { i, tag ->
            if (tag.length > 32) {
                errs += "metadata.tags[$i]: max length 32, got ${tag.length}"
            }
        }
        return errs
    }
}
