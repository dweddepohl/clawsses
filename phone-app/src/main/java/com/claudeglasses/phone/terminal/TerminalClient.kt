package com.claudeglasses.phone.terminal

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for connecting to Claude Code terminal server
 */
class TerminalClient {

    companion object {
        private const val TAG = "TerminalClient"
        private const val RECONNECT_DELAY_MS = 3000L
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _terminalLines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val terminalLines: StateFlow<List<TerminalLine>> = _terminalLines.asStateFlow()

    // Callback for session-related messages to forward to glasses
    var onSessionMessage: ((String) -> Unit)? = null

    // Callback for terminal output messages (with color info) to forward to glasses
    var onTerminalOutput: ((String) -> Unit)? = null

    private val _scrollPosition = MutableStateFlow(0)
    val scrollPosition: StateFlow<Int> = _scrollPosition.asStateFlow()

    private val _mode = MutableStateFlow("normal")
    val mode: StateFlow<String> = _mode.asStateFlow()

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverUrl: String = ""

    fun connect(url: String) {
        serverUrl = url
        Log.i(TAG, "Connecting to WebSocket server: $url")
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to $url (HTTP ${response.code})")
                _connectionState.value = ConnectionState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection FAILED to $url: ${t.javaClass.simpleName}: ${t.message}", t)
                if (response != null) {
                    Log.e(TAG, "HTTP response: ${response.code} ${response.message}")
                }
                _connectionState.value = ConnectionState.Error("${t.javaClass.simpleName}: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(json: String) {
        try {
            val msg = JSONObject(json)
            val type = msg.optString("type", "")

            Log.d(TAG, "Received message type: $type")

            when (type) {
                "output", "terminal_update" -> {
                    // Full update: server sends complete lines array
                    val linesArray = msg.optJSONArray("lines")
                    if (linesArray != null) {
                        val newLines = mutableListOf<TerminalLine>()
                        for (i in 0 until linesArray.length()) {
                            val line = linesArray.optString(i, "")
                            newLines.add(TerminalLine(line, TerminalLine.Type.OUTPUT))
                        }
                        if (newLines.any { it.content.isNotBlank() }) {
                            _terminalLines.value = newLines
                        }
                    }

                    // Forward raw message to glasses (includes lineColors)
                    onTerminalOutput?.invoke(json)
                }
                "output_delta" -> {
                    // Delta update: server sends only changed lines
                    val changedLines = msg.optJSONObject("changedLines")
                    val totalLines = msg.optInt("totalLines", 0)
                    if (changedLines != null) {
                        val currentLines = _terminalLines.value.toMutableList()
                        // Ensure list is large enough
                        while (currentLines.size < totalLines) {
                            currentLines.add(TerminalLine("", TerminalLine.Type.OUTPUT))
                        }
                        // Trim if server has fewer lines now
                        while (currentLines.size > totalLines) {
                            currentLines.removeAt(currentLines.size - 1)
                        }
                        // Apply changed lines
                        val keys = changedLines.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val idx = key.toIntOrNull() ?: continue
                            if (idx in 0 until currentLines.size) {
                                currentLines[idx] = TerminalLine(changedLines.optString(key, ""), TerminalLine.Type.OUTPUT)
                            }
                        }
                        _terminalLines.value = currentLines
                    }
                    // Forward to glasses
                    onTerminalOutput?.invoke(json)
                }
                "sessions", "session_switched" -> {
                    // Forward session messages to glasses
                    Log.d(TAG, "Forwarding session message: $type")
                    onSessionMessage?.invoke(json)
                }
                "error" -> {
                    val error = msg.optString("error", "Unknown error")
                    val currentLines = _terminalLines.value.toMutableList()
                    currentLines.add(TerminalLine("Error: $error", TerminalLine.Type.ERROR))
                    _terminalLines.value = currentLines
                }
                "exit" -> {
                    val code = msg.optInt("code", -1)
                    val currentLines = _terminalLines.value.toMutableList()
                    currentLines.add(TerminalLine("Process exited with code $code", TerminalLine.Type.SYSTEM))
                    _terminalLines.value = currentLines
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $json", e)
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (_connectionState.value is ConnectionState.Disconnected ||
                _connectionState.value is ConnectionState.Error) {
                connect(serverUrl)
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Send text input to the terminal
     */
    fun sendInput(text: String) {
        // Use JSONObject for proper escaping of all special characters
        val json = JSONObject().apply {
            put("type", "input")
            put("text", text)
        }
        val message = json.toString()
        webSocket?.send(message)
        Log.d(TAG, "Sent input (${text.length} chars): ${text.take(50).replace("\n", "\\n")}")
    }

    /**
     * Send a special key command
     */
    fun sendKey(key: SpecialKey) {
        sendKey(key.code)
    }

    /**
     * Send a key by its string code (e.g., "up", "down", "enter", "escape")
     */
    fun sendKey(keyCode: String) {
        val message = """{"type":"key","key":"$keyCode"}"""
        webSocket?.send(message)
        Log.d(TAG, "Sent key: $keyCode")
    }

    /**
     * Send an image (screenshot from glasses) to Claude
     */
    fun sendImage(base64Image: String) {
        val message = """{"type":"image","data":"$base64Image"}"""
        webSocket?.send(message)
        Log.d(TAG, "Sent image (${base64Image.length} chars)")
    }

    /**
     * Request list of available tmux sessions
     */
    fun requestSessions() {
        val message = """{"type":"list_sessions"}"""
        webSocket?.send(message)
        Log.d(TAG, "Requested session list")
    }

    /**
     * Switch to a different tmux session
     */
    fun switchSession(sessionName: String) {
        val message = """{"type":"switch_session","session":"$sessionName"}"""
        webSocket?.send(message)
        Log.d(TAG, "Switching to session: $sessionName")
    }

    /**
     * Kill a tmux session
     */
    fun killSession(sessionName: String) {
        val message = """{"type":"kill_session","session":"$sessionName"}"""
        webSocket?.send(message)
        Log.d(TAG, "Killing session: $sessionName")
    }

    fun scrollUp(lines: Int = 10) {
        _scrollPosition.value = maxOf(0, _scrollPosition.value - lines)
    }

    fun scrollDown(lines: Int = 10) {
        _scrollPosition.value = minOf(
            _terminalLines.value.size - 1,
            _scrollPosition.value + lines
        )
    }

    fun cleanup() {
        scope.cancel()
        disconnect()
    }

    enum class SpecialKey(val code: String) {
        ESCAPE("escape"),
        ENTER("enter"),
        TAB("tab"),
        SHIFT_TAB("shift_tab"),
        UP("up"),
        DOWN("down"),
        LEFT("left"),
        RIGHT("right"),
        BACKSPACE("backspace"),
        CTRL_C("ctrl_c"),
        CTRL_D("ctrl_d")
    }

    data class TerminalLine(
        val content: String,
        val type: Type,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class Type {
            INPUT,
            OUTPUT,
            ERROR,
            SYSTEM
        }
    }
}
