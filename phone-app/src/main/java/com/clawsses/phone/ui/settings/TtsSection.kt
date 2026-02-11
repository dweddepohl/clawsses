@file:OptIn(ExperimentalMaterial3Api::class)

package com.clawsses.phone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.clawsses.phone.tts.ElevenLabsClient
import com.clawsses.phone.tts.TtsSettingsManager
import com.clawsses.phone.tts.Voice
import kotlinx.coroutines.launch

@Composable
fun TtsSection(
    ttsSettingsManager: TtsSettingsManager,
    elevenLabsClient: ElevenLabsClient,
    modifier: Modifier = Modifier,
) {
    val apiKey by ttsSettingsManager.apiKey.collectAsState()
    val selectedVoiceId by ttsSettingsManager.selectedVoiceId.collectAsState()
    val selectedVoiceName by ttsSettingsManager.selectedVoiceName.collectAsState()
    val isEnabled by ttsSettingsManager.isEnabled.collectAsState()

    var localApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var showVoiceSheet by remember { mutableStateOf(false) }

    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var isLoadingVoices by remember { mutableStateOf(false) }
    var voicesError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val hasApiKey = localApiKey.isNotBlank()
    val hasVoice = selectedVoiceId != null
    val isConfigured = hasApiKey && hasVoice && isEnabled

    // Fetch voices when API key changes and is valid
    LaunchedEffect(apiKey) {
        if (apiKey.isNotBlank()) {
            isLoadingVoices = true
            voicesError = null
            elevenLabsClient.getVoices(apiKey)
                .onSuccess { fetchedVoices ->
                    voices = fetchedVoices
                    isLoadingVoices = false
                }
                .onFailure { error ->
                    voicesError = error.message
                    isLoadingVoices = false
                }
        } else {
            voices = emptyList()
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                // Header row with icon and enable switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = if (isConfigured) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Voice Responses",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            when {
                                !hasApiKey -> "API key required"
                                !hasVoice -> "Select a voice"
                                isEnabled -> "Active - ${selectedVoiceName ?: "Unknown"}"
                                else -> "Disabled"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                isConfigured -> Color(0xFF4CAF50)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { enabled ->
                            ttsSettingsManager.setEnabled(enabled)
                        },
                        enabled = hasApiKey && hasVoice,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // API Key input field
                OutlinedTextField(
                    value = localApiKey,
                    onValueChange = { newKey ->
                        localApiKey = newKey
                        ttsSettingsManager.setApiKey(newKey)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ElevenLabs API Key") },
                    placeholder = { Text("xi-...") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "Hide API key" else "Show API key",
                                )
                            }
                            if (localApiKey.isNotEmpty()) {
                                IconButton(onClick = {
                                    localApiKey = ""
                                    ttsSettingsManager.setApiKey("")
                                }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear API key",
                                    )
                                }
                            }
                        }
                    },
                    supportingText = {
                        when {
                            voicesError != null -> Text(
                                "Invalid API key",
                                color = MaterialTheme.colorScheme.error,
                            )
                            hasApiKey -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF4CAF50),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "API key saved",
                                    color = Color(0xFF4CAF50),
                                )
                            }
                            else -> Text("Required for ElevenLabs voice synthesis")
                        }
                    },
                )

                // Voice selector (only show when API key is set)
                if (hasApiKey) {
                    Spacer(Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoadingVoices && voices.isNotEmpty()) {
                                showVoiceSheet = true
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Voice",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    when {
                                        isLoadingVoices -> "Loading voices..."
                                        voicesError != null -> "Error loading voices"
                                        selectedVoiceName != null -> selectedVoiceName!!
                                        voices.isNotEmpty() -> "Select a voice"
                                        else -> "No voices available"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isLoadingVoices) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }

                // Info text
                Spacer(Modifier.height(12.dp))
                Text(
                    "ElevenLabs provides high-quality AI voice synthesis to read assistant responses aloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Voice selection bottom sheet
    if (showVoiceSheet && voices.isNotEmpty()) {
        VoiceBottomSheet(
            voices = voices,
            selectedVoiceId = selectedVoiceId,
            onSelect = { voice ->
                ttsSettingsManager.setSelectedVoice(voice.voiceId, voice.name)
                showVoiceSheet = false
            },
            onDismiss = { showVoiceSheet = false },
        )
    }
}

@Composable
private fun VoiceBottomSheet(
    voices: List<Voice>,
    selectedVoiceId: String?,
    onSelect: (Voice) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Group voices by category
    val premade = voices.filter { it.category == "premade" || it.category == null }
    val cloned = voices.filter { it.category == "cloned" }
    val generated = voices.filter { it.category == "generated" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(
                "Select Voice",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            if (premade.isNotEmpty()) {
                item {
                    Text(
                        "Premade Voices",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(premade) { voice ->
                    VoiceRow(voice, voice.voiceId == selectedVoiceId, onSelect)
                }
            }

            if (cloned.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Cloned Voices",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(cloned) { voice ->
                    VoiceRow(voice, voice.voiceId == selectedVoiceId, onSelect)
                }
            }

            if (generated.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Generated Voices",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(generated) { voice ->
                    VoiceRow(voice, voice.voiceId == selectedVoiceId, onSelect)
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: Voice,
    isSelected: Boolean,
    onSelect: (Voice) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(voice) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            voice.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
