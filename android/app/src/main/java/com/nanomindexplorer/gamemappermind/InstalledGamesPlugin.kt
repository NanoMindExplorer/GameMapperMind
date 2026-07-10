package com.nanomindexplorer.gamemappermind

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Base64
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.ByteArrayOutputStream

@CapacitorPlugin(name = "InstalledGames")
class InstalledGamesPlugin : Plugin() {

    /**
     * Scan installed apps and return a list of "games" — apps that are:
     *   1. Not system apps (FLAG_SYSTEM bit not set)
     *   2. Have a launch intent (Activity with ACTION_MAIN + CATEGORY_LAUNCHER)
     *   3. Either:
     *      - ApplicationInfo.category == CATEGORY_GAME (API 26+)
     *      - OR package name / app name matches known game patterns
     *      - OR the app is in our curated list of common mobile game packages
     *
     * Returns: [{ packageName, name, iconBase64 (PNG), isGame }]
     */
    @PluginMethod
    fun listInstalledGames(call: PluginCall) {
        try {
            val pm = context.packageManager
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val result = JSArray()

            // Curated list of common mobile game packages (Indonesia + global)
            // Used as fallback when ApplicationInfo.category is not set (many games don't set it)
            val knownGamePackages = setOf(
                // Konami
                "jp.konami.pesam", "com.konami.pesam", "com.konami.pes2017",
                // Tencent
                "com.tencent.ig", "com.tencent.tmgp.pubgmhd", "com.tencent.tmgp.sgame",
                // Genshin / HoYoverse
                "com.miHoYo.GenshinImpact", "com.miHoYo.HoYoLAB", "com.HoYoverse.hkrpgoversea",
                "com.miHoYo.GenshinImpact_lite",
                // Moonton
                "com.mobile.legends",
                // Garena
                "com.dts.freefireth", "com.dts.freefiremax",
                // Activision / CoD
                "com.activision.callofduty.shooter",
                // Supercell
                "com.supercell.clashroyale", "com.supercell.clashofclans",
                "com.supercell.brawlstars", "com.supercell.hayday",
                // Riot
                "com.riotgames.league.wildrift",
                // EA
                "com.ea.gp.fifamobile", "com.ea.gp.nbaamobile",
                // NetEase
                "com.netease.idv.googleplay", "com.netease.onmyoji",
                // Square Enix
                "com.square_enix.android_googleplay.FFVII",
                // Battle Royale variants
                "com.dts.freefireth", "com.garena.game.codm",
                // Honor of Kings
                "com.levelgame.hok", "com.tencent.tmgp.hok"
            )

            // Heuristic name patterns for game detection
            val gameNamePatterns = listOf(
                Regex("(?i)(game|games|gaming|arena|battle|war|legend|clash|royale|strike|force|hero|quest|adventure|rpg|fps|moba|survival|shooter|football|soccer|pes|fifa|nba|mobile legends|free fire|pubg|cod|callofduty|wildrift|genshin|hoyoverse|brawl|clash)"),
                Regex("(?i)^(p(es|esam)|ff|cod|ml|mlbb|hok|gi)")
            )

            for (appInfo in allApps) {
                // Skip system apps
                if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                // Skip apps without launch intent
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent == null) continue

                // Determine if this is a game
                var isGame = false

                // Method 1: ApplicationInfo.category (API 26+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (appInfo.category == ApplicationInfo.CATEGORY_GAME) {
                        isGame = true
                    }
                }

                // Method 2: Known curated package
                if (!isGame && knownGamePackages.contains(appInfo.packageName)) {
                    isGame = true
                }

                // Method 3: Name heuristic
                if (!isGame) {
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    if (gameNamePatterns.any { it.containsMatchIn(appName) }) {
                        isGame = true
                    }
                }

                if (isGame) {
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    val iconB64 = try {
                        val icon = pm.getApplicationIcon(appInfo)
                        drawableToBase64Png(icon)
                    } catch (e: Exception) { "" }

                    val obj = JSObject()
                    obj.put("packageName", appInfo.packageName)
                    obj.put("name", appName)
                    obj.put("iconBase64", iconB64)
                    obj.put("isGame", true)
                    result.put(obj)
                }
            }

            val ret = JSObject()
            ret.put("games", result)
            ret.put("count", result.length())
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to list installed games: ${e.message}")
        }
    }

    /**
     * List ALL installed user apps (not filtered by game heuristic).
     * Useful for user to manually add non-detected games.
     */
    @PluginMethod
    fun listAllUserApps(call: PluginCall) {
        try {
            val pm = context.packageManager
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val result = JSArray()

            for (appInfo in allApps) {
                if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent == null) continue

                val appName = pm.getApplicationLabel(appInfo).toString()
                val iconB64 = try {
                    val icon = pm.getApplicationIcon(appInfo)
                    drawableToBase64Png(icon)
                } catch (e: Exception) { "" }

                val obj = JSObject()
                obj.put("packageName", appInfo.packageName)
                obj.put("name", appName)
                obj.put("iconBase64", iconB64)
                result.put(obj)
            }

            val ret = JSObject()
            ret.put("apps", result)
            ret.put("count", result.length())
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("Failed to list installed apps: ${e.message}")
        }
    }

    /**
     * Launch an app by its package name via PackageManager.getLaunchIntentForPackage.
     */
    @PluginMethod
    fun launchApp(call: PluginCall) {
        val packageName = call.getString("packageName")
        if (packageName == null || packageName.isEmpty()) {
            call.reject("packageName must be provided")
            return
        }
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                call.reject("No launch intent for package: $packageName")
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Failed to launch $packageName: ${e.message}")
        }
    }

    /**
     * Convert a Drawable to a base64-encoded PNG string for transfer to JS.
     * Icon is downscaled to 96x96 to keep payload small (96 * 96 * 4 bytes raw = 36KB raw,
     * ~5KB as base64 PNG).
     */
    private fun drawableToBase64Png(drawable: Drawable): String {
        val size = 96
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        bitmap.recycle()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
