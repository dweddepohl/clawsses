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
 * 1. Buffers messages that need to be delivered
 * 2. Sends a wake signal to the glasses
 * 3. Waits for wake acknowledgment before delivering buffered messages
 * 4. Handles timeout and retry logic for reliable delivery
 *
 * The wake signal is sent via the CXR bridge which remains active even when
 * the glasses display is off. The glasses app receives the signal, wakes the
 * display, and sends an acknowledgment back.
 */
class WakeSignalManager(
    private val sendToGlasses: (String) -> Unit
) {
    companion object {
        private const val TAG = "WakeSignalManager"

        // Timeout waiting for wake acknowledgment
        private const val WAKE_ACK_TIMEOUT_MS = 3000L

        // Maximum buffer size to prevent memory issues
        private const val MAX_BUFFER_SIZE = 100

        // Minimum interval between wake signals to avoid spam
        private const val MIN_WAKE_INTERVAL_MS = 1000L
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

    // Track last wake signal time to avoid spam
    private var lastWakeSignalTime = 0L

    // Track streaming state - if actively streaming, glasses should be awake
    private var isStreaming = false
    private var lastActivityTime = System.currentTimeMillis()

    // Timeout job for wake acknowledgment
    private var wakeTimeoutJob: Job? = null

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
        lastActivityTime = now

        // Update streaming state
        if (isStreamContent) {
            isStreaming = true
        }

        return when (val state = _wakeState.value) {
            is WakeState.Awake -> {
                // Glasses is awake, send directly
                sendToGlasses(json)
                true
            }

            is WakeState.WakingUp -> {
                // Glasses is waking up, buffer the message
                bufferMessage(json, if (isStreamContent) "stream" else "message")
                false
            }

            is WakeState.Unknown -> {
                // Unknown state - need to determine if wake signal is needed
                // Check if we've had recent activity (glasses likely still awake)
                val timeSinceLastActivity = now - lastActivityTime
                if (timeSinceLastActivity < 5000) {
                    // Recent activity suggests glasses is probably awake
                    sendToGlasses(json)
                    true
                } else {
                    // May be in standby, initiate wake sequence
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
        lastActivityTime = System.currentTimeMillis()

        // If in unknown state, proactively send wake signal
        if (_wakeState.value is WakeState.Unknown && _enabled.value) {
            initiateWake(WakeSignal.REASON_STREAM_CONTENT, messageId)
        }
    }

    /**
     * Notify that streaming has ended for a message.
     */
    fun notifyStreamEnd(messageId: String) {
        isStreaming = false
        lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Handle wake acknowledgment from glasses.
     * This is called when glasses sends a wake_ack message.
     */
    fun handleWakeAck(ready: Boolean) {
        Log.i(TAG, "Received wake acknowledgment: ready=$ready")

        wakeTimeoutJob?.cancel()

        if (ready) {
            _wakeState.value = WakeState.Awake
            lastActivityTime = System.currentTimeMillis()

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
        lastActivityTime = System.currentTimeMillis()

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
    }

    /**
     * Notify that glasses has disconnected.
     * Reset state to unknown.
     */
    fun handleGlassesDisconnected() {
        _wakeState.value = WakeState.Unknown
        wakeTimeoutJob?.cancel()
        // Keep buffered messages - they'll be delivered on reconnect
        Log.d(TAG, "Glasses disconnected, state reset to Unknown")
    }

    /**
     * Notify that glasses has connected.
     * Reset to awake state and flush buffer.
     */
    fun handleGlassesConnected() {
        _wakeState.value = WakeState.Awake
        lastActivityTime = System.currentTimeMillis()
        wakeTimeoutJob?.cancel()

        // Flush buffered messages
        if (messageBuffer.isNotEmpty()) {
            Log.i(TAG, "Glasses connected, flushing ${messageBuffer.size} buffered messages")
            flushBuffer()
        }
    }

    /**
     * Mark glasses as potentially in standby.
     * Call this when significant time has passed without activity.
     */
    fun markPotentialStandby() {
        if (_wakeState.value is WakeState.Awake) {
            _wakeState.value = WakeState.Unknown
            Log.d(TAG, "Marked glasses as potential standby")
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

        // Send wake signal
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

            // If still waiting, assume glasses didn't wake or is offline
            if (_wakeState.value is WakeState.WakingUp) {
                Log.w(TAG, "Wake acknowledgment timeout, delivering messages anyway")
                _wakeState.value = WakeState.Unknown

                // Deliver buffered messages anyway - glasses might be awake
                // and just didn't receive the wake signal
                flushBuffer()
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
    }
}
