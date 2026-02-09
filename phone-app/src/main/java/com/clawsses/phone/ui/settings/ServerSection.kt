package com.clawsses.phone.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clawsses.phone.openclaw.OpenClawClient

@Composable
fun ServerSection(
    initialHost: String,
    initialPort: String,
    initialToken: String,
    connectionState: OpenClawClient.ConnectionState,
    onApply: (host: String, port: String, token: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var formHost by remember { mutableStateOf(initialHost) }
    var formPort by remember { mutableStateOf(initialPort) }
    var formToken by remember { mutableStateOf(initialToken) }
    var tokenVisible by remember { mutableStateOf(false) }

    // Sync form state when parent provides new applied values (e.g. after apply)
    LaunchedEffect(initialHost, initialPort, initialToken) {
        formHost = initialHost
        formPort = initialPort
        formToken = initialToken
    }

    val isDirty = formHost != initialHost || formPort != initialPort || formToken != initialToken

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = formHost,
                        onValueChange = { formHost = it },
                        label = { Text("Host") },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = formPort,
                        onValueChange = { formPort = it },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = formToken,
                    onValueChange = { formToken = it },
                    label = { Text("Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (tokenVisible)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (tokenVisible) "Hide token" else "Show token",
                            )
                        }
                    },
                )

                AnimatedVisibility(
                    visible = isDirty,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onApply(formHost, formPort, formToken) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Apply Changes")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        ConnectionStatusRow(connectionState, formHost, formPort)
    }
}

@Composable
private fun ConnectionStatusRow(
    state: OpenClawClient.ConnectionState,
    host: String,
    port: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Icon(
            Icons.Default.Circle,
            contentDescription = null,
            tint = when (state) {
                is OpenClawClient.ConnectionState.Connected -> Color(0xFF4CAF50)
                is OpenClawClient.ConnectionState.Connecting,
                is OpenClawClient.ConnectionState.Authenticating -> Color(0xFFFFC107)
                is OpenClawClient.ConnectionState.PairingRequired -> Color(0xFFFF8800)
                is OpenClawClient.ConnectionState.Error -> Color(0xFFF44336)
                is OpenClawClient.ConnectionState.Disconnected -> Color.Gray
            },
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = when (state) {
                    is OpenClawClient.ConnectionState.Connected -> "Connected to gateway"
                    is OpenClawClient.ConnectionState.Connecting -> "Connecting..."
                    is OpenClawClient.ConnectionState.Authenticating -> "Authenticating..."
                    is OpenClawClient.ConnectionState.PairingRequired -> "Pairing required"
                    is OpenClawClient.ConnectionState.Error -> "Connection error"
                    is OpenClawClient.ConnectionState.Disconnected -> "Not connected"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "ws://$host:$port",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
