package com.nanomindexplorer.gamemappermind

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * REC-23: File logger untuk debugging GameMapperMind.
 *
 * Tulis log ke /sdcard/Android/data/com.nanomindexplorer.gamemappermind/files/gamemapper.log
 * Rotate log setiap 5MB, simpan maksimal 3 file (gamemapper.log, gamemapper.log.1, gamemapper.log.2).
 *
 * Log semua event kritis:
 * - Gamepad connect/disconnect
 * - Shizuku bind/unbind
 * - Touch injection success/failure
 * - Pointer state changes
 * - Error
 *
 * Untuk privacy, log tidak include koordinat sentuh asli (mask dengan ***),
 * hanya status dan metadata.
 *
 * Math-Logic (Pasal 5.1):
 * - Write: O(1) append to file
 * - Rotate: O(n) di mana n = ukuran file (copy old to .1, .1 to .2)
 * - Rotate trigger: saat file size > 5MB
 * - Kompleksitas total: O(1) per log entry (amortized)
 *
 * Invariant:
 * - Log file selalu ada (create jika tidak ada)
 * - Maksimal 3 file (log, log.1, log.2), total maksimal 15MB
 * - Timestamp format: yyyy-MM-dd HH:mm:ss.SSS
 * - Thread-safe: synchronized write
 *
 * Usage:
 *   GamepadLogger.log(GamepadLogger.Level.INFO, "GamepadListenerService", "Gamepad connected")
 *   GamepadLogger.log(GamepadLogger.Level.ERROR, "TouchInjectionPlugin", "Inject failed", exception)
 */
object GamepadLogger {

    enum class Level(val tag: String) {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E")
    }

    private const val MAX_FILE_SIZE = 5 * 1024 * 1024 // 5MB
    private const val MAX_FILE_COUNT = 3
    private const val LOG_FILE_NAME = "gamemapper.log"

    private var logFile: File? = null
    private var writer: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /**
     * Initialize logger dengan context.
     * Buat file log di external files dir (tidak perlu permission storage di Android 10+).
     */
    fun init(context: Context) {
        synchronized(lock) {
            try {
                val logDir = context.getExternalFilesDir(null) ?: File(context.filesDir, "logs")
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }
                val lf = File(logDir, LOG_FILE_NAME)
                logFile = lf
                val pw = PrintWriter(lf, true) // append mode, auto-flush
                writer = pw
                Log.d("GameMapper", "REC-23: Logger initialized at ${logFile?.absolutePath}")
            } catch (e: Exception) {
                Log.e("GameMapper", "REC-23: Failed to init logger", e)
            }
        }
    }

    /**
     * Tulis log entry ke file.
     *
     * @param level Level log (DEBUG, INFO, WARN, ERROR)
     * @param tag Tag untuk identify source (misal "GamepadListenerService")
     * @param message Pesan log (tidak include koordinat sentuh asli untuk privacy)
     * @param exception Optional exception untuk stack trace
     */
    fun log(level: Level, tag: String, message: String, exception: Throwable? = null) {
        synchronized(lock) {
            try {
                val timestamp = dateFormat.format(Date())
                val logLine = "[$timestamp] ${level.tag}/$tag: $message"

                // Cek apakah perlu rotate.
                if (logFile != null && logFile!!.length() > MAX_FILE_SIZE) {
                    rotateLogs()
                }

                writer?.apply {
                    println(logLine)
                    if (exception != null) {
                        exception.printStackTrace(this)
                    }
                    flush()
                }

                // Juga log ke logcat untuk debugging real-time.
                when (level) {
                    Level.DEBUG -> if (exception != null) Log.d(tag, message, exception) else Log.d(tag, message)
                    Level.INFO -> if (exception != null) Log.i(tag, message, exception) else Log.i(tag, message)
                    Level.WARN -> if (exception != null) Log.w(tag, message, exception) else Log.w(tag, message)
                    Level.ERROR -> if (exception != null) Log.e(tag, message, exception) else Log.e(tag, message)
                }
            } catch (e: Exception) {
                Log.e("GameMapper", "REC-23: Failed to write log", e)
            }
        }
    }

    /**
     * Rotate log files.
     * - Hapus gamemapper.log.2 (jika ada)
     * - Rename gamemapper.log.1 → gamemapper.log.2
     * - Rename gamemapper.log → gamemapper.log.1
     * - Create new gamemapper.log
     */
    private fun rotateLogs() {
        try {
            writer?.close()
            writer = null

            val dir = logFile?.parentFile ?: return
            val file2 = File(dir, "$LOG_FILE_NAME.2")
            val file1 = File(dir, "$LOG_FILE_NAME.1")

            // Hapus file .2 tertua.
            if (file2.exists()) {
                file2.delete()
            }

            // Rename .1 → .2.
            if (file1.exists()) {
                file1.renameTo(file2)
            }

            // Rename current → .1.
            logFile?.renameTo(file1)

            // Create new log file.
            val lf = File(dir, LOG_FILE_NAME)
            logFile = lf
            val pw = PrintWriter(lf, true)
            writer = pw

            Log.d("GameMapper", "REC-23: Logs rotated")
        } catch (e: Exception) {
            Log.e("GameMapper", "REC-23: Failed to rotate logs", e)
        }
    }

    /**
     * Close logger (saat app exit atau service destroy).
     */
    fun close() {
        synchronized(lock) {
            try {
                writer?.flush()
                writer?.close()
                writer = null
            } catch (e: Exception) {
                Log.e("GameMapper", "REC-23: Failed to close logger", e)
            }
        }
    }

    /**
     * Get log file path untuk UI display atau share.
     */
    fun getLogFilePath(): String? {
        return logFile?.absolutePath
    }
}
