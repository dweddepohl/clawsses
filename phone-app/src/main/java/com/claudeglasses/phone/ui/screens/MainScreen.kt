package com.claudeglasses.phone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.claudeglasses.phone.R
import com.claudeglasses.phone.glasses.ApkInstaller
import com.claudeglasses.phone.glasses.GlassesConnectionManager
import com.claudeglasses.phone.glasses.RokidSdkManager
import com.claudeglasses.phone.terminal.TerminalClient
import com.claudeglasses.phone.voice.VoiceCommandHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Managers
    val glassesManager = remember { GlassesConnectionManager(context) }
    val terminalClient = remember { TerminalClient() }
    val voiceHandler = remember { VoiceCommandHandler(context) }
    val apkInstaller = remember { ApkInstaller(context) }

    // State
    val glassesState by glassesManager.connectionState.collectAsState()
    val terminalState by terminalClient.connectionState.collectAsState()
    val terminalLines by terminalClient.terminalLines.collectAsState()
    val isListening by voiceHandler.isListening.collectAsState()
    val installState by apkInstaller.installState.collectAsState()

    // Persist server URL in SharedPreferences
    val prefs = remember { context.getSharedPreferences("claude_glasses", android.content.Context.MODE_PRIVATE) }
    var serverUrl by remember {
        mutableStateOf(prefs.getString("server_url", "ws://10.0.2.2:8080") ?: "ws://10.0.2.2:8080")
    }
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Initialize voice handler and wire partial result callback
    LaunchedEffect(Unit) {
        voiceHandler.initialize()
        voiceHandler.onPartialResult = { partialText ->
            // Send partial ASR to the glasses built-in AI scene UI
            RokidSdkManager.sendAsrContent(partialText)
            // Also send via our custom message channel for our HUD overlay
            val stateMsg = org.json.JSONObject().apply {
                put("type", "voice_state")
                put("state", "recognizing")
                put("text", partialText)
            }
            glassesManager.sendRawMessage(stateMsg.toString())
        }
    }

    // Debug mode is off by default — user can enable in settings if needed

    // Start/stop foreground service based on glasses connection state
    LaunchedEffect(glassesState) {
        when (glassesState) {
            is GlassesConnectionManager.ConnectionState.Connected -> {
                android.util.Log.i("MainScreen", "Glasses connected — starting foreground service")
                com.claudeglasses.phone.service.GlassesConnectionService.start(context)
            }
            is GlassesConnectionManager.ConnectionState.Disconnected -> {
                android.util.Log.i("MainScreen", "Glasses disconnected — stopping foreground service")
                com.claudeglasses.phone.service.GlassesConnectionService.stop(context)
            }
            else -> {}
        }
    }

    // Auto-scroll to bottom when new lines arrive
    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(terminalLines.size - 1)
        }
    }

    // Handle AI scene events (glasses long-press triggers voice input)
    // Note: SDK callbacks run on background threads — must dispatch to main thread
    // for SpeechRecognizer (which requires main thread).
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    LaunchedEffect(Unit) {
        glassesManager.onAiKeyDown = {
            android.util.Log.i("MainScreen", ">>> AI key down from glasses - will start voice recognition")
            // Delay to let Bluetooth SCO audio channel establish before starting recognizer
            mainHandler.postDelayed({
                android.util.Log.i("MainScreen", ">>> Starting voice recognition on main thread")
                // Route glasses mic to phone via Bluetooth audio
                RokidSdkManager.setCommunicationDevice()
                startVoiceRecognition(voiceHandler, terminalClient, glassesManager, mainHandler, isRetry = false)
            }, 300)
        }
        glassesManager.onAiExit = {
            // AI scene closed on glasses (user released button or it auto-dismissed).
            // Do NOT stop the recognizer or clear the audio route here — the recognizer
            // may still be processing speech. Cleanup happens in the result callback.
            android.util.Log.d("MainScreen", "AI scene exited on glasses (recognizer continues)")
        }
    }

    // Handle commands from glasses and forward to server
    LaunchedEffect(Unit) {
        glassesManager.onMessageFromGlasses = { message ->
            try {
                val json = org.json.JSONObject(message)
                val type = json.optString("type", "")
                when (type) {
                    "command" -> {
                        val command = json.optString("command", "")
                        android.util.Log.d("MainScreen", "Received command from glasses: $command")
                        if (command.isNotEmpty()) {
                            terminalClient.sendKey(command)
                        }
                    }
                    "voice_input" -> {
                        val text = json.optString("text", "")
                        android.util.Log.d("MainScreen", "Received voice input from glasses (${text.length} chars): ${text.take(100)}")
                        if (text.isNotEmpty()) {
                            android.util.Log.d("MainScreen", "Forwarding voice input to server...")
                            terminalClient.sendInput(text)
                        } else {
                            android.util.Log.w("MainScreen", "Voice input was empty, not sending")
                        }
                    }
                    "start_voice" -> {
                        android.util.Log.d("MainScreen", "Glasses requested voice recognition start")
                        // Route glasses mic to phone via Bluetooth audio
                        com.claudeglasses.phone.glasses.RokidSdkManager.setCommunicationDevice()
                        // Start speech recognition on phone
                        voiceHandler.startListening { result ->
                            // Clear communication device routing
                            com.claudeglasses.phone.glasses.RokidSdkManager.clearCommunicationDevice()
                            // Send final result back to glasses
                            when (result) {
                                is VoiceCommandHandler.VoiceResult.Text -> {
                                    android.util.Log.d("MainScreen", "Voice result text: ${result.text.take(100)}")
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "voice_result")
                                        put("result_type", "text")
                                        put("text", result.text)
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                    // Also forward directly to server
                                    terminalClient.sendInput(result.text)
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
                        // Send listening state to glasses
                        val stateMsg = org.json.JSONObject().apply {
                            put("type", "voice_state")
                            put("state", "listening")
                        }
                        glassesManager.sendRawMessage(stateMsg.toString())
                    }
                    "cancel_voice" -> {
                        android.util.Log.d("MainScreen", "Glasses requested voice recognition cancel")
                        voiceHandler.stopListening()
                        com.claudeglasses.phone.glasses.RokidSdkManager.clearCommunicationDevice()
                        val stateMsg = org.json.JSONObject().apply {
                            put("type", "voice_state")
                            put("state", "idle")
                        }
                        glassesManager.sendRawMessage(stateMsg.toString())
                    }
                    "list_sessions" -> {
                        android.util.Log.d("MainScreen", "Requesting session list for glasses")
                        terminalClient.requestSessions()
                    }
                    "switch_session" -> {
                        val session = json.optString("session", "")
                        android.util.Log.d("MainScreen", "Switching to session: $session")
                        if (session.isNotEmpty()) {
                            terminalClient.switchSession(session)
                        }
                    }
                    "kill_session" -> {
                        val session = json.optString("session", "")
                        android.util.Log.d("MainScreen", "Killing session: $session")
                        if (session.isNotEmpty()) {
                            terminalClient.killSession(session)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainScreen", "Error parsing glasses message", e)
            }
        }

        // Forward session messages from server to glasses
        terminalClient.onSessionMessage = { message ->
            android.util.Log.d("MainScreen", "Forwarding session message to glasses")
            glassesManager.sendRawMessage(message)
        }

        // Forward terminal output (with lineColors) from server to glasses
        terminalClient.onTerminalOutput = { message ->
            glassesManager.sendRawMessage(message)
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            glassesManager.disconnect()
            terminalClient.cleanup()
            voiceHandler.cleanup()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Claude Glasses Terminal") },
                actions = {
                    // Settings button
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                // Input field
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("Type command...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) {
                                terminalClient.sendInput(inputText)
                                inputText = ""
                            }
                        }
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )

                // Voice button
                IconButton(
                    onClick = {
                        if (isListening) {
                            voiceHandler.stopListening()
                        } else {
                            voiceHandler.startListening { result ->
                                when (result) {
                                    is VoiceCommandHandler.VoiceResult.Text -> {
                                        terminalClient.sendInput(result.text)
                                    }
                                    is VoiceCommandHandler.VoiceResult.Command -> {
                                        handleVoiceCommand(result.command, terminalClient)
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
                            terminalClient.sendInput(inputText)
                            inputText = ""
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
                terminalState = terminalState,
                onConnectGlasses = { glassesManager.startScanning() },
                onConnectTerminal = { terminalClient.connect(serverUrl) }
            )

            // Terminal output - auto-scale font to fit TERMINAL_COLS characters
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 4.dp)  // Minimal horizontal padding
            ) {
                val terminalCols = 65  // Match server's DEFAULT_COLS
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()

                // Use JetBrains Mono - a proper terminal font with correct box-drawing characters
                val monoFontFamily = remember {
                    FontFamily(Font(R.font.jetbrains_mono))
                }

                // Measure actual text width using TextMeasurer
                // Use a reference string of exactly terminalCols characters
                val referenceText = "M".repeat(terminalCols)
                val referenceFontSize = 16.sp  // Reference size to measure at

                val terminalFontSize = remember(maxWidth) {
                    val referenceStyle = TextStyle(
                        fontFamily = monoFontFamily,
                        fontSize = referenceFontSize,
                        letterSpacing = 0.sp
                    )
                    val measuredWidth = textMeasurer.measure(referenceText, referenceStyle).size.width
                    val availableWidthPx = with(density) { maxWidth.toPx() }

                    // Scale with 1% safety margin for rounding errors
                    val scaledSize = referenceFontSize.value * (availableWidthPx / measuredWidth) * 0.99f
                    scaledSize.coerceIn(6f, 20f).sp
                }

                // Create text style with tight line height (no extra spacing)
                val terminalTextStyle = TextStyle(
                    fontFamily = monoFontFamily,
                    fontSize = terminalFontSize,
                    letterSpacing = 0.sp,
                    lineHeight = 1.0.em,  // Exactly 1x font size, no extra spacing
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(terminalLines) { line ->
                        Text(
                            text = line.content,
                            style = terminalTextStyle,
                            color = when (line.type) {
                                TerminalClient.TerminalLine.Type.INPUT -> Color(0xFF4EC9B0)
                                TerminalClient.TerminalLine.Type.OUTPUT -> Color(0xFFD4D4D4)
                                TerminalClient.TerminalLine.Type.ERROR -> Color(0xFFF14C4C)
                                TerminalClient.TerminalLine.Type.SYSTEM -> Color(0xFF569CD6)
                            },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Quick action buttons
            QuickActionBar(
                onEnter = { terminalClient.sendKey(TerminalClient.SpecialKey.ENTER) },
                onEscape = { terminalClient.sendKey(TerminalClient.SpecialKey.ESCAPE) },
                onTab = { terminalClient.sendKey(TerminalClient.SpecialKey.TAB) },
                onShiftTab = { terminalClient.sendKey(TerminalClient.SpecialKey.SHIFT_TAB) },
                onCtrlC = { terminalClient.sendKey(TerminalClient.SpecialKey.CTRL_C) }
            )
        }
    }

    // Debug mode state
    val debugModeEnabled by glassesManager.debugModeEnabled.collectAsState()

    // Settings dialog
    if (showSettings) {
        SettingsDialog(
            serverUrl = serverUrl,
            onServerUrlChange = {
                serverUrl = it
                prefs.edit().putString("server_url", it).apply()
            },
            debugModeEnabled = debugModeEnabled,
            onDebugModeChange = { enabled ->
                if (enabled) {
                    glassesManager.enableDebugMode()
                } else {
                    glassesManager.disableDebugMode()
                }
            },
            onDismiss = {
                showSettings = false
                // Reset install state when closing dialog
                if (installState is ApkInstaller.InstallState.Success ||
                    installState is ApkInstaller.InstallState.Error) {
                    apkInstaller.resetState()
                }
            },
            installState = installState,
            apkInstaller = apkInstaller,
            onCancelInstall = {
                apkInstaller.cancelInstallation()
            },
            onOpenGlassesApp = {
                apkInstaller.openGlassesApp()
            },
            // Pass glasses manager for device scanning/connection
            glassesManager = glassesManager,
            glassesState = glassesState,
        )
    }
}

@Composable
fun ConnectionStatusBar(
    glassesState: GlassesConnectionManager.ConnectionState,
    terminalState: TerminalClient.ConnectionState,
    onConnectGlasses: () -> Unit,
    onConnectTerminal: () -> Unit
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
            // Status icon (colored circle)
            Icon(
                when (glassesState) {
                    is GlassesConnectionManager.ConnectionState.Connected -> Icons.Default.CheckCircle
                    is GlassesConnectionManager.ConnectionState.Connecting,
                    is GlassesConnectionManager.ConnectionState.Scanning -> Icons.Default.Sync
                    is GlassesConnectionManager.ConnectionState.Error -> Icons.Default.Error
                    else -> Icons.Default.RadioButtonUnchecked  // Empty circle for disconnected
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

        // Terminal/Server status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f)
        ) {
            if (terminalState is TerminalClient.ConnectionState.Disconnected) {
                TextButton(
                    onClick = onConnectTerminal,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Connect", fontSize = 12.sp)
                }
            } else {
                Text(
                    text = when (terminalState) {
                        is TerminalClient.ConnectionState.Connected -> "Connected"
                        is TerminalClient.ConnectionState.Connecting -> "Connecting..."
                        is TerminalClient.ConnectionState.Error -> (terminalState as TerminalClient.ConnectionState.Error).message.take(40)
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = if (terminalState is TerminalClient.ConnectionState.Error) Color.Red else Color.Gray
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Terminal,
                contentDescription = "Server",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            // Status icon (colored circle)
            Icon(
                when (terminalState) {
                    is TerminalClient.ConnectionState.Connected -> Icons.Default.CheckCircle
                    is TerminalClient.ConnectionState.Connecting -> Icons.Default.Sync
                    is TerminalClient.ConnectionState.Error -> Icons.Default.Error
                    else -> Icons.Default.RadioButtonUnchecked  // Empty circle for disconnected
                },
                contentDescription = null,
                tint = when (terminalState) {
                    is TerminalClient.ConnectionState.Connected -> Color.Green
                    is TerminalClient.ConnectionState.Connecting -> Color.Yellow
                    is TerminalClient.ConnectionState.Error -> Color.Red
                    else -> Color.Gray
                },
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun QuickActionBar(
    onEnter: () -> Unit,
    onEscape: () -> Unit,
    onTab: () -> Unit,
    onShiftTab: () -> Unit,
    onCtrlC: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        OutlinedButton(onClick = onEnter) { Text("⏎") }
        OutlinedButton(onClick = onEscape) { Text("ESC") }
        OutlinedButton(onClick = onTab) { Text("TAB") }
        OutlinedButton(onClick = onShiftTab) { Text("⇧TAB") }
        OutlinedButton(onClick = onCtrlC) { Text("^C") }
    }
}

@Composable
fun SettingsDialog(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    debugModeEnabled: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    installState: ApkInstaller.InstallState,
    apkInstaller: ApkInstaller,
    onCancelInstall: () -> Unit,
    onOpenGlassesApp: () -> Unit,
    // Device connection
    glassesManager: GlassesConnectionManager? = null,
    glassesState: GlassesConnectionManager.ConnectionState? = null,
) {
    var glassesIp by remember { mutableStateOf("") }
    var connectionTestResult by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var showDeviceList by remember { mutableStateOf(false) }

    // Collect discovered devices if manager is provided
    val discoveredDevices = glassesManager?.discoveredDevices?.collectAsState()?.value ?: emptyList()
    val wifiP2PConnected = glassesManager?.wifiP2PConnected?.collectAsState()?.value ?: false

    // Determine if SDK installation is available
    val sdkConnected = glassesState is GlassesConnectionManager.ConnectionState.Connected && !debugModeEnabled

    AlertDialog(
        onDismissRequest = {
            // Don't allow dismissing while installing
            if (installState is ApkInstaller.InstallState.Idle ||
                installState is ApkInstaller.InstallState.Success ||
                installState is ApkInstaller.InstallState.Error) {
                glassesManager?.stopScanning()
                onDismiss()
            }
        },
        title = { Text("Settings") },
        text = {
            LazyColumn {
                item {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("Server URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))

                    // Debug mode toggle for emulator testing
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

                    Spacer(Modifier.height(24.dp))

                    // ============== Glasses Connection Section ==============
                    if (!debugModeEnabled) {
                        Text(
                            "Glasses Connection",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))

                        // Connection status
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

                        // WiFi P2P status (if Bluetooth connected)
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

                        // Scan / Connect buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isScanning = glassesState is GlassesConnectionManager.ConnectionState.Scanning

                            OutlinedButton(
                                onClick = {
                                    if (isScanning) {
                                        glassesManager?.stopScanning()
                                    } else {
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
                                // Init WiFi P2P button
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

                        // Device list (when scanning or has results)
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

                        Spacer(Modifier.height(24.dp))
                    }

                    // ============== Installation Section ==============
                    if (sdkConnected) {
                        Text(
                            "Glasses App Installation",
                            style = MaterialTheme.typography.titleSmall
                        )
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
                            Text("Install via SDK")
                        }

                        Spacer(Modifier.height(16.dp))

                        // Installation status
                        InstallationSection(
                            installState = installState,
                            onCancel = onCancelInstall,
                            onOpenApp = onOpenGlassesApp,
                            onRetry = { apkInstaller.installViaSdk() }
                        )
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
    onOpenApp: () -> Unit,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (installState) {
            is ApkInstaller.InstallState.Idle -> {
                // Nothing to show - install button is above
            }

            is ApkInstaller.InstallState.CheckingConnection -> {
                InstallProgressRow("Connecting to glasses...")
            }

            is ApkInstaller.InstallState.InitializingWifiP2P -> {
                InstallProgressRow("Establishing WiFi P2P connection...")
            }

            is ApkInstaller.InstallState.PreparingApk -> {
                InstallProgressRow("Preparing APK...")
            }

            is ApkInstaller.InstallState.Uploading -> {
                InstallProgressRow(installState.message)
                if (installState.progress >= 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${installState.progress}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }

            is ApkInstaller.InstallState.Installing -> {
                InstallProgressRow(installState.message)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Do not disconnect the glasses",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            is ApkInstaller.InstallState.Success -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        installState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF4CAF50)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenApp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open App on Glasses")
                }
            }

            is ApkInstaller.InstallState.Error -> {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        installState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF44336)
                    )
                }
                if (installState.canRetry) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth()
                    ) {
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Start voice recognition with automatic retry on error.
 * First attempt uses glasses mic (via setCommunicationDevice). If that fails,
 * retries once with phone mic (clearCommunicationDevice) as fallback.
 */
private fun startVoiceRecognition(
    voiceHandler: VoiceCommandHandler,
    terminalClient: TerminalClient,
    glassesManager: GlassesConnectionManager,
    mainHandler: android.os.Handler,
    isRetry: Boolean
) {
    voiceHandler.startListening { result ->
        android.util.Log.i("MainScreen", ">>> Voice result received (retry=$isRetry): $result")
        when (result) {
            is VoiceCommandHandler.VoiceResult.Text -> {
                RokidSdkManager.clearCommunicationDevice()
                if (result.text.isNotEmpty()) {
                    android.util.Log.i("MainScreen", "AI voice text: ${result.text.take(100)}")
                    RokidSdkManager.sendAsrContent(result.text)
                    RokidSdkManager.notifyAsrEnd()
                    terminalClient.sendInput(result.text)
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
                terminalClient.sendKey(result.command)
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
                    // First attempt failed — retry with phone mic as fallback
                    android.util.Log.w("MainScreen", "Voice error '${result.message}', retrying with phone mic...")
                    RokidSdkManager.clearCommunicationDevice()
                    mainHandler.postDelayed({
                        startVoiceRecognition(voiceHandler, terminalClient, glassesManager, mainHandler, isRetry = true)
                    }, 200)
                } else {
                    // Retry also failed — give up
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

private fun handleVoiceCommand(command: String, terminalClient: TerminalClient) {
    when (command) {
        "escape" -> terminalClient.sendKey(TerminalClient.SpecialKey.ESCAPE)
        "scroll up" -> terminalClient.scrollUp()
        "scroll down" -> terminalClient.scrollDown()
        "switch mode", "navigate mode" -> {
            // TODO: Switch interaction mode
        }
        // Screenshot handling would be done via glasses
    }
}
