package com.thanhtaxixanh.cardriverlauncher

/** Shared phone/Android Auto policy: follow GPS only after user gestures have
 * been idle for the configured delay. A gesture never freezes location updates
 * or traffic markers, only the camera. No background timers are needed.
 */
class CameraFollowGate(private val clock: () -> Long) {
    private var lastGestureAt: Long? = null
    private var gestureActive = false

    fun interactionStart() {
        gestureActive = true
        lastGestureAt = clock()
    }

    fun interactionUpdate() {
        lastGestureAt = clock()
    }

    fun interactionEnd() {
        gestureActive = false
        lastGestureAt = clock()
    }

    fun canFollow(delaySeconds: Int): Boolean {
        if (gestureActive) return false
        val last = lastGestureAt ?: return true
        val delayMs = delaySeconds.coerceIn(5, 60) * 1000L
        return (clock() - last) >= delayMs
    }

    fun recenterNow() {
        gestureActive = false
        lastGestureAt = null
    }
}
