package com.clawsses.phone.openclaw

import android.util.Log
import com.clawsses.shared.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket client for connecting to an OpenClaw Gateway.
 * Handles the connect handshake (with Ed25519 device identity),
 * request/response correlation, event streaming, and auto-reconnect.
 */
class OpenClawClient(
    private val deviceIdentity: DeviceIdentity
) {

    companion object {
        private const val TAG = "OpenClawClient"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val PROTOCOL_VERSION = 3
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Authenticating : ConnectionState()
        object Connected : ConnectionState()
        data class PairingRequired(val message: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _events = MutableSharedFlow<OpenClawEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<OpenClawEvent> = _events.asSharedFlow()

    // Callbacks for forwarding to glasses
    var onChatMessage: ((ChatMessage) -> Unit)? = null
    var onChatHistory: ((List<ChatMessage>) -> Unit)? = null
    var onAgentThinking: ((AgentThinking) -> Unit)? = null
    var onChatStream: ((ChatStream) -> Unit)? = null
    var onChatStreamEnd: ((ChatStreamEnd) -> Unit)? = null
    var onSessionList: ((SessionListUpdate) -> Unit)? = null
    var onConnectionUpdate: ((ConnectionUpdate) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestSeq = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<OpenClawResponse>>()

    // Connection params (saved for reconnect)
    private var host: String = ""
    private var port: Int = 18789
    private var token: String = ""
    private var shouldReconnect = false

    // Active agent run tracking
    private var activeRunId: String? = null
    private var activeMessageId: String? = null
    private var activeSessionKey: String? = null // session that initiated the current run
    private var streamingContent = StringBuilder()

    // Current session tracking (exposed as StateFlow for phone UI)
    private val _currentSessionKey = MutableStateFlow<String?>(null)
    val currentSessionKey: StateFlow<String?> = _currentSessionKey.asStateFlow()

    // Sessions with unread messages (received while that session was not active)
    private val _unreadSessions = MutableStateFlow<Set<String>>(emptySet())
    val unreadSessions: StateFlow<Set<String>> = _unreadSessions.asStateFlow()

    // Available sessions (exposed as StateFlow for phone UI)
    private val _sessionList = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessionList: StateFlow<List<SessionInfo>> = _sessionList.asStateFlow()

    // Challenge nonce for auth handshake
    private var challengeNonce: String? = null

    fun connect(host: String, port: Int, token: String) {
        // Strip any protocol prefix the user might have entered
        val cleanHost = host
            .removePrefix("ws://")
            .removePrefix("wss://")
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')

        this.host = cleanHost
        this.port = port
        this.token = token
        this.shouldReconnect = true

        val url = "ws://$cleanHost:$port"
        Log.i(TAG, "Connecting to OpenClaw Gateway: $url")
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(url)
            .header("Origin", "http://$cleanHost:$port")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to $url (HTTP ${response.code})")
                _connectionState.value = ConnectionState.Authenticating
                // Wait for connect.challenge event from server
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleFrame(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                _connectionState.value = ConnectionState.Disconnected
                notifyConnectionUpdate(false)
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failed: ${t.javaClass.simpleName}: ${t.message}", t)
                _connectionState.value = ConnectionState.Error("${t.javaClass.simpleName}: ${t.message}")
                notifyConnectionUpdate(false)
                failAllPending("Connection lost")
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        notifyConnectionUpdate(false)
        failAllPending("Disconnected")
    }

    /**
     * Send a user message to OpenClaw and trigger an agent run.
     */
    fun sendMessage(text: String, images: List<String>? = null) {
        scope.launch {
            try {
                // Add user message to local chat
                val userMsgId = UUID.randomUUID().toString()
                val userMsg = ChatMessage(
                    id = userMsgId,
                    role = "user",
                    content = text
                )
                addChatMessage(userMsg)
                onChatMessage?.invoke(userMsg)

                // Send to OpenClaw as chat.send
                val idempotencyKey = UUID.randomUUID().toString()
                val params = JsonObject().apply {
                    addProperty("sessionKey", _currentSessionKey.value ?: "main")
                    addProperty("idempotencyKey", idempotencyKey)
                    addProperty("message", text)
                    if (!images.isNullOrEmpty()) {
                        val attachments = JsonArray()
                        images.forEachIndexed { i, base64 ->
                            val mimeType = detectImageMimeType(base64)
                            val ext = if (mimeType == "image/webp") "webp" else "jpg"
                            attachments.add(JsonObject().apply {
                                addProperty("type", "image")
                                addProperty("mimeType", mimeType)
                                addProperty("fileName", "glasses-photo-${i + 1}.$ext")
                                addProperty("content", base64)
                            })
                        }
                        add("attachments", attachments)
                    }
                }

                val assistantMsgId = UUID.randomUUID().toString()
                activeMessageId = assistantMsgId
                activeSessionKey = _currentSessionKey.value
                streamingContent.clear()

                val response = sendRequest(OpenClawMethods.CHAT_SEND, params)
                if (response.ok) {
                    // Extract runId from response
                    activeRunId = response.payload?.get("runId")?.asString
                    Log.d(TAG, "Agent run started: runId=$activeRunId")
                    // Notify glasses that agent is thinking
                    onAgentThinking?.invoke(AgentThinking(id = assistantMsgId))
                } else {
                    val errorMsg = response.error?.get("message")?.asString ?: "Agent run failed"
                    Log.e(TAG, "Agent run failed: $errorMsg")
                    activeRunId = null
                    activeMessageId = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
            }
        }
    }

    /**
     * Request the list of available sessions from the OpenClaw gateway.
     * The server returns GatewaySessionRow objects with key, displayName, label,
     * derivedTitle, updatedAt, kind, etc.
     */
    fun requestSessions() {
        scope.launch {
            try {
                val params = JsonObject().apply {
                    addProperty("includeDerivedTitles", true)
                }
                val response = sendRequest(OpenClawMethods.SESSION_LIST, params)
                if (response.ok) {
                    val sessionsPayload = response.payload
                    val sessions = mutableListOf<SessionInfo>()
                    val sessionsArray = sessionsPayload?.getAsJsonArray("sessions")
                    sessionsArray?.forEach { element ->
                        val obj = element.asJsonObject
                        sessions.add(SessionInfo(
                            key = obj.get("key")?.asString ?: "",
                            displayName = obj.get("displayName")?.asString,
                            label = obj.get("label")?.asString,
                            derivedTitle = obj.get("derivedTitle")?.asString,
                            updatedAt = obj.get("updatedAt")?.asLong,
                            kind = obj.get("kind")?.asString
                        ))
                    }
                    _sessionList.value = sessions
                    onSessionList?.invoke(SessionListUpdate(
                        sessions = sessions,
                        currentSessionKey = _currentSessionKey.value,
                        unreadSessionKeys = _unreadSessions.value.toList()
                    ))
                } else {
                    Log.e(TAG, "Session list request failed: ${response.error}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting sessions", e)
            }
        }
    }

    /**
     * Create a new session by resetting the current session key via sessions.reset.
     * The server generates a fresh session ID while preserving settings.
     * After reset, reloads history (which will be empty) and notifies glasses.
     */
    fun createSession() {
        scope.launch {
            try {
                val key = _currentSessionKey.value ?: "main"
                Log.d(TAG, "Creating new session (resetting key=$key)")
                val params = JsonObject().apply {
                    addProperty("key", key)
                }
                val response = sendRequest(OpenClawMethods.SESSION_RESET, params)
                if (response.ok) {
                    val newKey = response.payload?.get("key")?.asString ?: key
                    Log.i(TAG, "Session reset ok, key=$newKey")
                    _currentSessionKey.value = newKey
                    _chatMessages.value = emptyList()
                    notifyConnectionUpdate(true, newKey)
                    onChatHistory?.invoke(emptyList())
                } else {
                    val errorMsg = response.error?.get("message")?.asString ?: "Session reset failed"
                    Log.e(TAG, "Session reset failed: $errorMsg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating session", e)
            }
        }
    }

    /**
     * Switch to a different session by key.
     */
    fun switchSession(sessionKey: String) {
        scope.launch {
            Log.d(TAG, "Switching to session: $sessionKey")
            _currentSessionKey.value = sessionKey
            _chatMessages.value = emptyList()
            // Clear unread flag for the session we're switching to
            _unreadSessions.value = _unreadSessions.value - sessionKey
            notifyConnectionUpdate(true, sessionKey)
            loadSessionHistory(sessionKey)
        }
    }

    /**
     * Load chat history for the current (or given) session from the gateway.
     * Fetches messages via chat.history, populates local chat, and forwards to glasses.
     * Always notifies glasses (even for empty history) so they can clear stale messages.
     */
    fun loadSessionHistory(sessionKey: String? = null) {
        scope.launch {
            val key = sessionKey ?: _currentSessionKey.value ?: "main"
            try {
                val params = JsonObject().apply {
                    addProperty("sessionKey", key)
                    addProperty("limit", 50)
                }
                Log.d(TAG, "Requesting chat history for session $key")
                val response = sendRequest(OpenClawMethods.CHAT_HISTORY, params)
                if (response.ok) {
                    val chatMessages = mutableListOf<ChatMessage>()
                    val messagesArray = response.payload?.getAsJsonArray("messages")
                    Log.d(TAG, "Chat history response: payload keys=${response.payload?.keySet()}, messages count=${messagesArray?.size() ?: "null"}")

                    if (messagesArray != null && messagesArray.size() > 0) {
                        for (element in messagesArray) {
                            try {
                                val msgObj = element.asJsonObject
                                val role = msgObj.get("role")?.asString ?: continue
                                // Only show user and assistant messages
                                if (role != "user" && role != "assistant") continue

                                // content can be either a string or an array of {type,text} blocks
                                val contentElement = msgObj.get("content")
                                val content: String = when {
                                    contentElement == null -> continue
                                    contentElement.isJsonPrimitive -> contentElement.asString
                                    contentElement.isJsonArray -> {
                                        val textBuilder = StringBuilder()
                                        for (block in contentElement.asJsonArray) {
                                            val blockObj = block.asJsonObject
                                            if (blockObj.get("type")?.asString == "text") {
                                                val text = blockObj.get("text")?.asString
                                                if (text != null) textBuilder.append(text)
                                            }
                                        }
                                        textBuilder.toString()
                                    }
                                    else -> continue
                                }
                                if (content.isEmpty()) continue

                                val id = UUID.randomUUID().toString()
                                val timestamp = msgObj.get("timestamp")?.asLong ?: System.currentTimeMillis()
                                chatMessages.add(ChatMessage(
                                    id = id,
                                    role = role,
                                    content = content,
                                    timestamp = timestamp
                                ))
                            } catch (e: Exception) {
                                Log.w(TAG, "Skipping unparseable history message", e)
                            }
                        }
                    }

                    Log.d(TAG, "Loaded ${chatMessages.size} history messages for session $key")
                    _chatMessages.value = chatMessages
                    onChatHistory?.invoke(chatMessages)
                } else {
                    Log.e(TAG, "Chat history request failed: ${response.error}")
                    // Still notify with empty list so glasses clear stale messages
                    _chatMessages.value = emptyList()
                    onChatHistory?.invoke(emptyList())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading session history for $key", e)
                // Still notify with empty list so glasses clear stale messages
                _chatMessages.value = emptyList()
                onChatHistory?.invoke(emptyList())
            }
        }
    }

    /**
     * Send a slash command (e.g., "/model", "/clear").
     */
    fun sendSlashCommand(command: String) {
        // Slash commands are just user messages starting with /
        sendMessage(command)
    }

    fun cleanup() {
        shouldReconnect = false
        scope.cancel()
        disconnect()
    }

    // ============== Internal methods ==============

    private suspend fun sendRequest(
        method: String,
        params: JsonObject? = null
    ): OpenClawResponse {
        val id = "${method}-${requestSeq.getAndIncrement()}"
        val request = OpenClawRequest(id = id, method = method, params = params)
        val deferred = CompletableDeferred<OpenClawResponse>()
        pendingRequests[id] = deferred

        val json = request.toJson()
        Log.d(TAG, "Sending request: method=$method id=$id json=${json.take(300)}")
        webSocket?.send(json) ?: throw IllegalStateException("Not connected")

        return withTimeout(30_000) {
            deferred.await()
        }
    }

    private fun handleFrame(json: String) {
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            when (obj.get("type")?.asString) {
                "res" -> handleResponse(obj)
                "event" -> handleEvent(obj)
                else -> Log.w(TAG, "Unknown frame type: ${json.take(200)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing frame: ${json.take(200)}", e)
        }
    }

    private fun handleResponse(obj: JsonObject) {
        val id = obj.get("id")?.asString ?: return
        val ok = obj.get("ok")?.asBoolean ?: false
        val payload = obj.getAsJsonObject("payload")
        val error = obj.getAsJsonObject("error")

        val response = OpenClawResponse(id = id, ok = ok, payload = payload, error = error)

        // Complete the pending request
        val deferred = pendingRequests.remove(id)
        if (deferred != null) {
            deferred.complete(response)
        } else {
            Log.d(TAG, "No pending request for id=$id (may be agent completion)")
        }
    }

    private fun handleEvent(obj: JsonObject) {
        val eventName = obj.get("event")?.asString ?: return
        val payload = obj.getAsJsonObject("payload")
        val event = OpenClawEvent(event = eventName, payload = payload)

        Log.d(TAG, "Received event: $eventName")

        when (eventName) {
            OpenClawEvents.CONNECT_CHALLENGE -> {
                challengeNonce = payload?.get("nonce")?.asString
                Log.d(TAG, "Received connect challenge, nonce=${challengeNonce?.take(16)}...")
                performAuth()
            }
            OpenClawEvents.CHAT -> {
                handleChatEvent(payload)
            }
            OpenClawEvents.AGENT -> {
                // Lower-level agent events (tool use, lifecycle)
                Log.d(TAG, "Agent event: ${payload?.toString()?.take(200)}")
            }
            "tick", OpenClawEvents.HEARTBEAT -> {
                // Keep-alive, no action needed
            }
            else -> {
                Log.d(TAG, "Unhandled event: $eventName")
            }
        }

        // Emit to shared flow for external observers
        scope.launch { _events.emit(event) }
    }

    private fun performAuth() {
        val nonce = challengeNonce
        if (nonce == null) {
            Log.e(TAG, "No challenge nonce available for auth")
            _connectionState.value = ConnectionState.Error("No challenge nonce")
            return
        }

        scope.launch {
            try {
                val params = JsonObject().apply {
                    addProperty("minProtocol", PROTOCOL_VERSION)
                    addProperty("maxProtocol", PROTOCOL_VERSION)

                    add("client", JsonObject().apply {
                        addProperty("id", "openclaw-control-ui")
                        addProperty("version", "1.0.0")
                        addProperty("platform", "android")
                        addProperty("mode", "ui")
                    })

                    addProperty("role", "operator")
                    add("scopes", JsonArray().apply {
                        add("operator.admin")
                    })

                    add("auth", JsonObject().apply {
                        addProperty("token", token)
                    })

                    addProperty("locale", "nl-NL")
                    addProperty("userAgent", "clawsses-android/1.0.0")
                }

                Log.d(TAG, "Sending connect...")
                val response = sendRequest(OpenClawMethods.CONNECT, params)
                if (response.ok) {
                    Log.i(TAG, "Authentication successful!")

                    // Persist deviceToken if returned (from pairing approval)
                    val dt = response.payload?.get("deviceToken")?.asString
                    if (dt != null) {
                        deviceIdentity.deviceToken = dt
                        Log.d(TAG, "Persisted deviceToken")
                    }

                    // Extract the default session key from the hello-ok snapshot
                    val snapshot = response.payload?.getAsJsonObject("snapshot")
                    val sessionDefaults = snapshot?.getAsJsonObject("sessionDefaults")
                    val mainSessionKey = sessionDefaults?.get("mainSessionKey")?.asString
                    if (mainSessionKey != null) {
                        _currentSessionKey.value = mainSessionKey
                        Log.d(TAG, "Default session key from gateway: $mainSessionKey")
                    } else {
                        Log.w(TAG, "No mainSessionKey in connect response, snapshot keys=${snapshot?.keySet()}")
                    }

                    _connectionState.value = ConnectionState.Connected
                    notifyConnectionUpdate(true, _currentSessionKey.value)

                    // Load history for the current session on connect
                    loadSessionHistory()
                } else {
                    val errorMsg = response.error?.get("message")?.asString ?: "Authentication failed"
                    val errorCode = response.error?.get("code")?.asString ?: ""
                    Log.e(TAG, "Authentication failed: $errorMsg (code=$errorCode)")

                    if (errorCode == "pairing_required" || errorMsg.contains("pair", ignoreCase = true)) {
                        _connectionState.value = ConnectionState.PairingRequired(errorMsg)
                        // Keep reconnecting — user needs to approve on gateway
                    } else {
                        _connectionState.value = ConnectionState.Error(errorMsg)
                        shouldReconnect = false
                    }
                    webSocket?.close(1000, "Auth failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auth error", e)
                _connectionState.value = ConnectionState.Error("Auth error: ${e.message}")
            }
        }
    }

    /**
     * Handle a "chat" event from the gateway.
     * Payload structure:
     *   { runId, sessionKey, seq, state: "delta"|"final"|"aborted"|"error",
     *     message: { role, content: [{type:"text", text:"..."}] } }
     */
    private fun handleChatEvent(payload: JsonObject?) {
        payload ?: return
        val state = payload.get("state")?.asString ?: return
        val runId = payload.get("runId")?.asString
        val eventSessionKey = payload.get("sessionKey")?.asString

        // Check if this event belongs to a different session than the currently active one.
        // If so, mark that session as having unread messages and don't render into the current view.
        val currentKey = _currentSessionKey.value
        if (eventSessionKey != null && currentKey != null && eventSessionKey != currentKey) {
            Log.d(TAG, "Chat event for inactive session $eventSessionKey (active=$currentKey), state=$state — marking unread")
            _unreadSessions.value = _unreadSessions.value + eventSessionKey
            // Still need to clean up our streaming state if this was our active run
            // (user switched sessions mid-stream)
            if (runId != null && runId == activeRunId) {
                if (state == "final" || state == "aborted" || state == "error") {
                    Log.d(TAG, "Clearing stale active run $activeRunId for inactive session")
                    activeRunId = null
                    activeMessageId = null
                    activeSessionKey = null
                    streamingContent.clear()
                }
            }
            return
        }

        // Only process events for our active run
        if (runId != null && activeRunId != null && runId != activeRunId) return

        val msgId = activeMessageId ?: return

        when (state) {
            "delta" -> {
                // Each delta contains the full accumulated text, not just the new chunk.
                // Diff against what we already have to extract only the new portion.
                val fullText = extractTextFromMessage(payload)
                val previous = streamingContent.toString()
                if (fullText.length > previous.length) {
                    val newChunk = fullText.substring(previous.length)
                    streamingContent.clear()
                    streamingContent.append(fullText)
                    onChatStream?.invoke(ChatStream(id = msgId, chunk = newChunk))
                    // Update phone UI with streaming text
                    updateStreamingMessage(msgId, fullText)
                }
            }
            "final" -> {
                val fullText = extractTextFromMessage(payload)
                val previous = streamingContent.toString()
                if (fullText.isNotEmpty() && fullText.length > previous.length) {
                    val newChunk = fullText.substring(previous.length)
                    onChatStream?.invoke(ChatStream(id = msgId, chunk = newChunk))
                }
                // Use the final full text if available, otherwise keep what we accumulated
                if (fullText.isNotEmpty()) {
                    streamingContent.clear()
                    streamingContent.append(fullText)
                }
                finalizeStreaming()
            }
            "aborted", "error" -> {
                val errorMsg = payload.get("errorMessage")?.asString
                Log.e(TAG, "Chat run $state: $errorMsg")
                finalizeStreaming()
            }
        }
    }

    /**
     * Extract text from a chat event message payload.
     * message.content is an array of {type:"text", text:"..."} blocks.
     */
    private fun extractTextFromMessage(payload: JsonObject): String {
        val message = payload.getAsJsonObject("message") ?: return ""
        val contentArray = message.getAsJsonArray("content") ?: return ""
        val sb = StringBuilder()
        for (element in contentArray) {
            val block = element.asJsonObject
            if (block.get("type")?.asString == "text") {
                val text = block.get("text")?.asString
                if (text != null) sb.append(text)
            }
        }
        return sb.toString()
    }

    private fun finalizeStreaming() {
        val msgId = activeMessageId ?: return
        val content = streamingContent.toString()

        if (content.isNotEmpty()) {
            val assistantMsg = ChatMessage(
                id = msgId,
                role = "assistant",
                content = content
            )
            // Update in place if already in the list (from streaming), otherwise add
            updateOrAddChatMessage(assistantMsg)
            // Send the complete finalized message to glasses so they have the full
            // content even if any streaming chunks were missed
            onChatMessage?.invoke(assistantMsg)
        }

        onChatStreamEnd?.invoke(ChatStreamEnd(id = msgId))

        activeRunId = null
        activeMessageId = null
        activeSessionKey = null
        streamingContent.clear()
    }

    private fun addChatMessage(message: ChatMessage) {
        val current = _chatMessages.value.toMutableList()
        current.add(message)
        _chatMessages.value = current
    }

    /** Update existing message by id or add if not found */
    private fun updateOrAddChatMessage(message: ChatMessage) {
        val current = _chatMessages.value.toMutableList()
        val index = current.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            current[index] = message
        } else {
            current.add(message)
        }
        _chatMessages.value = current
    }

    /** Update or insert a streaming assistant message in the chat list */
    private fun updateStreamingMessage(msgId: String, fullText: String) {
        val current = _chatMessages.value.toMutableList()
        val index = current.indexOfFirst { it.id == msgId }
        val msg = ChatMessage(id = msgId, role = "assistant", content = fullText)
        if (index >= 0) {
            current[index] = msg
        } else {
            current.add(msg)
        }
        _chatMessages.value = current
    }

    private fun notifyConnectionUpdate(connected: Boolean, sessionId: String? = null) {
        val sessionName = sessionId?.let { id ->
            _sessionList.value.firstOrNull { it.key == id }?.name
        }
        onConnectionUpdate?.invoke(ConnectionUpdate(
            connected = connected,
            sessionId = sessionId,
            sessionName = sessionName
        ))
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            val state = _connectionState.value
            if (state is ConnectionState.Disconnected || state is ConnectionState.Error || state is ConnectionState.PairingRequired) {
                connect(host, port, token)
            }
        }
    }

    private fun failAllPending(reason: String) {
        pendingRequests.forEach { (id, deferred) ->
            deferred.completeExceptionally(Exception(reason))
        }
        pendingRequests.clear()
    }

    /** Detect image MIME type from base64 magic bytes. */
    private fun detectImageMimeType(base64: String): String {
        // Decode just enough bytes to check the magic header
        val prefix = base64.take(16)
        return try {
            val bytes = android.util.Base64.decode(prefix, android.util.Base64.DEFAULT)
            when {
                bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/webp"
                bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
                else -> "image/webp" // Default for CXR SDK photos
            }
        } catch (e: Exception) {
            "image/webp"
        }
    }
}
