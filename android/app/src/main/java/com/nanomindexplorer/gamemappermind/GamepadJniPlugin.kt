package com.nanomindexplorer.gamemappermind

import android.os.Handler
import android.os.Looper
import android.util.Log

object GamepadJniPlugin {
    
    private val batchedEvents = mutableListOf<() -> Unit>()
    private var isPending = false
    
    // BUG-FIX #1: Replace Choreographer with Handler(MainLooper).
    // Choreographer.getInstance() requires a Looper thread. Binder threads (Shizuku getevent)
    // and background threads don't have Loopers → IllegalStateException → events SILENTLY DROPPED.
    // Handler(MainLooper) works from ANY thread — no crash, no dropped events.
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
        var needsPost = false
        synchronized(batchedEvents) {
            batchedEvents.add(eventAction)
            if (!isPending) {
                isPending = true
                needsPost = true
            }
        }
        if (needsPost) {
            // Post to main thread Handler — works from ANY thread (main, binder, background).
            mainHandler.post(processRunnable)
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
