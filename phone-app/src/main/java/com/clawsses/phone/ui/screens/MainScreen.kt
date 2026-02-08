package com.clawsses.phone.ui.screens

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import com.clawsses.phone.glasses.ApkInstaller
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.glasses.RokidSdkManager
import com.clawsses.phone.openclaw.DeviceIdentity
import com.clawsses.phone.openclaw.OpenClawClient
import com.clawsses.phone.voice.VoiceCommandHandler
import com.clawsses.phone.voice.VoiceLanguageManager
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
    val apkInstaller = remember { ApkInstaller(context) }

    // State
    val glassesState by glassesManager.connectionState.collectAsState()
    val openClawState by openClawClient.connectionState.collectAsState()
    val chatMessages by openClawClient.chatMessages.collectAsState()
    val isListening by voiceHandler.isListening.collectAsState()
    val installState by apkInstaller.installState.collectAsState()
    val selectedVoiceLanguage by voiceLanguageManager.selectedLanguage.collectAsState()
    val sessionList by openClawClient.sessionList.collectAsState()
    val currentSessionKey by openClawClient.currentSessionKey.collectAsState()

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
        voiceHandler.onPartialResult = { partialText ->
            RokidSdkManager.sendAsrContent(partialText)
            val stateMsg = org.json.JSONObject().apply {
                put("type", "voice_state")
                put("state", "recognizing")
                put("text", partialText)
            }
            glassesManager.sendRawMessage(stateMsg.toString())
        }
    }

    // Fetch session list when OpenClaw connects
    LaunchedEffect(openClawState) {
        if (openClawState is OpenClawClient.ConnectionState.Connected) {
            openClawClient.requestSessions()
        }
    }

    // Start/stop foreground service based on glasses connection state,
    // and send current chat history when glasses connect
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
                android.util.Log.i("MainScreen", "Glasses disconnected — stopping foreground service")
                com.clawsses.phone.service.GlassesConnectionService.stop(context)
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
                startVoiceRecognition(voiceHandler, openClawClient, glassesManager, mainHandler, isRetry = false, languageTag = voiceLanguageManager.getActiveLanguageTag(), pendingPhotos = { pendingPhotos }, onPhotosConsumed = { pendingPhotos = emptyList() })
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
                        voiceHandler.startListening(languageTag = voiceLanguageManager.getActiveLanguageTag()) { result ->
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
                                    openClawClient.sendMessage(result.text, pendingPhotos.ifEmpty { null })
                                    pendingPhotos = emptyList()
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
                        }
                        glassesManager.sendRawMessage(stateMsg.toString())
                    }
                    "cancel_voice" -> {
                        android.util.Log.d("MainScreen", "Glasses requested voice recognition cancel")
                        voiceHandler.stopListening()
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
                        val connUpdate = ConnectionUpdate(
                            connected = isConnected,
                            sessionId = openClawClient.currentSessionKey.value
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
        }
    }

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

                // Voice button
                IconButton(
                    onClick = {
                        if (isListening) {
                            voiceHandler.stopListening()
                        } else {
                            voiceHandler.startListening(languageTag = voiceLanguageManager.getActiveLanguageTag()) { result ->
                                when (result) {
                                    is VoiceCommandHandler.VoiceResult.Text -> {
                                        openClawClient.sendMessage(result.text)
                                    }
                                    is VoiceCommandHandler.VoiceResult.Command -> {
                                        // Voice commands handled by glasses
                                    }
                                    is VoiceCommandHandler.VoiceResult.Error -> {
                                        // Handle error
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Voice input",
                        tint = if (isListening) Color.Red else MaterialTheme.colorScheme.onSurface
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

    // Debug mode state
    val debugModeEnabled by glassesManager.debugModeEnabled.collectAsState()

    // Settings dialog
    if (showSettings) {
        SettingsDialog(
            openClawHost = openClawHost,
            onHostChange = {
                openClawHost = it
                prefs.edit().putString("openclaw_host", it).apply()
                // Cancel any in-progress connection so user can reconnect with new settings
                if (openClawState !is OpenClawClient.ConnectionState.Disconnected &&
                    openClawState !is OpenClawClient.ConnectionState.Connected) {
                    openClawClient.disconnect()
                }
            },
            openClawPort = openClawPort,
            onPortChange = {
                openClawPort = it
                prefs.edit().putString("openclaw_port", it).apply()
                if (openClawState !is OpenClawClient.ConnectionState.Disconnected &&
                    openClawState !is OpenClawClient.ConnectionState.Connected) {
                    openClawClient.disconnect()
                }
            },
            openClawToken = openClawToken,
            onTokenChange = {
                openClawToken = it
                prefs.edit().putString("openclaw_token", it).apply()
                if (openClawState !is OpenClawClient.ConnectionState.Disconnected &&
                    openClawState !is OpenClawClient.ConnectionState.Connected) {
                    openClawClient.disconnect()
                }
            },
            debugModeEnabled = debugModeEnabled,
            onDebugModeChange = { enabled ->
                if (enabled) glassesManager.enableDebugMode()
                else glassesManager.disableDebugMode()
            },
            onDismiss = {
                showSettings = false
                if (installState is ApkInstaller.InstallState.Success ||
                    installState is ApkInstaller.InstallState.Error) {
                    apkInstaller.resetState()
                }
            },
            installState = installState,
            apkInstaller = apkInstaller,
            onCancelInstall = { apkInstaller.cancelInstallation() },
            glassesManager = glassesManager,
            glassesState = glassesState,
            voiceLanguageManager = voiceLanguageManager,
        )
    }
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
                    is GlassesConnectionManager.ConnectionState.Scanning -> Icons.Default.Sync
                    is GlassesConnectionManager.ConnectionState.Error -> Icons.Default.Error
                    else -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when (glassesState) {
                    is GlassesConnectionManager.ConnectionState.Connected -> Color.Green
                    is GlassesConnectionManager.ConnectionState.Connecting,
                    is GlassesConnectionManager.ConnectionState.Scanning -> Color.Yellow
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
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (SessionInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val currentSession = sessions.firstOrNull { it.key == currentSessionKey }
    val displayName = currentSession?.name ?: currentSessionKey ?: "No session"

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
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
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
                                }
                                Text(
                                    text = session.name,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
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

@Composable
fun SettingsDialog(
    openClawHost: String,
    onHostChange: (String) -> Unit,
    openClawPort: String,
    onPortChange: (String) -> Unit,
    openClawToken: String,
    onTokenChange: (String) -> Unit,
    debugModeEnabled: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    installState: ApkInstaller.InstallState,
    apkInstaller: ApkInstaller,
    onCancelInstall: () -> Unit,
    glassesManager: GlassesConnectionManager? = null,
    glassesState: GlassesConnectionManager.ConnectionState? = null,
    voiceLanguageManager: VoiceLanguageManager? = null,
) {
    var showDeviceList by remember { mutableStateOf(false) }
    var tokenVisible by remember { mutableStateOf(false) }
    val discoveredDevices = glassesManager?.discoveredDevices?.collectAsState()?.value ?: emptyList()
    val wifiP2PConnected = glassesManager?.wifiP2PConnected?.collectAsState()?.value ?: false
    val sdkConnected = glassesState is GlassesConnectionManager.ConnectionState.Connected && !debugModeEnabled
    val availableLanguages = voiceLanguageManager?.availableLanguages?.collectAsState()?.value ?: emptyList()
    val selectedLanguage = voiceLanguageManager?.selectedLanguage?.collectAsState()?.value ?: ""
    var showLanguagePicker by remember { mutableStateOf(false) }

    val tabTitles = listOf("Glasses", "OpenClaw", "Customize")
    var selectedTab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = {
            if (installState is ApkInstaller.InstallState.Idle ||
                installState is ApkInstaller.InstallState.Success ||
                installState is ApkInstaller.InstallState.Error) {
                glassesManager?.stopScanning()
                onDismiss()
            }
        },
        title = {
            Column {
                Text("Settings")
                Spacer(Modifier.height(8.dp))
                TabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontSize = 12.sp) }
                        )
                    }
                }
            }
        },
        text = {
            when (selectedTab) {
                // ============== Tab 0: Glasses Connection ==============
                0 -> LazyColumn {
                    item {
                        if (isEmulator()) {
                            // Debug mode toggle (only shown in emulator)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Debug Mode", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Use WebSocket instead of Bluetooth for glasses",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                                Switch(
                                    checked = debugModeEnabled,
                                    onCheckedChange = onDebugModeChange
                                )
                            }

                            if (debugModeEnabled) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Glasses app connects to port 8081.\n" +
                                    "Run: adb forward tcp:8081 tcp:8081",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (!debugModeEnabled) {
                            Spacer(Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    when (glassesState) {
                                        is GlassesConnectionManager.ConnectionState.Connected -> Icons.Default.CheckCircle
                                        is GlassesConnectionManager.ConnectionState.Connecting,
                                        is GlassesConnectionManager.ConnectionState.Scanning,
                                        is GlassesConnectionManager.ConnectionState.InitializingWifiP2P -> Icons.Default.Sync
                                        is GlassesConnectionManager.ConnectionState.Error -> Icons.Default.Error
                                        else -> Icons.Default.BluetoothDisabled
                                    },
                                    contentDescription = null,
                                    tint = when (glassesState) {
                                        is GlassesConnectionManager.ConnectionState.Connected -> Color(0xFF4CAF50)
                                        is GlassesConnectionManager.ConnectionState.Connecting,
                                        is GlassesConnectionManager.ConnectionState.Scanning,
                                        is GlassesConnectionManager.ConnectionState.InitializingWifiP2P -> Color(0xFFFFC107)
                                        is GlassesConnectionManager.ConnectionState.Error -> Color(0xFFF44336)
                                        else -> Color.Gray
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when (glassesState) {
                                        is GlassesConnectionManager.ConnectionState.Connected ->
                                            "Connected: ${glassesState.deviceName}"
                                        is GlassesConnectionManager.ConnectionState.Connecting -> "Connecting..."
                                        is GlassesConnectionManager.ConnectionState.Scanning -> "Scanning for glasses..."
                                        is GlassesConnectionManager.ConnectionState.InitializingWifiP2P -> "Setting up WiFi P2P..."
                                        is GlassesConnectionManager.ConnectionState.Error -> "Error: ${glassesState.message}"
                                        else -> "Not connected"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            if (glassesState is GlassesConnectionManager.ConnectionState.Connected) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (wifiP2PConnected) Icons.Default.WifiTethering else Icons.Default.WifiTetheringOff,
                                        contentDescription = null,
                                        tint = if (wifiP2PConnected) Color(0xFF4CAF50) else Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (wifiP2PConnected) "WiFi P2P: Connected" else "WiFi P2P: Not connected",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (wifiP2PConnected) Color(0xFF4CAF50) else Color.Gray
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val isScanning = glassesState is GlassesConnectionManager.ConnectionState.Scanning

                                OutlinedButton(
                                    onClick = {
                                        if (isScanning) glassesManager?.stopScanning()
                                        else {
                                            glassesManager?.startScanning()
                                            showDeviceList = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        if (isScanning) Icons.Default.Stop else Icons.Default.BluetoothSearching,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isScanning) "Stop" else "Scan")
                                }

                                if (glassesState is GlassesConnectionManager.ConnectionState.Connected) {
                                    if (!wifiP2PConnected) {
                                        Button(
                                            onClick = { glassesManager?.initWifiP2P() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("WiFi P2P")
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = { glassesManager?.disconnect() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Disconnect")
                                    }
                                }
                            }

                            // Clear cached glasses SN
                            if (RokidSdkManager.hasCachedSn()) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { RokidSdkManager.clearCachedSn() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Clear saved glasses SN")
                                }
                                Text(
                                    "Use if switching to different glasses",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }

                            if (showDeviceList && discoveredDevices.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Found ${discoveredDevices.size} device(s):",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                                Spacer(Modifier.height(4.dp))

                                discoveredDevices.forEach { device ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(device.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${device.address} (RSSI: ${device.rssi})",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Gray
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                glassesManager?.connectToDevice(device)
                                                showDeviceList = false
                                            }
                                        ) {
                                            Text("Connect")
                                        }
                                    }
                                }
                            }
                        }

                        // Installation Section
                        if (sdkConnected) {
                            Spacer(Modifier.height(16.dp))

                            Text("Glasses App Installation", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))

                            Text(
                                "Install via Bluetooth + WiFi P2P",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Spacer(Modifier.height(8.dp))

                            Button(
                                onClick = { apkInstaller.installViaSdk() },
                                enabled = installState is ApkInstaller.InstallState.Idle ||
                                         installState is ApkInstaller.InstallState.Error ||
                                         installState is ApkInstaller.InstallState.Success,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Install app to glasses")
                            }

                            Spacer(Modifier.height(16.dp))

                            InstallationSection(
                                installState = installState,
                                onCancel = onCancelInstall,
                                onRetry = { apkInstaller.installViaSdk() }
                            )
                        }
                    }
                }

                // ============== Tab 1: OpenClaw Server ==============
                1 -> LazyColumn {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = openClawHost,
                                onValueChange = onHostChange,
                                label = { Text("Host / IP") },
                                modifier = Modifier.weight(2f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = openClawPort,
                                onValueChange = onPortChange,
                                label = { Text("Port") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = openClawToken,
                            onValueChange = onTokenChange,
                            label = { Text("Gateway Token") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Icon(
                                        imageVector = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (tokenVisible) "Hide token" else "Show token"
                                    )
                                }
                            }
                        )
                    }
                }

                // ============== Tab 2: Customize ==============
                2 -> LazyColumn {
                    item {
                        Text("Voice Language", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))

                        val currentLangDisplay = availableLanguages
                            .firstOrNull { it.tag == selectedLanguage }
                            ?.displayName ?: selectedLanguage.ifEmpty { "Default" }

                        OutlinedButton(
                            onClick = { showLanguagePicker = !showLanguagePicker },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(currentLangDisplay, modifier = Modifier.weight(1f))
                            Icon(
                                if (showLanguagePicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (showLanguagePicker) {
                            Spacer(Modifier.height(4.dp))
                            if (availableLanguages.isEmpty()) {
                                Text(
                                    "No languages available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            } else {
                                Column {
                                    availableLanguages.forEach { lang ->
                                        val isSelected = lang.tag == selectedLanguage
                                        TextButton(
                                            onClick = {
                                                voiceLanguageManager?.selectLanguage(lang.tag)
                                                showLanguagePicker = false
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    if (isSelected) Icons.Default.RadioButtonChecked
                                                    else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        lang.displayName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                                                else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        lang.tag,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color.Gray
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    glassesManager?.stopScanning()
                    onDismiss()
                },
                enabled = installState is ApkInstaller.InstallState.Idle ||
                         installState is ApkInstaller.InstallState.Success ||
                         installState is ApkInstaller.InstallState.Error
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun InstallationSection(
    installState: ApkInstaller.InstallState,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (installState) {
            is ApkInstaller.InstallState.Idle -> {}
            is ApkInstaller.InstallState.CheckingConnection -> InstallProgressRow("Connecting to glasses...")
            is ApkInstaller.InstallState.InitializingWifiP2P -> InstallProgressRow("Establishing WiFi P2P connection...")
            is ApkInstaller.InstallState.PreparingApk -> InstallProgressRow("Preparing APK...")
            is ApkInstaller.InstallState.Uploading -> {
                InstallProgressRow(installState.message)
                if (installState.progress >= 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("${installState.progress}%", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            is ApkInstaller.InstallState.Installing -> {
                InstallProgressRow(installState.message)
                Spacer(Modifier.height(4.dp))
                Text("Do not disconnect the glasses", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            is ApkInstaller.InstallState.Success -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(installState.message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4CAF50))
                }
            }
            is ApkInstaller.InstallState.Error -> {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFF44336), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(installState.message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFF44336))
                }
                if (installState.canRetry) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Try Again")
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallProgressRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Start voice recognition with automatic retry on error.
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
                    openClawClient.sendMessage(result.text, pendingPhotos().ifEmpty { null })
                    onPhotosConsumed()
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
private fun isEmulator(): Boolean {
    return (Build.FINGERPRINT.contains("generic")
            || Build.FINGERPRINT.contains("emulator")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for")
            || Build.MODEL.contains("sdk_gphone")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk")
            || Build.PRODUCT.contains("emulator"))
}

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
