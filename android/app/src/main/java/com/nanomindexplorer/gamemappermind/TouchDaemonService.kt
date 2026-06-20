package com.nanomindexplorer.gamemappermind

import android.app.Service
import android.content.Intent
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.Shizuku

class TouchDaemonService : Service() {

    private val touchStub = object : ITouchService.Stub() {
        override fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
            return this@TouchDaemonService.touchDown(pointerId, x, y)
        }

        override fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
            return this@TouchDaemonService.touchMove(pointerId, x, y)
        }

        override fun touchUp(pointerId: Int): Boolean {
            return this@TouchDaemonService.touchUp(pointerId)
        }

        override fun injectTap(x: Float, y: Float): Boolean {
            return this@TouchDaemonService.injectTap(x, y)
        }

        override fun isAlive(): Boolean {
            return true
        }

        override fun releaseAllPointers(): Boolean {
            return this@TouchDaemonService.releaseAllPointers()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return touchStub
    }

    // Clean up pointers if the service is destroyed
    override fun onDestroy() {
        super.onDestroy()
        releaseAllPointers()
    }

    private val inputManager: InputManager? by lazy {
        try {
            InputManager::class.java.getMethod("getInstance").invoke(null) as InputManager
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to get InputManager", e)
            null
        }
    }

    private val injectInputEventMethod by lazy {
        try {
            // mode 0 is INJECT_INPUT_EVENT_MODE_ASYNC
            InputManager::class.java.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (e: Exception) {
            Log.e("GameMapper", "Failed to get injectInputEvent method", e)
            null
        }
    }

    class PointerState {
        var x: Float = 0f
        var y: Float = 0f
        var isDown: Boolean = false
    }

    private val pointers = SparseArray<PointerState>()
    private var baseDownTime: Long = 0L

    private fun getCompactedIndex(targetPointerId: Int): Int {
        var compactedIdx = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) {
                if (pointers.keyAt(i) == targetPointerId) return compactedIdx
                compactedIdx++
            }
        }
        return 0 // fallback
    }

    private fun injectMotionEvent(action: Int, actionIndex: Int): Boolean {
        val downTime = baseDownTime
        val eventTime = SystemClock.uptimeMillis()

        var pointerCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) {
                pointerCount++
            }
        }
        
        if (pointerCount == 0) return false

        val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }

        var activeIndex = 0
        for (i in 0 until pointers.size()) {
            val pointerId = pointers.keyAt(i)
            val state = pointers.valueAt(i)
            
            if (state.isDown) {
                pointerProperties[activeIndex].id = pointerId
                pointerProperties[activeIndex].toolType = MotionEvent.TOOL_TYPE_FINGER
                
                pointerCoords[activeIndex].x = state.x
                pointerCoords[activeIndex].y = state.y
                pointerCoords[activeIndex].pressure = 1.0f
                pointerCoords[activeIndex].size = 1.0f
                activeIndex++
            }
        }

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            activeIndex,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        return try {
            val result = injectInputEventMethod?.invoke(inputManager, event, 0) as? Boolean ?: false
            if (!result) Log.w("GameMapper", "injectInputEvent returned false")
            result
        } catch (e: Exception) {
            Log.e("GameMapper", "Injection failed", e)
            false
        } finally {
            event.recycle()
        }
    }

    fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        var state = pointers.get(pointerId)
        if (state == null) {
            state = PointerState()
            pointers.put(pointerId, state)
        }
        state.x = x
        state.y = y
        state.isDown = true

        var activeCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activeCount++
        }

        return if (activeCount == 1) {
            baseDownTime = SystemClock.uptimeMillis()
            injectMotionEvent(MotionEvent.ACTION_DOWN, 0)
        } else {
            val compactedIdx = getCompactedIndex(pointerId)
            val action = MotionEvent.ACTION_POINTER_DOWN or (compactedIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            injectMotionEvent(action, compactedIdx)
        }
    }

    fun touchMove(pointerId: Int, x: Float, y: Float): Boolean {
        val state = pointers.get(pointerId) ?: return false
        state.x = x
        state.y = y
        if (state.isDown) {
            return injectMotionEvent(MotionEvent.ACTION_MOVE, 0)
        }
        return false
    }

    fun touchUp(pointerId: Int): Boolean {
        val state = pointers.get(pointerId) ?: return false
        val compactedIdx = getCompactedIndex(pointerId)
        
        var activeCount = 0
        for (i in 0 until pointers.size()) {
            if (pointers.valueAt(i).isDown) activeCount++
        }

        val result = if (activeCount == 1) {
            val res = injectMotionEvent(MotionEvent.ACTION_UP, 0)
            pointers.clear()
            res
        } else {
            val action = MotionEvent.ACTION_POINTER_UP or (compactedIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val res = injectMotionEvent(action, compactedIdx)
            pointers.remove(pointerId)
            res
        }
        
        state.isDown = false
        return result
    }

    /**
     * Release semua pointer yang masih aktif (isDown=true) dengan aman.
     *
     * Fix untuk BUG-N03 (regression dari fix BUG-C07):
     * - Sebelumnya iterasi SparseArray sambil memanggil touchUp yang mutasi SparseArray.
     * - SparseArray seperti ArrayList: tidak aman dimutasi selama iterasi.
     * - Setelah remove, entry berikutnya bergeser ke indeks yang sedang diiterasi,
     *   menyebabkan beberapa entry di-skip.
     * - Untuk kasus clear(), iterasi berhenti prematur.
     *
     * Collection Mutation Safety Analysis (Pasal 5.11):
     * - Collection: SparseArray<PointerState>
     * - Operasi mutasi selama iterasi: touchUp(key) → pointers.remove(key) atau pointers.clear()
     * - Risk: ConcurrentModificationException (logic), index shift, entry skip
     * - Mitigasi: snapshot keys terlebih dahulu ke List immutable, lalu iterasi snapshot
     *   sambil mutasi original SparseArray. Snapshot tidak terpengaruh oleh mutasi original.
     *
     * Invariant:
     * - Setelah method ini selesai, pointers.size() == 0 (semua pointer di-release)
     * - Setiap pointer yang isDown=true mendapat touchUp() call
     * - Tidak ada pointer yang skip (verifikasi via snapshot keys)
     *
     * Kompleksitas:
     * - Snapshot: O(n) untuk membuat List dari SparseArray
     * - Iterasi + touchUp: O(n) untuk loop, masing-masing touchUp O(n) untuk iterasi internal
     * - Total: O(n^2) di mana n = jumlah pointer (n maksimal 10, jadi 100 operations max)
     * - Acceptable karena n kecil dan method ini jarang dipanggil (saat kill switch)
     */
    fun releaseAllPointers(): Boolean {
        // Snapshot keys ke List immutable.
        // Setelah ini, snapshot tidak terpengaruh oleh mutasi pointers (SparseArray).
        val snapshotKeys: List<Int> = (0 until pointers.size()).map { pointers.keyAt(it) }.toList()

        var anyReleased = false
        for (pointerId in snapshotKeys) {
            // Ambil state dari original pointers (mungkin sudah berubah jika ada concurrent access,
            // tetapi karena ini service single-threaded untuk touch operations, aman).
            val state = pointers.get(pointerId)
            if (state != null && state.isDown) {
                touchUp(pointerId)
                anyReleased = true
            }
        }

        // Final safety: clear semua entry untuk memastikan state bersih.
        // Setelah iterasi snapshot, semua pointer yang isDown=true sudah di-touchUp.
        // pointers.remove(pointerId) sudah dipanggil di dalam touchUp untuk activeCount > 1,
        // atau pointers.clear() untuk activeCount == 1.
        // Clear di sini sebagai safety net untuk handle edge cases.
        pointers.clear()

        // Verify invariant: setelah release, pointers harus kosong.
        // Jika tidak kosong, log warning (seharusnya tidak terjadi).
        if (pointers.size() > 0) {
            Log.w("GameMapper", "releaseAllPointers: pointers not empty after clear, size=${pointers.size()}")
        }

        return anyReleased
    }

    private var nextTapId = 90
    fun injectTap(x: Float, y: Float): Boolean {
        val id = nextTapId
        nextTapId++
        if (nextTapId > 99) nextTapId = 90
        val downRes = touchDown(id, x, y)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            touchUp(id)
        }, 20L)
        return downRes
    }

    fun isAlive(): Boolean {
        return true
    }
}
