package com.claudeglasses.shared

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Shared protocol definitions for communication between:
 * - Server <-> Phone (WebSocket)
 * - Phone <-> Glasses (BLE/CXR)
 */

private val gson = Gson()

// ============================================
// Server <-> Phone Messages
// ============================================

/**
 * Message sent from phone to server
 */
sealed class ClientMessage {
    data class TextInput(
        @SerializedName("type") val type: String = "input",
        @SerializedName("text") val text: String
    ) : ClientMessage()

    data class KeyPress(
        @SerializedName("type") val type: String = "key",
        @SerializedName("key") val key: String
    ) : ClientMessage()

    data class ImageUpload(
        @SerializedName("type") val type: String = "image",
        @SerializedName("data") val base64Data: String
    ) : ClientMessage()

    fun toJson(): String = gson.toJson(this)
}

/**
 * Message sent from server to phone
 */
data class ServerMessage(
    @SerializedName("type") val type: String,
    @SerializedName("lines") val lines: List<String>? = null,
    @SerializedName("mode") val mode: String? = null,
    @SerializedName("cursor") val cursor: Int? = null,
    @SerializedName("scroll") val scroll: Int? = null,
    @SerializedName("error") val error: String? = null
) {
    companion object {
        fun fromJson(json: String): ServerMessage = gson.fromJson(json, ServerMessage::class.java)
    }
}

// ============================================
// Phone <-> Glasses Messages
// ============================================

/**
 * Terminal update sent from phone to glasses
 */
data class TerminalUpdate(
    @SerializedName("type") val type: String = "terminal_update",
    @SerializedName("lines") val lines: List<String>,
    @SerializedName("cursor") val cursorPosition: Int,
    @SerializedName("mode") val mode: String,
    @SerializedName("scroll") val scrollPosition: Int,
    @SerializedName("connected") val isConnected: Boolean
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): TerminalUpdate = gson.fromJson(json, TerminalUpdate::class.java)
    }
}

/**
 * Command sent from glasses to phone
 */
data class GlassesCommand(
    @SerializedName("type") val type: String = "command",
    @SerializedName("command") val command: String,
    @SerializedName("data") val data: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): GlassesCommand = gson.fromJson(json, GlassesCommand::class.java)
    }
}

// ============================================
// Special Keys
// ============================================

object SpecialKeys {
    const val ESCAPE = "escape"
    const val ENTER = "enter"
    const val TAB = "tab"
    const val SHIFT_TAB = "shift_tab"
    const val UP = "up"
    const val DOWN = "down"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val BACKSPACE = "backspace"
    const val CTRL_C = "ctrl_c"
    const val CTRL_D = "ctrl_d"
    const val PAGE_UP = "page_up"
    const val PAGE_DOWN = "page_down"
}

// ============================================
// Interaction Modes
// ============================================

object InteractionModes {
    const val SCROLL = "scroll"
    const val NAVIGATE = "navigate"
    const val COMMAND = "command"
}
