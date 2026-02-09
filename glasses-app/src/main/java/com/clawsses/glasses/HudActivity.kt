package com.clawsses.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.clawsses.glasses.camera.CameraCapture
import com.clawsses.glasses.camera.PhotoCaptureState
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
import com.clawsses.glasses.ui.InputActionItem
import com.clawsses.glasses.ui.MenuBarItem
import com.clawsses.glasses.ui.MoreMenuItem
import com.clawsses.glasses.ui.SessionPickerInfo
import com.clawsses.glasses.ui.SLASH_COMMANDS
import com.clawsses.glasses.ui.VoiceInputState
import com.clawsses.glasses.ui.theme.GlassesHudTheme
import com.clawsses.glasses.voice.GlassesVoiceHandler
import kotlinx.coroutines.Job
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
        private const val CAMERA_PERMISSION_REQUEST = 1001

        /** Idle timeout before entering standby mode (ms) */
        const val STANDBY_IDLE_TIMEOUT_MS = 90_000L // 90 seconds

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
    private lateinit var cameraCapture: CameraCapture

    // Thumbnails to attach to the next user message echo from the server

    // Standby idle timer
    private var idleTimerJob: Job? = null

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

        cameraCapture = CameraCapture(this)

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

        // Observe camera capture state
        lifecycleScope.launch {
            cameraCapture.state.collect { photoState ->
                when (photoState) {
                    is PhotoCaptureState.Captured -> {
                        val current = hudState.value
                        hudState.value = current.copy(
                            photoThumbnails = current.photoThumbnails + photoState.thumbnail
                        )
                    }
                    is PhotoCaptureState.Error -> {
                        Log.e(GlassesApp.TAG, "Photo capture error: ${photoState.message}")
                        lifecycleScope.launch {
                            delay(3000)
                            cameraCapture.clearPhoto()
                        }
                    }
                    is PhotoCaptureState.Idle -> { /* no-op for list-based photos */ }
                    is PhotoCaptureState.Capturing -> { /* capture in progress */ }
                }
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

        // Connection is started in onStart() so it reliably reconnects on every
        // foreground transition, not just the first launch. See onStart()/onStop().

        // Start standby idle timer
        resetIdleTimer()

        // Observe connection state and request current state when phone connects
        lifecycleScope.launch {
            phoneConnection.connectionState.collect { state ->
                val isConnected = state is PhoneConnectionService.ConnectionState.Connected
                val current = hudState.value
                if (current.isConnected != isConnected) {
                    hudState.value = current.copy(isConnected = isConnected)
                    if (isConnected) {
                        phoneConnection.sendToPhone("""{"type":"request_state"}""")
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        phoneConnection.stop()
    }

    override fun onStart() {
        super.onStart()
        // Reconnect every time the activity becomes visible. On first launch, onStop()
        // hasn't been called yet so restart() calls stop() (a no-op) then startListening().
        // On subsequent returns (foreground after background, relaunch after kill) this
        // re-establishes the CXR bridge or debug WebSocket so the glasses reliably
        // show "connected" instead of staying stuck on "disconnected".
        phoneConnection.restart()
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
        // Any key press resets the standby timer (wake if in standby)
        if (hudState.value.isStandby) {
            resetIdleTimer()
            return true  // consume the key that woke us
        }
        resetIdleTimer()

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

    // ============== Standby / Wake ==============

    /**
     * Reset (or start) the idle timer. Any meaningful activity calls this
     * to prevent or exit standby mode. If the display is currently in standby,
     * wake it first.
     */
    private fun resetIdleTimer() {
        if (hudState.value.isStandby) {
            wakeFromStandby()
        }
        idleTimerJob?.cancel()
        idleTimerJob = lifecycleScope.launch {
            delay(STANDBY_IDLE_TIMEOUT_MS)
            enterStandby()
        }
    }

    private fun enterStandby() {
        Log.i(GlassesApp.TAG, "Entering standby mode")
        hudState.value = hudState.value.copy(isStandby = true)
        // Tell phone to set hardware brightness to 0 via CXR-M SDK
        phoneConnection.sendToPhone("""{"type":"set_brightness","brightness":0}""")
    }

    private fun wakeFromStandby() {
        Log.i(GlassesApp.TAG, "Waking from standby")
        hudState.value = hudState.value.copy(isStandby = false)
        // Tell phone to restore hardware brightness via CXR-M SDK
        phoneConnection.sendToPhone("""{"type":"set_brightness","brightness":15}""")
    }

    // ============== Simplified 3-Area Gesture Handling ==============

    private fun handleGesture(gesture: Gesture) {
        // Wake from standby on any gesture — consume the gesture so
        // the user doesn't accidentally trigger an action on wake.
        if (hudState.value.isStandby) {
            resetIdleTimer()
            return
        }
        resetIdleTimer()

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

        // Route by focused area
        when (current.focusedArea) {
            ChatFocusArea.CONTENT -> handleContentGesture(gesture)
            ChatFocusArea.PHOTOS -> handlePhotosGesture(gesture)
            ChatFocusArea.INPUT -> handleInputGesture(gesture)
            ChatFocusArea.MENU -> handleMenuGesture(gesture)
        }
    }

    // CONTENT area gestures
    private fun handleContentGesture(gesture: Gesture) {
        when (gesture) {
            Gesture.SWIPE_FORWARD -> scrollUp()
            Gesture.SWIPE_BACKWARD -> {
                val current = hudState.value
                val maxScroll = maxOf(0, current.messages.size - 1)
                if (current.scrollPosition >= maxScroll && current.isScrolledToEnd) {
                    // Push through: CONTENT → PHOTOS (if any) → INPUT (if staging) → MENU
                    if (current.photoThumbnails.isNotEmpty()) {
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.PHOTOS,
                            selectedPhotoIndex = 0
                        )
                    } else if (current.showInputStaging) {
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.INPUT,
                            inputActionIndex = 0
                        )
                    } else {
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.MENU,
                            menuBarIndex = 0
                        )
                    }
                } else if (current.scrollPosition >= maxScroll) {
                    scrollToBottom()
                } else {
                    scrollDown()
                }
            }
            Gesture.TAP -> scrollToBottom()
            Gesture.DOUBLE_TAP -> {
                val current = hudState.value
                if (current.photoThumbnails.isNotEmpty()) {
                    hudState.value = current.copy(
                        focusedArea = ChatFocusArea.PHOTOS,
                        selectedPhotoIndex = 0
                    )
                } else if (current.showInputStaging) {
                    hudState.value = current.copy(
                        focusedArea = ChatFocusArea.INPUT,
                        inputActionIndex = 0
                    )
                } else {
                    hudState.value = current.copy(
                        focusedArea = ChatFocusArea.MENU,
                        menuBarIndex = 0
                    )
                }
            }
            Gesture.LONG_PRESS -> startVoice()
        }
    }

    // PHOTOS strip gestures
    private fun handlePhotosGesture(gesture: Gesture) {
        val current = hudState.value
        val count = current.photoThumbnails.size

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                if (current.selectedPhotoIndex == 0) {
                    hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
                } else {
                    hudState.value = current.copy(selectedPhotoIndex = current.selectedPhotoIndex - 1)
                }
            }
            Gesture.SWIPE_BACKWARD -> {
                if (current.selectedPhotoIndex >= count - 1) {
                    // Push through: PHOTOS → INPUT (if staging) → MENU
                    if (current.showInputStaging) {
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.INPUT,
                            inputActionIndex = 0
                        )
                    } else {
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.MENU,
                            menuBarIndex = 0
                        )
                    }
                } else {
                    hudState.value = current.copy(selectedPhotoIndex = current.selectedPhotoIndex + 1)
                }
            }
            Gesture.TAP -> {
                // Remove selected photo
                val index = current.selectedPhotoIndex
                if (index in current.photoThumbnails.indices) {
                    val newThumbnails = current.photoThumbnails.toMutableList().apply { removeAt(index) }
                    val newIndex = minOf(index, newThumbnails.size - 1).coerceAtLeast(0)
                    if (newThumbnails.isEmpty()) {
                        hudState.value = current.copy(
                            photoThumbnails = emptyList(),
                            selectedPhotoIndex = 0,
                            focusedArea = ChatFocusArea.MENU,
                            menuBarIndex = 0
                        )
                    } else {
                        hudState.value = current.copy(
                            photoThumbnails = newThumbnails,
                            selectedPhotoIndex = newIndex
                        )
                    }
                    // Tell phone to remove photo at this index
                    phoneConnection.sendToPhone("""{"type":"remove_photo","index":$index}""")
                }
            }
            Gesture.DOUBLE_TAP -> {
                hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
            }
            Gesture.LONG_PRESS -> startVoice()
        }
    }

    // INPUT staging area gestures
    private fun handleInputGesture(gesture: Gesture) {
        val current = hudState.value
        val items = InputActionItem.entries

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                if (current.inputActionIndex == 0) {
                    // Push through: INPUT → PHOTOS (if any) → CONTENT
                    if (current.photoThumbnails.isNotEmpty()) {
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.PHOTOS,
                            selectedPhotoIndex = current.photoThumbnails.size - 1
                        )
                    } else {
                        hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
                    }
                } else {
                    hudState.value = current.copy(inputActionIndex = current.inputActionIndex - 1)
                }
            }
            Gesture.SWIPE_BACKWARD -> {
                if (current.inputActionIndex >= items.size - 1) {
                    // Push through: INPUT → MENU
                    hudState.value = current.copy(
                        focusedArea = ChatFocusArea.MENU,
                        menuBarIndex = 0
                    )
                } else {
                    hudState.value = current.copy(inputActionIndex = current.inputActionIndex + 1)
                }
            }
            Gesture.TAP -> {
                val selectedItem = items[current.inputActionIndex]
                when (selectedItem) {
                    InputActionItem.SEND -> {
                        // Submit the staged text and dismiss
                        val text = current.stagingText.trim()
                        if (text.isNotEmpty()) {
                            hudState.value = current.copy(inputText = text)
                            submitInput()
                        }
                        // Dismiss staging area (submitInput resets focusedArea to CONTENT)
                        hudState.value = hudState.value.copy(
                            showInputStaging = false,
                            stagingText = "",
                            inputActionIndex = 0
                        )
                    }
                    InputActionItem.CLEAR -> {
                        // Clear staged text and dismiss
                        hudState.value = current.copy(
                            showInputStaging = false,
                            stagingText = "",
                            inputActionIndex = 0,
                            focusedArea = ChatFocusArea.CONTENT
                        )
                    }
                }
            }
            Gesture.DOUBLE_TAP -> {
                // Go back to CONTENT
                hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
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
                if (current.menuBarIndex == 0) {
                    // Push through: MENU → INPUT (if staging) → PHOTOS (if any) → CONTENT
                    if (current.showInputStaging) {
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.INPUT,
                            inputActionIndex = InputActionItem.entries.size - 1
                        )
                    } else if (current.photoThumbnails.isNotEmpty()) {
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.PHOTOS,
                            selectedPhotoIndex = current.photoThumbnails.size - 1
                        )
                    } else {
                        hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
                    }
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
            MenuBarItem.PHOTO -> {
                if (DEBUG_MODE) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        cameraCapture.capture()
                    } else {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
                    }
                } else {
                    phoneConnection.sendToPhone("""{"type":"take_photo"}""")
                    Log.d(GlassesApp.TAG, "Requested photo capture from phone")
                }
            }
            MenuBarItem.SESSION -> {
                requestSessionList()
            }
            MenuBarItem.SIZE -> {
                val nextPosition = when (current.hudPosition) {
                    HudPosition.FULL -> HudPosition.BOTTOM_HALF
                    HudPosition.BOTTOM_HALF -> HudPosition.TOP_HALF
                    HudPosition.TOP_HALF -> HudPosition.FULL
                }
                hudState.value = current.copy(
                    hudPosition = nextPosition,
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

        val thumbnails = current.photoThumbnails.toList()
        Log.d(GlassesApp.TAG, "submitInput: text='${text.take(50)}', photos=${thumbnails.size}, focusArea=${current.focusedArea}")

        // Add user message to display immediately (optimistic update)
        val userMsg = DisplayMessage(
            id = "local-${System.currentTimeMillis()}",
            role = "user",
            content = text,
            isStreaming = false,
            thumbnails = thumbnails
        )
        val messages = current.messages.toMutableList()
        messages.add(userMsg)

        // Send user_input to phone
        val json = JSONObject().apply {
            put("type", "user_input")
            put("text", text)
        }
        phoneConnection.sendToPhone(json.toString())

        // Tell phone to clear its photos too
        if (thumbnails.isNotEmpty()) {
            phoneConnection.sendToPhone("""{"type":"remove_photo","all":true}""")
        }
        hudState.value = current.copy(
            messages = messages,
            inputText = "",
            photoThumbnails = emptyList(),
            selectedPhotoIndex = 0,
            focusedArea = ChatFocusArea.CONTENT,
            scrollPosition = messages.size - 1,
            scrollTrigger = current.scrollTrigger + 1,
            showInputStaging = false,
            stagingText = "",
            inputActionIndex = 0
        )
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

        if (item.displaySize != null) {
            hudState.value = current.copy(
                displaySize = item.displaySize,
                scrollTrigger = current.scrollTrigger + 1
            )
            return
        }

        when (item) {
            MoreMenuItem.SLASH -> {
                hudState.value = current.copy(
                    showMoreMenu = false,
                    showSlashMenu = true,
                    selectedSlashIndex = 0
                )
            }
            else -> {}
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
            // Don't pass a result callback — voice_result messages from the phone
            // are handled directly in handlePhoneMessage to avoid the AI key path
            // issue where onResult is never set because startVoice() isn't called.
            voiceHandler.startListening { /* handled in handlePhoneMessage */ }
        }
    }

    /** Append voice text to the staging area and show it. */
    private fun stageVoiceText(text: String) {
        Log.d(GlassesApp.TAG, "Staging voice text: ${text.take(100)}")
        val current = hudState.value
        val newStagingText = if (current.stagingText.isEmpty()) {
            text
        } else {
            "${current.stagingText} $text"
        }
        hudState.value = current.copy(
            stagingText = newStagingText,
            showInputStaging = true,
            focusedArea = ChatFocusArea.INPUT,
            inputActionIndex = 0,
            scrollTrigger = current.scrollTrigger + 1
        )
    }

    private fun handleVoiceResult(result: GlassesVoiceHandler.VoiceResult) {
        // Called from onResult callback (start_voice path) and simulateVoiceInput (keyboard).
        // For phone-originated voice_result messages, staging is handled in handlePhoneMessage.
        when (result) {
            is GlassesVoiceHandler.VoiceResult.Text -> {
                val text = result.text.trim()
                if (text.isNotEmpty()) {
                    stageVoiceText(text)
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
            "clear" -> {
                // Clear staging area if visible, otherwise clear inputText
                val current = hudState.value
                if (current.showInputStaging) {
                    hudState.value = current.copy(
                        showInputStaging = false,
                        stagingText = "",
                        inputActionIndex = 0,
                        focusedArea = ChatFocusArea.CONTENT
                    )
                } else {
                    hudState.value = current.copy(inputText = "")
                }
            }
            "send", "enter" -> {
                // Submit staging text if visible, otherwise submit inputText
                val current = hudState.value
                if (current.showInputStaging && current.stagingText.isNotBlank()) {
                    hudState.value = current.copy(inputText = current.stagingText.trim())
                    submitInput()
                    hudState.value = hudState.value.copy(
                        showInputStaging = false,
                        stagingText = "",
                        inputActionIndex = 0
                    )
                } else {
                    submitInput()
                }
            }
            else -> {
                // Treat as text input — stage it
                val text = command.trim()
                if (text.isNotEmpty()) {
                    stageVoiceText(text)
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

            // Wake from standby and reset idle timer on content-bearing messages
            if (type in setOf("chat_message", "chat_stream", "chat_stream_end",
                    "agent_thinking", "chat_history", "voice_result")) {
                resetIdleTimer()
            }

            when (type) {
                "chat_message" -> {
                    // Complete message (user echo or finished assistant message)
                    val id = msg.optString("id", "")
                    val role = msg.optString("role", "assistant")
                    val content = unwrapContent(msg.optString("content", ""))

                    var current = hudState.value
                    val messages = current.messages.toMutableList()

                    if (role == "user") {
                        // Check if submitInput already added this message optimistically
                        val existingLocal = messages.indexOfLast { it.role == "user" && it.content == content }
                        if (existingLocal >= 0) {
                            Log.d(GlassesApp.TAG, "User echo already displayed, skipping: ${content.take(50)}")
                            // Clear any lingering photos (belt-and-suspenders)
                            if (current.photoThumbnails.isNotEmpty()) {
                                hudState.value = current.copy(
                                    photoThumbnails = emptyList(),
                                    selectedPhotoIndex = 0
                                )
                            }
                            return
                        }
                        // Phone-originated user message — grab photos from strip if any
                        val thumbnails = current.photoThumbnails.toList()
                        val displayMsg = DisplayMessage(
                            id = id,
                            role = role,
                            content = content,
                            isStreaming = false,
                            thumbnails = thumbnails
                        )
                        messages.add(displayMsg)
                        hudState.value = current.copy(
                            messages = messages,
                            agentState = AgentState.IDLE,
                            photoThumbnails = emptyList(),
                            selectedPhotoIndex = 0,
                            scrollPosition = messages.size - 1,
                            scrollTrigger = current.scrollTrigger + 1
                        )
                        Log.d(GlassesApp.TAG, "User message (phone): ${content.take(50)}, photos=${thumbnails.size}")
                    } else {
                        // Assistant message
                        val existingIndex = messages.indexOfFirst { it.id == id }
                        val displayMsg = DisplayMessage(
                            id = id,
                            role = role,
                            content = content,
                            isStreaming = false
                        )

                        if (existingIndex >= 0) {
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
                        Log.d(GlassesApp.TAG, "Assistant message: ${content.take(50)}")
                    }
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
                            val content = unwrapContent(msgObj.optString("content", ""))
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
                    // Streaming complete — unwrap soft line breaks now that full content is available
                    val id = msg.optString("id", "")

                    val current = hudState.value
                    val messages = current.messages.toMutableList()

                    val existingIndex = messages.indexOfFirst { it.id == id }
                    if (existingIndex >= 0) {
                        val existing = messages[existingIndex]
                        messages[existingIndex] = existing.copy(
                            content = unwrapContent(existing.content),
                            isStreaming = false
                        )
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
                    val unreadArray = msg.optJSONArray("unreadSessionKeys")
                    val unreadKeys = mutableSetOf<String>()
                    if (unreadArray != null) {
                        for (i in 0 until unreadArray.length()) {
                            unreadKeys.add(unreadArray.optString(i, ""))
                        }
                    }
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
                                val updatedAt = if (sessionObj.has("updatedAt")) sessionObj.optLong("updatedAt", 0L).takeIf { it > 0 } else null
                                // Use best available name: label > displayName > derivedTitle > key
                                val name = label.ifEmpty { displayName.ifEmpty { derivedTitle.ifEmpty { key } } }
                                sessions.add(SessionPickerInfo(
                                    key = key,
                                    name = name,
                                    kind = kind.ifEmpty { null },
                                    hasUnread = key in unreadKeys,
                                    updatedAt = updatedAt
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
                    // Update voice handler state (sets to Idle, clears onResult)
                    voiceHandler.handleVoiceResult(resultType, text)
                    // Stage text directly — don't rely on onResult callback
                    // which may not be set (AI key path bypasses startVoice on glasses)
                    when (resultType) {
                        "text" -> {
                            val trimmed = text.trim()
                            if (trimmed.isNotEmpty()) {
                                stageVoiceText(trimmed)
                            }
                        }
                        "command" -> handleVoiceCommand(text)
                    }
                }

                "photo_result" -> {
                    val status = msg.optString("status", "")
                    if (status == "captured") {
                        val thumbnailBase64 = msg.optString("thumbnail", "")
                        if (thumbnailBase64.isNotEmpty()) {
                            val bytes = Base64.decode(thumbnailBase64, Base64.DEFAULT)
                            val thumbnail = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            val current = hudState.value
                            hudState.value = current.copy(
                                photoThumbnails = current.photoThumbnails + thumbnail
                            )
                            Log.d(GlassesApp.TAG, "Photo captured, thumbnail added (total: ${current.photoThumbnails.size + 1})")
                        }
                    } else {
                        Log.e(GlassesApp.TAG, "Photo capture failed: ${msg.optString("message", "")}")
                    }
                }

                "remove_photo" -> {
                    val all = msg.optBoolean("all", false)
                    val current = hudState.value
                    if (all) {
                        hudState.value = current.copy(
                            photoThumbnails = emptyList(),
                            selectedPhotoIndex = 0
                        )
                    } else {
                        val index = msg.optInt("index", -1)
                        if (index in current.photoThumbnails.indices) {
                            val updated = current.photoThumbnails.toMutableList().apply { removeAt(index) }
                            hudState.value = current.copy(
                                photoThumbnails = updated,
                                selectedPhotoIndex = minOf(current.selectedPhotoIndex, updated.size - 1).coerceAtLeast(0)
                            )
                        }
                    }
                    Log.d(GlassesApp.TAG, "Photo removed from phone request")
                }

                else -> {
                    Log.d(GlassesApp.TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(GlassesApp.TAG, "Error parsing message: ${json.take(100)}", e)
        }
    }

    /**
     * Unwrap soft line breaks from AI model output so Compose can re-wrap
     * to the actual widget width. Preserves paragraph breaks (blank lines),
     * list items, and other structural markdown.
     *
     * A single `\n` between two non-empty, non-structural lines is treated
     * as a soft wrap inserted by the model and replaced with a space.
     */
    private fun unwrapContent(text: String): String {
        val lines = text.split("\n")
        if (lines.size <= 1) return text

        val result = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i]
            result.append(line)
            if (i < lines.lastIndex) {
                val next = lines[i + 1]
                // Keep newline (don't join) when:
                // - current line is blank → paragraph break
                // - next line is blank → paragraph break
                // - next line starts with markdown structure (list, heading, code fence, blockquote)
                val keepNewline = line.isBlank() ||
                    next.isBlank() ||
                    next.trimStart().let {
                        it.startsWith("- ") ||
                        it.startsWith("* ") ||
                        it.startsWith("+ ") ||
                        it.matches(Regex("^\\d+[.)].+")) ||
                        it.startsWith("#") ||
                        it.startsWith("```") ||
                        it.startsWith("> ")
                    }

                if (keepNewline) {
                    result.append("\n")
                } else {
                    // Join with space (soft wrap from model)
                    if (line.isNotEmpty()) result.append(" ")
                }
            }
        }
        return result.toString()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cameraCapture.capture()
            } else {
                Log.w(GlassesApp.TAG, "Camera permission denied")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraCapture.cleanup()
        voiceHandler.cleanup()
        // phoneConnection.stop() is handled by onStop(), which always runs before onDestroy
    }
}
