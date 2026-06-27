package com.nanomindexplorer.gamemappermind

import android.os.Handler
import android.os.Looper
import android.util.Log

object GamepadJniPlugin {

    private val batchedEvents = mutableListOf<() -> Unit>()
    private var isPending = false

    // BUG-CRITICAL-7 FIX: Use dedicated HandlerThread for injection instead of Main Thread.
    // Binder calls (touchDown/touchMove/touchUp) are cross-process and synchronous —
    // running them on Main Thread causes ANR risk and adds ~16ms latency per frame.
    // HandlerThread has its own Looper on a background thread, so binder calls don't block UI.
    private val injectionThread = android.os.HandlerThread("GamepadInjection").also { it.start() }
    private val injectionHandler = Handler(injectionThread.looper)

    // Keep mainHandler for UI-only events (rarely used now)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val processRunnable = Runnable {
        val toProcess: List<() -> Unit>
        synchronized(batchedEvents) {
            isPending = false
            toProcess = batchedEvents.toList()
            batchedEvents.clear()
        }
        if (toProcess.isNotEmpty()) {
            Log.d("GameMapper", "GamepadJniPlugin: processing ${toProcess.size} batched events")
            toProcess.forEach { it.invoke() }
        }
    }

    fun queueEvent(eventAction: () -> Unit) {
        // BUG-CRITICAL-7 FIX: Use injectionHandler (background HandlerThread) instead of mainHandler.
        // If already on injection thread, execute immediately (zero latency).
        if (Looper.myLooper() == injectionThread.looper) {
            eventAction.invoke()
            return
        }
        var needsPost = false
        synchronized(batchedEvents) {
            batchedEvents.add(eventAction)
            if (!isPending) {
                isPending = true
                needsPost = true
            }
        }
        if (needsPost) {
            injectionHandler.post(processRunnable)
        }
    }

    fun handleAxisBatched(gamepadIndex: Int, lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        queueEvent {
            if (NativeGamepadMapper.instance == null) {
                Log.w("GameMapper", "handleAxisBatched: NativeGamepadMapper.instance is NULL — not created yet")
            } else {
                NativeGamepadMapper.instance?.handleAxes(gamepadIndex, lx, ly, rx, ry, l2, r2)
            }
        }
    }

    fun handleButtonBatched(gamepadIndex: Int, buttonName: String, isDown: Boolean) {
        queueEvent {
            if (NativeGamepadMapper.instance == null) {
                Log.w("GameMapper", "handleButtonBatched: NativeGamepadMapper.instance is NULL — not created yet")
            } else {
                Log.d("GameMapper", "handleButtonBatched: $buttonName isDown=$isDown → calling handleButton")
                NativeGamepadMapper.instance?.handleButton(gamepadIndex, buttonName, isDown)
            }
        }
    }

    // Overloaded 2-arg version for backward compatibility (default gamepadIndex = 0)
    fun handleButtonBatched(buttonName: String, isDown: Boolean) {
        handleButtonBatched(0, buttonName, isDown)
    }
}
