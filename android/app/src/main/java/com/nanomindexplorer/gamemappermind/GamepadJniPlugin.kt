package com.nanomindexplorer.gamemappermind

object GamepadJniPlugin {

    private val batchedEvents = mutableListOf<() -> Unit>()
    private var isPending = false

    @Volatile private var pendingAxisGpIdx: Int = -1
    @Volatile private var pendingAxisLx: Float = 0f
    @Volatile private var pendingAxisLy: Float = 0f
    @Volatile private var pendingAxisRx: Float = 0f
    @Volatile private var pendingAxisRy: Float = 0f
    @Volatile private var pendingAxisL2: Float = 0f
    @Volatile private var pendingAxisR2: Float = 0f
    @Volatile private var hasPendingAxis: Boolean = false

    private val injectionThread = android.os.HandlerThread("GamepadInjection").also { it.start() }
    val injectionHandler = android.os.Handler(injectionThread.looper)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val processRunnable = Runnable {
        if (hasPendingAxis) {
            val gpIdx = pendingAxisGpIdx
            val lx = pendingAxisLx; val ly = pendingAxisLy
            val rx = pendingAxisRx; val ry = pendingAxisRy
            val l2 = pendingAxisL2; val r2 = pendingAxisR2
            hasPendingAxis = false
            pendingAxisGpIdx = -1
            if (NativeGamepadMapper.instance != null) {
                NativeGamepadMapper.instance?.handleAxes(gpIdx, lx, ly, rx, ry, l2, r2)
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
        pendingAxisGpIdx = gamepadIndex
        pendingAxisLx = lx; pendingAxisLy = ly
        pendingAxisRx = rx; pendingAxisRy = ry
        pendingAxisL2 = l2; pendingAxisR2 = r2
        hasPendingAxis = true
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
