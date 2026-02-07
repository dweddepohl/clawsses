package com.claudeglasses.glasses.input

import android.util.Log
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Handles touch gestures on the glasses touchpad
 *
 * The Rokid temple touchpad sends events as either:
 * - Standard touch: ACTION_DOWN / ACTION_MOVE / ACTION_UP
 * - Hover/generic motion: ACTION_HOVER_ENTER / ACTION_HOVER_MOVE / ACTION_HOVER_EXIT
 *
 * This handler normalizes both into a unified start/move/end flow.
 *
 * Gesture mappings (unified across all modes):
 * - Swipe Forward: Scroll up / Arrow up / Tab
 * - Swipe Backward: Scroll down / Arrow down / Shift-Tab
 * - Tap: Confirm action
 * - Double-tap: Switch mode
 * - Long press: Voice input
 */
class GestureHandler(
    private val onGesture: (Gesture) -> Unit
) {
    enum class Gesture {
        SWIPE_FORWARD,   // Towards eyes - scroll up, arrow up, tab
        SWIPE_BACKWARD,  // Towards ear - scroll down, arrow down, shift-tab
        TAP,
        DOUBLE_TAP,
        LONG_PRESS
    }

    companion object {
        private const val TAG = "GestureHandler"
        private const val SWIPE_THRESHOLD = 100
        private const val TAP_MOVE_THRESHOLD = 50
    }

    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var tracking = false

    private var lastTapTime = 0L
    private val doubleTapTimeout = 400L  // 400ms for temple touchpad (more forgiving)
    private val longPressTimeout = 500L

    fun onTouchEvent(event: MotionEvent): Boolean {
        // Normalize hover events (from external touchpad) to touch equivalents
        val action = when (event.action) {
            MotionEvent.ACTION_HOVER_ENTER -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_HOVER_MOVE -> MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_HOVER_EXIT -> MotionEvent.ACTION_UP
            else -> event.action
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                startTime = System.currentTimeMillis()
                tracking = true
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false

                val endX = event.x
                val endY = event.y
                val endTime = System.currentTimeMillis()

                val deltaX = endX - startX
                val deltaY = endY - startY
                val duration = endTime - startTime

                // Check for long press
                if (duration > longPressTimeout && abs(deltaX) < TAP_MOVE_THRESHOLD && abs(deltaY) < TAP_MOVE_THRESHOLD) {
                    Log.d(TAG, "Gesture: LONG_PRESS (${duration}ms)")
                    onGesture(Gesture.LONG_PRESS)
                    return true
                }

                // Check for swipe (forward/backward along temple touchpad)
                // Forward = towards eyes = negative Y (up on screen)
                // Backward = towards ear = positive Y (down on screen)
                // Note: Also detect horizontal as forward/backward since touchpad is linear
                val primaryDelta = if (abs(deltaY) > abs(deltaX)) deltaY else -deltaX
                if (abs(deltaX) > SWIPE_THRESHOLD || abs(deltaY) > SWIPE_THRESHOLD) {
                    if (primaryDelta < 0) {
                        Log.d(TAG, "Gesture: SWIPE_FORWARD (dx=$deltaX, dy=$deltaY)")
                        onGesture(Gesture.SWIPE_FORWARD)
                    } else {
                        Log.d(TAG, "Gesture: SWIPE_BACKWARD (dx=$deltaX, dy=$deltaY)")
                        onGesture(Gesture.SWIPE_BACKWARD)
                    }
                    return true
                }

                // Check for tap/double-tap
                if (abs(deltaX) < TAP_MOVE_THRESHOLD && abs(deltaY) < TAP_MOVE_THRESHOLD) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < doubleTapTimeout) {
                        Log.d(TAG, "Gesture: DOUBLE_TAP (${now - lastTapTime}ms between taps)")
                        lastTapTime = 0
                        onGesture(Gesture.DOUBLE_TAP)
                    } else {
                        lastTapTime = now
                        // Delay single tap to check for double tap
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (lastTapTime == now) {
                                Log.d(TAG, "Gesture: TAP")
                                onGesture(Gesture.TAP)
                            }
                        }, doubleTapTimeout)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                return tracking
            }
        }
        return false
    }
}
