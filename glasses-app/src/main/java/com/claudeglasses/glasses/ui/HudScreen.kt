package com.claudeglasses.glasses.ui

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.claudeglasses.glasses.R
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Display size presets for the 480x640 portrait HUD
 * Each preset optimizes for different character counts vs readability
 */
enum class HudDisplaySize(val fontSizeSp: Int, val cols: Int, val rows: Int, val label: String) {
    COMPACT(10, 64, 40, "Compact"),     // Max characters, smaller text
    NORMAL(12, 42, 33, "Normal"),       // Balanced
    COMFORTABLE(14, 35, 28, "Comfortable"), // Larger, easier to read
    LARGE(16, 30, 24, "Large")          // Maximum readability
}

/**
 * Focus hierarchy levels for the glasses UI
 */
enum class FocusLevel {
    AREA_SELECT,   // Level 0: Swipe between areas
    AREA_FOCUSED,  // Level 1: Within an area
    FINE_CONTROL   // Level 2: Character/selection mode
}

/**
 * The three main areas of the UI
 */
enum class FocusArea {
    CONTENT,  // Terminal output (scrollable)
    INPUT,    // Claude's prompts/questions
    COMMAND   // Quick action bar
}

/**
 * Content area sub-modes for progressive interaction depth
 */
enum class ContentMode {
    PAGE,       // Swipe to scroll by page
    LINE,       // Swipe to move line by line
    CHARACTER,  // Swipe to move character by character
    SELECTION   // Selecting text
}

/**
 * Quick commands available in the command bar
 */
enum class QuickCommand(val icon: String, val label: String, val key: String) {
    ESCAPE("✕", "ESC", "escape"),
    CLEAR("⌫", "CLEAR", "ctrl_u"),
    ENTER("↵", "ENTER", "enter"),
    SHIFT_TAB("⇤", "S-TAB", "shift_tab"),
    MORE("…", "MORE", "more_menu"),
    SESSION("◎", "SESSION", "list_sessions")
}

/**
 * Items available in the MORE menu
 */
enum class MoreMenuItem(val icon: String, val label: String, val key: String) {
    SLASH("/", "Slash", "slash"),
    TAB("⇥", "Tab", "tab"),
    BACKSLASH("\\", "Backslash", "backslash"),
    CTRL_B("^B", "Ctrl-B", "ctrl_b"),
    CTRL_O("^O", "Ctrl-O", "ctrl_o"),
    CTRL_C("^C", "Ctrl-C", "ctrl_c")
}

/**
 * Hierarchical focus state for the glasses UI
 */
data class FocusState(
    val level: FocusLevel = FocusLevel.AREA_SELECT,
    val focusedArea: FocusArea = FocusArea.CONTENT,
    val contentMode: ContentMode = ContentMode.PAGE,
    val selectedLine: Int = 0,
    val cursorPosition: Int = 0,
    val selectionStart: Int? = null,
    val commandIndex: Int = 0,
    val inputOptionIndex: Int = 0,
    val pendingInput: String = ""  // Text copied from selection or voice
)

/**
 * Detected prompt types from Claude's output
 */
sealed class DetectedPrompt {
    data class MultipleChoice(val options: List<String>, val selectedIndex: Int) : DetectedPrompt()
    data class Confirmation(val yesDefault: Boolean) : DetectedPrompt()
    data class TextInput(val placeholder: String) : DetectedPrompt()
    object None : DetectedPrompt()
}

/**
 * Claude Code interaction modes detected from status line
 */
enum class ClaudeMode {
    NORMAL,      // Regular mode
    ACCEPT_EDITS, // "accept edits on" - reviewing changes
    PLAN         // "plan mode on" - planning mode
}

/**
 * Detected input section from terminal lines.
 * The input section is identified by a horizontal line followed by the ❯ prompt.
 */
data class InputSection(
    val lines: List<String> = emptyList(),           // Input lines (prompt area, excludes status)
    val lineColors: List<LineColorType> = emptyList(),
    val statusLine: String? = null,                   // Status line shown below input (tmux info)
    val claudeMode: ClaudeMode = ClaudeMode.NORMAL,  // Detected Claude Code mode
    val startIndex: Int = -1  // Index in terminal lines where input section starts (after horizontal line)
) {
    val isEmpty: Boolean get() = lines.isEmpty()
    val hasPrompt: Boolean get() = lines.any { it.contains("❯") }
}

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
 * Terminal state data class with hierarchical focus model
 */
/**
 * Line color types from server ANSI parsing
 */
enum class LineColorType {
    ADDITION,   // Green - added lines in diff
    DELETION,   // Red - removed lines in diff
    HEADER,     // Cyan - diff headers
    NORMAL      // Default text
}

data class TerminalState(
    val lines: List<String> = emptyList(),
    val lineColors: List<LineColorType> = emptyList(),  // Color info from server
    val scrollPosition: Int = 0,
    val scrollTrigger: Int = 0,
    val cursorLine: Int = 0,
    val promptLineIndex: Int = -1,  // Line index of the ❯ prompt (for highlighting)
    val focus: FocusState = FocusState(),
    val detectedPrompt: DetectedPrompt = DetectedPrompt.None,
    val inputSection: InputSection = InputSection(),  // Detected input section at bottom
    val displaySize: HudDisplaySize = HudDisplaySize.NORMAL,
    val isConnected: Boolean = false,
    val voiceState: VoiceInputState = VoiceInputState.Idle,
    val voiceText: String = "",
    // Session picker state
    val showSessionPicker: Boolean = false,
    val availableSessions: List<String> = emptyList(),
    val currentSession: String = "",
    val selectedSessionIndex: Int = 0,
    // Kill session confirmation state
    val showKillConfirmation: Boolean = false,
    val sessionToKill: String = "",
    val killConfirmSelected: Int = 0,  // 0 = Cancel (default), 1 = Kill
    // More menu state
    val showMoreMenu: Boolean = false,
    val selectedMoreIndex: Int = 0
) {
    val visibleLines: Int get() = displaySize.rows

    // Content lines = all lines except input section
    val contentLines: List<String> get() = if (inputSection.startIndex >= 0) {
        lines.take(inputSection.startIndex)
    } else {
        lines
    }

    val contentLineColors: List<LineColorType> get() = if (inputSection.startIndex >= 0) {
        lineColors.take(inputSection.startIndex)
    } else {
        lineColors
    }

    // Convenience properties for focus state
    val focusLevel: FocusLevel get() = focus.level
    val focusedArea: FocusArea get() = focus.focusedArea
    val contentMode: ContentMode get() = focus.contentMode

    // Legacy mode compatibility (for gradual migration)
    @Deprecated("Use focus.focusedArea instead")
    val mode: LegacyMode get() = when {
        focus.focusedArea == FocusArea.COMMAND -> LegacyMode.COMMAND
        focus.focusedArea == FocusArea.INPUT -> LegacyMode.NAVIGATE
        else -> LegacyMode.SCROLL
    }

    @Deprecated("Use FocusArea instead")
    enum class LegacyMode { SCROLL, NAVIGATE, COMMAND }
}

// ============================================================================
// BRIGHTNESS ANIMATION UTILITIES
// ============================================================================

/**
 * Calculate brightness alpha based on focus state
 */
@Composable
fun focusBrightness(isFocused: Boolean, isPulsing: Boolean = false): Float {
    val baseAlpha = if (isFocused) 1f else 0.4f

    return if (isPulsing && isFocused) {
        // Gentle pulsing for focused area at Level 0
        val infiniteTransition = rememberInfiniteTransition(label = "focus")
        infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(600)),
            label = "pulse"
        ).value
    } else {
        animateFloatAsState(
            targetValue = baseAlpha,
            animationSpec = tween(200),
            label = "brightness"
        ).value
    }
}

// ============================================================================
// MAIN HUD SCREEN
// ============================================================================

/**
 * HUD-optimized terminal display for Rokid Glasses
 *
 * Design: Hierarchical focus-based interaction with three areas:
 * - Content Area: Terminal output (scrollable, selectable)
 * - Input Area: Claude's prompts/questions
 * - Command Bar: Quick action buttons
 *
 * Focus is indicated via brightness (40% unfocused, 100% focused)
 */
@Composable
fun HudScreen(
    state: TerminalState,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Use JetBrains Mono for proper monospace box-drawing characters
    val monoFontFamily = remember { FontFamily(Font(R.font.jetbrains_mono)) }

    // Double-tap detection state
    var lastTapTime by remember { mutableStateOf(0L) }
    var pendingTapJob by remember { mutableStateOf<Job?>(null) }
    val doubleTapTimeout = 300L

    // Auto-scroll when position or trigger changes
    // Use contentLines (not lines) since that's what the LazyColumn displays
    LaunchedEffect(state.scrollPosition, state.scrollTrigger) {
        if (state.contentLines.isNotEmpty() && state.scrollPosition < state.contentLines.size) {
            Log.d("HudScreen", "Auto-scrolling to position ${state.scrollPosition}")
            listState.animateScrollToItem(state.scrollPosition)
        }
    }

    // Calculate focus-based brightness for each area
    val isAreaSelectLevel = state.focusLevel == FocusLevel.AREA_SELECT
    val contentFocused = state.focusedArea == FocusArea.CONTENT
    val inputFocused = state.focusedArea == FocusArea.INPUT
    val commandFocused = state.focusedArea == FocusArea.COMMAND

    val contentAlpha = focusBrightness(contentFocused, isPulsing = false)
    val inputAlpha = focusBrightness(inputFocused, isPulsing = isAreaSelectLevel)
    val commandAlpha = focusBrightness(commandFocused, isPulsing = isAreaSelectLevel)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        Log.d("HudScreen", "Compose onTap detected at $it")
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < doubleTapTimeout) {
                            Log.d("HudScreen", "Double tap detected")
                            pendingTapJob?.cancel()
                            pendingTapJob = null
                            lastTapTime = 0L
                            onDoubleTap()
                        } else {
                            lastTapTime = now
                            pendingTapJob?.cancel()
                            pendingTapJob = scope.launch {
                                delay(doubleTapTimeout)
                                if (lastTapTime == now) {
                                    Log.d("HudScreen", "Single tap confirmed")
                                    onTap()
                                }
                            }
                        }
                    },
                    onLongPress = {
                        Log.d("HudScreen", "Long press detected at $it")
                        pendingTapJob?.cancel()
                        onLongPress()
                    }
                )
            }
    ) {
        // Calculate font size to fit 67 columns in available width
        val targetColumns = 67
        val referenceText = "M".repeat(targetColumns)
        val referenceFontSize = 12.sp

        val fontSize = remember(maxWidth, monoFontFamily) {
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

        // Three-area vertical layout with brightness-based focus
        Column(modifier = Modifier.fillMaxSize()) {
            // Status bar (always dim, informational)
            StatusBar(
                focusedArea = state.focusedArea,
                focusLevel = state.focusLevel,
                lineInfo = "${state.scrollPosition + 1}/${state.lines.size}",
                isConnected = state.isConnected,
                displaySize = state.displaySize,
                fontFamily = monoFontFamily
            )

            Spacer(modifier = Modifier.height(4.dp))

            // CONTENT AREA - Terminal output (weight: fills remaining space)
            // Uses contentLines which excludes the input section
            ContentArea(
                lines = state.contentLines,
                lineColors = state.contentLineColors,
                listState = listState,
                cursorLine = state.cursorLine,
                promptLineIndex = -1,  // Prompt is now in input section, not content
                showPromptHighlight = false,
                contentMode = state.contentMode,
                selectedLine = state.focus.selectedLine,
                cursorPosition = state.focus.cursorPosition,
                selectionStart = state.focus.selectionStart,
                fontSize = fontSize,
                fontFamily = monoFontFamily,
                alpha = contentAlpha,
                isFocused = contentFocused && state.focusLevel != FocusLevel.AREA_SELECT,
                modifier = Modifier.weight(1f)
            )

            // Calculate if at bottom (used for hiding input area and showing hints)
            val isAtBottom = state.scrollPosition >= maxOf(0, state.contentLines.size - state.visibleLines)

            // INPUT AREA - Shows detected input section from terminal
            // Hidden when scrolled up to give more space for content
            // Limited to ensure at least 1 content line + status bar + command bar remain visible
            // Reserve: 2 lines for status, 1 minimum content, 2 lines for command bar = 5 lines
            val maxInputLines = (state.visibleLines - 5).coerceIn(3, 10)

            AnimatedVisibility(
                visible = isAtBottom || inputFocused,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    InputArea(
                        inputSection = state.inputSection,
                        fontSize = fontSize,
                        fontFamily = monoFontFamily,
                        alpha = inputAlpha,
                        isFocused = inputFocused && state.focusLevel != FocusLevel.AREA_SELECT,
                        maxLines = maxInputLines
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // COMMAND BAR or HINTS depending on focus
            val showScrollHints = contentFocused && state.focusLevel == FocusLevel.AREA_FOCUSED && !isAtBottom
            val showInputHints = inputFocused && state.focusLevel == FocusLevel.AREA_FOCUSED

            when {
                showScrollHints -> {
                    // Show hints when in SCROLL mode and not at bottom
                    ScrollModeHints(
                        displaySize = state.displaySize,
                        fontFamily = monoFontFamily
                    )
                }
                showInputHints -> {
                    // Show hints when in INPUT mode
                    InputModeHints(
                        displaySize = state.displaySize,
                        fontFamily = monoFontFamily
                    )
                }
                else -> {
                    // Show command bar
                    CommandBar(
                        commands = QuickCommand.values().toList(),
                        selectedIndex = state.focus.commandIndex,
                        claudeMode = state.inputSection.claudeMode,
                        displaySize = state.displaySize,
                        fontFamily = monoFontFamily,
                        alpha = commandAlpha,
                        isFocused = commandFocused && state.focusLevel != FocusLevel.AREA_SELECT
                    )
                }
            }
        }

        // Voice input overlay (shown when voice recognition is active)
        AnimatedVisibility(
            visible = state.voiceState !is VoiceInputState.Idle,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            VoiceInputOverlay(
                voiceState = state.voiceState,
                voiceText = state.voiceText,
                fontFamily = monoFontFamily
            )
        }

        // Session picker overlay
        AnimatedVisibility(
            visible = state.showSessionPicker,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SessionPickerOverlay(
                sessions = state.availableSessions,
                currentSession = state.currentSession,
                selectedIndex = state.selectedSessionIndex,
                fontFamily = monoFontFamily
            )
        }

        // Kill session confirmation dialog
        AnimatedVisibility(
            visible = state.showKillConfirmation,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            KillSessionConfirmDialog(
                sessionName = state.sessionToKill,
                selectedOption = state.killConfirmSelected,
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
    }
}

// ============================================================================
// CONTENT AREA
// ============================================================================

@Composable
private fun ContentArea(
    lines: List<String>,
    lineColors: List<LineColorType>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    cursorLine: Int,
    promptLineIndex: Int,
    showPromptHighlight: Boolean,
    contentMode: ContentMode,
    selectedLine: Int,
    cursorPosition: Int,
    selectionStart: Int?,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily,
    alpha: Float,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
    ) {
        if (lines.isEmpty()) {
            Text(
                text = "Waiting for connection...",
                color = HudColors.dimText,
                fontSize = fontSize,
                fontFamily = fontFamily,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(lines) { index, line ->
                    val isHighlighted = when (contentMode) {
                        ContentMode.LINE, ContentMode.CHARACTER, ContentMode.SELECTION ->
                            index == selectedLine && isFocused
                        else -> false
                    }
                    val isCurrentLine = index == cursorLine
                    // Only show prompt highlight when INPUT area is active
                    val isPromptLine = index == promptLineIndex && showPromptHighlight
                    val lineColor = lineColors.getOrNull(index) ?: LineColorType.NORMAL

                    ContentLine(
                        text = line,
                        lineIndex = index,
                        lineColor = lineColor,
                        isCurrentLine = isCurrentLine,
                        isPromptLine = isPromptLine,
                        isHighlighted = isHighlighted,
                        contentMode = contentMode,
                        cursorPosition = if (isHighlighted) cursorPosition else null,
                        selectionStart = if (isHighlighted) selectionStart else null,
                        fontSize = fontSize,
                        fontFamily = fontFamily
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentLine(
    text: String,
    lineIndex: Int,
    lineColor: LineColorType,
    isCurrentLine: Boolean,
    isPromptLine: Boolean,
    isHighlighted: Boolean,
    contentMode: ContentMode,
    cursorPosition: Int?,
    selectionStart: Int?,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily
) {
    // Use server-provided line color for monochrome styling
    // Prompt line gets special highlight to show connection with input area
    val backgroundColor = when {
        isHighlighted -> HudColors.green.copy(alpha = 0.3f)
        isPromptLine -> HudColors.yellow.copy(alpha = 0.15f)  // Subtle yellow highlight for prompt
        lineColor == LineColorType.ADDITION -> HudColors.green.copy(alpha = 0.15f)
        lineColor == LineColorType.DELETION -> Color.Transparent
        else -> Color.Transparent
    }

    val textColor = when {
        isHighlighted -> HudColors.green
        isPromptLine -> HudColors.yellow  // Yellow for prompt line to match INPUT area color
        isCurrentLine -> HudColors.cyan
        lineColor == LineColorType.ADDITION -> HudColors.green  // Bright green for additions
        lineColor == LineColorType.DELETION -> HudColors.dimText  // Dim for deletions
        lineColor == LineColorType.HEADER -> HudColors.cyan  // Cyan for diff headers
        else -> HudColors.primaryText
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        // For character/selection mode, we could render character-by-character
        // For now, show the line with visual indication of cursor position
        if (isHighlighted && contentMode == ContentMode.CHARACTER && cursorPosition != null) {
            // Show cursor as inverse character
            Row {
                val beforeCursor = text.take(cursorPosition)
                val atCursor = text.getOrNull(cursorPosition)?.toString() ?: " "
                val afterCursor = text.drop(cursorPosition + 1)

                Text(
                    text = beforeCursor,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp
                )
                Text(
                    text = atCursor,
                    color = Color.Black,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp,
                    modifier = Modifier.background(HudColors.green)
                )
                Text(
                    text = afterCursor,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp
                )
            }
        } else if (isHighlighted && contentMode == ContentMode.SELECTION && selectionStart != null && cursorPosition != null) {
            // Show selection range
            val start = minOf(selectionStart, cursorPosition)
            val end = maxOf(selectionStart, cursorPosition)
            Row {
                val beforeSelection = text.take(start)
                val selected = text.substring(start, minOf(end + 1, text.length))
                val afterSelection = text.drop(end + 1)

                Text(
                    text = beforeSelection,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp
                )
                Text(
                    text = selected,
                    color = Color.Black,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp,
                    modifier = Modifier.background(HudColors.cyan)
                )
                Text(
                    text = afterSelection,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = 0.sp
                )
            }
        } else {
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize,
                fontFamily = fontFamily,
                lineHeight = fontSize,
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ============================================================================
// INPUT AREA
// ============================================================================

/**
 * Input area showing the detected input section from terminal lines.
 * Displays actual terminal content instead of a replicated prompt.
 * Status line (tmux info) is shown below the input box.
 *
 * The input area is limited to maxLines to ensure content area and command bar
 * remain visible. When truncated, shows the last maxLines of input.
 */
@Composable
private fun InputArea(
    inputSection: InputSection,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily,
    alpha: Float,
    isFocused: Boolean,
    maxLines: Int = 8,  // Default max lines for input area
    modifier: Modifier = Modifier
) {
    // Show placeholder when no input section detected
    if (inputSection.isEmpty) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(20.dp)
                .alpha(alpha)
                .border(1.dp, HudColors.dimText.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "No input",
                color = HudColors.dimText,
                fontSize = fontSize,
                fontFamily = fontFamily
            )
        }
        return
    }

    // Limit lines shown to maxLines, taking the last lines if truncated
    val isTruncated = inputSection.lines.size > maxLines
    val visibleLines = if (isTruncated) {
        inputSection.lines.takeLast(maxLines)
    } else {
        inputSection.lines
    }
    val visibleLineColors = if (isTruncated) {
        inputSection.lineColors.takeLast(maxLines)
    } else {
        inputSection.lineColors
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Input box with prompt lines
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(alpha)
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) HudColors.green else HudColors.dimText.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            // Input lines
            Column(modifier = Modifier.fillMaxWidth()) {
                // Show truncation indicator if there are more lines above
                if (isTruncated) {
                    Text(
                        text = "···",
                        color = HudColors.dimText,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        letterSpacing = 0.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                visibleLines.forEachIndexed { index, line ->
                    val lineColor = visibleLineColors.getOrNull(index) ?: LineColorType.NORMAL

                    // Determine text color based on focus state and line content
                    val textColor = when {
                        !isFocused -> HudColors.dimText  // Grey when not focused
                        line.contains("❯") -> HudColors.cyan  // Prompt line gets cyan
                        lineColor == LineColorType.ADDITION -> HudColors.green
                        lineColor == LineColorType.DELETION -> HudColors.dimText
                        lineColor == LineColorType.HEADER -> HudColors.cyan
                        else -> HudColors.primaryText  // Bright green when focused
                    }

                    Text(
                        text = line,
                        color = textColor,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        lineHeight = fontSize,
                        letterSpacing = 0.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Enter icon in bottom right when focused
            if (isFocused) {
                Text(
                    text = "↵",
                    color = HudColors.green,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }

        // Status line below the input box (dimmed)
        inputSection.statusLine?.let { status ->
            Text(
                text = status,
                color = HudColors.dimText,
                fontSize = fontSize,
                fontFamily = fontFamily,
                lineHeight = fontSize,
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

// ============================================================================
// COMMAND BAR
// ============================================================================

@Composable
private fun CommandBar(
    commands: List<QuickCommand>,
    selectedIndex: Int,
    claudeMode: ClaudeMode,
    displaySize: HudDisplaySize,
    fontFamily: FontFamily,
    alpha: Float,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val commandFontSize = (displaySize.fontSizeSp - 6).coerceAtLeast(6).sp
    val scrollState = rememberScrollState()

    // Mode indicator icon
    val modeIcon = when (claudeMode) {
        ClaudeMode.ACCEPT_EDITS -> "⏩"  // Fast forward for accept edits
        ClaudeMode.PLAN -> "🤔"          // Thinking for plan mode
        ClaudeMode.NORMAL -> null
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        commands.forEachIndexed { index, command ->
            val isSelected = index == selectedIndex && isFocused

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
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = command.icon,
                        color = if (isSelected) HudColors.green else HudColors.primaryText,
                        fontSize = (commandFontSize.value + 2).sp
                    )
                    Text(
                        text = command.label,
                        color = if (isSelected) HudColors.green else HudColors.dimText,
                        fontSize = commandFontSize,
                        fontFamily = fontFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    // Show mode icon after SHIFT-TAB
                    if (command == QuickCommand.SHIFT_TAB && modeIcon != null) {
                        Text(
                            text = modeIcon,
                            fontSize = (commandFontSize.value + 2).sp
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// SCROLL MODE HINTS
// ============================================================================

@Composable
private fun ScrollModeHints(
    displaySize: HudDisplaySize,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val hintFontSize = (displaySize.fontSizeSp - 2).coerceAtLeast(8).sp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tap = scroll to bottom hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TAP",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= jump to end",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }

        // Double-tap = exit hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "2×TAP",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= exit scroll",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// INPUT MODE HINTS
// ============================================================================

@Composable
private fun InputModeHints(
    displaySize: HudDisplaySize,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val hintFontSize = (displaySize.fontSizeSp - 2).coerceAtLeast(8).sp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tap = Enter hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TAP",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= enter",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }

        // Up/Down hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "↑↓",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= navigate",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }

        // Hold = voice hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HOLD",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= voice",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }

        // Double-tap = exit hint
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "2×TAP",
                color = HudColors.cyan,
                fontSize = hintFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "= exit",
                color = HudColors.dimText,
                fontSize = hintFontSize,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// STATUS BAR
// ============================================================================

@Composable
private fun StatusBar(
    focusedArea: FocusArea,
    focusLevel: FocusLevel,
    lineInfo: String,
    isConnected: Boolean,
    displaySize: HudDisplaySize,
    fontFamily: FontFamily
) {
    val statusFontSize = (displaySize.fontSizeSp - 2).coerceAtLeast(8).sp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Focus area indicator - simple labels
        val areaLabel = when (focusedArea) {
            FocusArea.CONTENT -> "SCROLL"
            FocusArea.INPUT -> "INPUT"
            FocusArea.COMMAND -> "COMMAND"
        }
        Text(
            text = "[$areaLabel]",
            color = when (focusedArea) {
                FocusArea.CONTENT -> HudColors.cyan
                FocusArea.INPUT -> HudColors.yellow
                FocusArea.COMMAND -> HudColors.green
            },
            fontSize = statusFontSize,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold
        )

        // Connection status
        Text(
            text = if (isConnected) "\u25CF" else "\u25CB",
            color = if (isConnected) HudColors.green else HudColors.dimText,
            fontSize = (statusFontSize.value + 2).sp
        )

        // Input hint icons + line info (right side)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gesture hint icons: ↑↓ swipe, ↵ tap, ⌧ back
            Text(
                text = "\u2191\u2193",  // ↑↓
                color = HudColors.dimText,
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )
            Text(
                text = "\u21B5",  // ↵
                color = HudColors.dimText,
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )
            Text(
                text = "\u232B",  // ⌫
                color = HudColors.dimText,
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )

            // Separator
            Text(
                text = "\u2502",  // │
                color = HudColors.dimText.copy(alpha = 0.4f),
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )

            // Line info
            Text(
                text = lineInfo,
                color = HudColors.dimText,
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// VOICE INPUT OVERLAY
// ============================================================================

/**
 * Voice input overlay shown when voice recognition is active
 */
@Composable
private fun VoiceInputOverlay(
    voiceState: VoiceInputState,
    voiceText: String,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    // Pulsing animation for listening indicator
    val infiniteTransition = rememberInfiniteTransition(label = "voice")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800)
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (voiceState) {
                is VoiceInputState.Listening -> {
                    // Pulsing microphone indicator
                    Text(
                        text = "\uD83C\uDF99",  // Microphone emoji
                        fontSize = 48.sp,
                        modifier = Modifier.graphicsLayer { this.alpha = alpha }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Listening...",
                        color = HudColors.cyan,
                        fontSize = 18.sp,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }

                is VoiceInputState.Recognizing -> {
                    // Show partial transcription
                    Text(
                        text = "\uD83C\uDF99",  // Microphone emoji
                        fontSize = 32.sp,
                        color = HudColors.green
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = voiceText.ifEmpty { "..." },
                        color = HudColors.green,
                        fontSize = 20.sp,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                is VoiceInputState.Error -> {
                    // Show error message
                    Text(
                        text = "\u26A0",  // Warning emoji
                        fontSize = 32.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = voiceState.message,
                        color = HudColors.error,
                        fontSize = 16.sp,
                        fontFamily = fontFamily,
                        textAlign = TextAlign.Center
                    )
                }

                is VoiceInputState.Idle -> {
                    // Should not be visible when idle
                }
            }

            // Tap to cancel hint (shown for all active states)
            if (voiceState !is VoiceInputState.Idle) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Tap to cancel",
                    color = HudColors.dimText,
                    fontSize = 12.sp,
                    fontFamily = fontFamily
                )
            }
        }
    }
}

/**
 * Session picker overlay for switching between tmux sessions
 */
@Composable
private fun SessionPickerOverlay(
    sessions: List<String>,
    currentSession: String,
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

            // Session list with "New Session" option at the end
            val allOptions = sessions + listOf("+ New Session")

            allOptions.forEachIndexed { index, session ->
                val isSelected = index == selectedIndex
                val isCurrent = session == currentSession

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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSelected) "▶" else " ",
                            color = HudColors.green,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                        Text(
                            text = session,
                            color = if (isSelected) HudColors.green else HudColors.primaryText,
                            fontSize = 14.sp,
                            fontFamily = fontFamily,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    if (isCurrent) {
                        Text(
                            text = "●",
                            color = HudColors.cyan,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "↑↓ Navigate  TAP Select  HOLD Kill  2×TAP Cancel",
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
}

/**
 * Kill session confirmation dialog
 */
@Composable
private fun KillSessionConfirmDialog(
    sessionName: String,
    selectedOption: Int,  // 0 = Cancel, 1 = Kill
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
                text = "KILL SESSION?",
                color = HudColors.error,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = sessionName,
                color = HudColors.primaryText,
                fontSize = 14.sp,
                fontFamily = fontFamily
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Options row
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cancel option
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "▶",
                        color = if (selectedOption == 0) HudColors.green else Color.Transparent,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                    Text(
                        text = "Cancel",
                        color = if (selectedOption == 0) HudColors.green else HudColors.dimText,
                        fontSize = 14.sp,
                        fontFamily = fontFamily,
                        fontWeight = if (selectedOption == 0) FontWeight.Bold else FontWeight.Normal
                    )
                }

                // Kill option
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "▶",
                        color = if (selectedOption == 1) HudColors.error else Color.Transparent,
                        fontSize = 14.sp,
                        fontFamily = fontFamily
                    )
                    Text(
                        text = "Kill",
                        color = if (selectedOption == 1) HudColors.error else HudColors.dimText,
                        fontSize = 14.sp,
                        fontFamily = fontFamily,
                        fontWeight = if (selectedOption == 1) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "←→ Select  TAP Confirm",
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
}

/**
 * More menu overlay showing additional commands
 */
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
                text = "MORE COMMANDS",
                color = HudColors.green,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Menu items in a grid (2 columns)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                                    text = "▶",
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
                        // Fill empty space if odd number of items in row
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "↑↓ Navigate  TAP Select  2×TAP Cancel",
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
}

/**
 * Color palette optimized for monochrome HUD display
 */
object HudColors {
    val primaryText = Color(0xFF00FF00)    // Bright green - most visible
    val cyan = Color(0xFF00FFFF)           // Cyan - stands out well
    val green = Color(0xFF39FF14)          // Neon green
    val yellow = Color(0xFFFFFF00)         // Yellow for warnings
    val dimText = Color(0xFF666666)        // Dimmed text for secondary info
    val error = Color(0xFFFF4444)          // Red for errors
}
