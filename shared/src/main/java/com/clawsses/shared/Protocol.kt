package com.clawsses.shared

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * Shared protocol definitions for communication between:
 * - Phone <-> OpenClaw Gateway (WebSocket)
 * - Phone <-> Glasses (BLE/CXR)
 */

private val gson = Gson()

// ============================================
// OpenClaw Gateway Protocol
// ============================================

/**
 * Request sent from client to OpenClaw Gateway.
 */
data class OpenClawRequest(
    @SerializedName("type") val type: String = "req",
    @SerializedName("id") val id: String,
    @SerializedName("method") val method: String,
    @SerializedName("params") val params: JsonObject? = null
) {
    fun toJson(): String = gson.toJson(this)
}

/**
 * Response from OpenClaw Gateway to client.
 */
data class OpenClawResponse(
    @SerializedName("type") val type: String = "res",
    @SerializedName("id") val id: String,
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("payload") val payload: JsonObject? = null,
    @SerializedName("error") val error: JsonObject? = null
) {
    companion object {
        fun fromJson(json: String): OpenClawResponse = gson.fromJson(json, OpenClawResponse::class.java)
    }
}

/**
 * Server-pushed event from OpenClaw Gateway.
 */
data class OpenClawEvent(
    @SerializedName("type") val type: String = "event",
    @SerializedName("event") val event: String,
    @SerializedName("payload") val payload: JsonObject? = null,
    @SerializedName("seq") val seq: Long? = null,
    @SerializedName("stateVersion") val stateVersion: Long? = null
) {
    companion object {
        fun fromJson(json: String): OpenClawEvent = gson.fromJson(json, OpenClawEvent::class.java)
    }
}

/** OpenClaw Gateway methods. */
object OpenClawMethods {
    const val CONNECT = "connect"
    const val CHAT_SEND = "chat.send"
    const val CHANNEL_SEND = "channel.send"
    const val CHANNEL_LIST = "channel.list"
    const val SESSION_CREATE = "session.create"
    const val SESSION_RESET = "sessions.reset"
    const val SESSION_LIST = "sessions.list"
    const val SESSION_RUN = "session.run"
    const val CHAT_HISTORY = "chat.history"
    const val CONFIG_GET = "config.get"
    const val SYSTEM_PRESENCE = "system-presence"
}

/** OpenClaw Gateway event names. */
object OpenClawEvents {
    const val CONNECT_CHALLENGE = "connect.challenge"
    const val AGENT = "agent"
    const val CHAT = "chat"
    const val PRESENCE = "presence"
    const val HEARTBEAT = "heartbeat"
}

/**
 * Parse a raw WebSocket frame into the appropriate OpenClaw message type.
 * Returns null if the frame is not valid JSON or has no recognized type.
 */
fun parseOpenClawFrame(json: String): Any? {
    return try {
        val obj = JsonParser.parseString(json).asJsonObject
        when (obj.get("type")?.asString) {
            "res" -> gson.fromJson(obj, OpenClawResponse::class.java)
            "event" -> gson.fromJson(obj, OpenClawEvent::class.java)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

// ============================================
// Phone -> Glasses Messages
// ============================================

/**
 * A chat message to display on the glasses HUD.
 * Sent when a message is complete (user echo or finished assistant message).
 */
data class ChatMessage(
    @SerializedName("type") val type: String = "chat_message",
    @SerializedName("id") val id: String,
    @SerializedName("role") val role: String,  // "user" or "assistant"
    @SerializedName("content") val content: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): ChatMessage = gson.fromJson(json, ChatMessage::class.java)
    }
}

/**
 * Agent has acknowledged the request but no content yet.
 * Glasses should show a thinking/processing indicator.
 */
data class AgentThinking(
    @SerializedName("type") val type: String = "agent_thinking",
    @SerializedName("id") val id: String
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): AgentThinking = gson.fromJson(json, AgentThinking::class.java)
    }
}

/**
 * A streaming text chunk from the agent.
 * Glasses should append this to the message with the given id.
 */
data class ChatStream(
    @SerializedName("type") val type: String = "chat_stream",
    @SerializedName("id") val id: String,
    @SerializedName("role") val role: String = "assistant",
    @SerializedName("chunk") val chunk: String
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): ChatStream = gson.fromJson(json, ChatStream::class.java)
    }
}

/**
 * Streaming is complete for the given message.
 * Glasses should remove the streaming cursor and mark the message as final.
 */
data class ChatStreamEnd(
    @SerializedName("type") val type: String = "chat_stream_end",
    @SerializedName("id") val id: String
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): ChatStreamEnd = gson.fromJson(json, ChatStreamEnd::class.java)
    }
}

/**
 * OpenClaw connection state update.
 */
data class ConnectionUpdate(
    @SerializedName("type") val type: String = "connection_update",
    @SerializedName("connected") val connected: Boolean,
    @SerializedName("sessionId") val sessionId: String? = null,
    @SerializedName("sessionName") val sessionName: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): ConnectionUpdate = gson.fromJson(json, ConnectionUpdate::class.java)
    }
}

/**
 * List of available sessions from OpenClaw.
 */
data class SessionListUpdate(
    @SerializedName("type") val type: String = "session_list",
    @SerializedName("sessions") val sessions: List<SessionInfo>,
    @SerializedName("currentSessionKey") val currentSessionKey: String? = null,
    @SerializedName("unreadSessionKeys") val unreadSessionKeys: List<String> = emptyList()
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): SessionListUpdate = gson.fromJson(json, SessionListUpdate::class.java)
    }
}

data class SessionInfo(
    @SerializedName("key") val key: String,
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("derivedTitle") val derivedTitle: String? = null,
    @SerializedName("updatedAt") val updatedAt: Long? = null,
    @SerializedName("kind") val kind: String? = null
) {
    /** Best available display name for this session */
    val name: String get() = label ?: displayName ?: derivedTitle ?: key
}

// ============================================
// Glasses -> Phone Messages
// ============================================

/**
 * User input from glasses (text and optional photo).
 */
data class UserInput(
    @SerializedName("type") val type: String = "user_input",
    @SerializedName("text") val text: String,
    @SerializedName("imageBase64") val imageBase64: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): UserInput = gson.fromJson(json, UserInput::class.java)
    }
}

/**
 * Session management action from glasses.
 */
data class SessionAction(
    @SerializedName("type") val type: String,  // "list_sessions" or "switch_session"
    @SerializedName("sessionKey") val sessionKey: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): SessionAction = gson.fromJson(json, SessionAction::class.java)
    }
}

/**
 * Slash command from glasses (e.g. "/model", "/clear").
 */
data class SlashCommand(
    @SerializedName("type") val type: String = "slash_command",
    @SerializedName("command") val command: String
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): SlashCommand = gson.fromJson(json, SlashCommand::class.java)
    }
}

/**
 * Request for more chat history from glasses.
 * Phone should load more history and send back a history_prepend message.
 * @param beforeMessageId The ID of the oldest currently-displayed message.
 *                        Phone uses this to know what messages glasses already have.
 */
data class RequestMoreHistory(
    @SerializedName("type") val type: String = "request_more_history",
    @SerializedName("beforeMessageId") val beforeMessageId: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): RequestMoreHistory = gson.fromJson(json, RequestMoreHistory::class.java)
    }
}

// ============================================
// Wake Signal Protocol (Phone <-> Glasses)
// ============================================

/**
 * Wake signal sent from phone to glasses to wake the display.
 * Phone sends this before sending content when glasses may be in standby.
 *
 * The wake mechanism works as follows:
 * 1. Phone detects new streaming content or spontaneous messages
 * 2. Phone sends wake_signal with reason and buffered message count
 * 3. Glasses receives via CXR bridge (which stays active even in standby)
 * 4. Glasses wakes display and sends wake_ack confirming readiness
 * 5. Phone delivers buffered messages after receiving ack
 *
 * @param reason The reason for the wake signal (stream_content, new_message, cron_message)
 * @param bufferedCount Number of messages buffered and waiting to be delivered
 * @param messageId Optional ID of the message that triggered the wake (for correlation)
 */
data class WakeSignal(
    @SerializedName("type") val type: String = "wake_signal",
    @SerializedName("reason") val reason: String,
    @SerializedName("bufferedCount") val bufferedCount: Int = 0,
    @SerializedName("messageId") val messageId: String? = null,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        const val REASON_STREAM_CONTENT = "stream_content"
        const val REASON_NEW_MESSAGE = "new_message"
        const val REASON_CRON_MESSAGE = "cron_message"

        fun fromJson(json: String): WakeSignal = gson.fromJson(json, WakeSignal::class.java)
    }
}

/**
 * Acknowledgment from glasses that it has woken and is ready to receive messages.
 * Phone should deliver buffered messages after receiving this.
 *
 * @param ready True if glasses is awake and ready, false if wake failed
 * @param timestamp When the glasses acknowledged the wake signal
 */
data class WakeAck(
    @SerializedName("type") val type: String = "wake_ack",
    @SerializedName("ready") val ready: Boolean = true,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): WakeAck = gson.fromJson(json, WakeAck::class.java)
    }
}

// ============================================
// TTS State Protocol (Phone <-> Glasses)
// ============================================

/**
 * TTS toggle request from glasses to phone.
 * Glasses sends this when user toggles voice responses in the More menu.
 */
data class TtsToggle(
    @SerializedName("type") val type: String = "tts_toggle",
    @SerializedName("enabled") val enabled: Boolean
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): TtsToggle = gson.fromJson(json, TtsToggle::class.java)
    }
}

/**
 * TTS state update from phone to glasses.
 * Phone sends this when TTS settings change or on connection.
 */
data class TtsState(
    @SerializedName("type") val type: String = "tts_state",
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("voiceName") val voiceName: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): TtsState = gson.fromJson(json, TtsState::class.java)
    }
}

// ============================================
// Utility
// ============================================

/**
 * Extract the "type" field from a JSON message string.
 */
fun extractMessageType(json: String): String? {
    return try {
        JsonParser.parseString(json).asJsonObject.get("type")?.asString
    } catch (e: Exception) {
        null
    }
}
