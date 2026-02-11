package com.clawsses.phone.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.WifiTetheringOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clawsses.phone.glasses.GlassesConnectionManager

@Composable
fun GlassesSection(
    state: GlassesConnectionManager.ConnectionState,
    discoveredDevices: List<GlassesConnectionManager.DiscoveredDevice>,
    wifiP2PConnected: Boolean,
    debugModeEnabled: Boolean,
    onStartScanning: () -> Unit,
    onStopScanning: () -> Unit,
    onConnectDevice: (GlassesConnectionManager.DiscoveredDevice) -> Unit,
    onDisconnectGlasses: () -> Unit,
    onInitWifiP2P: () -> Unit,
    onClearSn: () -> Unit,
    onCancelReconnect: () -> Unit,
    hasCachedSn: Boolean,
    cachedSn: String?,
    cachedDeviceName: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .animateContentSize(),
    ) {
        if (debugModeEnabled) {
            DebugModeContent(state)
        } else {
            when (state) {
                is GlassesConnectionManager.ConnectionState.Disconnected ->
                    DisconnectedContent(onStartScanning)

                is GlassesConnectionManager.ConnectionState.Scanning ->
                    ScanningContent(discoveredDevices, onStopScanning, onConnectDevice)

                is GlassesConnectionManager.ConnectionState.Connecting ->
                    ConnectingContent()

                is GlassesConnectionManager.ConnectionState.InitializingWifiP2P ->
                    ConnectingContent(message = "Setting up WiFi P2P...")

                is GlassesConnectionManager.ConnectionState.Reconnecting ->
                    ReconnectingContent(
                        attempt = state.attempt,
                        nextRetryMs = state.nextRetryMs,
                        onCancel = onCancelReconnect,
                    )

                is GlassesConnectionManager.ConnectionState.Connected ->
                    ConnectedContent(
                        deviceName = state.deviceName,
                        wifiP2PConnected = wifiP2PConnected,
                        hasCachedSn = hasCachedSn,
                        cachedSn = cachedSn,
                        cachedDeviceName = cachedDeviceName,
                        onDisconnect = onDisconnectGlasses,
                        onInitWifiP2P = onInitWifiP2P,
                        onClearSn = onClearSn,
                    )

                is GlassesConnectionManager.ConnectionState.Error ->
                    ErrorContent(
                        message = state.message,
                        onRetry = onStartScanning,
                    )
            }
        }
    }
}

@Composable
private fun DebugModeContent(state: GlassesConnectionManager.ConnectionState) {
    StatusRow(
        color = when (state) {
            is GlassesConnectionManager.ConnectionState.Connected -> Color(0xFF4CAF50)
            is GlassesConnectionManager.ConnectionState.Connecting -> Color(0xFFFFC107)
            else -> Color.Gray
        },
        title = when (state) {
            is GlassesConnectionManager.ConnectionState.Connected -> "Connected"
            is GlassesConnectionManager.ConnectionState.Connecting -> "Connecting..."
            else -> "Not connected"
        },
        subtitle = "WebSocket debug mode",
    )
}

@Composable
private fun DisconnectedContent(onScan: () -> Unit) {
    StatusRow(
        color = Color.Gray,
        title = "Not connected",
        subtitle = "Tap Scan to find nearby glasses",
    )

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = onScan,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Scan for Glasses")
    }
}

@Composable
private fun ScanningContent(
    devices: List<GlassesConnectionManager.DiscoveredDevice>,
    onStop: () -> Unit,
    onConnect: (GlassesConnectionManager.DiscoveredDevice) -> Unit,
) {
    StatusRow(
        color = Color(0xFFFFC107),
        title = "Scanning...",
        subtitle = "Looking for nearby glasses",
        showProgress = true,
    )

    Spacer(Modifier.height(16.dp))

    OutlinedButton(
        onClick = onStop,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Stop Scanning")
    }

    if (devices.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                devices.forEachIndexed { index, device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                device.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${signalDescription(device.rssi)} (${device.rssi} dBm)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onConnect(device) }) {
                            Text("Connect")
                        }
                    }
                    if (index < devices.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "${devices.size} device${if (devices.size != 1) "s" else ""} found",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectingContent(message: String = "Connecting...") {
    StatusRow(
        color = Color(0xFFFFC107),
        title = message,
        subtitle = "Please wait",
        showProgress = true,
    )
}

@Composable
private fun ReconnectingContent(attempt: Int, nextRetryMs: Long, onCancel: () -> Unit) {
    StatusRow(
        color = Color(0xFFFFA500), // Orange
        title = "Reconnecting...",
        subtitle = "Attempt #$attempt (next retry in ${nextRetryMs / 1000}s)",
        showProgress = true,
    )

    Spacer(Modifier.height(16.dp))

    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Cancel")
    }
}

@Composable
private fun ConnectedContent(
    deviceName: String,
    wifiP2PConnected: Boolean,
    hasCachedSn: Boolean,
    cachedSn: String?,
    cachedDeviceName: String?,
    onDisconnect: () -> Unit,
    onInitWifiP2P: () -> Unit,
    onClearSn: () -> Unit,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }

    StatusRow(
        color = Color(0xFF4CAF50),
        title = "Connected",
        subtitle = deviceName,
    )

    Spacer(Modifier.height(12.dp))

    // Connection details
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionDetail(
            icon = { Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = "Bluetooth",
            connected = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectionDetail(
                icon = {
                    Icon(
                        if (wifiP2PConnected) Icons.Default.WifiTethering else Icons.Default.WifiTetheringOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = "WiFi P2P",
                connected = wifiP2PConnected,
            )
            if (!wifiP2PConnected) {
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onInitWifiP2P,
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text("Setup", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // Paired device card
    if (hasCachedSn) {
        Spacer(Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Paired Device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    cachedDeviceName ?: deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))

                if (showClearConfirmation) {
                    Text(
                        "Clear pairing data?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "You'll need to re-pair next time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showClearConfirmation = false }) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                onClearSn()
                                showClearConfirmation = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF44336),
                            ),
                        ) {
                            Text("Clear")
                        }
                    }
                } else {
                    TextButton(
                        onClick = { showClearConfirmation = true },
                    ) {
                        Text(
                            "Clear pairing",
                            color = Color(0xFFF44336),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    OutlinedButton(
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Disconnect")
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    StatusRow(
        color = Color(0xFFF44336),
        title = "Connection error",
        subtitle = message,
    )

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Scan for Glasses")
    }
}

@Composable
private fun StatusRow(
    color: Color,
    title: String,
    subtitle: String,
    showProgress: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = color,
            )
        } else {
            Icon(
                Icons.Default.Circle,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(12.dp)
                    .padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionDetail(
    icon: @Composable () -> Unit,
    label: String,
    connected: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Default.Circle,
            contentDescription = if (connected) "Connected" else "Not connected",
            tint = if (connected) Color(0xFF4CAF50) else Color.Gray,
            modifier = Modifier.size(8.dp),
        )
    }
}

private fun signalDescription(rssi: Int): String = when {
    rssi >= -50 -> "Strong"
    rssi >= -70 -> "Medium"
    else -> "Weak"
}
