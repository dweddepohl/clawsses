package com.claudeglasses.glasses

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
import com.claudeglasses.glasses.input.GestureHandler
import com.claudeglasses.glasses.input.GestureHandler.Gesture
import com.claudeglasses.glasses.service.PhoneConnectionService
import com.claudeglasses.glasses.ui.ContentMode
import com.claudeglasses.glasses.ui.DetectedPrompt
import com.claudeglasses.glasses.ui.FocusArea
import com.claudeglasses.glasses.ui.FocusLevel
import com.claudeglasses.glasses.ui.ClaudeMode
import com.claudeglasses.glasses.ui.FocusState
import com.claudeglasses.glasses.ui.HudScreen
import com.claudeglasses.glasses.ui.InputSection
import com.claudeglasses.glasses.ui.LineColorType
import com.claudeglasses.glasses.ui.MoreMenuItem
import com.claudeglasses.glasses.ui.QuickCommand
import com.claudeglasses.glasses.ui.TerminalState
import com.claudeglasses.glasses.ui.VoiceInputState
import com.claudeglasses.glasses.ui.theme.GlassesHudTheme
import com.claudeglasses.glasses.voice.GlassesVoiceHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

import android.os.Build
import com.claudeglasses.glasses.BuildConfig

class HudActivity : ComponentActivity() {

    companion object {
        // Enable debug mode only on actual emulators (not debug builds on real hardware).
        // Emulators use generic hardware identifiers like "goldfish", "ranchu", "sdk_gphone".
        val DEBUG_MODE = BuildConfig.DEBUG && isEmulator()

        // Debug connection settings (10.0.2.2 is host from Android emulator)
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

    private val terminalState = MutableStateFlow(TerminalState())
    private lateinit var gestureHandler: GestureHandler
    private lateinit var phoneConnection: PhoneConnectionService
    private lateinit var voiceHandler: GlassesVoiceHandler

    // Debug keyboard input mode - captures keys as simulated voice input
    private var isCapturingKeyboardInput = false
    private var keyboardInputBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while app is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enable immersive fullscreen mode (hide system bars)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        gestureHandler = GestureHandler { gesture ->
            handleGesture(gesture)
        }

        // Create phone connection service - uses WebSocket in debug mode, Bluetooth otherwise
        phoneConnection = PhoneConnectionService(
            context = this,
            onMessageReceived = { message -> handlePhoneMessage(message) },
            debugMode = DEBUG_MODE,
            debugHost = DEBUG_HOST,
            debugPort = DEBUG_PORT
        )

        Log.i(GlassesApp.TAG, "HudActivity created, debugMode=$DEBUG_MODE")

        // Initialize voice handler (delegates recognition to phone)
        voiceHandler = GlassesVoiceHandler()
        voiceHandler.sendToPhone = { message -> phoneConnection.sendToPhone(message) }
        voiceHandler.initialize()

        // Observe voice state and update terminal state
        lifecycleScope.launch {
            voiceHandler.voiceState.collect { voiceState ->
                val current = terminalState.value
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
                terminalState.value = current.copy(
                    voiceState = newVoiceState,
                    voiceText = newVoiceText
                )
            }
        }

        setContent {
            GlassesHudTheme {
                val state by terminalState.collectAsState()
                HudScreen(
                    state = state,
                    onTap = { handleGesture(Gesture.TAP) },
                    onDoubleTap = { handleGesture(Gesture.DOUBLE_TAP) },
                    onLongPress = { handleGesture(Gesture.LONG_PRESS) }
                )
            }
        }

        // Start listening for phone connection
        lifecycleScope.launch {
            phoneConnection.startListening()
        }

        // Observe connection state and update UI
        lifecycleScope.launch {
            phoneConnection.connectionState.collect { state ->
                val isConnected = state is PhoneConnectionService.ConnectionState.Connected
                val current = terminalState.value
                val wasConnected = current.isConnected

                if (current.isConnected != isConnected) {
                    terminalState.value = current.copy(isConnected = isConnected)
                }

                // When connection is established, request session list to show picker
                // (unless a session was already selected in this app session)
                if (isConnected && !wasConnected && current.currentSession.isEmpty()) {
                    Log.i(GlassesApp.TAG, "Connected to phone, requesting session list")
                    requestSessionList()
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { gestureHandler.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    // Handle generic motion events from external touchpad (Rokid glasses temple touchpad)
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event?.let {
            Log.d(GlassesApp.TAG, "GenericMotionEvent: action=${it.action}, source=${it.source}")
            if (gestureHandler.onTouchEvent(it)) {
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val keyName = KeyEvent.keyCodeToString(keyCode)
        Log.d(GlassesApp.TAG, "onKeyDown: keyCode=$keyCode ($keyName), repeat=${event?.repeatCount}, source=0x${event?.source?.toString(16)}")

        // If capturing keyboard input for simulated voice, handle specially
        if (isCapturingKeyboardInput) {
            return handleKeyboardCapture(keyCode, event)
        }

        // Ignore auto-repeat events for gesture detection
        if (event?.repeatCount ?: 0 > 0) return true

        // Rokid touchpad key mappings (hardware translates gestures to KeyEvents):
        //   Tap         → KEYCODE_ENTER
        //   Double-tap  → KEYCODE_BACK
        //   Swipe fwd   → KEYCODE_DPAD_UP
        //   Swipe back  → KEYCODE_DPAD_DOWN
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
            // Emulator shortcuts
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_DEL -> {
                handleGesture(Gesture.DOUBLE_TAP)
                return true
            }
            else -> {
                // Any other key starts keyboard capture mode with that character
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

    /**
     * Start capturing keyboard input as simulated voice recognition.
     * Shows voice overlay and captures typed characters until Enter/Escape.
     * @param initialChar Optional first character to include in the buffer
     */
    private fun startKeyboardCapture(initialChar: Char? = null) {
        isCapturingKeyboardInput = true
        keyboardInputBuffer.clear()
        if (initialChar != null) {
            keyboardInputBuffer.append(initialChar)
        }
        terminalState.value = terminalState.value.copy(
            voiceState = if (initialChar != null) VoiceInputState.Recognizing else VoiceInputState.Listening,
            voiceText = initialChar?.toString() ?: ""
        )
        Log.d(GlassesApp.TAG, "Started keyboard capture for voice simulation")
    }

    /**
     * Handle key events while in keyboard capture mode.
     * Enter submits, Escape cancels, other keys are captured as text.
     */
    private fun handleKeyboardCapture(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                // Submit the captured text
                val text = keyboardInputBuffer.toString().trim()
                isCapturingKeyboardInput = false
                keyboardInputBuffer.clear()

                if (text.isNotEmpty()) {
                    voiceHandler.simulateVoiceInput(text) { result ->
                        handleVoiceResult(result)
                    }
                } else {
                    terminalState.value = terminalState.value.copy(
                        voiceState = VoiceInputState.Idle,
                        voiceText = ""
                    )
                }
                return true
            }
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
                // Cancel keyboard capture
                isCapturingKeyboardInput = false
                keyboardInputBuffer.clear()
                terminalState.value = terminalState.value.copy(
                    voiceState = VoiceInputState.Idle,
                    voiceText = ""
                )
                Log.d(GlassesApp.TAG, "Cancelled keyboard capture")
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                // Backspace - remove last character
                if (keyboardInputBuffer.isNotEmpty()) {
                    keyboardInputBuffer.deleteCharAt(keyboardInputBuffer.length - 1)
                    updateKeyboardCaptureDisplay()
                }
                return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                // Space character
                keyboardInputBuffer.append(' ')
                updateKeyboardCaptureDisplay()
                return true
            }
            else -> {
                // Try to get the character for this key
                val char = event?.unicodeChar?.toChar()
                if (char != null && char.code > 0 && !char.isISOControl()) {
                    keyboardInputBuffer.append(char)
                    updateKeyboardCaptureDisplay()
                    return true
                }
            }
        }
        return true // Consume all keys while capturing
    }

    /**
     * Update the voice overlay display with current keyboard input.
     */
    private fun updateKeyboardCaptureDisplay() {
        val text = keyboardInputBuffer.toString()
        terminalState.value = terminalState.value.copy(
            voiceState = if (text.isEmpty()) VoiceInputState.Listening else VoiceInputState.Recognizing,
            voiceText = text
        )
    }

    // ============== Hierarchical Focus-Based Gesture Handling ==============

    private fun handleGesture(gesture: Gesture) {
        val current = terminalState.value
        val focus = current.focus
        val isVoiceActive = voiceHandler.isListening()

        Log.d(GlassesApp.TAG, "Gesture: $gesture, Level: ${focus.level}, Area: ${focus.focusedArea}")

        // If kill confirmation dialog is open, handle gestures for it
        if (current.showKillConfirmation) {
            handleKillConfirmGesture(gesture)
            return
        }

        // If more menu is open, handle gestures for it
        if (current.showMoreMenu) {
            handleMoreMenuGesture(gesture)
            return
        }

        // If session picker is open, handle gestures for it
        if (current.showSessionPicker) {
            handleSessionPickerGesture(gesture)
            return
        }

        // If voice is active, TAP cancels voice recognition
        if (isVoiceActive && gesture == Gesture.TAP) {
            Log.d(GlassesApp.TAG, "Cancelling voice recognition via tap")
            voiceHandler.cancel()
            return
        }

        // Route gesture based on current focus level
        when (focus.level) {
            FocusLevel.AREA_SELECT -> handleAreaSelectGesture(gesture)
            FocusLevel.AREA_FOCUSED, FocusLevel.FINE_CONTROL -> when (focus.focusedArea) {
                FocusArea.CONTENT -> handleContentGesture(gesture)
                FocusArea.INPUT -> handleInputGesture(gesture)
                FocusArea.COMMAND -> handleCommandGesture(gesture)
            }
        }
    }

    private fun handleSessionPickerGesture(gesture: Gesture) {
        val current = terminalState.value
        val sessions = current.availableSessions
        val totalOptions = sessions.size + 1  // +1 for "New Session"

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                // Move selection up
                val newIndex = maxOf(0, current.selectedSessionIndex - 1)
                terminalState.value = current.copy(selectedSessionIndex = newIndex)
            }
            Gesture.SWIPE_BACKWARD -> {
                // Move selection down
                val newIndex = minOf(totalOptions - 1, current.selectedSessionIndex + 1)
                terminalState.value = current.copy(selectedSessionIndex = newIndex)
            }
            Gesture.TAP -> {
                // Select session
                val selectedIndex = current.selectedSessionIndex
                if (selectedIndex < sessions.size) {
                    // Existing session
                    switchToSession(sessions[selectedIndex])
                } else {
                    // New session - generate name
                    val newName = generateNewSessionName(sessions)
                    switchToSession(newName)
                }
                terminalState.value = current.copy(showSessionPicker = false)
            }
            Gesture.DOUBLE_TAP -> {
                // Cancel
                terminalState.value = current.copy(showSessionPicker = false)
            }
            Gesture.LONG_PRESS -> {
                // Show kill confirmation for existing sessions (not for "New Session" option)
                val selectedIndex = current.selectedSessionIndex
                if (selectedIndex < sessions.size) {
                    val sessionToKill = sessions[selectedIndex]
                    Log.d(GlassesApp.TAG, "Long press on session: $sessionToKill - showing kill confirmation")
                    terminalState.value = current.copy(
                        showSessionPicker = false,
                        showKillConfirmation = true,
                        sessionToKill = sessionToKill,
                        killConfirmSelected = 0  // Default to Cancel
                    )
                }
            }
        }
    }

    private fun handleKillConfirmGesture(gesture: Gesture) {
        val current = terminalState.value

        when (gesture) {
            Gesture.SWIPE_FORWARD, Gesture.SWIPE_BACKWARD -> {
                // Toggle between Cancel (0) and Kill (1)
                val newSelection = if (current.killConfirmSelected == 0) 1 else 0
                terminalState.value = current.copy(killConfirmSelected = newSelection)
            }
            Gesture.TAP -> {
                // Confirm the selected action
                if (current.killConfirmSelected == 1) {
                    // Kill the session
                    Log.d(GlassesApp.TAG, "Killing session: ${current.sessionToKill}")
                    killSession(current.sessionToKill)
                } else {
                    // Cancel - go back to session picker
                    Log.d(GlassesApp.TAG, "Kill cancelled, returning to session picker")
                }
                // Close confirmation and refresh session list
                terminalState.value = current.copy(
                    showKillConfirmation = false,
                    sessionToKill = "",
                    killConfirmSelected = 0
                )
                // Refresh session list after a brief delay to allow kill to complete
                if (current.killConfirmSelected == 1) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        requestSessionList()
                    }, 200)
                } else {
                    requestSessionList()
                }
            }
            Gesture.DOUBLE_TAP -> {
                // Cancel and go back to session picker
                Log.d(GlassesApp.TAG, "Kill confirmation cancelled via double tap")
                terminalState.value = current.copy(
                    showKillConfirmation = false,
                    sessionToKill = "",
                    killConfirmSelected = 0
                )
                requestSessionList()
            }
            Gesture.LONG_PRESS -> {
                // Ignore
            }
        }
    }

    private fun killSession(sessionName: String) {
        Log.d(GlassesApp.TAG, "Sending kill_session request for: $sessionName")
        phoneConnection.sendToPhone("""{"type":"kill_session","session":"$sessionName"}""")
    }

    private fun handleMoreMenuGesture(gesture: Gesture) {
        val current = terminalState.value
        val items = MoreMenuItem.entries
        val itemCount = items.size

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                // Move selection up (or wrap to bottom)
                val newIndex = if (current.selectedMoreIndex > 0) {
                    current.selectedMoreIndex - 1
                } else {
                    itemCount - 1
                }
                terminalState.value = current.copy(selectedMoreIndex = newIndex)
            }
            Gesture.SWIPE_BACKWARD -> {
                // Move selection down (or wrap to top)
                val newIndex = if (current.selectedMoreIndex < itemCount - 1) {
                    current.selectedMoreIndex + 1
                } else {
                    0
                }
                terminalState.value = current.copy(selectedMoreIndex = newIndex)
            }
            Gesture.TAP -> {
                // Execute the selected command
                val selectedItem = items[current.selectedMoreIndex]
                Log.d(GlassesApp.TAG, "More menu: selected ${selectedItem.label}")
                sendCommand(selectedItem.key)
                // Close the menu and return to INPUT area
                terminalState.value = current.copy(
                    showMoreMenu = false,
                    selectedMoreIndex = 0,
                    focus = current.focus.copy(
                        focusedArea = FocusArea.INPUT,
                        level = FocusLevel.AREA_SELECT
                    )
                )
            }
            Gesture.DOUBLE_TAP -> {
                // Cancel and close the menu, return to INPUT area
                Log.d(GlassesApp.TAG, "More menu cancelled")
                terminalState.value = current.copy(
                    showMoreMenu = false,
                    selectedMoreIndex = 0,
                    focus = current.focus.copy(
                        focusedArea = FocusArea.INPUT,
                        level = FocusLevel.AREA_SELECT
                    )
                )
            }
            Gesture.LONG_PRESS -> {
                // Ignore
            }
        }
    }

    private fun generateNewSessionName(existingSessions: List<String>): String {
        var counter = 1
        var name = "claude-glasses"
        while (existingSessions.contains(name)) {
            counter++
            name = "claude-glasses-$counter"
        }
        return name
    }

    // Level 0: Area Selection
    private fun handleAreaSelectGesture(gesture: Gesture) {
        val current = terminalState.value
        val focus = current.focus

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                // Move focus up: Command → Input → Content
                when (focus.focusedArea) {
                    FocusArea.COMMAND -> updateFocus(focus.copy(focusedArea = FocusArea.INPUT))
                    FocusArea.INPUT -> updateFocus(focus.copy(focusedArea = FocusArea.CONTENT))
                    FocusArea.CONTENT -> {
                        // Already at top - enter level 1 and scroll up
                        updateFocus(focus.copy(level = FocusLevel.AREA_FOCUSED))
                        scrollUp()
                    }
                }
            }
            Gesture.SWIPE_BACKWARD -> {
                // Move focus down: Content → Input → Command
                when (focus.focusedArea) {
                    FocusArea.CONTENT -> updateFocus(focus.copy(focusedArea = FocusArea.INPUT))
                    FocusArea.INPUT -> {
                        // Auto-enter level 1 for COMMAND bar (highlight ENTER right away)
                        updateFocus(focus.copy(
                            focusedArea = FocusArea.COMMAND,
                            level = FocusLevel.AREA_FOCUSED,
                            commandIndex = 0
                        ))
                    }
                    FocusArea.COMMAND -> { } // Already at bottom
                }
            }
            Gesture.TAP -> {
                // Enter the focused area (go to Level 1)
                // For COMMAND, also reset to first command
                if (focus.focusedArea == FocusArea.COMMAND) {
                    updateFocus(focus.copy(level = FocusLevel.AREA_FOCUSED, commandIndex = 0))
                } else {
                    updateFocus(focus.copy(level = FocusLevel.AREA_FOCUSED))
                }
            }
            Gesture.DOUBLE_TAP -> {
                // No action at root level
            }
            Gesture.LONG_PRESS -> {
                // Voice input → auto-focus Input area
                if (voiceHandler.isListening()) {
                    voiceHandler.cancel()
                } else {
                    updateFocus(focus.copy(focusedArea = FocusArea.INPUT))
                    requestVoicePermissionAndStart()
                }
            }
        }
    }

    // Level 1: Content Area (Scroll mode only)
    private fun handleContentGesture(gesture: Gesture) {
        when (gesture) {
            Gesture.SWIPE_FORWARD -> scrollUp()
            Gesture.SWIPE_BACKWARD -> scrollDownOrPushThrough()
            Gesture.TAP -> {
                // Scroll to bottom
                scrollToBottom()
            }
            Gesture.DOUBLE_TAP -> exitToAreaSelect()
            Gesture.LONG_PRESS -> {
                if (voiceHandler.isListening()) {
                    voiceHandler.cancel()
                } else {
                    requestVoicePermissionAndStart()
                }
            }
        }
    }

    /**
     * Scroll down, or if at bottom, "push through" to Input area at level 0
     */
    private fun scrollDownOrPushThrough() {
        val current = terminalState.value
        // Use contentLines size (not lines) since that's what the content area displays
        val maxScroll = maxOf(0, current.contentLines.size - 1)

        Log.d(GlassesApp.TAG, "scrollDownOrPushThrough: pos=${current.scrollPosition}, max=$maxScroll, contentLines=${current.contentLines.size}")

        if (current.scrollPosition >= maxScroll) {
            // Already at bottom - push through to Input area at level 0
            Log.d(GlassesApp.TAG, "Pushing through to INPUT")
            updateFocus(current.focus.copy(
                focusedArea = FocusArea.INPUT,
                level = FocusLevel.AREA_SELECT
            ))
        } else {
            scrollDown()
        }
    }

    // Level 1: Input Area
    private fun handleInputGesture(gesture: Gesture) {
        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                // Send up arrow to Claude Code
                sendCommand("up")
            }
            Gesture.SWIPE_BACKWARD -> {
                // Send down arrow to Claude Code
                sendCommand("down")
            }
            Gesture.TAP -> {
                // Send enter to Claude Code and return to level 0
                sendCommand("enter")
                exitToAreaSelect()
            }
            Gesture.DOUBLE_TAP -> exitToAreaSelect()
            Gesture.LONG_PRESS -> {
                if (voiceHandler.isListening()) {
                    voiceHandler.cancel()
                } else {
                    requestVoicePermissionAndStart()
                }
            }
        }
    }

    // Level 1: Command Bar
    private fun handleCommandGesture(gesture: Gesture) {
        val current = terminalState.value
        val focus = current.focus
        val commands = QuickCommand.values()

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                if (focus.commandIndex == 0) {
                    // At first command, push through to Input area
                    updateFocus(focus.copy(
                        focusedArea = FocusArea.INPUT,
                        level = FocusLevel.AREA_SELECT
                    ))
                } else {
                    // Previous command (left)
                    val newIndex = focus.commandIndex - 1
                    updateFocus(focus.copy(commandIndex = newIndex))
                }
            }
            Gesture.SWIPE_BACKWARD -> {
                // Next command (right)
                val newIndex = minOf(commands.size - 1, focus.commandIndex + 1)
                updateFocus(focus.copy(commandIndex = newIndex))
            }
            Gesture.TAP -> {
                // Execute selected command
                val command = commands.getOrNull(focus.commandIndex)
                if (command != null) {
                    when (command.key) {
                        "list_sessions" -> {
                            // Request session list from server
                            requestSessionList()
                        }
                        "more_menu" -> {
                            // Show the more menu
                            val current = terminalState.value
                            terminalState.value = current.copy(
                                showMoreMenu = true,
                                selectedMoreIndex = 0
                            )
                        }
                        else -> {
                            sendCommand(command.key)
                        }
                    }
                }
            }
            Gesture.DOUBLE_TAP -> {
                // Go back to level 0 and highlight INPUT
                updateFocus(focus.copy(
                    focusedArea = FocusArea.INPUT,
                    level = FocusLevel.AREA_SELECT
                ))
            }
            Gesture.LONG_PRESS -> {
                if (voiceHandler.isListening()) {
                    voiceHandler.cancel()
                } else {
                    requestVoicePermissionAndStart()
                }
            }
        }
    }

    // ============== Focus Update Helpers ==============

    private fun updateFocus(newFocus: FocusState) {
        val current = terminalState.value

        // Auto-scroll to bottom when entering INPUT or COMMAND areas
        if (newFocus.focusedArea != current.focus.focusedArea &&
            (newFocus.focusedArea == FocusArea.INPUT || newFocus.focusedArea == FocusArea.COMMAND)) {
            scrollToBottom()
        }

        terminalState.value = current.copy(focus = newFocus)
    }

    private fun exitToAreaSelect() {
        val current = terminalState.value
        val focus = current.focus
        // Don't use updateFocus to avoid auto-scroll behavior
        terminalState.value = current.copy(
            focus = focus.copy(
                level = FocusLevel.AREA_SELECT,
                contentMode = ContentMode.PAGE,
                selectionStart = null
            )
        )
    }

    private fun selectInputOption() {
        val current = terminalState.value
        val focus = current.focus

        when (val prompt = current.detectedPrompt) {
            is DetectedPrompt.MultipleChoice -> {
                // Send the number key for the selected option
                val optionNum = focus.inputOptionIndex + 1
                sendCommand("$optionNum")
            }
            is DetectedPrompt.Confirmation -> {
                sendCommand(if (focus.inputOptionIndex == 0) "y" else "n")
            }
            is DetectedPrompt.TextInput -> {
                // Send pending input text
                if (focus.pendingInput.isNotEmpty()) {
                    sendVoiceInput(focus.pendingInput)
                    updateFocus(focus.copy(pendingInput = ""))
                }
            }
            DetectedPrompt.None -> {
                // Send pending input if any
                if (focus.pendingInput.isNotEmpty()) {
                    sendVoiceInput(focus.pendingInput)
                    updateFocus(focus.copy(pendingInput = ""))
                }
            }
        }
    }

    // ============== Scroll Helpers ==============

    private fun scrollToLine(lineIndex: Int) {
        val current = terminalState.value
        terminalState.value = current.copy(
            scrollPosition = lineIndex,
            scrollTrigger = current.scrollTrigger + 1
        )
    }

    private fun scrollToBottom() {
        val current = terminalState.value
        // Use contentLines (what's displayed) not lines (includes input section)
        val lastIndex = maxOf(0, current.contentLines.size - 1)
        Log.d(GlassesApp.TAG, "scrollToBottom: scrollingTo=$lastIndex, contentLines=${current.contentLines.size}")
        terminalState.value = current.copy(
            scrollPosition = lastIndex,
            scrollTrigger = current.scrollTrigger + 1
        )
    }

    private fun scrollUp() {
        val current = terminalState.value
        val newPosition = maxOf(0, current.scrollPosition - current.visibleLines)
        terminalState.value = current.copy(scrollPosition = newPosition)
    }

    private fun scrollDown() {
        val current = terminalState.value
        // Use contentLines size (what's displayed in content area)
        val maxScroll = maxOf(0, current.contentLines.size - 1)
        val newPosition = minOf(maxScroll, current.scrollPosition + current.visibleLines)
        terminalState.value = current.copy(scrollPosition = newPosition)
    }

    private fun sendCommand(command: String) {
        phoneConnection.sendToPhone("""{"type":"command","command":"$command"}""")
    }

    // ============== Voice Recognition Methods ==============

    private fun requestVoicePermissionAndStart() {
        // No local permission needed — voice recognition runs on the phone
        startVoiceRecognition()
    }

    private fun startVoiceRecognition() {
        Log.d(GlassesApp.TAG, "Starting voice recognition")
        voiceHandler.startListening { result ->
            handleVoiceResult(result)
        }
    }

    private fun handleVoiceResult(result: GlassesVoiceHandler.VoiceResult) {
        when (result) {
            is GlassesVoiceHandler.VoiceResult.Text -> {
                Log.d(GlassesApp.TAG, "Voice input text (${result.text.length} chars): ${result.text.take(100)}")
                // Send text directly to server
                sendVoiceInput(result.text)
                // Focus on INPUT area at AREA_FOCUSED level so TAP immediately sends Enter
                val current = terminalState.value
                val focus = current.focus
                updateFocus(focus.copy(
                    focusedArea = FocusArea.INPUT,
                    level = FocusLevel.AREA_FOCUSED  // Stay focused so TAP = Enter
                ))
            }
            is GlassesVoiceHandler.VoiceResult.Command -> {
                Log.d(GlassesApp.TAG, "Voice command: ${result.command}")
                handleVoiceCommand(result.command)
            }
            is GlassesVoiceHandler.VoiceResult.Error -> {
                Log.e(GlassesApp.TAG, "Voice error: ${result.message}")
                // Error is already shown via voiceState, auto-dismiss after delay
                lifecycleScope.launch {
                    delay(3000)
                    val current = terminalState.value
                    if (current.voiceState is VoiceInputState.Error) {
                        terminalState.value = current.copy(
                            voiceState = VoiceInputState.Idle,
                            voiceText = ""
                        )
                    }
                }
            }
        }
    }

    private fun sendVoiceInput(text: String) {
        // Send voice input to phone app for forwarding to server
        // Use JSONObject for proper escaping of all special characters
        val json = JSONObject().apply {
            put("type", "voice_input")
            put("text", text)
        }
        val message = json.toString()
        Log.d(GlassesApp.TAG, "Sending voice input to phone (${text.length} chars): ${text.take(50).replace("\n", "\\n")}")
        phoneConnection.sendToPhone(message)
    }

    private fun requestSessionList() {
        Log.d(GlassesApp.TAG, "Requesting session list")
        phoneConnection.sendToPhone("""{"type":"list_sessions"}""")
    }

    private fun switchToSession(sessionName: String) {
        Log.d(GlassesApp.TAG, "Switching to session: $sessionName")
        phoneConnection.sendToPhone("""{"type":"switch_session","session":"$sessionName"}""")
    }

    /**
     * Parse terminal lines to detect Claude's current prompt.
     * Finds the bottom-most ❯ character which marks the current input.
     */
    private fun parsePrompt(lines: List<String>): DetectedPrompt {
        // Find the bottom-most line starting with ❯ (the current prompt)
        val promptLineIndex = lines.indexOfLast { line ->
            line.trimStart().startsWith("❯") || line.contains("❯")
        }

        if (promptLineIndex == -1) {
            return DetectedPrompt.None
        }

        val promptLine = lines[promptLineIndex]
        val promptText = promptLine.substringAfter("❯").trim()

        // Look at lines below the prompt for options (multiple choice)
        val linesBelow = lines.drop(promptLineIndex + 1).take(10)

        // Check for numbered options: [1] Option, [2] Option, etc.
        val numberedOptions = linesBelow.mapNotNull { line ->
            val match = Regex("""^\s*\[(\d+)\]\s*(.+)$""").find(line)
            match?.groupValues?.get(2)?.trim()
        }
        if (numberedOptions.size >= 2) {
            return DetectedPrompt.MultipleChoice(numberedOptions, 0)
        }

        // Check for Yes/No confirmation patterns
        if (promptText.contains(Regex("""\([Yy]/[Nn]\)|\[[Yy]/[Nn]\]|\(yes/no\)""", RegexOption.IGNORE_CASE))) {
            val yesDefault = promptText.contains(Regex("""\(Y/|\[Y/"""))
            return DetectedPrompt.Confirmation(yesDefault)
        }

        // Otherwise it's a text input prompt (the line after ❯ is the current input)
        return DetectedPrompt.TextInput(promptText.ifEmpty { "Type here..." })
    }

    /**
     * Detect the input section from terminal lines.
     * The input section starts after a horizontal line and contains the ❯ prompt.
     * Everything after a second horizontal line goes to the status area.
     *
     * When no ❯ is visible (input is taller than screen), assume the entire
     * visible area is the input section until we can see a ❯ again.
     */
    private fun detectInputSection(lines: List<String>, lineColors: List<LineColorType>): InputSection {
        if (lines.isEmpty()) return InputSection()

        // Find the ❯ prompt line from the bottom
        val promptLineIndex = lines.indexOfLast { line ->
            line.trimStart().startsWith("❯") || line.contains("❯")
        }

        // If no prompt is visible, assume the entire screen is input content
        // This happens when the input is taller than the visible screen area
        if (promptLineIndex == -1) {
            Log.d(GlassesApp.TAG, "No ❯ visible - assuming entire screen is input")
            return InputSection(
                lines = lines.toList(),
                lineColors = lineColors.toList(),
                statusLine = null,
                claudeMode = ClaudeMode.NORMAL,
                startIndex = 0  // Entire screen is input
            )
        }

        // Horizontal line characters (including dashed variants)
        val horizontalLineChars = setOf('━', '─', '═', '▀', '▄', '█', '―', '⎯', '╌', '╍', '┄', '┅', '┈', '┉')

        fun isHorizontalLine(line: String): Boolean {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return false
            // A line is "horizontal" if >50% of its non-space chars are horizontal line chars
            val horizontalCount = trimmed.count { it in horizontalLineChars }
            return horizontalCount > trimmed.length / 2
        }

        // Strip horizontal line characters from a line but preserve spacing
        fun stripHorizontalChars(line: String): String {
            // Only strip if line starts with horizontal chars (prefix decoration)
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) return line

            // Check if line starts with horizontal chars
            val firstNonHorizontal = trimmed.indexOfFirst { it !in horizontalLineChars }
            return if (firstNonHorizontal > 0) {
                // Has horizontal prefix - remove it but keep the rest
                trimmed.substring(firstNonHorizontal)
            } else {
                // No horizontal prefix - return as-is
                line
            }
        }

        // Search backwards from prompt to find the starting horizontal line
        var horizontalLineIndex = -1
        for (i in (promptLineIndex - 1) downTo maxOf(0, promptLineIndex - 10)) {
            val line = lines[i]
            if (isHorizontalLine(line)) {
                horizontalLineIndex = i
                break
            }
        }

        // Input section starts AT the horizontal line (so it's excluded from content)
        // But we skip the horizontal line when extracting input lines
        val inputSectionStart = if (horizontalLineIndex >= 0) {
            horizontalLineIndex  // Include horizontal line in "input section" so it's excluded from content
        } else {
            promptLineIndex
        }

        // Extract lines AFTER the horizontal line for display
        val inputLinesStart = if (horizontalLineIndex >= 0) horizontalLineIndex + 1 else promptLineIndex
        val allInputLines = lines.subList(inputLinesStart, lines.size)
        val allInputColors = if (lineColors.size >= lines.size) {
            lineColors.subList(inputLinesStart, lineColors.size)
        } else {
            emptyList()
        }

        // Find second horizontal line - everything after it goes to status
        var secondHorizontalIndex = -1
        for (i in allInputLines.indices) {
            if (isHorizontalLine(allInputLines[i])) {
                secondHorizontalIndex = i
                break
            }
        }

        // Split into input lines and status lines
        val inputLines = mutableListOf<String>()
        val inputColors = mutableListOf<LineColorType>()
        val statusLines = mutableListOf<String>()

        allInputLines.forEachIndexed { index, line ->
            // Skip pure horizontal lines
            if (isHorizontalLine(line)) return@forEachIndexed

            val stripped = stripHorizontalChars(line)

            // After second horizontal line = status area
            if (secondHorizontalIndex >= 0 && index > secondHorizontalIndex) {
                if (stripped.isNotBlank()) {
                    statusLines.add(stripped)
                }
            } else {
                // Before second horizontal line = input area (keep all lines for formatting)
                inputLines.add(stripped)
                if (index < allInputColors.size) {
                    inputColors.add(allInputColors[index])
                }
            }
        }

        // Combine status lines into one
        val statusLine = if (statusLines.isNotEmpty()) {
            statusLines.joinToString("  ")
        } else {
            null
        }

        // Detect Claude mode from status lines
        val allStatusText = statusLines.joinToString(" ").lowercase()
        val claudeMode = when {
            allStatusText.contains("accept edits on") -> ClaudeMode.ACCEPT_EDITS
            allStatusText.contains("plan mode on") -> ClaudeMode.PLAN
            else -> ClaudeMode.NORMAL
        }

        return InputSection(
            lines = inputLines,
            lineColors = inputColors,
            statusLine = statusLine,
            claudeMode = claudeMode,
            startIndex = inputSectionStart
        )
    }

    private fun handleVoiceCommand(command: String) {
        // Handle special voice commands locally
        val current = terminalState.value
        val focus = current.focus

        when (command) {
            "escape" -> sendCommand("escape")
            "scroll up" -> scrollUp()
            "scroll down" -> scrollDown()
            "switch mode", "navigate mode", "input" -> {
                // Focus on input area
                updateFocus(focus.copy(
                    focusedArea = FocusArea.INPUT,
                    level = FocusLevel.AREA_FOCUSED
                ))
            }
            "scroll mode", "content" -> {
                // Focus on content area
                updateFocus(focus.copy(
                    focusedArea = FocusArea.CONTENT,
                    level = FocusLevel.AREA_FOCUSED
                ))
            }
            "command mode", "commands" -> {
                // Focus on command bar
                updateFocus(focus.copy(
                    focusedArea = FocusArea.COMMAND,
                    level = FocusLevel.AREA_FOCUSED
                ))
            }
            "back", "exit" -> {
                // Return to area selection
                exitToAreaSelect()
            }
            else -> {
                // Unknown command, send as text input
                sendVoiceInput(command)
            }
        }
    }

    private fun showVoiceError(message: String) {
        terminalState.value = terminalState.value.copy(
            voiceState = VoiceInputState.Error(message),
            voiceText = ""
        )
        // Auto-dismiss after 3 seconds
        lifecycleScope.launch {
            delay(3000)
            val current = terminalState.value
            if (current.voiceState is VoiceInputState.Error) {
                terminalState.value = current.copy(
                    voiceState = VoiceInputState.Idle,
                    voiceText = ""
                )
            }
        }
    }

    // ============== Phone Message Handling ==============

    private fun handlePhoneMessage(json: String) {
        try {
            val msg = JSONObject(json)
            val type = msg.optString("type", "")

            when (type) {
                "terminal_update", "output" -> {
                    // Parse terminal update from server (via phone app)
                    val linesArray = msg.optJSONArray("lines")
                    val lineColorsArray = msg.optJSONArray("lineColors")
                    val cursorPos = msg.optInt("cursorPosition", 0)

                    val lines = mutableListOf<String>()
                    if (linesArray != null) {
                        for (i in 0 until linesArray.length()) {
                            lines.add(linesArray.optString(i, ""))
                        }
                    }

                    // Parse line colors from server ANSI detection
                    val lineColors = mutableListOf<LineColorType>()
                    if (lineColorsArray != null) {
                        for (i in 0 until lineColorsArray.length()) {
                            val colorStr = lineColorsArray.optString(i, "")
                            val colorType = when (colorStr) {
                                "addition" -> LineColorType.ADDITION
                                "deletion" -> LineColorType.DELETION
                                "header" -> LineColorType.HEADER
                                else -> LineColorType.NORMAL
                            }
                            lineColors.add(colorType)
                        }
                    }

                    val current = terminalState.value
                    // Detect input section (horizontal line + prompt at bottom)
                    val inputSection = detectInputSection(lines, lineColors)
                    // Parse prompt from terminal lines (for interaction hints)
                    val detectedPrompt = parsePrompt(lines)
                    // Find the prompt line index for highlighting
                    val promptLineIndex = lines.indexOfLast { line ->
                        line.trimStart().startsWith("❯") || line.contains("❯")
                    }

                    // Content lines = everything except input section
                    val contentLineCount = if (inputSection.startIndex >= 0) inputSection.startIndex else lines.size

                    // Only auto-scroll if:
                    // 1. Line count increased (new content added, not just changed)
                    // 2. User is NOT actively scrolling (in CONTENT area at AREA_FOCUSED level)
                    val previousContentCount = if (current.inputSection.startIndex >= 0) current.inputSection.startIndex else current.lines.size
                    val lineCountIncreased = contentLineCount > previousContentCount
                    val userIsScrolling = current.focus.focusedArea == FocusArea.CONTENT &&
                                          current.focus.level == FocusLevel.AREA_FOCUSED
                    val shouldAutoScroll = lineCountIncreased && !userIsScrolling

                    val newScrollPosition = if (shouldAutoScroll) {
                        maxOf(0, contentLineCount - 1)
                    } else {
                        // Keep current scroll position, but clamp to valid range
                        current.scrollPosition.coerceIn(0, maxOf(0, contentLineCount - 1))
                    }
                    val newScrollTrigger = if (shouldAutoScroll) {
                        current.scrollTrigger + 1
                    } else {
                        current.scrollTrigger
                    }

                    terminalState.value = current.copy(
                        lines = lines,
                        lineColors = lineColors,
                        cursorLine = cursorPos,
                        promptLineIndex = promptLineIndex,
                        inputSection = inputSection,
                        scrollPosition = newScrollPosition,
                        scrollTrigger = newScrollTrigger,
                        isConnected = true,
                        detectedPrompt = detectedPrompt
                    )

                    Log.d(GlassesApp.TAG, "Terminal update: ${lines.size} lines, content=${contentLineCount}, inputSection=${inputSection.lines.size} lines, autoScroll=$shouldAutoScroll")
                }
                "output_delta" -> {
                    // Delta update: only changed lines from server
                    val changedLines = msg.optJSONObject("changedLines")
                    val changedColors = msg.optJSONObject("changedColors")
                    val totalLines = msg.optInt("totalLines", 0)

                    if (changedLines != null) {
                        val current = terminalState.value
                        val newLines = current.lines.toMutableList()
                        val newColors = current.lineColors.toMutableList()

                        // Resize to match server
                        while (newLines.size < totalLines) {
                            newLines.add("")
                            newColors.add(LineColorType.NORMAL)
                        }
                        while (newLines.size > totalLines) {
                            newLines.removeAt(newLines.size - 1)
                            newColors.removeAt(newColors.size - 1)
                        }

                        // Apply changes
                        val keys = changedLines.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val idx = key.toIntOrNull() ?: continue
                            if (idx in 0 until newLines.size) {
                                newLines[idx] = changedLines.optString(key, "")
                            }
                        }
                        if (changedColors != null) {
                            val colorKeys = changedColors.keys()
                            while (colorKeys.hasNext()) {
                                val key = colorKeys.next()
                                val idx = key.toIntOrNull() ?: continue
                                if (idx in 0 until newColors.size) {
                                    newColors[idx] = when (changedColors.optString(key, "")) {
                                        "addition" -> LineColorType.ADDITION
                                        "deletion" -> LineColorType.DELETION
                                        "header" -> LineColorType.HEADER
                                        else -> LineColorType.NORMAL
                                    }
                                }
                            }
                        }

                        val inputSection = detectInputSection(newLines, newColors)
                        val detectedPrompt = parsePrompt(newLines)
                        val promptLineIndex = newLines.indexOfLast { line ->
                            line.trimStart().startsWith("❯") || line.contains("❯")
                        }
                        val contentLineCount = if (inputSection.startIndex >= 0) inputSection.startIndex else newLines.size
                        val previousContentCount = if (current.inputSection.startIndex >= 0) current.inputSection.startIndex else current.lines.size
                        val lineCountIncreased = contentLineCount > previousContentCount
                        val userIsScrolling = current.focus.focusedArea == FocusArea.CONTENT &&
                                              current.focus.level == FocusLevel.AREA_FOCUSED
                        val shouldAutoScroll = lineCountIncreased && !userIsScrolling
                        val newScrollPosition = if (shouldAutoScroll) {
                            maxOf(0, contentLineCount - 1)
                        } else {
                            current.scrollPosition.coerceIn(0, maxOf(0, contentLineCount - 1))
                        }
                        val newScrollTrigger = if (shouldAutoScroll) current.scrollTrigger + 1 else current.scrollTrigger

                        terminalState.value = current.copy(
                            lines = newLines,
                            lineColors = newColors,
                            cursorLine = 0,
                            promptLineIndex = promptLineIndex,
                            inputSection = inputSection,
                            scrollPosition = newScrollPosition,
                            scrollTrigger = newScrollTrigger,
                            isConnected = true,
                            detectedPrompt = detectedPrompt
                        )
                    }
                }
                "sessions" -> {
                    // Session list from server
                    val sessionsArray = msg.optJSONArray("sessions")
                    val currentSession = msg.optString("current", "")
                    val sessions = mutableListOf<String>()
                    if (sessionsArray != null) {
                        for (i in 0 until sessionsArray.length()) {
                            sessions.add(sessionsArray.optString(i, ""))
                        }
                    }

                    val current = terminalState.value
                    terminalState.value = current.copy(
                        showSessionPicker = true,
                        availableSessions = sessions,
                        currentSession = currentSession,
                        selectedSessionIndex = sessions.indexOf(currentSession).coerceAtLeast(0)
                    )
                    Log.d(GlassesApp.TAG, "Sessions: $sessions, current: $currentSession")
                }
                "session_switched" -> {
                    // Confirmation that session was switched
                    val session = msg.optString("session", "")
                    val success = msg.optBoolean("success", false)
                    Log.d(GlassesApp.TAG, "Session switched to $session: $success")

                    val current = terminalState.value
                    terminalState.value = current.copy(
                        currentSession = if (success) session else current.currentSession
                    )
                }
                "voice_state" -> {
                    // Voice recognition state update from phone
                    val state = msg.optString("state", "")
                    val text = msg.optString("text", "")
                    voiceHandler.handleVoiceState(state, text)
                }
                "voice_result" -> {
                    // Final voice recognition result from phone
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
