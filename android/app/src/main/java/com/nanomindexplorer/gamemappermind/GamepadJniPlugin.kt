package com.nanomindexplorer.gamemappermind

import android.view.Choreographer
import android.os.Handler
import android.os.Looper

object GamepadJniPlugin {
    
    private val batchedEvents = mutableListOf<() -> Unit>()
    private var isFramePending = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            isFramePending = false
            synchronized(batchedEvents) {
                if (batchedEvents.isNotEmpty()) {
                    batchedEvents.forEach { it.invoke() }
                    batchedEvents.clear()
                }
            }
        }
    }

    fun queueEvent(eventAction: () -> Unit) {
        synchronized(batchedEvents) {
            batchedEvents.add(eventAction)
            if (!isFramePending) {
                isFramePending = true
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
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
}
