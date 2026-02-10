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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.clawsses.phone.voice.VoiceLanguageManager
import com.clawsses.phone.voice.VoiceRecognitionManager

@Composable
fun VoiceSection(
    voiceLanguageManager: VoiceLanguageManager,
    voiceRecognitionManager: VoiceRecognitionManager? = null,
    modifier: Modifier = Modifier,
) {
    val availableLanguages by voiceLanguageManager.availableLanguages.collectAsState()
    val selectedLanguage by voiceLanguageManager.selectedLanguage.collectAsState()
    var showSheet by remember { mutableStateOf(false) }

    val currentLangDisplay = availableLanguages
        .firstOrNull { it.tag == selectedLanguage }
        ?.displayName ?: selectedLanguage.ifEmpty { "Default" }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        // OpenAI Voice Recognition settings
        if (voiceRecognitionManager != null) {
            OpenAIVoiceSettings(voiceRecognitionManager)
            Spacer(Modifier.height(12.dp))
        }

        // Language selection
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSheet = true },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Recognition Language",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        currentLangDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    if (showSheet) {
        LanguageBottomSheet(
            languages = availableLanguages,
            selectedTag = selectedLanguage,
            preferredLocales = VoiceLanguageManager.PREFERRED_LOCALES,
            onSelect = { tag ->
                voiceLanguageManager.selectLanguage(tag)
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun OpenAIVoiceSettings(
    voiceRecognitionManager: VoiceRecognitionManager,
) {
    var apiKey by remember { mutableStateOf(voiceRecognitionManager.getOpenAIApiKey()) }
    var showApiKey by remember { mutableStateOf(false) }
    var isEnabled by remember { mutableStateOf(voiceRecognitionManager.isOpenAIVoiceEnabled()) }

    val hasApiKey = apiKey.isNotEmpty()
    val isConfigured = hasApiKey && isEnabled

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
                    Icons.Default.Cloud,
                    contentDescription = null,
                    tint = if (isConfigured) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "OpenAI Voice Recognition",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        when {
                            !hasApiKey -> "API key required"
                            isEnabled -> "Active - using GPT-4o"
                            else -> "Disabled - using device"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isConfigured -> Color(0xFF4CAF50)
                            !hasApiKey -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { enabled ->
                        isEnabled = enabled
                        voiceRecognitionManager.setOpenAIVoiceEnabled(enabled)
                    },
                    enabled = hasApiKey,
                )
            }

            Spacer(Modifier.height(16.dp))

            // API Key input field
            OutlinedTextField(
                value = apiKey,
                onValueChange = { newKey ->
                    apiKey = newKey
                    voiceRecognitionManager.setOpenAIApiKey(newKey)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenAI API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Row {
                        // Toggle visibility
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showApiKey) "Hide API key" else "Show API key",
                            )
                        }
                        // Clear button
                        if (apiKey.isNotEmpty()) {
                            IconButton(onClick = {
                                apiKey = ""
                                voiceRecognitionManager.setOpenAIApiKey("")
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
                    if (hasApiKey) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    } else {
                        Text("Required for OpenAI voice recognition")
                    }
                },
            )

            // Info text
            Spacer(Modifier.height(8.dp))
            Text(
                "OpenAI provides higher accuracy voice recognition using GPT-4o. " +
                    "Falls back to device recognition if unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LanguageBottomSheet(
    languages: List<VoiceLanguageManager.LanguageOption>,
    selectedTag: String,
    preferredLocales: List<java.util.Locale>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val preferredTags = preferredLocales.map { it.toLanguageTag() }.toSet()
    val preferred = languages.filter { it.tag in preferredTags }
    val remaining = languages.filter { it.tag !in preferredTags }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(
                "Recognition Language",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            if (preferred.isNotEmpty()) {
                items(preferred) { lang ->
                    LanguageRow(lang, lang.tag == selectedTag, onSelect)
                }
                item {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(remaining) { lang ->
                LanguageRow(lang, lang.tag == selectedTag, onSelect)
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun LanguageRow(
    language: VoiceLanguageManager.LanguageOption,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(language.tag) }
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
            language.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            language.tag,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
