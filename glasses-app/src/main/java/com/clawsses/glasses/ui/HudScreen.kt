package com.clawsses.glasses.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * The two focus areas of the chat UI.
 * Input is voice-only (long-press) so it doesn't need its own focus.
 */
enum class ChatFocusArea {
    CONTENT,  // Chat messages (scrollable)
    MENU      // Bottom menu bar
}

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
    SESSION("\u25CE", "Sess"),
    SIZE("\u2588", "Size"),  // Icon overridden dynamically based on next HudPosition
    FONT("Aa", "Font"),
    MORE("\u2026", "More"),
    EXIT("\u2715", "Exit")
}

/**
 * Items available in the MORE menu
 */
enum class MoreMenuItem(val icon: String, val label: String) {
    SLASH("/", "Slash Cmds"),
    PHOTO("\uD83D\uDCF7", "Photo"),
    REMOVE_PHOTO("\u2716", "Rm Photo")
}

/**
 * A display-ready chat message for the HUD.
 * Stores raw content; wrapping is computed at render time.
 */
data class DisplayMessage(
    val id: String,
    val role: String,  // "user" or "assistant"
    val content: String,
    val isStreaming: Boolean = false
)

/**
 * Voice input states for HUD display
 */
sealed class VoiceInputState {
    object Idle : VoiceInputState()
    object Listening : VoiceInputState()
    object Recognizing : VoiceInputState()
    data class Error(val message: String) : VoiceInputState()
}

/**
 * Session info for session picker
 */
data class SessionPickerInfo(
    val key: String,
    val name: String,
    val kind: String? = null
)

/**
 * Chat HUD state — replaces the old TerminalState
 */
data class ChatHudState(
    val messages: List<DisplayMessage> = emptyList(),
    val scrollPosition: Int = 0,
    val scrollTrigger: Int = 0,
    val isScrolledToEnd: Boolean = false,
    val inputText: String = "",
    val hasPhoto: Boolean = false,
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
    val selectedSessionIndex: Int = 0,
    // More menu
    val showMoreMenu: Boolean = false,
    val selectedMoreIndex: Int = 0,
    // Slash command menu
    val showSlashMenu: Boolean = false,
    val selectedSlashIndex: Int = 0
) {
    /** Total number of messages */
    val totalMessages: Int get() = messages.size
}

// Common slash commands
val SLASH_COMMANDS = listOf("/model", "/clear", "/compact", "/help", "/status")

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
                // This ensures the last message is fully visible even with large
                // fonts or half-screen mode.
                listState.animateScrollToItem(state.scrollPosition, Int.MAX_VALUE)
            } else {
                listState.animateScrollToItem(state.scrollPosition)
            }
        }
    }

    // Focus brightness — only CONTENT and MENU can be focused
    val contentFocused = state.focusedArea == ChatFocusArea.CONTENT
    val menuFocused = state.focusedArea == ChatFocusArea.MENU

    val contentAlpha = focusBrightness(contentFocused)
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
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // MENU BAR
                ChatMenuBar(
                    selectedIndex = state.menuBarIndex,
                    isFocused = menuFocused,
                    hudPosition = state.hudPosition,
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
    fontFamily: FontFamily,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val statusFontSize = (fontSize.value - 2).coerceAtLeast(8f).sp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Connection dot + state label
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u25CF",
                color = if (isConnected) HudColors.green else HudColors.error,
                fontSize = (statusFontSize.value + 2).sp
            )
            val stateLabel = when (agentState) {
                AgentState.IDLE -> if (isConnected) "connected" else "disconnected"
                AgentState.THINKING -> "thinking..."
                AgentState.STREAMING -> "streaming..."
            }
            Text(
                text = stateLabel,
                color = HudColors.dimText,
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )
        }

        // Mode indicator + scroll info
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (modeLabel, modeColor) = when (focusedArea) {
                ChatFocusArea.CONTENT -> "SCROLL" to HudColors.cyan
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
    modifier: Modifier = Modifier
) {
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
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(messages) { _, message ->
                    ChatMessageItem(
                        message = message,
                        fontSize = fontSize,
                        fontFamily = fontFamily
                    )
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

// ============================================================================
// MENU BAR
// ============================================================================

@Composable
private fun ChatMenuBar(
    selectedIndex: Int,
    isFocused: Boolean,
    hudPosition: HudPosition,
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
            .alpha(alpha)
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
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
                sessions.forEachIndexed { index, session ->
                    val isSelected = index == selectedIndex
                    val isCurrent = session.key == currentSessionKey

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
                                color = HudColors.green,
                                fontSize = 14.sp,
                                fontFamily = fontFamily
                            )
                            Text(
                                text = session.name,
                                color = if (isSelected) HudColors.green else HudColors.primaryText,
                                fontSize = 14.sp,
                                fontFamily = fontFamily,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                        if (isCurrent) {
                            Text(
                                text = "\u25CF",
                                color = HudColors.cyan,
                                fontSize = 12.sp
                            )
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
                items.chunked(2).forEach { rowItems ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowItems.forEach { item ->
                            val itemIndex = items.indexOf(item)
                            val isSelected = itemIndex == selectedIndex

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "\u25B6",
                                    color = if (isSelected) HudColors.green else Color.Transparent,
                                    fontSize = 14.sp,
                                    fontFamily = fontFamily
                                )
                                Text(
                                    text = item.icon,
                                    color = if (isSelected) HudColors.cyan else HudColors.primaryText,
                                    fontSize = 14.sp,
                                    fontFamily = fontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = item.label,
                                    color = if (isSelected) HudColors.green else HudColors.dimText,
                                    fontSize = 12.sp,
                                    fontFamily = fontFamily
                                )
                            }
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
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
// SLASH COMMAND OVERLAY
// ============================================================================

@Composable
private fun SlashCommandOverlay(
    selectedIndex: Int,
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
                text = "SLASH COMMANDS",
                color = HudColors.cyan,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            SLASH_COMMANDS.forEachIndexed { index, command ->
                val isSelected = index == selectedIndex

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) HudColors.green.copy(alpha = 0.3f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSelected) "\u25B6" else " ",
                        color = HudColors.green,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = command,
                        color = if (isSelected) HudColors.green else HudColors.primaryText,
                        fontSize = 14.sp,
                        fontFamily = fontFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
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
