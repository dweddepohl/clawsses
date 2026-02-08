package com.clawsses.glasses

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.clawsses.glasses.input.GestureHandler
import com.clawsses.glasses.input.GestureHandler.Gesture
import com.clawsses.glasses.service.PhoneConnectionService
import com.clawsses.glasses.ui.AgentState
import com.clawsses.glasses.ui.ChatFocusArea
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.DisplayMessage
import com.clawsses.glasses.ui.HudDisplaySize
import com.clawsses.glasses.ui.HudPosition
import com.clawsses.glasses.ui.HudScreen
import com.clawsses.glasses.ui.MenuBarItem
import com.clawsses.glasses.ui.MoreMenuItem
import com.clawsses.glasses.ui.SessionPickerInfo
import com.clawsses.glasses.ui.SLASH_COMMANDS
import com.clawsses.glasses.ui.VoiceInputState
import com.clawsses.glasses.ui.theme.GlassesHudTheme
import com.clawsses.glasses.voice.GlassesVoiceHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

import android.os.Build
import com.clawsses.glasses.BuildConfig

class HudActivity : ComponentActivity() {

    companion object {
        val DEBUG_MODE = BuildConfig.DEBUG && isEmulator()

        const val DEBUG_HOST = "10.0.2.2"
        const val DEBUG_PORT = 8081

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
    }

    private val hudState = MutableStateFlow(ChatHudState())
    private lateinit var gestureHandler: GestureHandler
    private lateinit var phoneConnection: PhoneConnectionService
    private lateinit var voiceHandler: GlassesVoiceHandler

    // Debug keyboard input mode
    private var isCapturingKeyboardInput = false
    private var keyboardInputBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        gestureHandler = GestureHandler { gesture ->
            handleGesture(gesture)
        }

        phoneConnection = PhoneConnectionService(
            context = this,
            onMessageReceived = { message -> handlePhoneMessage(message) },
            debugMode = DEBUG_MODE,
            debugHost = DEBUG_HOST,
            debugPort = DEBUG_PORT
        )

        Log.i(GlassesApp.TAG, "HudActivity created, debugMode=$DEBUG_MODE")

        voiceHandler = GlassesVoiceHandler()
        voiceHandler.sendToPhone = { message -> phoneConnection.sendToPhone(message) }
        voiceHandler.initialize()

        // Observe voice state
        lifecycleScope.launch {
            voiceHandler.voiceState.collect { voiceState ->
                val current = hudState.value
                val newVoiceState = when (voiceState) {
                    is GlassesVoiceHandler.VoiceState.Idle -> VoiceInputState.Idle
                    is GlassesVoiceHandler.VoiceState.Listening -> VoiceInputState.Listening
                    is GlassesVoiceHandler.VoiceState.Recognizing -> VoiceInputState.Recognizing
                    is GlassesVoiceHandler.VoiceState.Error -> VoiceInputState.Error(voiceState.message)
                }
                val newVoiceText = when (voiceState) {
                    is GlassesVoiceHandler.VoiceState.Recognizing -> voiceState.partialText
                    else -> ""
                }
                hudState.value = current.copy(
                    voiceState = newVoiceState,
                    voiceText = newVoiceText
                )
            }
        }

        setContent {
            GlassesHudTheme {
                val state by hudState.collectAsState()
                HudScreen(
                    state = state,
                    onTap = { handleGesture(Gesture.TAP) },
                    onDoubleTap = { handleGesture(Gesture.DOUBLE_TAP) },
                    onLongPress = { handleGesture(Gesture.LONG_PRESS) },
                    onScrolledToEndChanged = { atEnd ->
                        val current = hudState.value
                        if (current.isScrolledToEnd != atEnd) {
                            hudState.value = current.copy(isScrolledToEnd = atEnd)
                        }
                    }
                )
            }
        }

        lifecycleScope.launch {
            phoneConnection.startListening()
        }

        // Observe connection state
        lifecycleScope.launch {
            phoneConnection.connectionState.collect { state ->
                val isConnected = state is PhoneConnectionService.ConnectionState.Connected
                val current = hudState.value
                if (current.isConnected != isConnected) {
                    hudState.value = current.copy(isConnected = isConnected)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { gestureHandler.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event?.let {
            if (gestureHandler.onTouchEvent(it)) {
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If capturing keyboard input for simulated voice, handle specially
        if (isCapturingKeyboardInput) {
            return handleKeyboardCapture(keyCode, event)
        }

        if (event?.repeatCount ?: 0 > 0) return true

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> {
                handleGesture(Gesture.SWIPE_FORWARD)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleGesture(Gesture.SWIPE_BACKWARD)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                handleGesture(Gesture.TAP)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                handleGesture(Gesture.DOUBLE_TAP)
                return true
            }
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_DEL -> {
                handleGesture(Gesture.DOUBLE_TAP)
                return true
            }
            else -> {
                if (DEBUG_MODE) {
                    val char = event?.unicodeChar?.toChar()
                    if (char != null && char.code > 0 && !char.isISOControl()) {
                        startKeyboardCapture(char)
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startKeyboardCapture(initialChar: Char? = null) {
        isCapturingKeyboardInput = true
        keyboardInputBuffer.clear()
        if (initialChar != null) {
            keyboardInputBuffer.append(initialChar)
        }
        hudState.value = hudState.value.copy(
            voiceState = if (initialChar != null) VoiceInputState.Recognizing else VoiceInputState.Listening,
            voiceText = initialChar?.toString() ?: ""
        )
    }

    private fun handleKeyboardCapture(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                val text = keyboardInputBuffer.toString().trim()
                isCapturingKeyboardInput = false
                keyboardInputBuffer.clear()
                if (text.isNotEmpty()) {
                    voiceHandler.simulateVoiceInput(text) { result ->
                        handleVoiceResult(result)
                    }
                } else {
                    hudState.value = hudState.value.copy(
                        voiceState = VoiceInputState.Idle,
                        voiceText = ""
                    )
                }
                return true
            }
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
                isCapturingKeyboardInput = false
                keyboardInputBuffer.clear()
                hudState.value = hudState.value.copy(
                    voiceState = VoiceInputState.Idle,
                    voiceText = ""
                )
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (keyboardInputBuffer.isNotEmpty()) {
                    keyboardInputBuffer.deleteCharAt(keyboardInputBuffer.length - 1)
                    updateKeyboardCaptureDisplay()
                }
                return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                keyboardInputBuffer.append(' ')
                updateKeyboardCaptureDisplay()
                return true
            }
            else -> {
                val char = event?.unicodeChar?.toChar()
                if (char != null && char.code > 0 && !char.isISOControl()) {
                    keyboardInputBuffer.append(char)
                    updateKeyboardCaptureDisplay()
                    return true
                }
            }
        }
        return true
    }

    private fun updateKeyboardCaptureDisplay() {
        val text = keyboardInputBuffer.toString()
        hudState.value = hudState.value.copy(
            voiceState = if (text.isEmpty()) VoiceInputState.Listening else VoiceInputState.Recognizing,
            voiceText = text
        )
    }

    // ============== Simplified 3-Area Gesture Handling ==============

    private fun handleGesture(gesture: Gesture) {
        val current = hudState.value
        val isVoiceActive = voiceHandler.isListening()

        Log.d(GlassesApp.TAG, "Gesture: $gesture, Area: ${current.focusedArea}")

        // If overlays are open, handle gestures for them
        if (current.showSlashMenu) {
            handleSlashMenuGesture(gesture)
            return
        }
        if (current.showMoreMenu) {
            handleMoreMenuGesture(gesture)
            return
        }
        if (current.showSessionPicker) {
            handleSessionPickerGesture(gesture)
            return
        }

        // If voice is active, TAP cancels
        if (isVoiceActive && gesture == Gesture.TAP) {
            voiceHandler.cancel()
            return
        }

        // Route by focused area (CONTENT or MENU — input is voice-only)
        when (current.focusedArea) {
            ChatFocusArea.CONTENT -> handleContentGesture(gesture)
            ChatFocusArea.MENU -> handleMenuGesture(gesture)
        }
    }

    // CONTENT area gestures
    private fun handleContentGesture(gesture: Gesture) {
        when (gesture) {
            Gesture.SWIPE_FORWARD -> scrollUp()
            Gesture.SWIPE_BACKWARD -> {
                // Scroll down — push through bottom to MENU
                val current = hudState.value
                val maxScroll = maxOf(0, current.messages.size - 1)
                if (current.scrollPosition >= maxScroll && current.isScrolledToEnd) {
                    // Already fully scrolled to bottom: push through to MENU
                    hudState.value = current.copy(
                        focusedArea = ChatFocusArea.MENU,
                        menuBarIndex = 0
                    )
                } else if (current.scrollPosition >= maxScroll) {
                    // At last message but not fully scrolled — scroll to end
                    scrollToBottom()
                } else {
                    scrollDown()
                }
            }
            Gesture.TAP -> scrollToBottom()
            Gesture.DOUBLE_TAP -> {
                // Exit scroll → MENU
                hudState.value = hudState.value.copy(
                    focusedArea = ChatFocusArea.MENU,
                    menuBarIndex = 0
                )
            }
            Gesture.LONG_PRESS -> startVoice()
        }
    }

    // MENU area gestures
    private fun handleMenuGesture(gesture: Gesture) {
        val current = hudState.value
        val items = MenuBarItem.entries

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                // Previous menu item, or push through to CONTENT
                if (current.menuBarIndex == 0) {
                    hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
                } else {
                    hudState.value = current.copy(menuBarIndex = current.menuBarIndex - 1)
                }
            }
            Gesture.SWIPE_BACKWARD -> {
                // Next menu item
                val newIndex = minOf(items.size - 1, current.menuBarIndex + 1)
                hudState.value = current.copy(menuBarIndex = newIndex)
            }
            Gesture.TAP -> {
                // Execute selected menu item
                executeMenuItem(items[current.menuBarIndex])
            }
            Gesture.DOUBLE_TAP -> {
                // Go back to CONTENT
                hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
            }
            Gesture.LONG_PRESS -> startVoice()
        }
    }

    // ============== Menu Item Actions ==============

    private fun executeMenuItem(item: MenuBarItem) {
        val current = hudState.value

        when (item) {
            MenuBarItem.SESSION -> {
                // Request session list from phone
                requestSessionList()
            }
            MenuBarItem.SIZE -> {
                // Cycle HUD position: FULL → BOTTOM_HALF → TOP_HALF → FULL
                val nextPosition = when (current.hudPosition) {
                    HudPosition.FULL -> HudPosition.BOTTOM_HALF
                    HudPosition.BOTTOM_HALF -> HudPosition.TOP_HALF
                    HudPosition.TOP_HALF -> HudPosition.FULL
                }
                // Bump scrollTrigger so the scroll re-evaluates with the new viewport height
                hudState.value = current.copy(
                    hudPosition = nextPosition,
                    scrollTrigger = current.scrollTrigger + 1
                )
            }
            MenuBarItem.FONT -> {
                // Cycle display size
                val sizes = HudDisplaySize.entries
                val currentIndex = sizes.indexOf(current.displaySize)
                val nextSize = sizes[(currentIndex + 1) % sizes.size]
                // Bump scrollTrigger so the scroll re-evaluates with the new font size
                hudState.value = current.copy(
                    displaySize = nextSize,
                    scrollTrigger = current.scrollTrigger + 1
                )
            }
            MenuBarItem.MORE -> {
                hudState.value = current.copy(
                    showMoreMenu = true,
                    selectedMoreIndex = 0
                )
            }
            MenuBarItem.EXIT -> {
                finishAffinity()
            }
        }
    }

    // ============== Submit Input ==============

    private fun submitInput() {
        val current = hudState.value
        val text = current.inputText.trim()
        if (text.isEmpty()) return

        // Send user_input to phone
        val json = JSONObject().apply {
            put("type", "user_input")
            put("text", text)
            if (current.hasPhoto) {
                // Photo is handled separately — glasses camera not yet implemented
                // Placeholder for future photo attachment
            }
        }
        phoneConnection.sendToPhone(json.toString())

        // Clear input
        hudState.value = current.copy(
            inputText = "",
            hasPhoto = false
        )

        Log.d(GlassesApp.TAG, "Submitted input: ${text.take(50)}")
    }

    // ============== Session Picker Gestures ==============

    private fun handleSessionPickerGesture(gesture: Gesture) {
        val current = hudState.value
        val totalOptions = current.availableSessions.size

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                val newIndex = maxOf(0, current.selectedSessionIndex - 1)
                hudState.value = current.copy(selectedSessionIndex = newIndex)
            }
            Gesture.SWIPE_BACKWARD -> {
                val newIndex = minOf(totalOptions - 1, current.selectedSessionIndex + 1)
                hudState.value = current.copy(selectedSessionIndex = newIndex)
            }
            Gesture.TAP -> {
                if (totalOptions > 0) {
                    val selected = current.availableSessions[current.selectedSessionIndex]
                    switchToSession(selected.key)
                }
                hudState.value = current.copy(showSessionPicker = false)
            }
            Gesture.DOUBLE_TAP -> {
                hudState.value = current.copy(showSessionPicker = false)
            }
            Gesture.LONG_PRESS -> {}
        }
    }

    // ============== More Menu Gestures ==============

    private fun handleMoreMenuGesture(gesture: Gesture) {
        val current = hudState.value
        val items = MoreMenuItem.entries
        val itemCount = items.size

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                val newIndex = if (current.selectedMoreIndex > 0) current.selectedMoreIndex - 1 else itemCount - 1
                hudState.value = current.copy(selectedMoreIndex = newIndex)
            }
            Gesture.SWIPE_BACKWARD -> {
                val newIndex = if (current.selectedMoreIndex < itemCount - 1) current.selectedMoreIndex + 1 else 0
                hudState.value = current.copy(selectedMoreIndex = newIndex)
            }
            Gesture.TAP -> {
                val selectedItem = items[current.selectedMoreIndex]
                // Close more menu first, then execute (which may open a submenu)
                hudState.value = current.copy(
                    showMoreMenu = false,
                    selectedMoreIndex = 0
                )
                executeMoreMenuItem(selectedItem)
            }
            Gesture.DOUBLE_TAP -> {
                hudState.value = current.copy(
                    showMoreMenu = false,
                    selectedMoreIndex = 0
                )
            }
            Gesture.LONG_PRESS -> {}
        }
    }

    private fun executeMoreMenuItem(item: MoreMenuItem) {
        val current = hudState.value

        when (item) {
            MoreMenuItem.SLASH -> {
                // Open slash command submenu
                hudState.value = current.copy(
                    showMoreMenu = false,
                    showSlashMenu = true,
                    selectedSlashIndex = 0
                )
            }
            MoreMenuItem.PHOTO -> {
                // TODO: trigger glasses camera capture
                hudState.value = current.copy(hasPhoto = true)
                Log.d(GlassesApp.TAG, "Photo capture requested (not yet implemented)")
            }
            MoreMenuItem.REMOVE_PHOTO -> {
                hudState.value = current.copy(hasPhoto = false)
            }
        }
    }

    private fun appendToInput(char: String) {
        val current = hudState.value
        hudState.value = current.copy(inputText = current.inputText + char)
    }

    // ============== Slash Command Menu Gestures ==============

    private fun handleSlashMenuGesture(gesture: Gesture) {
        val current = hudState.value
        val commands = SLASH_COMMANDS

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                val newIndex = maxOf(0, current.selectedSlashIndex - 1)
                hudState.value = current.copy(selectedSlashIndex = newIndex)
            }
            Gesture.SWIPE_BACKWARD -> {
                val newIndex = minOf(commands.size - 1, current.selectedSlashIndex + 1)
                hudState.value = current.copy(selectedSlashIndex = newIndex)
            }
            Gesture.TAP -> {
                val item = commands[current.selectedSlashIndex]
                // Send slash command to phone
                val json = JSONObject().apply {
                    put("type", "slash_command")
                    put("command", item.command)
                }
                phoneConnection.sendToPhone(json.toString())
                hudState.value = current.copy(
                    showSlashMenu = false,
                    selectedSlashIndex = 0
                )
                Log.d(GlassesApp.TAG, "Slash command: ${item.command}")
            }
            Gesture.DOUBLE_TAP -> {
                hudState.value = current.copy(
                    showSlashMenu = false,
                    selectedSlashIndex = 0
                )
            }
            Gesture.LONG_PRESS -> {}
        }
    }

    // ============== Scroll Helpers ==============

    private fun scrollToBottom() {
        val current = hudState.value
        val lastIndex = maxOf(0, current.messages.size - 1)
        hudState.value = current.copy(
            scrollPosition = lastIndex,
            scrollTrigger = current.scrollTrigger + 1
        )
    }

    private fun scrollUp() {
        val current = hudState.value
        val newPosition = maxOf(0, current.scrollPosition - 5) // scroll by 5 messages
        hudState.value = current.copy(scrollPosition = newPosition)
    }

    private fun scrollDown() {
        val current = hudState.value
        val maxScroll = maxOf(0, current.messages.size - 1)
        val newPosition = minOf(maxScroll, current.scrollPosition + 5)
        hudState.value = current.copy(scrollPosition = newPosition)
    }

    // ============== Voice Recognition ==============

    private fun startVoice() {
        if (voiceHandler.isListening()) {
            voiceHandler.cancel()
        } else {
            voiceHandler.startListening { result ->
                handleVoiceResult(result)
            }
        }
    }

    private fun handleVoiceResult(result: GlassesVoiceHandler.VoiceResult) {
        when (result) {
            is GlassesVoiceHandler.VoiceResult.Text -> {
                Log.d(GlassesApp.TAG, "Voice input: ${result.text.take(100)}")
                // Auto-submit voice text
                val text = result.text.trim()
                if (text.isNotEmpty()) {
                    hudState.value = hudState.value.copy(inputText = text)
                    submitInput()
                }
            }
            is GlassesVoiceHandler.VoiceResult.Command -> {
                handleVoiceCommand(result.command)
            }
            is GlassesVoiceHandler.VoiceResult.Error -> {
                Log.e(GlassesApp.TAG, "Voice error: ${result.message}")
                lifecycleScope.launch {
                    delay(3000)
                    val current = hudState.value
                    if (current.voiceState is VoiceInputState.Error) {
                        hudState.value = current.copy(
                            voiceState = VoiceInputState.Idle,
                            voiceText = ""
                        )
                    }
                }
            }
        }
    }

    private fun handleVoiceCommand(command: String) {
        when (command) {
            "scroll up" -> scrollUp()
            "scroll down" -> scrollDown()
            "clear" -> hudState.value = hudState.value.copy(inputText = "")
            "send", "enter" -> submitInput()
            else -> {
                // Treat as text input — auto-submit
                val text = command.trim()
                if (text.isNotEmpty()) {
                    hudState.value = hudState.value.copy(inputText = text)
                    submitInput()
                }
            }
        }
    }

    // ============== Phone Communication ==============

    private fun requestSessionList() {
        phoneConnection.sendToPhone("""{"type":"list_sessions"}""")
    }

    private fun switchToSession(sessionKey: String) {
        val json = JSONObject().apply {
            put("type", "switch_session")
            put("sessionKey", sessionKey)
        }
        phoneConnection.sendToPhone(json.toString())
    }

    // ============== Phone Message Handling ==============

    private fun handlePhoneMessage(json: String) {
        try {
            Log.d(GlassesApp.TAG, "handlePhoneMessage: ${json.length} chars, preview=${json.take(120)}")
            val msg = JSONObject(json)
            val type = msg.optString("type", "")

            when (type) {
                "chat_message" -> {
                    // Complete message (user echo or finished assistant message)
                    val id = msg.optString("id", "")
                    val role = msg.optString("role", "assistant")
                    val content = msg.optString("content", "")

                    val current = hudState.value
                    val messages = current.messages.toMutableList()

                    // Check if we already have a streaming message with this id
                    val existingIndex = messages.indexOfFirst { it.id == id }
                    val displayMsg = DisplayMessage(
                        id = id,
                        role = role,
                        content = content,
                        isStreaming = false
                    )

                    if (existingIndex >= 0) {
                        // Replace streaming message with complete one
                        messages[existingIndex] = displayMsg
                    } else {
                        messages.add(displayMsg)
                    }

                    hudState.value = current.copy(
                        messages = messages,
                        agentState = AgentState.IDLE,
                        scrollPosition = messages.size - 1,
                        scrollTrigger = current.scrollTrigger + 1
                    )

                    Log.d(GlassesApp.TAG, "Chat message ($role): ${content.take(50)}")
                }

                "chat_history" -> {
                    // Batched session history — replace all messages and scroll to bottom
                    val messagesArray = msg.optJSONArray("messages")
                    val messages = mutableListOf<DisplayMessage>()

                    if (messagesArray != null) {
                        for (i in 0 until messagesArray.length()) {
                            val msgObj = messagesArray.optJSONObject(i) ?: continue
                            val id = msgObj.optString("id", "")
                            val role = msgObj.optString("role", "assistant")
                            val content = msgObj.optString("content", "")
                            messages.add(DisplayMessage(
                                id = id,
                                role = role,
                                content = content,
                                isStreaming = false
                            ))
                        }
                    }

                    val current = hudState.value
                    hudState.value = current.copy(
                        messages = messages,
                        agentState = AgentState.IDLE,
                        scrollPosition = maxOf(0, messages.size - 1),
                        scrollTrigger = current.scrollTrigger + 1
                    )

                    Log.d(GlassesApp.TAG, "Loaded ${messages.size} history messages")
                }

                "agent_thinking" -> {
                    // Agent acknowledged request, waiting for first chunk
                    val current = hudState.value
                    hudState.value = current.copy(agentState = AgentState.THINKING)
                    Log.d(GlassesApp.TAG, "Agent thinking")
                }

                "chat_stream" -> {
                    // Streaming text chunk from agent
                    val id = msg.optString("id", "")
                    val chunk = msg.optString("chunk", "")

                    val current = hudState.value
                    val messages = current.messages.toMutableList()

                    val existingIndex = messages.indexOfFirst { it.id == id }
                    if (existingIndex >= 0) {
                        // Append chunk to existing streaming message
                        val existing = messages[existingIndex]
                        val newContent = existing.content + chunk
                        messages[existingIndex] = existing.copy(
                            content = newContent,
                            isStreaming = true
                        )
                    } else {
                        // Create new streaming message
                        messages.add(DisplayMessage(
                            id = id,
                            role = "assistant",
                            content = chunk,
                            isStreaming = true
                        ))
                    }

                    // Auto-scroll to bottom during streaming (unless user scrolled up)
                    val shouldAutoScroll = current.focusedArea != ChatFocusArea.CONTENT ||
                        current.scrollPosition >= current.messages.size - 2

                    hudState.value = current.copy(
                        messages = messages,
                        agentState = AgentState.STREAMING,
                        scrollPosition = if (shouldAutoScroll) messages.size - 1 else current.scrollPosition,
                        scrollTrigger = if (shouldAutoScroll) current.scrollTrigger + 1 else current.scrollTrigger
                    )
                }

                "chat_stream_end" -> {
                    // Streaming complete for a message
                    val id = msg.optString("id", "")

                    val current = hudState.value
                    val messages = current.messages.toMutableList()

                    val existingIndex = messages.indexOfFirst { it.id == id }
                    if (existingIndex >= 0) {
                        messages[existingIndex] = messages[existingIndex].copy(isStreaming = false)
                    }

                    hudState.value = current.copy(
                        messages = messages,
                        agentState = AgentState.IDLE
                    )

                    Log.d(GlassesApp.TAG, "Stream ended for $id")
                }

                "connection_update" -> {
                    val connected = msg.optBoolean("connected", false)
                    val sessionKey = msg.optString("sessionId", "")

                    val current = hudState.value
                    val newSessionKey = sessionKey.ifEmpty { current.currentSessionKey }
                    val sessionChanged = newSessionKey != current.currentSessionKey
                    hudState.value = current.copy(
                        isConnected = connected,
                        currentSessionKey = newSessionKey,
                        showSessionPicker = if (sessionChanged) false else current.showSessionPicker
                    )

                    Log.d(GlassesApp.TAG, "Connection update: connected=$connected, session=$sessionKey")
                }

                "session_list" -> {
                    // Session list from phone
                    val sessionsArray = msg.optJSONArray("sessions")
                    val currentSessionKey = msg.optString("currentSessionKey", "")
                    val sessions = mutableListOf<SessionPickerInfo>()

                    if (sessionsArray != null) {
                        for (i in 0 until sessionsArray.length()) {
                            val sessionObj = sessionsArray.optJSONObject(i)
                            if (sessionObj != null) {
                                val key = sessionObj.optString("key", "")
                                val label = sessionObj.optString("label", "")
                                val displayName = sessionObj.optString("displayName", "")
                                val derivedTitle = sessionObj.optString("derivedTitle", "")
                                val kind = sessionObj.optString("kind", "")
                                // Use best available name: label > displayName > derivedTitle > key
                                val name = label.ifEmpty { displayName.ifEmpty { derivedTitle.ifEmpty { key } } }
                                sessions.add(SessionPickerInfo(
                                    key = key,
                                    name = name,
                                    kind = kind.ifEmpty { null }
                                ))
                            }
                        }
                    }

                    val current = hudState.value
                    hudState.value = current.copy(
                        showSessionPicker = true,
                        availableSessions = sessions,
                        currentSessionKey = currentSessionKey.ifEmpty { current.currentSessionKey },
                        selectedSessionIndex = sessions.indexOfFirst { it.key == currentSessionKey }.coerceAtLeast(0)
                    )

                    Log.d(GlassesApp.TAG, "Sessions: ${sessions.size}, current: $currentSessionKey")
                }

                "voice_state" -> {
                    val state = msg.optString("state", "")
                    val text = msg.optString("text", "")
                    voiceHandler.handleVoiceState(state, text)
                }

                "voice_result" -> {
                    val resultType = msg.optString("result_type", "text")
                    val text = msg.optString("text", "")
                    voiceHandler.handleVoiceResult(resultType, text)
                }

                else -> {
                    Log.d(GlassesApp.TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(GlassesApp.TAG, "Error parsing message: ${json.take(100)}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHandler.cleanup()
        phoneConnection.stop()
    }
}
