package com.clawsses.phone.glasses

import android.util.Log
import com.clawsses.shared.WakeSignal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages wake signal coordination between phone and glasses.
 *
 * When the glasses may be in standby (display off), this manager:
 * 1. Wakes the hardware display via CXR-M SDK (setGlassBrightness from phone side)
 * 2. Sends a wake signal message to glasses for notification UI
 * 3. Buffers messages and waits for acknowledgment before delivering
 * 4. Handles timeout and retry logic for reliable delivery
 *
 * The Rokid micro-LED display is controlled from the phone via CXR SDK — Android
 * PowerManager on the glasses does NOT work. The phone calls setGlassBrightness()
 * and setScreenOffTimeout() to physically turn on the display.
 */
class WakeSignalManager(
    private val sendToGlasses: (String) -> Unit,
    private val wakeHardwareDisplay: () -> Boolean = { false }
) {
    companion object {
        private const val TAG = "WakeSignalManager"

        // Timeout waiting for wake acknowledgment
        private const val WAKE_ACK_TIMEOUT_MS = 3000L

        // Maximum buffer size to prevent memory issues
        private const val MAX_BUFFER_SIZE = 100

        // Minimum interval between wake signals to avoid spam
        private const val MIN_WAKE_INTERVAL_MS = 1000L

        // Time after last confirmed activity before assuming glasses may be in standby.
        // Slightly less than the 30s screen-off timeout to be conservative.
        private const val STANDBY_DETECTION_MS = 25_000L

        // Minimum interval between hardware wake keep-alive calls during streaming
        private const val WAKE_KEEPALIVE_INTERVAL_MS = 20_000L
    }

    /**
     * Current wake state of the glasses
     */
    sealed class WakeState {
        /** Glasses is awake and ready to receive messages */
        object Awake : WakeState()

        /** Unknown state - glasses may be in standby */
        object Unknown : WakeState()

        /** Wake signal sent, waiting for acknowledgment */
        data class WakingUp(val reason: String, val sentAt: Long) : WakeState()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Message buffer for when glasses is waking up
    private val messageBuffer = ConcurrentLinkedQueue<BufferedMessage>()

    // Current wake state
    private val _wakeState = MutableStateFlow<WakeState>(WakeState.Unknown)
    val wakeState: StateFlow<WakeState> = _wakeState

    // Track last CONFIRMED activity from glasses (message received, wake_ack, connect).
    // NOT updated on outgoing messages — only on proof the glasses is responsive.
    private var lastConfirmedActivityTime = 0L

    // Track last hardware wake call to rate-limit keep-alives
    private var lastHardwareWakeTime = 0L

    // Track last wake signal time to avoid spam
    private var lastWakeSignalTime = 0L

    // Track streaming state - if actively streaming, glasses should be awake
    private var isStreaming = false

    // Timeout job for wake acknowledgment
    private var wakeTimeoutJob: Job? = null

    // Standby detection timer — fires STANDBY_DETECTION_MS after last confirmed activity
    private var standbyTimerJob: Job? = null

    // Feature toggle
    private var _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    data class BufferedMessage(
        val json: String,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Enable or disable the wake signal feature.
     * When disabled, messages are sent directly without buffering.
     */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (!enabled) {
            // Flush any buffered messages immediately when disabled
            flushBuffer()
        }
        Log.i(TAG, "Wake signal feature ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Send a message to glasses, handling wake signal if needed.
     *
     * @param json The JSON message to send
     * @param isStreamContent True if this is part of an ongoing stream
     * @param isNewMessage True if this is a new spontaneous message (e.g., cron)
     * @return True if the message was sent immediately, false if buffered
     */
    fun sendMessage(
        json: String,
        isStreamContent: Boolean = false,
        isNewMessage: Boolean = false
    ): Boolean {
        // If feature is disabled, send directly
        if (!_enabled.value) {
            sendToGlasses(json)
            return true
        }

        val now = System.currentTimeMillis()

        // Update streaming state
        if (isStreamContent) {
            isStreaming = true
        }

        return when (val state = _wakeState.value) {
            is WakeState.Awake -> {
                // Glasses is awake, send directly
                sendToGlasses(json)

                // Keep-alive: periodically reset hardware display during streaming
                // to prevent the 30s screen-off timeout from firing mid-stream
                if (isStreamContent && (now - lastHardwareWakeTime > WAKE_KEEPALIVE_INTERVAL_MS)) {
                    Log.d(TAG, "Stream keep-alive: resetting display timeout")
                    wakeHardwareDisplay()
                    lastHardwareWakeTime = now
                }

                true
            }

            is WakeState.WakingUp -> {
                // Glasses is waking up, buffer the message
                bufferMessage(json, if (isStreamContent) "stream" else "message")
                false
            }

            is WakeState.Unknown -> {
                // Unknown state - glasses may be in standby
                val timeSinceLastActivity = now - lastConfirmedActivityTime

                if (lastConfirmedActivityTime > 0 && timeSinceLastActivity < 5000) {
                    // Very recent confirmed activity — glasses is likely still awake
                    sendToGlasses(json)
                    true
                } else {
                    // May be in standby — wake hardware and initiate wake protocol
                    val reason = when {
                        isStreamContent -> WakeSignal.REASON_STREAM_CONTENT
                        isNewMessage -> WakeSignal.REASON_CRON_MESSAGE
                        else -> WakeSignal.REASON_NEW_MESSAGE
                    }
                    bufferMessage(json, reason)
                    initiateWake(reason)
                    false
                }
            }
        }
    }

    /**
     * Notify that streaming has started for a message.
     * This prepares the wake manager for continuous streaming.
     */
    fun notifyStreamStart(messageId: String) {
        isStreaming = true

        // If in unknown state, proactively send wake signal
        if (_wakeState.value is WakeState.Unknown && _enabled.value) {
            initiateWake(WakeSignal.REASON_STREAM_CONTENT, messageId)
        } else if (_wakeState.value is WakeState.Awake && _enabled.value) {
            // Even if awake, wake the hardware as keep-alive for stream start
            val now = System.currentTimeMillis()
            if (now - lastHardwareWakeTime > WAKE_KEEPALIVE_INTERVAL_MS) {
                wakeHardwareDisplay()
                lastHardwareWakeTime = now
            }
        }
    }

    /**
     * Notify that streaming has ended for a message.
     */
    fun notifyStreamEnd(messageId: String) {
        isStreaming = false
    }

    /**
     * Handle wake acknowledgment from glasses.
     * This is called when glasses sends a wake_ack message.
     */
    fun handleWakeAck(ready: Boolean) {
        Log.i(TAG, "Received wake acknowledgment: ready=$ready")

        wakeTimeoutJob?.cancel()
        lastConfirmedActivityTime = System.currentTimeMillis()

        if (ready) {
            _wakeState.value = WakeState.Awake
            resetStandbyTimer()

            // Deliver all buffered messages
            flushBuffer()
        } else {
            // Wake failed - try again after a delay
            Log.w(TAG, "Wake acknowledgment indicated failure, retrying...")
            scope.launch {
                delay(500)
                if (messageBuffer.isNotEmpty()) {
                    initiateWake(WakeSignal.REASON_NEW_MESSAGE)
                }
            }
        }
    }

    /**
     * Handle activity from glasses (any message received).
     * This indicates glasses is awake and responsive.
     */
    fun handleGlassesActivity() {
        lastConfirmedActivityTime = System.currentTimeMillis()

        // If we were in unknown or waking state, mark as awake
        when (_wakeState.value) {
            is WakeState.Unknown, is WakeState.WakingUp -> {
                Log.d(TAG, "Glasses activity detected, marking as awake")
                _wakeState.value = WakeState.Awake
                wakeTimeoutJob?.cancel()

                // Flush any buffered messages
                if (messageBuffer.isNotEmpty()) {
                    flushBuffer()
                }
            }
            else -> {}
        }

        // Reset standby detection timer
        resetStandbyTimer()
    }

    /**
     * Notify that glasses has disconnected.
     * Reset state to unknown.
     */
    fun handleGlassesDisconnected() {
        _wakeState.value = WakeState.Unknown
        wakeTimeoutJob?.cancel()
        standbyTimerJob?.cancel()
        lastConfirmedActivityTime = 0
        // Keep buffered messages - they'll be delivered on reconnect
        Log.d(TAG, "Glasses disconnected, state reset to Unknown")
    }

    /**
     * Notify that glasses has connected.
     * Reset to awake state and flush buffer.
     */
    fun handleGlassesConnected() {
        _wakeState.value = WakeState.Awake
        lastConfirmedActivityTime = System.currentTimeMillis()
        lastHardwareWakeTime = System.currentTimeMillis()
        wakeTimeoutJob?.cancel()
        resetStandbyTimer()

        // Flush buffered messages
        if (messageBuffer.isNotEmpty()) {
            Log.i(TAG, "Glasses connected, flushing ${messageBuffer.size} buffered messages")
            flushBuffer()
        }
    }

    private fun bufferMessage(json: String, reason: String) {
        if (messageBuffer.size >= MAX_BUFFER_SIZE) {
            // Drop oldest message to make room
            messageBuffer.poll()
            Log.w(TAG, "Buffer full, dropping oldest message")
        }

        messageBuffer.offer(BufferedMessage(json, reason))
        Log.d(TAG, "Buffered message (total: ${messageBuffer.size})")
    }

    private fun initiateWake(reason: String, messageId: String? = null) {
        val now = System.currentTimeMillis()

        // Rate limit wake signals
        if (now - lastWakeSignalTime < MIN_WAKE_INTERVAL_MS) {
            Log.d(TAG, "Skipping wake signal (rate limited)")
            return
        }

        // Already waking up
        if (_wakeState.value is WakeState.WakingUp) {
            Log.d(TAG, "Already waking up, skipping duplicate wake signal")
            return
        }

        lastWakeSignalTime = now
        _wakeState.value = WakeState.WakingUp(reason, now)

        // Wake the hardware display from the phone side via CXR-M SDK.
        // This is the primary wake mechanism — setGlassBrightness() turns on
        // the micro-LED display, setScreenOffTimeout() resets the idle timer.
        val hwWakeResult = wakeHardwareDisplay()
        lastHardwareWakeTime = now
        Log.i(TAG, "Hardware wake: $hwWakeResult")

        // Send wake signal message to glasses for notification UI
        val wakeSignal = WakeSignal(
            reason = reason,
            bufferedCount = messageBuffer.size,
            messageId = messageId
        )
        Log.i(TAG, "Sending wake signal: reason=$reason, buffered=${messageBuffer.size}")
        sendToGlasses(wakeSignal.toJson())

        // Set timeout for wake acknowledgment
        wakeTimeoutJob?.cancel()
        wakeTimeoutJob = scope.launch {
            delay(WAKE_ACK_TIMEOUT_MS)

            // If still waiting, assume glasses didn't receive the wake signal
            // or is offline. Deliver messages anyway — CXR bridge delivers even
            // when display is off, so content won't be lost.
            if (_wakeState.value is WakeState.WakingUp) {
                Log.w(TAG, "Wake acknowledgment timeout, delivering messages anyway")
                _wakeState.value = WakeState.Unknown

                // Deliver buffered messages
                flushBuffer()
            }
        }
    }

    /**
     * Reset the standby detection timer.
     * After STANDBY_DETECTION_MS without confirmed activity from glasses,
     * transition from Awake to Unknown to detect potential standby.
     */
    private fun resetStandbyTimer() {
        standbyTimerJob?.cancel()
        standbyTimerJob = scope.launch {
            delay(STANDBY_DETECTION_MS)
            if (_wakeState.value is WakeState.Awake) {
                Log.d(TAG, "Standby detection: no activity for ${STANDBY_DETECTION_MS}ms, marking Unknown")
                _wakeState.value = WakeState.Unknown
            }
        }
    }

    private fun flushBuffer() {
        var count = 0
        while (true) {
            val msg = messageBuffer.poll() ?: break
            sendToGlasses(msg.json)
            count++
        }
        if (count > 0) {
            Log.i(TAG, "Flushed $count buffered messages")
        }
    }

    /**
     * Get current buffer size for debugging/monitoring
     */
    fun getBufferSize(): Int = messageBuffer.size

    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        messageBuffer.clear()
        wakeTimeoutJob?.cancel()
        standbyTimerJob?.cancel()
    }
}
