package com.clawsses.phone.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawsses.phone.glasses.ApkInstaller
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.glasses.RokidSdkManager
import com.clawsses.phone.openclaw.DeviceIdentity
import com.clawsses.phone.openclaw.OpenClawClient
import com.clawsses.phone.ui.settings.SettingsScreen
import com.clawsses.phone.voice.VoiceCommandHandler
import com.clawsses.phone.voice.VoiceLanguageManager
import com.clawsses.phone.voice.VoiceRecognitionManager
import com.clawsses.shared.ChatMessage
import com.clawsses.shared.ConnectionUpdate
import com.clawsses.shared.SessionInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Managers
    val glassesManager = remember { GlassesConnectionManager(context) }
    val deviceIdentity = remember { DeviceIdentity(context) }
    val openClawClient = remember { OpenClawClient(deviceIdentity) }
    val voiceHandler = remember { VoiceCommandHandler(context) }
    val voiceLanguageManager = remember { VoiceLanguageManager(context) }
    val voiceRecognitionManager = remember { VoiceRecognitionManager(context) }
    val apkInstaller = remember { ApkInstaller(context) }

    // State
    val glassesState by glassesManager.connectionState.collectAsState()
    val openClawState by openClawClient.connectionState.collectAsState()
    val chatMessages by openClawClient.chatMessages.collectAsState()
    val isListening by voiceRecognitionManager.isListening.collectAsState()
    val voiceMode by voiceRecognitionManager.activeMode.collectAsState()
    val installState by apkInstaller.installState.collectAsState()
    val selectedVoiceLanguage by voiceLanguageManager.selectedLanguage.collectAsState()
    val sessionList by openClawClient.sessionList.collectAsState()
    val currentSessionKey by openClawClient.currentSessionKey.collectAsState()
    val unreadSessions by openClawClient.unreadSessions.collectAsState()

    // Persist OpenClaw settings in SharedPreferences
    val prefs = remember { context.getSharedPreferences("clawsses", android.content.Context.MODE_PRIVATE) }
    var openClawHost by remember {
        mutableStateOf(prefs.getString("openclaw_host", "10.0.2.2") ?: "10.0.2.2")
    }
    var openClawPort by remember {
        mutableStateOf(prefs.getString("openclaw_port", "18789") ?: "18789")
    }
    var openClawToken by remember {
        mutableStateOf(prefs.getString("openclaw_token", "") ?: "")
    }
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showSessionPicker by remember { mutableStateOf(false) }
    var pendingPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()

    // Initialize voice handler and query available languages
    LaunchedEffect(Unit) {
        voiceHandler.initialize()
        voiceLanguageManager.queryAvailableLanguages()
        // Set up partial result forwarding for both voice recognition managers
        voiceHandler.onPartialResult = { partialText ->
            RokidSdkManager.sendAsrContent(partialText)
            val stateMsg = org.json.JSONObject().apply {
                put("type", "voice_state")
                put("state", "recognizing")
                put("text", partialText)
            }
            glassesManager.sendRawMessage(stateMsg.toString())
        }
        voiceRecognitionManager.onPartialResult = { partialText ->
            RokidSdkManager.sendAsrContent(partialText)
            val stateMsg = org.json.JSONObject().apply {
                put("type", "voice_state")
                put("state", "recognizing")
                put("text", partialText)
            }
            glassesManager.sendRawMessage(stateMsg.toString())
        }

        // Try to auto-reconnect to previously paired glasses on startup
        glassesManager.tryAutoReconnectOnStartup()
    }

    // Fetch session list when OpenClaw connects
    LaunchedEffect(openClawState) {
        if (openClawState is OpenClawClient.ConnectionState.Connected) {
            openClawClient.requestSessions()
        }
    }

    // Start/stop foreground service based on glasses connection state,
    // and send current chat history when glasses connect.
    // IMPORTANT: Don't stop the service during Reconnecting — killing the foreground
    // service drops the wake lock and lets Android kill the Bluetooth connection,
    // making reconnection impossible. Only stop on true Disconnected (not reconnecting).
    LaunchedEffect(glassesState) {
        when (glassesState) {
            is GlassesConnectionManager.ConnectionState.Connected -> {
                android.util.Log.i("MainScreen", "Glasses connected — starting foreground service")
                com.clawsses.phone.service.GlassesConnectionService.start(context)
                // Send current chat history to glasses if we have any
                val currentMessages = openClawClient.chatMessages.value
                if (currentMessages.isNotEmpty()) {
                    android.util.Log.i("MainScreen", "Sending ${currentMessages.size} history messages to newly connected glasses")
                    glassesManager.sendRawMessage(buildChatHistoryJson(currentMessages))
                }
            }
            is GlassesConnectionManager.ConnectionState.Disconnected -> {
                // Only stop the service if we're truly disconnected (no saved pairing to reconnect to).
                // If we have a pairing, the service keeps BT alive for auto-reconnect.
                if (!RokidSdkManager.hasSavedConnectionInfo()) {
                    android.util.Log.i("MainScreen", "Glasses disconnected (no pairing) — stopping foreground service")
                    com.clawsses.phone.service.GlassesConnectionService.stop(context)
                } else {
                    android.util.Log.i("MainScreen", "Glasses disconnected but paired — keeping foreground service for reconnect")
                }
            }
            is GlassesConnectionManager.ConnectionState.Reconnecting -> {
                // Keep foreground service alive during reconnection attempts
                android.util.Log.i("MainScreen", "Glasses reconnecting — keeping foreground service")
                com.clawsses.phone.service.GlassesConnectionService.start(context)
            }
            else -> {}
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    // Wire OpenClaw client callbacks to forward to glasses
    LaunchedEffect(Unit) {
        openClawClient.onChatMessage = { msg ->
            glassesManager.sendRawMessage(msg.toJson())
        }
        openClawClient.onChatHistory = { messages ->
            val json = buildChatHistoryJson(messages)
            android.util.Log.i("MainScreen", "Forwarding chat_history to glasses: ${messages.size} messages, ${json.length} chars")
            glassesManager.sendRawMessage(json)
        }
        openClawClient.onAgentThinking = { msg ->
            glassesManager.sendRawMessage(msg.toJson())
        }
        openClawClient.onChatStream = { msg ->
            glassesManager.sendRawMessage(msg.toJson())
        }
        openClawClient.onChatStreamEnd = { msg ->
            glassesManager.sendRawMessage(msg.toJson())
        }
        openClawClient.onSessionList = { msg ->
            glassesManager.sendRawMessage(msg.toJson())
        }
        openClawClient.onConnectionUpdate = { msg ->
            glassesManager.sendRawMessage(msg.toJson())
        }
    }

    // Handle AI scene events (glasses long-press triggers voice input)
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    LaunchedEffect(Unit) {
        glassesManager.onAiKeyDown = {
            android.util.Log.i("MainScreen", ">>> AI key down from glasses - will start voice recognition")
            mainHandler.postDelayed({
                android.util.Log.i("MainScreen", ">>> Starting voice recognition on main thread")
                RokidSdkManager.setCommunicationDevice()
                startVoiceRecognitionWithManager(
                    voiceRecognitionManager = voiceRecognitionManager,
                    voiceHandler = voiceHandler,
                    openClawClient = openClawClient,
                    glassesManager = glassesManager,
                    mainHandler = mainHandler,
                    isRetry = false,
                    languageTag = voiceLanguageManager.getActiveLanguageTag(),
                    pendingPhotos = { pendingPhotos },
                    onPhotosConsumed = { pendingPhotos = emptyList() }
                )
            }, 300)
        }
        glassesManager.onAiExit = {
            android.util.Log.d("MainScreen", "AI scene exited on glasses (recognizer continues)")
        }
    }

    // Handle messages from glasses and forward to OpenClaw
    LaunchedEffect(Unit) {
        glassesManager.onMessageFromGlasses = { message ->
            try {
                val json = org.json.JSONObject(message)
                val type = json.optString("type", "")
                when (type) {
                    "user_input" -> {
                        val text = json.optString("text", "")
                        val images = pendingPhotos.ifEmpty { null }
                        android.util.Log.d("MainScreen", "Received user input from glasses (${text.length} chars, photos=${pendingPhotos.size})")
                        if (text.isNotEmpty()) {
                            openClawClient.sendMessage(text, images)
                        }
                        pendingPhotos = emptyList()
                    }
                    "start_voice" -> {
                        android.util.Log.d("MainScreen", "Glasses requested voice recognition start")
                        com.clawsses.phone.glasses.RokidSdkManager.setCommunicationDevice()
                        // Send voice state with mode info
                        val modeIndicator = if (voiceRecognitionManager.isOpenAIAvailable()) "openai" else "device"
                        voiceRecognitionManager.startListening(languageTag = voiceLanguageManager.getActiveLanguageTag()) { result ->
                            com.clawsses.phone.glasses.RokidSdkManager.clearCommunicationDevice()
                            when (result) {
                                is VoiceCommandHandler.VoiceResult.Text -> {
                                    android.util.Log.d("MainScreen", "Voice result text: ${result.text.take(100)}")
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "voice_result")
                                        put("result_type", "text")
                                        put("text", result.text)
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                    // Don't send to OpenClaw here — glasses stages the text
                                    // and sends user_input when user confirms via Send button
                                }
                                is VoiceCommandHandler.VoiceResult.Command -> {
                                    android.util.Log.d("MainScreen", "Voice result command: ${result.command}")
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "voice_result")
                                        put("result_type", "command")
                                        put("text", result.command)
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                }
                                is VoiceCommandHandler.VoiceResult.Error -> {
                                    android.util.Log.e("MainScreen", "Voice result error: ${result.message}")
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "voice_result")
                                        put("result_type", "error")
                                        put("text", result.message)
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                }
                            }
                        }
                        val stateMsg = org.json.JSONObject().apply {
                            put("type", "voice_state")
                            put("state", "listening")
                            put("mode", modeIndicator)
                        }
                        glassesManager.sendRawMessage(stateMsg.toString())
                    }
                    "cancel_voice" -> {
                        android.util.Log.d("MainScreen", "Glasses requested voice recognition cancel")
                        voiceRecognitionManager.stopListening()
                        com.clawsses.phone.glasses.RokidSdkManager.clearCommunicationDevice()
                        val stateMsg = org.json.JSONObject().apply {
                            put("type", "voice_state")
                            put("state", "idle")
                        }
                        glassesManager.sendRawMessage(stateMsg.toString())
                    }
                    "list_sessions" -> {
                        android.util.Log.d("MainScreen", "Requesting session list for glasses")
                        openClawClient.requestSessions()
                    }
                    "switch_session" -> {
                        val sessionKey = json.optString("sessionKey", "")
                        android.util.Log.d("MainScreen", "Switching to session: $sessionKey")
                        if (sessionKey.isNotEmpty()) {
                            openClawClient.switchSession(sessionKey)
                        }
                    }
                    "create_session" -> {
                        android.util.Log.d("MainScreen", "Creating new session from glasses")
                        openClawClient.createSession()
                    }
                    "slash_command" -> {
                        val command = json.optString("command", "")
                        android.util.Log.d("MainScreen", "Slash command from glasses: $command")
                        if (command.isNotEmpty()) {
                            openClawClient.sendSlashCommand(command)
                        }
                    }
                    "request_state" -> {
                        android.util.Log.d("MainScreen", "Glasses requested current state")
                        // Send OpenClaw connection status
                        val isConnected = openClawState is OpenClawClient.ConnectionState.Connected
                        val currentKey = openClawClient.currentSessionKey.value
                        val currentName = currentKey?.let { key ->
                            openClawClient.sessionList.value.firstOrNull { it.key == key }?.name
                        }
                        val connUpdate = ConnectionUpdate(
                            connected = isConnected,
                            sessionId = currentKey,
                            sessionName = currentName
                        )
                        glassesManager.sendRawMessage(connUpdate.toJson())
                        // Send current chat history
                        val currentMessages = openClawClient.chatMessages.value
                        glassesManager.sendRawMessage(buildChatHistoryJson(currentMessages))
                    }
                    "take_photo" -> {
                        android.util.Log.d("MainScreen", "Glasses requested photo capture")
                        RokidSdkManager.onPhotoResult = { status, photoBytes ->
                            mainHandler.post {
                                android.util.Log.d("MainScreen", "Photo callback: status=$status, bytes=${photoBytes?.size}")
                                if (photoBytes != null && photoBytes.isNotEmpty()) {
                                    val base64 = android.util.Base64.encodeToString(photoBytes, android.util.Base64.NO_WRAP)
                                    pendingPhotos = pendingPhotos + base64
                                    val thumbnail = createThumbnailBase64(photoBytes, 80, 60)
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "photo_result")
                                        put("status", "captured")
                                        put("thumbnail", thumbnail)
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                } else {
                                    android.util.Log.e("MainScreen", "Photo capture failed: status=$status")
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "photo_result")
                                        put("status", "error")
                                        put("message", "Capture failed: $status")
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                }
                                RokidSdkManager.onPhotoResult = null
                            }
                        }
                        RokidSdkManager.takeGlassPhotoGlobal(640, 480, 75)
                    }
                    "remove_photo" -> {
                        val all = json.optBoolean("all", false)
                        val index = json.optInt("index", -1)
                        if (all) {
                            pendingPhotos = emptyList()
                        } else if (index in pendingPhotos.indices) {
                            pendingPhotos = pendingPhotos.toMutableList().apply { removeAt(index) }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainScreen", "Error parsing glasses message", e)
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            glassesManager.disconnect()
            openClawClient.cleanup()
            voiceHandler.cleanup()
            voiceRecognitionManager.cleanup()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clawsses") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Thumbnail strip for queued photos
                if (pendingPhotos.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pendingPhotos.forEachIndexed { index, base64 ->
                            val thumbnail = remember(base64) {
                                try {
                                    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                        ?.asImageBitmap()
                                } catch (_: Exception) { null }
                            }
                            if (thumbnail != null) {
                                Box {
                                    Image(
                                        bitmap = thumbnail,
                                        contentDescription = "Queued photo ${index + 1}",
                                        modifier = Modifier
                                            .height(56.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                    // Remove button
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove photo",
                                        modifier = Modifier
                                            .size(18.dp)
                                            .align(Alignment.TopEnd)
                                            .background(
                                                Color.Black.copy(alpha = 0.6f),
                                                RoundedCornerShape(9.dp)
                                            )
                                            .clickable {
                                                pendingPhotos = pendingPhotos
                                                    .toMutableList()
                                                    .apply { removeAt(index) }
                                                glassesManager.sendRawMessage(
                                                    """{"type":"remove_photo","index":$index}"""
                                                )
                                            }
                                            .padding(2.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            BottomAppBar {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("Type message...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) {
                                val hadPhotos = pendingPhotos.isNotEmpty()
                                openClawClient.sendMessage(inputText, pendingPhotos.ifEmpty { null })
                                inputText = ""
                                pendingPhotos = emptyList()
                                if (hadPhotos) {
                                    glassesManager.sendRawMessage("""{"type":"remove_photo","all":true}""")
                                }
                            }
                        }
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )

                // Camera button — always takes a new photo, adds to pending list
                IconButton(
                    onClick = {
                        android.util.Log.d("MainScreen", "Taking photo from glasses camera")
                        android.widget.Toast.makeText(context, "Capturing photo...", android.widget.Toast.LENGTH_SHORT).show()
                        RokidSdkManager.onPhotoResult = { status, photoBytes ->
                            mainHandler.post {
                                android.util.Log.d("MainScreen", "Photo callback: status=$status, bytes=${photoBytes?.size}")
                                if (photoBytes != null && photoBytes.isNotEmpty()) {
                                    val base64 = android.util.Base64.encodeToString(photoBytes, android.util.Base64.NO_WRAP)
                                    pendingPhotos = pendingPhotos + base64
                                    android.util.Log.d("MainScreen", "Photo added (total: ${pendingPhotos.size})")
                                    android.widget.Toast.makeText(context, "Photo ${pendingPhotos.size} captured!", android.widget.Toast.LENGTH_SHORT).show()
                                    val thumbnail = createThumbnailBase64(photoBytes, 80, 60)
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "photo_result")
                                        put("status", "captured")
                                        put("thumbnail", thumbnail)
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                } else {
                                    android.util.Log.e("MainScreen", "Photo capture failed: status=$status")
                                    android.widget.Toast.makeText(context, "Photo failed: $status", android.widget.Toast.LENGTH_LONG).show()
                                }
                                RokidSdkManager.onPhotoResult = null
                            }
                        }
                        RokidSdkManager.takeGlassPhotoGlobal(640, 480, 75)
                    },
                    enabled = glassesState is GlassesConnectionManager.ConnectionState.Connected
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "Take photo",
                        tint = if (pendingPhotos.isNotEmpty()) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                    )
                }

                // Voice button with mode indicator
                IconButton(
                    onClick = {
                        if (isListening) {
                            voiceRecognitionManager.stopListening()
                        } else {
                            voiceRecognitionManager.startListening(languageTag = voiceLanguageManager.getActiveLanguageTag()) { result ->
                                when (result) {
                                    is VoiceCommandHandler.VoiceResult.Text -> {
                                        if (result.text.isNotEmpty()) {
                                            openClawClient.sendMessage(result.text)
                                        }
                                    }
                                    is VoiceCommandHandler.VoiceResult.Command -> {
                                        // Voice commands handled by glasses
                                    }
                                    is VoiceCommandHandler.VoiceResult.Error -> {
                                        // Handle error - could show toast
                                    }
                                }
                            }
                        }
                    }
                ) {
                    // Icon color indicates mode when listening:
                    // Red = listening, with tint for OpenAI (blue) vs device (red)
                    val iconTint = when {
                        !isListening -> MaterialTheme.colorScheme.onSurface
                        voiceMode == VoiceRecognitionManager.RecognitionMode.OPENAI -> Color(0xFF2196F3)  // Blue for OpenAI
                        else -> Color.Red  // Red for device/fallback
                    }
                    Icon(
                        if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = when {
                            !isListening -> "Voice input"
                            voiceMode == VoiceRecognitionManager.RecognitionMode.OPENAI -> "Listening (OpenAI)"
                            else -> "Listening (Device)"
                        },
                        tint = iconTint
                    )
                }

                // Send button
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            val hadPhotos = pendingPhotos.isNotEmpty()
                            openClawClient.sendMessage(inputText, pendingPhotos.ifEmpty { null })
                            inputText = ""
                            pendingPhotos = emptyList()
                            if (hadPhotos) {
                                glassesManager.sendRawMessage("""{"type":"remove_photo","all":true}""")
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, "Send")
                }
            }
            } // Column
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection status bar
            ConnectionStatusBar(
                glassesState = glassesState,
                openClawState = openClawState,
                onConnectGlasses = { glassesManager.startScanning() },
                onConnectOpenClaw = {
                    val portNum = openClawPort.toIntOrNull() ?: 18789
                    openClawClient.connect(openClawHost, portNum, openClawToken)
                }
            )

            // Session selector
            if (openClawState is OpenClawClient.ConnectionState.Connected) {
                SessionSelector(
                    sessions = sessionList,
                    currentSessionKey = currentSessionKey,
                    unreadSessionKeys = unreadSessions,
                    expanded = showSessionPicker,
                    onToggle = {
                        if (!showSessionPicker) {
                            openClawClient.requestSessions()
                        }
                        showSessionPicker = !showSessionPicker
                    },
                    onSelect = { session ->
                        showSessionPicker = false
                        openClawClient.switchSession(session.key)
                    },
                    onDismiss = { showSessionPicker = false }
                )
            }

            // Chat messages
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (chatMessages.isEmpty()) {
                    Text(
                        "No messages yet. Connect to OpenClaw and send a message.",
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(chatMessages) { msg ->
                            ChatMessageRow(msg)
                        }
                    }
                }
            }
        }
    }

    // Glasses state for settings
    val debugModeEnabled by glassesManager.debugModeEnabled.collectAsState()
    val discoveredDevices by glassesManager.discoveredDevices.collectAsState()
    val wifiP2PConnected by glassesManager.wifiP2PConnected.collectAsState()
    var hasCachedSn by remember { mutableStateOf(RokidSdkManager.hasCachedSn()) }
    var cachedSn by remember { mutableStateOf(RokidSdkManager.getCachedSn()) }
    var cachedDeviceName by remember { mutableStateOf(RokidSdkManager.getCachedDeviceName()) }
    val sdkConnected = glassesState is GlassesConnectionManager.ConnectionState.Connected && !debugModeEnabled

    // Settings screen (full-screen overlay with slide-up animation)
    AnimatedVisibility(
        visible = showSettings,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        SettingsScreen(
            // Server
            openClawHost = openClawHost,
            openClawPort = openClawPort,
            openClawToken = openClawToken,
            openClawState = openClawState,
            onApplyServerSettings = { host, port, token ->
                openClawHost = host
                openClawPort = port
                openClawToken = token
                prefs.edit()
                    .putString("openclaw_host", host)
                    .putString("openclaw_port", port)
                    .putString("openclaw_token", token)
                    .apply()
                openClawClient.disconnect()
            },
            // Glasses
            glassesState = glassesState,
            discoveredDevices = discoveredDevices,
            wifiP2PConnected = wifiP2PConnected,
            debugModeEnabled = debugModeEnabled,
            onStartScanning = { glassesManager.startScanning() },
            onStopScanning = { glassesManager.stopScanning() },
            onConnectDevice = { device -> glassesManager.connectToDevice(device) },
            onDisconnectGlasses = { glassesManager.disconnect() },
            onInitWifiP2P = { glassesManager.initWifiP2P() },
            onClearSn = {
                RokidSdkManager.clearCachedSn()
                hasCachedSn = false
                cachedSn = null
                cachedDeviceName = null
            },
            hasCachedSn = hasCachedSn,
            cachedSn = cachedSn,
            cachedDeviceName = cachedDeviceName,
            // Software Update
            installState = installState,
            sdkConnected = sdkConnected,
            onInstall = { apkInstaller.installViaSdk() },
            onCancelInstall = { apkInstaller.cancelInstallation() },
            // Voice
            voiceLanguageManager = voiceLanguageManager,
            voiceRecognitionManager = voiceRecognitionManager,
            // Developer
            onDebugModeChange = { enabled ->
                if (enabled) glassesManager.enableDebugMode()
                else glassesManager.disableDebugMode()
            },
            // Navigation
            onBack = {
                showSettings = false
                glassesManager.stopScanning()
                if (installState is ApkInstaller.InstallState.Success ||
                    installState is ApkInstaller.InstallState.Error) {
                    apkInstaller.resetState()
                }
            },
        )
    }
    } // Box
}

@Composable
fun ChatMessageRow(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Text(
            text = msg.content,
            color = if (isUser) Color(0xFF4EC9B0) else Color(0xFFD4D4D4),
            fontSize = 13.sp,
            modifier = Modifier
                .background(
                    if (isUser) Color(0xFF2A3A2A) else Color.Transparent,
                    shape = MaterialTheme.shapes.small
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .fillMaxWidth(0.85f)
        )
    }
}

@Composable
fun ConnectionStatusBar(
    glassesState: GlassesConnectionManager.ConnectionState,
    openClawState: OpenClawClient.ConnectionState,
    onConnectGlasses: () -> Unit,
    onConnectOpenClaw: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Glasses status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                when (glassesState) {
                    is GlassesConnectionManager.ConnectionState.Connected -> Icons.Default.CheckCircle
                    is GlassesConnectionManager.ConnectionState.Connecting,
                    is GlassesConnectionManager.ConnectionState.Scanning,
                    is GlassesConnectionManager.ConnectionState.Reconnecting -> Icons.Default.Sync
                    is GlassesConnectionManager.ConnectionState.Error -> Icons.Default.Error
                    else -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when (glassesState) {
                    is GlassesConnectionManager.ConnectionState.Connected -> Color.Green
                    is GlassesConnectionManager.ConnectionState.Connecting,
                    is GlassesConnectionManager.ConnectionState.Scanning -> Color.Yellow
                    is GlassesConnectionManager.ConnectionState.Reconnecting -> Color(0xFFFFA500) // Orange
                    is GlassesConnectionManager.ConnectionState.Error -> Color.Red
                    else -> Color.Gray
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Visibility,
                contentDescription = "Glasses",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            if (glassesState is GlassesConnectionManager.ConnectionState.Disconnected) {
                TextButton(
                    onClick = onConnectGlasses,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Connect", fontSize = 12.sp)
                }
            } else {
                Text(
                    text = when (glassesState) {
                        is GlassesConnectionManager.ConnectionState.Connected -> "Connected"
                        is GlassesConnectionManager.ConnectionState.Connecting -> "Connecting..."
                        is GlassesConnectionManager.ConnectionState.Scanning -> "Scanning..."
                        is GlassesConnectionManager.ConnectionState.Reconnecting -> {
                            val state = glassesState as GlassesConnectionManager.ConnectionState.Reconnecting
                            "Reconnecting (#${state.attempt})..."
                        }
                        is GlassesConnectionManager.ConnectionState.Error -> "Error"
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        // OpenClaw status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f)
        ) {
            if (openClawState is OpenClawClient.ConnectionState.Disconnected) {
                TextButton(
                    onClick = onConnectOpenClaw,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Connect", fontSize = 12.sp)
                }
            } else {
                Text(
                    text = when (openClawState) {
                        is OpenClawClient.ConnectionState.Connected -> "Connected"
                        is OpenClawClient.ConnectionState.Connecting -> "Connecting..."
                        is OpenClawClient.ConnectionState.Authenticating -> "Authenticating..."
                        is OpenClawClient.ConnectionState.PairingRequired -> "Pairing required"
                        is OpenClawClient.ConnectionState.Error -> (openClawState as OpenClawClient.ConnectionState.Error).message.take(40)
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = when (openClawState) {
                        is OpenClawClient.ConnectionState.Error -> Color.Red
                        is OpenClawClient.ConnectionState.PairingRequired -> Color(0xFFFF8800)
                        else -> Color.Gray
                    }
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Cloud,
                contentDescription = "OpenClaw",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                when (openClawState) {
                    is OpenClawClient.ConnectionState.Connected -> Icons.Default.CheckCircle
                    is OpenClawClient.ConnectionState.Connecting,
                    is OpenClawClient.ConnectionState.Authenticating -> Icons.Default.Sync
                    is OpenClawClient.ConnectionState.PairingRequired -> Icons.Default.Warning
                    is OpenClawClient.ConnectionState.Error -> Icons.Default.Error
                    else -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when (openClawState) {
                    is OpenClawClient.ConnectionState.Connected -> Color.Green
                    is OpenClawClient.ConnectionState.Connecting,
                    is OpenClawClient.ConnectionState.Authenticating -> Color.Yellow
                    is OpenClawClient.ConnectionState.PairingRequired -> Color(0xFFFF8800)
                    is OpenClawClient.ConnectionState.Error -> Color.Red
                    else -> Color.Gray
                },
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun SessionSelector(
    sessions: List<SessionInfo>,
    currentSessionKey: String?,
    unreadSessionKeys: Set<String> = emptySet(),
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (SessionInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val currentSession = sessions.firstOrNull { it.key == currentSessionKey }
    val displayName = currentSession?.name ?: currentSessionKey ?: "No session"
    val hasAnyUnread = unreadSessionKeys.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Forum,
                contentDescription = "Session",
                modifier = Modifier.size(18.dp),
                tint = if (hasAnyUnread) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (hasAnyUnread) {
                Icon(
                    Icons.Default.Circle,
                    contentDescription = "Unread messages in other sessions",
                    modifier = Modifier.size(8.dp),
                    tint = Color(0xFF4CAF50)
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            if (sessions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Loading sessions...") },
                    onClick = {},
                    enabled = false
                )
            } else {
                sessions.forEach { session ->
                    val isCurrent = session.key == currentSessionKey
                    val hasUnread = session.key in unreadSessionKeys
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isCurrent) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Current",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else if (hasUnread) {
                                    Icon(
                                        Icons.Default.Circle,
                                        contentDescription = "New messages",
                                        modifier = Modifier.size(10.dp),
                                        tint = Color(0xFF4CAF50)
                                    )
                                    Spacer(Modifier.width(11.dp))
                                }
                                Text(
                                    text = session.name,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                            else if (hasUnread) Color(0xFF4CAF50)
                                            else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        },
                        onClick = { onSelect(session) }
                    )
                }
            }
        }
    }
}

/**
 * Start voice recognition using VoiceRecognitionManager (OpenAI with fallback).
 */
private fun startVoiceRecognitionWithManager(
    voiceRecognitionManager: VoiceRecognitionManager,
    voiceHandler: VoiceCommandHandler,
    openClawClient: OpenClawClient,
    glassesManager: GlassesConnectionManager,
    mainHandler: android.os.Handler,
    isRetry: Boolean,
    languageTag: String? = null,
    pendingPhotos: () -> List<String> = { emptyList() },
    onPhotosConsumed: () -> Unit = {}
) {
    // Send initial voice state with mode indicator
    val modeIndicator = if (voiceRecognitionManager.isOpenAIAvailable()) "openai" else "device"
    val stateMsg = org.json.JSONObject().apply {
        put("type", "voice_state")
        put("state", "listening")
        put("mode", modeIndicator)
    }
    glassesManager.sendRawMessage(stateMsg.toString())

    voiceRecognitionManager.startListening(languageTag = languageTag) { result ->
        val actualMode = voiceRecognitionManager.getModeDescription()
        android.util.Log.i("MainScreen", ">>> Voice result received (mode=$actualMode, retry=$isRetry): $result")

        when (result) {
            is VoiceCommandHandler.VoiceResult.Text -> {
                RokidSdkManager.clearCommunicationDevice()
                if (result.text.isNotEmpty()) {
                    android.util.Log.i("MainScreen", "Voice text ($actualMode): ${result.text.take(100)}")
                    RokidSdkManager.sendAsrContent(result.text)
                    RokidSdkManager.notifyAsrEnd()
                    // Don't send to OpenClaw here — glasses stages the text
                    // and sends user_input when user confirms via Send button
                    val resultMsg = org.json.JSONObject().apply {
                        put("type", "voice_result")
                        put("result_type", "text")
                        put("text", result.text)
                    }
                    glassesManager.sendRawMessage(resultMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 1500)
                } else {
                    android.util.Log.i("MainScreen", "Voice: no speech detected, dismissing")
                    RokidSdkManager.notifyAsrNone()
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 500)
                }
            }
            is VoiceCommandHandler.VoiceResult.Command -> {
                RokidSdkManager.clearCommunicationDevice()
                android.util.Log.i("MainScreen", "Voice command ($actualMode): ${result.command}")
                RokidSdkManager.sendAsrContent(result.command)
                RokidSdkManager.notifyAsrEnd()
                val resultMsg = org.json.JSONObject().apply {
                    put("type", "voice_result")
                    put("result_type", "command")
                    put("text", result.command)
                }
                glassesManager.sendRawMessage(resultMsg.toString())
                mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 1000)
            }
            is VoiceCommandHandler.VoiceResult.Error -> {
                // VoiceRecognitionManager handles fallback internally, but if we still get an error
                // after fallback attempt, we can retry with phone mic as last resort
                if (!isRetry) {
                    android.util.Log.w("MainScreen", "Voice error '${result.message}', retrying with phone mic...")
                    RokidSdkManager.clearCommunicationDevice()
                    mainHandler.postDelayed({
                        startVoiceRecognition(voiceHandler, openClawClient, glassesManager, mainHandler, isRetry = true, languageTag = languageTag, pendingPhotos = pendingPhotos, onPhotosConsumed = onPhotosConsumed)
                    }, 200)
                } else {
                    android.util.Log.e("MainScreen", "Voice error (after retry): ${result.message}")
                    RokidSdkManager.clearCommunicationDevice()
                    RokidSdkManager.notifyAsrError()
                    val resultMsg = org.json.JSONObject().apply {
                        put("type", "voice_result")
                        put("result_type", "error")
                        put("text", result.message)
                    }
                    glassesManager.sendRawMessage(resultMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 2000)
                }
            }
        }
    }
}

/**
 * Start voice recognition with automatic retry on error (fallback handler only).
 */
private fun startVoiceRecognition(
    voiceHandler: VoiceCommandHandler,
    openClawClient: OpenClawClient,
    glassesManager: GlassesConnectionManager,
    mainHandler: android.os.Handler,
    isRetry: Boolean,
    languageTag: String? = null,
    pendingPhotos: () -> List<String> = { emptyList() },
    onPhotosConsumed: () -> Unit = {}
) {
    voiceHandler.startListening(languageTag = languageTag) { result ->
        android.util.Log.i("MainScreen", ">>> Voice result received (retry=$isRetry): $result")
        when (result) {
            is VoiceCommandHandler.VoiceResult.Text -> {
                RokidSdkManager.clearCommunicationDevice()
                if (result.text.isNotEmpty()) {
                    android.util.Log.i("MainScreen", "AI voice text: ${result.text.take(100)}")
                    RokidSdkManager.sendAsrContent(result.text)
                    RokidSdkManager.notifyAsrEnd()
                    // Don't send to OpenClaw here — glasses stages the text
                    // and sends user_input when user confirms via Send button
                    val resultMsg = org.json.JSONObject().apply {
                        put("type", "voice_result")
                        put("result_type", "text")
                        put("text", result.text)
                    }
                    glassesManager.sendRawMessage(resultMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 1500)
                } else {
                    android.util.Log.i("MainScreen", "AI voice: no speech detected, dismissing")
                    RokidSdkManager.notifyAsrNone()
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 500)
                }
            }
            is VoiceCommandHandler.VoiceResult.Command -> {
                RokidSdkManager.clearCommunicationDevice()
                android.util.Log.i("MainScreen", "AI voice command: ${result.command}")
                RokidSdkManager.sendAsrContent(result.command)
                RokidSdkManager.notifyAsrEnd()
                val resultMsg = org.json.JSONObject().apply {
                    put("type", "voice_result")
                    put("result_type", "command")
                    put("text", result.command)
                }
                glassesManager.sendRawMessage(resultMsg.toString())
                mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 1000)
            }
            is VoiceCommandHandler.VoiceResult.Error -> {
                if (!isRetry) {
                    android.util.Log.w("MainScreen", "Voice error '${result.message}', retrying with phone mic...")
                    RokidSdkManager.clearCommunicationDevice()
                    mainHandler.postDelayed({
                        startVoiceRecognition(voiceHandler, openClawClient, glassesManager, mainHandler, isRetry = true, languageTag = languageTag, pendingPhotos = pendingPhotos, onPhotosConsumed = onPhotosConsumed)
                    }, 200)
                } else {
                    android.util.Log.e("MainScreen", "AI voice error (after retry): ${result.message}")
                    RokidSdkManager.clearCommunicationDevice()
                    RokidSdkManager.notifyAsrError()
                    val resultMsg = org.json.JSONObject().apply {
                        put("type", "voice_result")
                        put("result_type", "error")
                        put("text", result.message)
                    }
                    glassesManager.sendRawMessage(resultMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 2000)
                }
            }
        }
    }
}

/**
 * Build a chat_history JSON message for sending to glasses.
 * Truncates long messages and limits total size for CXR/Bluetooth safety.
 */
private fun buildChatHistoryJson(messages: List<ChatMessage>): String {
    // Take only the most recent messages — CXR channel has limited bandwidth
    val maxMessages = 20
    val maxContentLength = 2000
    val recentMessages = if (messages.size > maxMessages) messages.takeLast(maxMessages) else messages

    return org.json.JSONObject().apply {
        put("type", "chat_history")
        val arr = org.json.JSONArray()
        for (msg in recentMessages) {
            arr.put(org.json.JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role)
                put("content", if (msg.content.length > maxContentLength)
                    msg.content.take(maxContentLength) + "..." else msg.content)
                put("timestamp", msg.timestamp)
            })
        }
        put("messages", arr)
    }.toString()
}

private fun createThumbnailBase64(imageBytes: ByteArray, maxWidth: Int, maxHeight: Int): String {
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        ?: return ""
    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, maxWidth, maxHeight, true)
    // Convert to high-contrast grayscale for the monochrome green glasses display.
    // Store luminance in alpha channel so glasses can tint it green.
    val grayscale = android.graphics.Bitmap.createBitmap(scaled.width, scaled.height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(grayscale)
    val paint = android.graphics.Paint()
    // Grayscale color matrix
    val cm = android.graphics.ColorMatrix()
    cm.setSaturation(0f)
    // Boost contrast: scale by 1.8, offset by -100
    val contrast = android.graphics.ColorMatrix(floatArrayOf(
        1.8f, 0f, 0f, 0f, -100f,
        0f, 1.8f, 0f, 0f, -100f,
        0f, 0f, 1.8f, 0f, -100f,
        0f, 0f, 0f, 1f, 0f
    ))
    cm.postConcat(contrast)
    paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
    canvas.drawBitmap(scaled, 0f, 0f, paint)
    if (scaled !== bitmap) bitmap.recycle()
    scaled.recycle()
    val stream = java.io.ByteArrayOutputStream()
    grayscale.compress(android.graphics.Bitmap.CompressFormat.WEBP, 60, stream)
    grayscale.recycle()
    return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
}
