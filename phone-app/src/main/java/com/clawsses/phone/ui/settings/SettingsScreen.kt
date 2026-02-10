@file:OptIn(ExperimentalMaterial3Api::class)

package com.clawsses.phone.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawsses.phone.glasses.ApkInstaller
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.openclaw.OpenClawClient
import com.clawsses.phone.util.isEmulator
import com.clawsses.phone.voice.VoiceLanguageManager
import com.clawsses.phone.voice.VoiceRecognitionManager

@Composable
fun SettingsScreen(
    // Server
    openClawHost: String,
    openClawPort: String,
    openClawToken: String,
    openClawState: OpenClawClient.ConnectionState,
    onApplyServerSettings: (host: String, port: String, token: String) -> Unit,
    // Glasses
    glassesState: GlassesConnectionManager.ConnectionState,
    discoveredDevices: List<GlassesConnectionManager.DiscoveredDevice>,
    wifiP2PConnected: Boolean,
    debugModeEnabled: Boolean,
    onStartScanning: () -> Unit,
    onStopScanning: () -> Unit,
    onConnectDevice: (GlassesConnectionManager.DiscoveredDevice) -> Unit,
    onDisconnectGlasses: () -> Unit,
    onInitWifiP2P: () -> Unit,
    onClearSn: () -> Unit,
    hasCachedSn: Boolean,
    cachedSn: String?,
    cachedDeviceName: String?,
    // Software Update
    installState: ApkInstaller.InstallState,
    sdkConnected: Boolean,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    // Voice
    voiceLanguageManager: VoiceLanguageManager,
    voiceRecognitionManager: VoiceRecognitionManager? = null,
    // Developer
    onDebugModeChange: (Boolean) -> Unit,
    // Navigation
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Server section
            item { SectionHeader("Server") }
            item {
                ServerSection(
                    initialHost = openClawHost,
                    initialPort = openClawPort,
                    initialToken = openClawToken,
                    connectionState = openClawState,
                    onApply = onApplyServerSettings,
                )
            }

            // Glasses section
            item { SectionHeader("Glasses") }
            item {
                GlassesSection(
                    state = glassesState,
                    discoveredDevices = discoveredDevices,
                    wifiP2PConnected = wifiP2PConnected,
                    debugModeEnabled = debugModeEnabled,
                    onStartScanning = onStartScanning,
                    onStopScanning = onStopScanning,
                    onConnectDevice = onConnectDevice,
                    onDisconnectGlasses = onDisconnectGlasses,
                    onInitWifiP2P = onInitWifiP2P,
                    onClearSn = onClearSn,
                    hasCachedSn = hasCachedSn,
                    cachedSn = cachedSn,
                    cachedDeviceName = cachedDeviceName,
                )
            }

            // Software Update section
            item { SectionHeader("Software Update") }
            item {
                SoftwareUpdateSection(
                    installState = installState,
                    sdkConnected = sdkConnected,
                    onInstall = onInstall,
                    onCancel = onCancelInstall,
                )
            }

            // Voice section
            item { SectionHeader("Voice") }
            item {
                VoiceSection(
                    voiceLanguageManager = voiceLanguageManager,
                    voiceRecognitionManager = voiceRecognitionManager,
                )
            }

            // Developer section (emulator only)
            if (isEmulator()) {
                item { SectionHeader("Developer") }
                item {
                    DeveloperSection(
                        debugModeEnabled = debugModeEnabled,
                        onDebugModeChange = onDebugModeChange,
                    )
                }
            }

            // Bottom spacing
            item {
                androidx.compose.foundation.layout.Spacer(
                    Modifier.padding(bottom = 32.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = 32.dp,
            bottom = 8.dp,
        ),
    )
}
