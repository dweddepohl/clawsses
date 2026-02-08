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
    @SerializedName("currentSessionKey") val currentSessionKey: String? = null
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
