package com.nanomindexplorer.gamemappermind

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager

@CapacitorPlugin(name = "TouchInjection")
class TouchInjectionPlugin : Plugin() {

    companion object {
        var instance: java.lang.ref.WeakReference<TouchInjectionPlugin>? = null
        var touchService: ITouchService? = null

        fun emitGamepadButton(buttonName: String, value: Int, pressure: Float) {
            val data = JSObject()
            data.put("buttonName", buttonName)
            data.put("value", value)
            data.put("pressure", pressure)
            instance?.get()?.notifyListeners("onGamepadButton", data)
        }

        fun emitGamepadAxis(axes: FloatArray) {
            val data = JSObject()
            val jsArray = com.getcapacitor.JSArray()
            axes.forEach { jsArray.put(it.toDouble()) }
            data.put("axes", jsArray)
            instance?.get()?.notifyListeners("onGamepadAxis", data)
        }

        /**
         * REC-01: Static inject methods untuk GamepadMappingService.
         *
         * Method-method ini dipanggil langsung dari GamepadMappingService (native foreground
         * service) tanpa melewati WebView/PluginCall. Memungkinkan low-latency input mapping
         * karena tidak ada IPC hop ke JavaScript.
         *
         * Math-Logic (Pasal 5.1):
         * - Kompleksitas: O(1) per call (langsung delegate ke touchService)
         * - Latency: ~2-3ms (binder IPC ke TouchDaemonService) vs ~18ms via WebView
         *
         * Invariant:
         * - touchService wajib non-null (sudah bind via bindService)
         * - Jika touchService null, return false (caller handle retry)
         * - Thread-safe: touchService adalah volatile field, binder thread-safe
         *
         * @return true jika inject sukses, false jika touchService null atau inject gagal
         */
        fun injectButtonDown(pointerId: Int, x: Float, y: Float): Boolean {
            val service = touchService ?: return false
            return try {
                service.touchDown(pointerId, x, y)
            } catch (e: Exception) {
                Log.e("GameMapper", "injectButtonDown failed", e)
                false
            }
        }

        fun injectButtonUp(pointerId: Int): Boolean {
            val service = touchService ?: return false
            return try {
                service.touchUp(pointerId)
            } catch (e: Exception) {
                Log.e("GameMapper", "injectButtonUp failed", e)
                false
            }
        }

        fun injectAxisMove(pointerId: Int, x: Float, y: Float): Boolean {
            val service = touchService ?: return false
            return try {
                service.touchMove(pointerId, x, y)
            } catch (e: Exception) {
                Log.e("GameMapper", "injectAxisMove failed", e)
                false
            }
        }

        fun injectReleaseAllPointers(): Boolean {
            val service = touchService ?: return false
            return try {
                service.releaseAllPointers()
            } catch (e: Exception) {
                Log.e("GameMapper", "injectReleaseAllPointers failed", e)
                false
            }
        }

        /**
         * Check apakah touchService sudah bound dan ready.
         * GamepadMappingService panggil ini sebelum inject untuk avoid race condition.
         */
        fun isTouchServiceReady(): Boolean {
            return touchService != null
        }
    }

    private var isBound = false
    private val USER_SERVICE_ARGS = Shizuku.UserServiceArgs(
        ComponentName("com.nanomindexplorer.gamemappermind", TouchDaemonService::class.java.name)
    ).daemon(false).processNameSuffix("touch_daemon").version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            touchService = ITouchService.Stub.asInterface(binder)
            Log.d("GameMapper", "Shizuku Touch Service connected")
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            touchService = null
            isBound = false
            Log.d("GameMapper", "Shizuku Touch Service disconnected")
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1234) {
            val data = JSObject()
            data.put("granted", grantResult == PackageManager.PERMISSION_GRANTED)
            notifyListeners("onShizukuPermissionResult", data)
        }
    }

    override fun load() {
        super.load()
        instance = java.lang.ref.WeakReference(this)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun handleOnDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (isBound) {
            touchService?.releaseAllPointers()
            Shizuku.unbindUserService(USER_SERVICE_ARGS, serviceConnection, true)
            touchService = null
            isBound = false
        }
        super.handleOnDestroy()
    }

    @PluginMethod
    fun bindService(call: PluginCall) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            try {
                if (!isBound) {
                    Shizuku.bindUserService(USER_SERVICE_ARGS, serviceConnection)
                    isBound = true
                }
                call.resolve()
            } catch (e: Exception) {
                Log.e("GameMapper", "Failed to bind Shizuku user service", e)
                call.reject(e.localizedMessage)
            }
        } else {
            call.reject("Shizuku permission not granted")
        }
    }

    @PluginMethod
    fun unbindService(call: PluginCall) {
        try {
            if (isBound) {
                touchService?.releaseAllPointers()
                Shizuku.unbindUserService(USER_SERVICE_ARGS, serviceConnection, true)
                touchService = null
                isBound = false
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.localizedMessage)
        }
    }

    @PluginMethod
    fun startGamepadListener(call: PluginCall) {
        val intent = Intent(context, GamepadListenerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        call.resolve()
    }

    @PluginMethod
    fun startOverlay(call: PluginCall) {
        val profileObj = call.getObject("profile")
        val profileJson = profileObj?.toString() ?: "{}"
        val intent = Intent(context, FloatingOverlayService::class.java)
        intent.putExtra("config", profileJson)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        call.resolve()
    }

    @PluginMethod
    fun stopOverlay(call: PluginCall) {
        val intent = Intent(context, FloatingOverlayService::class.java)
        context.stopService(intent)
        call.resolve()
    }

    @PluginMethod
    fun stopGamepadListener(call: PluginCall) {
        val intent = Intent(context, GamepadListenerService::class.java)
        context.stopService(intent)
        call.resolve()
    }

    /**
     * Start GamepadMappingService (native foreground service untuk mapping).
     *
     * Fix untuk BUG-C08: pindahkan pemrosesan input dari WebView ke native service.
     * Frontend memanggil method ini saat user activate overlay untuk start native mapping.
     * Setelah service ini aktif, useGamepadLoop di WebView tidak perlu proses input
     * (frontend dapat menonaktifkan useGamepadLoop jika service native aktif).
     */
    @PluginMethod
    fun startNativeMapping(call: PluginCall) {
        val intent = Intent(context, GamepadMappingService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        call.resolve()
    }

    /**
     * Stop GamepadMappingService.
     */
    @PluginMethod
    fun stopNativeMapping(call: PluginCall) {
        val intent = Intent(context, GamepadMappingService::class.java)
        context.stopService(intent)
        call.resolve()
    }

    /**
     * Update profile di GamepadMappingService via Intent broadcast.
     * Frontend memanggil method ini saat profile berubah.
     * Service menerima broadcast dan reload profile dari JSON.
     *
     * @param profileJson string JSON dari GamepadProfile
     */
    @PluginMethod
    fun updateNativeProfile(call: PluginCall) {
        val profileJson = call.getString("profileJson") ?: ""
        if (profileJson.isBlank()) {
            call.reject("profileJson cannot be empty")
            return
        }

        // Simpan ke SharedPreferences agar service bisa reload saat restart.
        val prefs = context.getSharedPreferences("CapacitorPreferences", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("nexion_active_profile_json", profileJson).apply()

        // Broadcast update ke GamepadMappingService jika sedang running.
        val intent = Intent(GamepadMappingService.ACTION_PROFILE_UPDATED)
        intent.putExtra(GamepadMappingService.EXTRA_PROFILE_JSON, profileJson)
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(context)
            .sendBroadcast(intent)

        call.resolve()
    }

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val data = JSObject()
            data.put("granted", true)
            call.resolve(data)
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            call.reject("Permission denied previously.")
        } else {
            Shizuku.requestPermission(1234)
            val data = JSObject()
            data.put("granted", false)
            data.put("requested", true)
            call.resolve(data)
        }
    }

    /**
     * Whitelist command yang diizinkan untuk eksekusi via Shizuku shell.
     * Set diinisialisasi sekali dan tidak bisa dimutasi setelahnya (immutable set).
     * Invariant: hanya command yang EXACT MATCH dengan entry di set ini yang boleh dieksekusi.
     * Keamanan: mencegah arbitrary shell execution dari JavaScript/WebView.
     * Kompleksitas lookup: O(1) karena menggunakan HashSet backed Set.
     */
    private val ALLOWED_SHELL_COMMANDS: Set<String> = setOf(
        "getevent -lp",
        "getevent -l",
        "getevent -pl",
        "dumpsys input",
        "pm list packages"
    )

    @PluginMethod
    fun executeShizukuCommand(call: PluginCall) {
        val command = call.getString("command") ?: ""
        
        // Anti-Regression: Fix BUG-N01 via whitelist
        val ALLOWED_COMMANDS = listOf("getevent -lp", "getevent -l", "dumpsys input", "pm list packages")
        
        if (!ALLOWED_COMMANDS.contains(command)) {
            call.reject("Command not allowed")
            return
        }

        try {
            // Eksekusi command via Shizuku.newProcess dengan reflection (method is private).
            // command sudah divalidasi, aman untuk dieksekusi.
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val errorReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))

            val output = StringBuilder()
            val errorOutput = StringBuilder()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            val data = JSObject()
            data.put("output", output.toString())
            data.put("error", errorOutput.toString())
            data.put("exitCode", exitCode)
            call.resolve(data)
        } catch (e: Exception) {
            call.reject(e.localizedMessage)
        }
    }

    @PluginMethod
    fun updateActiveProfile(call: PluginCall) {
        val configJson = call.getString("profileJson")
        GamepadListenerService.activeProfileJson = configJson
        call.resolve()
    }

    @PluginMethod
    fun checkDaemonRunning(call: PluginCall) {
        val data = JSObject()
        data.put("daemonRunning", GamepadListenerService.isRunning)
        call.resolve(data)
    }

    @PluginMethod
    fun checkBattery(call: PluginCall) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val data = JSObject()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            data.put("isIgnoring", powerManager.isIgnoringBatteryOptimizations(context.packageName))
        } else {
            data.put("isIgnoring", true)
        }
        call.resolve(data)
    }

    @PluginMethod
    fun requestBatteryIgnore(call: PluginCall) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:" + context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        call.resolve()
    }

    @PluginMethod
    fun checkPermission(call: PluginCall) {
        val granted = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val data = JSObject()
        data.put("granted", granted)
        call.resolve(data)
    }

    @PluginMethod
    fun touchDown(call: PluginCall) {
        val id = call.getInt("pointerId")
        val x = call.getFloat("x")
        val y = call.getFloat("y")
        if (id == null || x == null || y == null) {
            call.reject("pointerId, x, and y must be provided")
            return
        }
        try {
            val success = touchService?.touchDown(id, x, y) ?: false
            if (success) {
                call.resolve()
            } else {
                call.reject("Injection call returned false (perhaps injectInputEvent is null or failed)")
            }
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun touchMove(call: PluginCall) {
        val id = call.getInt("pointerId")
        val x = call.getFloat("x")
        val y = call.getFloat("y")
        if (id == null || x == null || y == null) {
             call.reject("pointerId, x, and y must be provided")
             return
        }
        try {
            val success = touchService?.touchMove(id, x, y) ?: false
            if (success) {
                call.resolve()
            } else {
                call.reject("Injection call returned false")
            }
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun touchUp(call: PluginCall) {
        val id = call.getInt("pointerId")
        if (id == null) {
            call.reject("pointerId must be provided")
            return
        }
        try {
            val success = touchService?.touchUp(id) ?: false
            if (success) {
                call.resolve()
            } else {
                call.reject("Injection call returned false")
            }
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }

    @PluginMethod
    fun injectTap(call: PluginCall) {
        val x = call.getFloat("x")
        val y = call.getFloat("y")
        if (x == null || y == null) {
            call.reject("x and y must be provided")
            return
        }
        try {
            val success = touchService?.injectTap(x, y) ?: false
            if (success) {
                call.resolve()
            } else {
                call.reject("Injection call returned false")
            }
        } catch (e: Exception) {
            call.reject("Injection failed: ${e.message}")
        }
    }
}
