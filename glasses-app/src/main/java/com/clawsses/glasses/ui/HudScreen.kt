package com.clawsses.glasses.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.clawsses.glasses.R
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import kotlinx.coroutines.delay

/**
 * Display size presets for the 480x640 portrait HUD
 * Each preset optimizes for different character counts vs readability
 */
enum class HudDisplaySize(val fontSizeSp: Int, val label: String) {
    COMPACT(10, "Compact"),
    NORMAL(12, "Normal"),
    COMFORTABLE(14, "Comfortable"),
    LARGE(16, "Large")
}

/**
 * HUD position controls how much of the 480x640 display is used.
 * Smaller positions let the user see more of the outside world.
 */
enum class HudPosition(val label: String) {
    FULL("Full"),
    BOTTOM_HALF("Bottom"),
    TOP_HALF("Top")
}

/**
 * Focus areas of the chat UI.
 */
enum class ChatFocusArea {
    CONTENT,  // Chat messages (scrollable)
    INPUT,    // Voice input staging area (photos + Send / Clear buttons)
    MENU      // Bottom menu bar
}

/**
 * Action buttons in the input staging area
 */
enum class InputActionItem(val icon: String, val label: String) {
    SEND("\u21B5", "Send"),
    CLEAR("\u2715", "Clear")
}

/** Maximum number of photos that can be attached. */
const val MAX_PHOTOS = 4

/**
 * Agent response states
 */
enum class AgentState {
    IDLE,       // No active request
    THINKING,   // Ack received, waiting for first chunk
    STREAMING   // Receiving streaming chunks
}

/**
 * Menu bar items
 */
enum class MenuBarItem(val icon: String, val label: String) {
    PHOTO("\uD83D\uDCF7", "Photo"),
    SESSION("\u25CE", "Sess"),
    SIZE("\u2588", "Size"),  // Icon overridden dynamically based on next HudPosition
    MORE("\u2026", "More"),
}

/**
 * Items available in the MORE menu
 */
enum class MoreMenuItem(val icon: String, val label: String, val displaySize: HudDisplaySize? = null) {
    FONT_COMPACT("Aa", "Compact", HudDisplaySize.COMPACT),
    FONT_NORMAL("Aa", "Normal", HudDisplaySize.NORMAL),
    FONT_COMFORTABLE("Aa", "Comfortable", HudDisplaySize.COMFORTABLE),
    FONT_LARGE("Aa", "Large", HudDisplaySize.LARGE),
    SLASH("/", "Slash Cmds"),
    VOICE("\uD83D\uDD0A", "Voice"),  // speaker icon - label is dynamic
}

/**
 * A display-ready chat message for the HUD.
 * Stores raw content; wrapping is computed at render time.
 */
data class DisplayMessage(
    val id: String,
    val role: String,  // "user" or "assistant"
    val content: String,
    val isStreaming: Boolean = false,
    val thumbnails: List<Bitmap> = emptyList()
)

/**
 * Recognition mode indicator (OpenAI vs device)
 */
enum class RecognitionMode {
    DEVICE,  // Android's SpeechRecognizer
    OPENAI   // OpenAI Realtime API
}

/**
 * Voice input states for HUD display
 */
sealed class VoiceInputState {
    object Idle : VoiceInputState()
    data class Listening(val mode: RecognitionMode = RecognitionMode.DEVICE) : VoiceInputState()
    data class Recognizing(val mode: RecognitionMode = RecognitionMode.DEVICE) : VoiceInputState()
    data class Processing(val mode: RecognitionMode = RecognitionMode.DEVICE) : VoiceInputState()
    data class Error(val message: String) : VoiceInputState()
}

/**
 * Session info for session picker
 */
data class SessionPickerInfo(
    val key: String,
    val name: String,
    val kind: String? = null,
    val hasUnread: Boolean = false,
    val updatedAt: Long? = null
)

/** Format a millisecond epoch timestamp as a short relative time string. */
private fun formatRelativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = now - timestampMs
    if (diffMs < 0) return "now"
    val seconds = diffMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "yesterday"
        days < 30 -> "${days}d ago"
        else -> "${days / 30}mo ago"
    }
}

/**
 * Chat HUD state — replaces the old TerminalState
 */
data class ChatHudState(
    val messages: List<DisplayMessage> = emptyList(),
    val scrollPosition: Int = 0,
    val scrollTrigger: Int = 0,
    val isScrolledToEnd: Boolean = false,
    val inputText: String = "",
    val photoThumbnails: List<Bitmap> = emptyList(),
    val isConnected: Boolean = false,
    val agentState: AgentState = AgentState.IDLE,
    val menuBarIndex: Int = 0,
    val hudPosition: HudPosition = HudPosition.FULL,
    val displaySize: HudDisplaySize = HudDisplaySize.NORMAL,
    val focusedArea: ChatFocusArea = ChatFocusArea.CONTENT,
    val voiceState: VoiceInputState = VoiceInputState.Idle,
    val voiceText: String = "",
    // Session picker
    val showSessionPicker: Boolean = false,
    val availableSessions: List<SessionPickerInfo> = emptyList(),
    val currentSessionKey: String? = null,
    val currentSessionName: String? = null,
    val selectedSessionIndex: Int = 0,
    // More menu
    val showMoreMenu: Boolean = false,
    val selectedMoreIndex: Int = 0,
    // Slash command menu
    val showSlashMenu: Boolean = false,
    val selectedSlashIndex: Int = 0,
    // Input staging area (voice text accumulation)
    val stagingText: String = "",
    val showInputStaging: Boolean = false,
    val inputActionIndex: Int = 0,  // Index into combined row: [photo0..N-1, Clear, Send]. Default = Send (last)
    // Exit confirmation dialog
    val showExitConfirm: Boolean = false,
    // Battery level (0-100), null = unavailable / hide indicator
    val batteryLevel: Int? = null,
    val batteryCharging: Boolean = false,
    // Current time (HH:MM, 24-hour format)
    val currentTime: String = "",
    // History loading state
    val isLoadingMoreHistory: Boolean = false,
    val hasMoreHistory: Boolean = true,  // Assume there's more until we're told otherwise
    val newPrependCount: Int = 0,  // Number of newly prepended messages (for fade-in animation)
    // Wake notification (shown briefly when glasses wakes from standby due to new content)
    val showWakeNotification: Boolean = false,
    val wakeReason: String? = null,  // "stream_content", "new_message", "cron_message"
    // TTS state (voice responses)
    val ttsEnabled: Boolean = false
) {
    /** Total number of messages */
    val totalMessages: Int get() = messages.size
}

/**
 * Slash command with display label.
 * Commands are sent to the OpenClaw Gateway as chat messages.
 */
data class SlashCommandItem(val command: String, val description: String)

/**
 * Available slash commands from the OpenClaw Gateway.
 * See .openclaw-ref/docs/tools/slash-commands.md for the full reference.
 */
val SLASH_COMMANDS = listOf(
    SlashCommandItem("/help", "Show help"),
    SlashCommandItem("/commands", "List commands"),
    SlashCommandItem("/status", "Show status"),
    SlashCommandItem("/model", "Switch model"),
    SlashCommandItem("/compact", "Compact context"),
    SlashCommandItem("/reset", "New session"),
    SlashCommandItem("/stop", "Stop generation"),
    SlashCommandItem("/think", "Thinking level"),
    SlashCommandItem("/context", "Show context"),
    SlashCommandItem("/usage", "Usage info"),
    SlashCommandItem("/whoami", "Show identity"),
    SlashCommandItem("/reasoning", "Toggle reasoning"),
    SlashCommandItem("/elevated", "Elevated mode"),
    SlashCommandItem("/verbose", "Verbose output"),
    SlashCommandItem("/exec", "Exec settings"),
    SlashCommandItem("/subagents", "Sub-agents"),
)

// ============================================================================
// MAIN HUD SCREEN
// ============================================================================

/**
 * Chat-oriented HUD display for Rokid Glasses with OpenClaw backend.
 *
 * Layout:
 * ┌─[TopBar]──────────────────────────────────┐
 * │ ● connected                    12/42 lines │
 * ├────────────────────────────────────────────┤
 * │ Assistant message (left-aligned, green)     │
 * │         User message (right, light bg) │
 * │ Assistant streaming...█                     │
 * ├───[Input]──────────────────────────────────┤
 * │ > current input text...                     │
 * ├───[Menu Bar]───────────────────────────────┤
 * │ ↵Enter ⌫Clear ◎Sess ⬚Size AaFont …More    │
 * └────────────────────────────────────────────┘
 */
@Composable
fun HudScreen(
    state: ChatHudState,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onScrolledToEndChanged: (Boolean) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val monoFontFamily = remember { FontFamily(Font(R.font.jetbrains_mono)) }

    // Track whether the list is scrolled to the very end (pixel-level)
    val canScrollForward = listState.canScrollForward
    LaunchedEffect(canScrollForward) {
        onScrolledToEndChanged(!canScrollForward)
    }

    // Auto-scroll when position or trigger changes
    LaunchedEffect(state.scrollPosition, state.scrollTrigger) {
        val totalItems = state.messages.size
        if (totalItems > 0 && state.scrollPosition < totalItems) {
            val currentIndex = listState.firstVisibleItemIndex
            if (state.scrollPosition < currentIndex) {
                // Scrolling up: use pixel-based animation for smoothness
                // (animateScrollToItem can jump when target items aren't composed yet)
                val viewportHeight = listState.layoutInfo.viewportSize.height
                val itemsToScroll = currentIndex - state.scrollPosition
                // Estimate scroll distance from average visible item height
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val avgItemHeight = if (visibleItems.isNotEmpty()) {
                    visibleItems.sumOf { it.size } / visibleItems.size.toFloat()
                } else {
                    viewportHeight / 5f
                }
                val scrollDistance = -(itemsToScroll * avgItemHeight)
                listState.animateScrollBy(scrollDistance)
            } else if (state.scrollPosition == totalItems - 1) {
                // Scrolling to last item: use a large offset so the bottom of the
                // item aligns with the viewport bottom (Compose clamps internally).
                // During streaming, use instant scroll — animated scroll gets
                // cancelled and restarted on every chunk, causing visible flicker.
                val isStreaming = state.messages.lastOrNull()?.isStreaming == true
                if (isStreaming) {
                    listState.scrollToItem(state.scrollPosition, Int.MAX_VALUE)
                } else {
                    listState.animateScrollToItem(state.scrollPosition, Int.MAX_VALUE)
                }
            } else {
                listState.animateScrollToItem(state.scrollPosition)
            }
        }
    }

    // Focus brightness
    val contentFocused = state.focusedArea == ChatFocusArea.CONTENT
    val inputFocused = state.focusedArea == ChatFocusArea.INPUT
    val menuFocused = state.focusedArea == ChatFocusArea.MENU

    val contentAlpha = focusBrightness(contentFocused)
    val inputAlpha = focusBrightness(inputFocused)
    val menuAlpha = focusBrightness(menuFocused)

    // HUD position offset
    val hudHeight = when (state.hudPosition) {
        HudPosition.FULL -> 1f
        HudPosition.BOTTOM_HALF, HudPosition.TOP_HALF -> 0.5f
    }
    val hudAlignment = when (state.hudPosition) {
        HudPosition.FULL -> Alignment.TopStart
        HudPosition.BOTTOM_HALF -> Alignment.BottomStart
        HudPosition.TOP_HALF -> Alignment.TopStart
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        // Calculate font size to fit content width — varies with displaySize
        val targetColumns = when (state.displaySize) {
            HudDisplaySize.COMPACT -> 70
            HudDisplaySize.NORMAL -> 60
            HudDisplaySize.COMFORTABLE -> 50
            HudDisplaySize.LARGE -> 40
        }
        val referenceText = "M".repeat(targetColumns)
        val referenceFontSize = 12.sp

        val fontSize = remember(maxWidth, monoFontFamily, targetColumns) {
            val referenceStyle = TextStyle(
                fontFamily = monoFontFamily,
                fontSize = referenceFontSize,
                letterSpacing = 0.sp
            )
            val measuredWidth = textMeasurer.measure(referenceText, referenceStyle).size.width
            val availableWidthPx = with(density) { maxWidth.toPx() }
            val scaledSize = referenceFontSize.value * (availableWidthPx / measuredWidth) * 0.99f
            scaledSize.coerceIn(6f, 24f).sp
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = hudAlignment
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(hudHeight)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // TOP BAR
                TopBar(
                    isConnected = state.isConnected,
                    scrollInfo = "${state.scrollPosition + 1}/${state.messages.size}",
                    agentState = state.agentState,
                    focusedArea = state.focusedArea,
                    voiceState = state.voiceState,
                    sessionTitle = state.currentSessionName,
                    isLoadingMoreHistory = state.isLoadingMoreHistory,
                    showWakeNotification = state.showWakeNotification,
                    wakeReason = state.wakeReason,
                    fontFamily = monoFontFamily,
                    fontSize = fontSize
                )

                Spacer(modifier = Modifier.height(4.dp))

                // CONTENT AREA — chat messages
                ChatContentArea(
                    messages = state.messages,
                    agentState = state.agentState,
                    listState = listState,
                    fontSize = fontSize,
                    fontFamily = monoFontFamily,
                    alpha = contentAlpha,
                    hasMoreHistory = state.hasMoreHistory,
                    newPrependCount = state.newPrependCount,
                    modifier = Modifier.weight(1f)
                )

                // INPUT STAGING AREA (with inline photo thumbnails)
                AnimatedVisibility(
                    visible = state.showInputStaging || state.photoThumbnails.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    InputStagingArea(
                        text = state.stagingText,
                        showText = state.showInputStaging,
                        photos = state.photoThumbnails,
                        selectedIndex = state.inputActionIndex,
                        isFocused = inputFocused,
                        isProcessing = state.voiceState is VoiceInputState.Processing,
                        fontFamily = monoFontFamily,
                        fontSize = fontSize,
                        alpha = inputAlpha
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // MENU BAR
                ChatMenuBar(
                    selectedIndex = state.menuBarIndex,
                    isFocused = menuFocused,
                    hudPosition = state.hudPosition,
                    batteryLevel = state.batteryLevel,
                    batteryCharging = state.batteryCharging,
                    currentTime = state.currentTime,
                    fontFamily = monoFontFamily,
                    alpha = menuAlpha
                )
            }
        }

        // Session picker overlay
        AnimatedVisibility(
            visible = state.showSessionPicker,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SessionPickerOverlay(
                sessions = state.availableSessions,
                currentSessionKey = state.currentSessionKey,
                selectedIndex = state.selectedSessionIndex,
                fontFamily = monoFontFamily
            )
        }

        // More menu overlay
        AnimatedVisibility(
            visible = state.showMoreMenu,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            MoreMenuOverlay(
                selectedIndex = state.selectedMoreIndex,
                currentDisplaySize = state.displaySize,
                ttsEnabled = state.ttsEnabled,
                fontFamily = monoFontFamily
            )
        }

        // Slash command menu overlay
        AnimatedVisibility(
            visible = state.showSlashMenu,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SlashCommandOverlay(
                selectedIndex = state.selectedSlashIndex,
                fontFamily = monoFontFamily
            )
        }

        // Exit confirmation overlay
        AnimatedVisibility(
            visible = state.showExitConfirm,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ExitConfirmOverlay(fontFamily = monoFontFamily)
        }
    }
}

// ============================================================================
// BRIGHTNESS ANIMATION
// ============================================================================

@Composable
fun focusBrightness(isFocused: Boolean): Float {
    val baseAlpha = if (isFocused) 1f else 0.4f
    return animateFloatAsState(
        targetValue = baseAlpha,
        animationSpec = tween(200),
        label = "brightness"
    ).value
}

// ============================================================================
// TOP BAR
// ============================================================================

@Composable
private fun TopBar(
    isConnected: Boolean,
    scrollInfo: String,
    agentState: AgentState,
    focusedArea: ChatFocusArea,
    voiceState: VoiceInputState,
    sessionTitle: String?,
    isLoadingMoreHistory: Boolean = false,
    showWakeNotification: Boolean = false,
    wakeReason: String? = null,
    fontFamily: FontFamily,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val statusFontSize = (fontSize.value - 2).coerceAtLeast(8f).sp

    // Check if voice is active
    val isVoiceActive = voiceState is VoiceInputState.Listening ||
                        voiceState is VoiceInputState.Recognizing ||
                        voiceState is VoiceInputState.Processing ||
                        voiceState is VoiceInputState.Error

    // Get voice mode for display
    val voiceMode = when (voiceState) {
        is VoiceInputState.Listening -> voiceState.mode
        is VoiceInputState.Recognizing -> voiceState.mode
        is VoiceInputState.Processing -> voiceState.mode
        else -> null
    }

    // Animated dots for processing state
    val processingDots = if (voiceState is VoiceInputState.Processing) {
        var dotCount by remember { mutableIntStateOf(1) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(400)
                dotCount = (dotCount % 3) + 1
            }
        }
        ".".repeat(dotCount)
    } else {
        ""
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        // Connection dot + state label (left-aligned)
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u25CF",
                color = if (isConnected) HudColors.green else HudColors.error,
                fontSize = (statusFontSize.value + 2).sp
            )
            // Show voice state when active, wake notification, otherwise show agent state
            val stateLabel = when {
                showWakeNotification -> {
                    when (wakeReason) {
                        "stream_content" -> "\u26A1 streaming..."
                        "new_message" -> "\u26A1 new message"
                        "cron_message" -> "\u26A1 notification"
                        else -> "\u26A1 waking..."
                    }
                }
                voiceState is VoiceInputState.Listening -> {
                    val modeSuffix = if (voiceMode == RecognitionMode.OPENAI) " [AI]" else ""
                    "listening$modeSuffix..."
                }
                voiceState is VoiceInputState.Recognizing -> {
                    val modeSuffix = if (voiceMode == RecognitionMode.OPENAI) " [AI]" else ""
                    "recognizing$modeSuffix..."
                }
                voiceState is VoiceInputState.Processing -> {
                    val modeSuffix = if (voiceMode == RecognitionMode.OPENAI) " [AI]" else ""
                    "processing$modeSuffix $processingDots"
                }
                voiceState is VoiceInputState.Error -> "voice error"
                isLoadingMoreHistory -> "loading..."
                agentState == AgentState.IDLE -> if (isConnected) "connected" else "disconnected"
                agentState == AgentState.THINKING -> "thinking..."
                agentState == AgentState.STREAMING -> "streaming..."
                else -> ""
            }
            Text(
                text = stateLabel,
                color = when {
                    showWakeNotification -> HudColors.yellow  // Yellow for wake notification (attention-grabbing)
                    isVoiceActive && voiceMode == RecognitionMode.OPENAI -> Color(0xFF64B5F6)  // Light blue for OpenAI
                    isVoiceActive -> HudColors.yellow  // Yellow for device/fallback voice
                    isLoadingMoreHistory -> HudColors.cyan
                    else -> HudColors.dimText
                },
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )
        }

        // Session title (centered)
        if (!sessionTitle.isNullOrEmpty()) {
            Text(
                text = sessionTitle,
                color = HudColors.primaryText,
                fontSize = statusFontSize,
                fontFamily = fontFamily,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.45f)
            )
        }

        // Mode indicator + scroll info (right-aligned)
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (modeLabel, modeColor) = when (focusedArea) {
                ChatFocusArea.CONTENT -> "SCROLL" to HudColors.cyan
                ChatFocusArea.INPUT -> "INPUT" to HudColors.yellow
                ChatFocusArea.MENU -> "MENU" to HudColors.green
            }
            Text(
                text = modeLabel,
                color = modeColor,
                fontSize = statusFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = scrollInfo,
                color = HudColors.dimText,
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// CHAT CONTENT AREA
// ============================================================================

@Composable
private fun ChatContentArea(
    messages: List<DisplayMessage>,
    agentState: AgentState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily,
    alpha: Float,
    hasMoreHistory: Boolean = true,
    newPrependCount: Int = 0,
    modifier: Modifier = Modifier
) {
    // Auto-scroll to reveal the thinking indicator when it appears.
    // Uses a pixel-based scrollBy after a frame delay so the LazyColumn
    // has laid out the new item before we scroll.
    val isThinking = agentState == AgentState.THINKING
    LaunchedEffect(isThinking) {
        if (isThinking && messages.isNotEmpty()) {
            // Wait for the thinking indicator item to be composed and laid out
            delay(50)
            // Only auto-scroll if the user is near the bottom
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= messages.size - 2) {
                listState.animateScrollBy(500f)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .alpha(alpha)
    ) {
        if (messages.isEmpty() && agentState == AgentState.IDLE) {
            Text(
                text = "No messages yet",
                color = HudColors.dimText,
                fontSize = fontSize,
                fontFamily = fontFamily,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // "Beginning of conversation" marker (static, no displacement issues)
                if (!hasMoreHistory && messages.isNotEmpty()) {
                    item(key = "history_start") {
                        HistoryStartIndicator(
                            fontSize = fontSize,
                            fontFamily = fontFamily
                        )
                    }
                }

                itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
                    // Fade in newly prepended messages as they scroll into view
                    if (index < newPrependCount) {
                        val fadeAlpha = remember { Animatable(0.15f) }
                        LaunchedEffect(Unit) {
                            fadeAlpha.animateTo(1f, tween(400))
                        }
                        Box(modifier = Modifier.alpha(fadeAlpha.value)) {
                            ChatMessageItem(
                                message = message,
                                fontSize = fontSize,
                                fontFamily = fontFamily
                            )
                        }
                    } else {
                        ChatMessageItem(
                            message = message,
                            fontSize = fontSize,
                            fontFamily = fontFamily
                        )
                    }
                }

                // Thinking indicator (shown after last message when agent is thinking)
                if (agentState == AgentState.THINKING) {
                    item {
                        ThinkingIndicator(
                            fontSize = fontSize,
                            fontFamily = fontFamily
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: DisplayMessage,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily
) {
    val isUser = message.role == "user"
    val isStreaming = message.isStreaming

    // Blinking cursor for streaming
    val cursorVisible = if (isStreaming) {
        val infiniteTransition = rememberInfiniteTransition(label = "cursor")
        val cursorAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = tween(500)),
            label = "blink"
        )
        cursorAlpha > 0.5f
    } else {
        false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (isUser) {
                    it.padding(start = 40.dp)
                } else {
                    it.padding(end = 16.dp)
                }
            },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .let {
                    if (isUser) {
                        it.background(
                            HudColors.green.copy(alpha = 0.15f),
                            RoundedCornerShape(6.dp)
                        )
                    } else {
                        it
                    }
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Column {
                if (message.thumbnails.isNotEmpty()) {
                    PhotoThumbnailRow(thumbnails = message.thumbnails)
                }

                val displayText = if (message.content.isEmpty() && isStreaming) {
                    if (cursorVisible) "\u2588" else " "
                } else if (isStreaming && cursorVisible) {
                    "${message.content}\u2588"
                } else {
                    message.content
                }

                Text(
                    text = displayText,
                    color = if (isUser) HudColors.primaryText else HudColors.green,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    lineHeight = fontSize,
                    letterSpacing = 0.sp,
                    textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily
) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600)),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .padding(end = 16.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        Text(
            text = "...",
            color = HudColors.cyan,
            fontSize = (fontSize.value + 2).sp,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HistoryStartIndicator(
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "\u2500\u2500 beginning of conversation \u2500\u2500",
            color = HudColors.dimText,
            fontSize = fontSize,
            fontFamily = fontFamily
        )
    }
}

// ============================================================================
// PHOTO STRIP
// ============================================================================

private val greenColorMatrix = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
    0f, 0f, 0f, 0f, 0f,
    0.3f, 0.59f, 0.11f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f
)))


@Composable
private fun PhotoThumbnailRow(
    thumbnails: List<Bitmap>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        thumbnails.forEach { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(width = 24.dp, height = 18.dp)
                    .border(1.dp, HudColors.green.copy(alpha = 0.5f), RoundedCornerShape(1.dp)),
                contentScale = ContentScale.Crop,
                colorFilter = greenColorMatrix
            )
        }
    }
}

// ============================================================================
// INPUT STAGING AREA
// ============================================================================

/**
 * Combined input staging area.
 *
 * Layout (single line below text):
 *   [Photo1] [Photo2] ... [Photo4]  ←spacer→  [Clear] [Send]
 *
 * Clear and Send buttons are only shown when there is staged input (text or photos).
 *
 * `selectedIndex` maps into: photos (0..N-1), then CLEAR (N), SEND (N+1).
 */
@Composable
private fun InputStagingArea(
    text: String,
    showText: Boolean,
    photos: List<Bitmap>,
    selectedIndex: Int,
    isFocused: Boolean,
    isProcessing: Boolean,
    fontFamily: FontFamily,
    fontSize: androidx.compose.ui.unit.TextUnit,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    val commandFontSize = 8.sp  // Match menu bar fixed size
    val photoCount = photos.size
    val hasContent = text.isNotEmpty()

    // Blinking cursor for processing state
    val cursorVisible = if (isProcessing) {
        val infiniteTransition = rememberInfiniteTransition(label = "processingCursor")
        val cursorAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = tween(500)),
            label = "blink"
        )
        cursorAlpha > 0.5f
    } else {
        false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        // Staged text display (show when text is present OR when processing)
        if (showText || isProcessing) {
            val borderColor = if (isProcessing) {
                HudColors.cyan.copy(alpha = 0.6f)
            } else if (isFocused) {
                HudColors.yellow.copy(alpha = 0.6f)
            } else {
                HudColors.dimText.copy(alpha = 0.4f)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isProcessing) HudColors.cyan.copy(alpha = 0.05f)
                        else HudColors.green.copy(alpha = 0.08f),
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .heightIn(min = 20.dp, max = 60.dp)
            ) {
                val displayText = if (isProcessing) {
                    val cursor = if (cursorVisible) "\u2588" else " "
                    if (text.isNotEmpty()) "$text $cursor" else cursor
                } else {
                    text.ifEmpty { "..." }
                }

                val textColor = if (isProcessing && text.isEmpty()) {
                    HudColors.cyan
                } else if (text.isEmpty()) {
                    HudColors.dimText
                } else {
                    HudColors.primaryText
                }

                Text(
                    text = displayText,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    lineHeight = fontSize,
                    letterSpacing = 0.sp,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
        }

        // Single-line: photos (left) + buttons (right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Photo thumbnails — left-aligned
            photos.forEachIndexed { index, bitmap ->
                val isSelected = index == selectedIndex && isFocused
                Box(modifier = Modifier.padding(end = 4.dp)) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Photo ${index + 1}",
                        modifier = Modifier
                            .size(width = 36.dp, height = 27.dp)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) HudColors.green else HudColors.dimText,
                                shape = RoundedCornerShape(2.dp)
                            ),
                        contentScale = ContentScale.Crop,
                        colorFilter = greenColorMatrix
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 27.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "\u2715",
                                color = HudColors.green,
                                fontSize = 12.sp,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (hasContent) {
                Spacer(modifier = Modifier.weight(1f))

                // Clear button
                val clearIndex = photoCount  // index right after photos
                val clearSelected = selectedIndex == clearIndex && isFocused
                Box(
                    modifier = Modifier
                        .background(
                            if (clearSelected) HudColors.green.copy(alpha = 0.3f) else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            width = if (clearSelected) 1.dp else 0.dp,
                            color = if (clearSelected) HudColors.green else Color.Transparent,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = InputActionItem.CLEAR.icon,
                            color = if (clearSelected) HudColors.green else HudColors.primaryText,
                            fontSize = (commandFontSize.value + 2).sp,
                            fontFamily = fontFamily
                        )
                        Text(
                            text = InputActionItem.CLEAR.label,
                            color = if (clearSelected) HudColors.green else HudColors.dimText,
                            fontSize = commandFontSize,
                            fontFamily = fontFamily,
                            fontWeight = if (clearSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Send button
                val sendIndex = photoCount + 1  // last item
                val sendSelected = selectedIndex == sendIndex && isFocused
                Box(
                    modifier = Modifier
                        .background(
                            if (sendSelected) HudColors.green.copy(alpha = 0.3f) else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            width = if (sendSelected) 1.dp else 0.dp,
                            color = if (sendSelected) HudColors.green else Color.Transparent,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = InputActionItem.SEND.icon,
                            color = if (sendSelected) HudColors.green else HudColors.primaryText,
                            fontSize = (commandFontSize.value + 2).sp,
                            fontFamily = fontFamily
                        )
                        Text(
                            text = InputActionItem.SEND.label,
                            color = if (sendSelected) HudColors.green else HudColors.dimText,
                            fontSize = commandFontSize,
                            fontFamily = fontFamily,
                            fontWeight = if (sendSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// MENU BAR
// ============================================================================

@Composable
private fun ChatMenuBar(
    selectedIndex: Int,
    isFocused: Boolean,
    hudPosition: HudPosition,
    batteryLevel: Int?,
    batteryCharging: Boolean,
    currentTime: String,
    fontFamily: FontFamily,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    val commandFontSize = 8.sp  // Fixed size — FONT only affects content
    val items = MenuBarItem.entries
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex && isFocused

                // Dynamic icon for SIZE: shows what next position will look like
                val displayIcon = if (item == MenuBarItem.SIZE) {
                    when (hudPosition) {
                        HudPosition.FULL -> "\u2584"        // ▄ next: bottom half
                        HudPosition.BOTTOM_HALF -> "\u2580" // ▀ next: top half
                        HudPosition.TOP_HALF -> "\u2588"    // █ next: full
                    }
                } else {
                    item.icon
                }

                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) HudColors.green.copy(alpha = 0.3f) else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            width = if (isSelected) 1.dp else 0.dp,
                            color = if (isSelected) HudColors.green else Color.Transparent,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayIcon,
                            color = if (isSelected) HudColors.green else HudColors.primaryText,
                            fontSize = (commandFontSize.value + 2).sp,
                            fontFamily = fontFamily
                        )
                        Text(
                            text = item.label,
                            color = if (isSelected) HudColors.green else HudColors.dimText,
                            fontSize = commandFontSize,
                            fontFamily = fontFamily,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Current time (HH:MM, 24-hour format)
        if (currentTime.isNotEmpty()) {
            Text(
                text = currentTime,
                color = HudColors.dimText,
                fontSize = commandFontSize,
                fontFamily = fontFamily,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // Battery indicator (bottom-right, only shown when available)
        if (batteryLevel != null) {
            Text(
                text = "${if (batteryCharging) "\u26A1" else "\uD83D\uDD0B"}${batteryLevel}%",  // ⚡ or 🔋
                color = if (batteryLevel <= 15) HudColors.error else HudColors.dimText,
                fontSize = commandFontSize,
                fontFamily = fontFamily,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

// ============================================================================
// SESSION PICKER OVERLAY
// ============================================================================

@Composable
private fun SessionPickerOverlay(
    sessions: List<SessionPickerInfo>,
    currentSessionKey: String?,
    selectedIndex: Int,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Keep selected item visible
    LaunchedEffect(selectedIndex) {
        if (sessions.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxHeight()
                .padding(24.dp)
        ) {
            Text(
                text = "SELECT SESSION",
                color = HudColors.cyan,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (sessions.isEmpty()) {
                Text(
                    text = "No sessions available",
                    color = HudColors.dimText,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    itemsIndexed(sessions) { index, session ->
                        val isSelected = index == selectedIndex
                        val isCurrent = session.key == currentSessionKey
                        val isNewSession = session.key == "__new_session__"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) HudColors.green.copy(alpha = 0.3f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (isSelected) "\u25B6" else " ",
                                    color = if (isNewSession) HudColors.cyan else HudColors.green,
                                    fontSize = 14.sp,
                                    fontFamily = fontFamily
                                )
                                Text(
                                    text = session.name,
                                    color = when {
                                        isNewSession && isSelected -> HudColors.cyan
                                        isNewSession -> HudColors.cyan
                                        isSelected -> HudColors.green
                                        else -> HudColors.primaryText
                                    },
                                    fontSize = 14.sp,
                                    fontFamily = fontFamily,
                                    fontWeight = if (isCurrent || isNewSession) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (session.updatedAt != null) {
                                    Text(
                                        text = formatRelativeTime(session.updatedAt),
                                        color = if (isSelected) Color.Black else HudColors.dimText,
                                        fontSize = 10.sp,
                                        fontFamily = fontFamily,
                                        maxLines = 1
                                    )
                                }
                                if (isCurrent) {
                                    Text(
                                        text = "\u25CF",
                                        color = HudColors.cyan,
                                        fontSize = 12.sp
                                    )
                                } else if (session.hasUnread) {
                                    Text(
                                        text = "\u25CF",
                                        color = HudColors.green,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "\u2191\u2193 Navigate  TAP Select  2\u00D7TAP Cancel",
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// MORE MENU OVERLAY
// ============================================================================

@Composable
private fun MoreMenuOverlay(
    selectedIndex: Int,
    currentDisplaySize: HudDisplaySize,
    ttsEnabled: Boolean,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val items = MoreMenuItem.entries

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "MORE",
                color = HudColors.green,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEachIndexed { itemIndex, item ->
                    val isSelected = itemIndex == selectedIndex
                    val isActive = when (item) {
                        MoreMenuItem.VOICE -> ttsEnabled
                        else -> item.displaySize == currentDisplaySize
                    }

                    // Dynamic label for VOICE item
                    val displayLabel = when (item) {
                        MoreMenuItem.VOICE -> if (ttsEnabled) "Voice On" else "Voice Off"
                        else -> item.label
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Selection indicator
                        Text(
                            text = "\u25B6",
                            color = if (isSelected) HudColors.green else Color.Transparent,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                        // Active checkmark for font size items and voice toggle
                        Text(
                            text = if (isActive) "\u2713" else " ",
                            color = HudColors.green,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                        // Icon and label rendered at the item's own font size for font entries
                        val itemFontSize = item.displaySize?.fontSizeSp?.sp ?: 14.sp
                        Text(
                            text = item.icon,
                            color = if (isSelected) HudColors.cyan else HudColors.primaryText,
                            fontSize = itemFontSize,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = displayLabel,
                            color = if (isSelected) HudColors.green else HudColors.dimText,
                            fontSize = itemFontSize,
                            fontFamily = fontFamily
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "\u2191\u2193 Navigate  TAP Select  2\u00D7TAP Cancel",
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// SLASH COMMAND OVERLAY
// ============================================================================

@Composable
private fun SlashCommandOverlay(
    selectedIndex: Int,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Keep selected item visible
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(selectedIndex)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "SLASH COMMANDS",
                color = HudColors.cyan,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(SLASH_COMMANDS) { index, item ->
                    val isSelected = index == selectedIndex

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) HudColors.green.copy(alpha = 0.3f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSelected) "\u25B6" else " ",
                            color = HudColors.green,
                            fontSize = 12.sp,
                            fontFamily = fontFamily
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.command,
                            color = if (isSelected) HudColors.green else HudColors.primaryText,
                            fontSize = 12.sp,
                            fontFamily = fontFamily,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(80.dp)
                        )
                        Text(
                            text = item.description,
                            color = HudColors.dimText,
                            fontSize = 10.sp,
                            fontFamily = fontFamily,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "\u2191\u2193 Navigate  TAP Select  2\u00D7TAP Cancel",
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// EXIT CONFIRMATION OVERLAY
// ============================================================================

@Composable
private fun ExitConfirmOverlay(
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "EXIT",
                color = HudColors.error,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "2\u00D7TAP again to exit",
                color = HudColors.primaryText,
                fontSize = 14.sp,
                fontFamily = fontFamily,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Any other input to continue",
                color = HudColors.dimText,
                fontSize = 12.sp,
                fontFamily = fontFamily,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ============================================================================
// HUD COLORS
// ============================================================================

object HudColors {
    val primaryText = Color(0xFF00FF00)    // Bright green
    val cyan = Color(0xFF00FFFF)           // Cyan
    val green = Color(0xFF39FF14)          // Neon green
    val yellow = Color(0xFFFFFF00)         // Yellow
    val dimText = Color(0xFF666666)        // Dimmed
    val error = Color(0xFFFF4444)          // Red
}
