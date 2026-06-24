package com.nanomindexplorer.gamemappermind

import android.view.Choreographer
import android.os.Handler
import android.os.Looper

object GamepadJniPlugin {
    
    private val batchedEvents = mutableListOf<() -> Unit>()
    private var isFramePending = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // BUG-N9 FIX: Snapshot events BEFORE clearing, then process outside synchronized.
            // Previously, isFramePending was set to false inside synchronized, but if a new
            // event was queued between isFramePending=false and batchedEvents.clear(),
            // that event would be lost (cleared without being processed).
            val toProcess: List<() -> Unit>
            synchronized(batchedEvents) {
                isFramePending = false
                toProcess = batchedEvents.toList()
                batchedEvents.clear()
            }
            // Process events OUTSIDE synchronized to avoid holding lock during
            // potentially long-running NativeGamepadMapper.handleAxes/handleButton.
            if (toProcess.isNotEmpty()) {
                toProcess.forEach { it.invoke() }
            }
        }
    }

    fun queueEvent(eventAction: () -> Unit) {
        var needsPost = false
        synchronized(batchedEvents) {
            batchedEvents.add(eventAction)
            if (!isFramePending) {
                isFramePending = true
                needsPost = true
            }
        }
        // BUG-G1 FIX: Call Choreographer.postFrameCallback OUTSIDE synchronized block to avoid potential deadlock.
        if (needsPost) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun handleAxisBatched(gamepadIndex: Int, lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        queueEvent {
            NativeGamepadMapper.instance?.handleAxes(gamepadIndex, lx, ly, rx, ry, l2, r2)
        }
    }

    fun handleButtonBatched(gamepadIndex: Int, buttonName: String, isDown: Boolean) {
        queueEvent {
            NativeGamepadMapper.instance?.handleButton(gamepadIndex, buttonName, isDown)
        }
    }

    // Overloaded 2-arg version for backward compatibility (default gamepadIndex = 0)
    fun handleButtonBatched(buttonName: String, isDown: Boolean) {
        handleButtonBatched(0, buttonName, isDown)
    }
}
