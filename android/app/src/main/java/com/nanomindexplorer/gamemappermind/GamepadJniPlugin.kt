package com.nanomindexplorer.gamemappermind

object GamepadJniPlugin {

    private val batchedEvents = mutableListOf<() -> Unit>()
    private var isPending = false

    private class PendingAxisState {
        var lx: Float = 0f
        var ly: Float = 0f
        var rx: Float = 0f
        var ry: Float = 0f
        var l2: Float = 0f
        var r2: Float = 0f
        var dirty: Boolean = false
    }

    private val pendingAxes = Array(4) { PendingAxisState() }
    private val axisLock = Any()

    private val injectionThread = android.os.HandlerThread("GamepadInjection").also { it.start() }
    val injectionHandler = android.os.Handler(injectionThread.looper)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val processRunnable = Runnable {
        val dirtyUpdates = mutableListOf<Pair<Int, PendingAxisState>>()
        synchronized(axisLock) {
            for (i in 0 until 4) {
                val state = pendingAxes[i]
                if (state.dirty) {
                    val copy = PendingAxisState().apply {
                        lx = state.lx; ly = state.ly
                        rx = state.rx; ry = state.ry
                        l2 = state.l2; r2 = state.r2
                    }
                    dirtyUpdates.add(Pair(i, copy))
                    state.dirty = false
                }
            }
        }

        for ((gpIdx, state) in dirtyUpdates) {
            if (NativeGamepadMapper.instance != null) {
                NativeGamepadMapper.instance?.handleAxes(gpIdx, state.lx, state.ly, state.rx, state.ry, state.l2, state.r2)
            }
        }

        val toProcess: List<() -> Unit>
        synchronized(batchedEvents) {
            isPending = false
            toProcess = batchedEvents.toList()
            batchedEvents.clear()
        }
        toProcess.forEach { it.invoke() }
    }

    fun queueEvent(eventAction: () -> Unit) {
        if (android.os.Looper.myLooper() == injectionThread.looper) {
            eventAction.invoke()
            return
        }
        var needsPost = false
        synchronized(batchedEvents) {
            batchedEvents.add(eventAction)
            if (!isPending) { isPending = true; needsPost = true }
        }
        if (needsPost) injectionHandler.post(processRunnable)
    }

    fun handleAxisBatched(gamepadIndex: Int, lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float) {
        if (gamepadIndex !in 0..3) return
        synchronized(axisLock) {
            val state = pendingAxes[gamepadIndex]
            state.lx = lx; state.ly = ly
            state.rx = rx; state.ry = ry
            state.l2 = l2; state.r2 = r2
            state.dirty = true
        }
        var needsPost = false
        synchronized(batchedEvents) {
            if (!isPending) { isPending = true; needsPost = true }
        }
        if (needsPost) injectionHandler.post(processRunnable)
    }

    fun handleButtonBatched(gamepadIndex: Int, buttonName: String, isDown: Boolean) {
        queueEvent {
            if (NativeGamepadMapper.instance == null) {
                android.util.Log.w("GameMapper", "handleButtonBatched: instance NULL")
            } else {
                NativeGamepadMapper.instance?.handleButton(gamepadIndex, buttonName, isDown)
            }
        }
    }

    fun handleButtonBatched(buttonName: String, isDown: Boolean) {
        handleButtonBatched(0, buttonName, isDown)
    }
}
