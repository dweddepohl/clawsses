package com.clawsses.phone.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clawsses.phone.glasses.ApkInstaller
import org.junit.Rule
import org.junit.Test

class SettingsDialogTokenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(token: String = "my-secret-token") {
        composeTestRule.setContent {
            SettingsDialog(
                openClawHost = "localhost",
                onHostChange = {},
                openClawPort = "18789",
                onPortChange = {},
                openClawToken = token,
                onTokenChange = {},
                debugModeEnabled = false,
                onDebugModeChange = {},
                onDismiss = {},
                installState = ApkInstaller.InstallState.Idle,
                apkInstaller = ApkInstaller(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                ),
                onCancelInstall = {},
                onOpenGlassesApp = {},
            )
        }
    }

    @Test
    fun tokenIsMaskedByDefault() {
        setContent()
        // The "Show token" button should be visible (meaning token is currently hidden)
        composeTestRule.onNodeWithContentDescription("Show token")
            .assertIsDisplayed()
        // The plain text token should not be displayed
        composeTestRule.onNodeWithText("my-secret-token")
            .assertDoesNotExist()
    }

    @Test
    fun tappingToggleRevealsToken() {
        setContent()
        // Tap the show button
        composeTestRule.onNodeWithContentDescription("Show token")
            .performClick()
        // Now the hide button should be visible
        composeTestRule.onNodeWithContentDescription("Hide token")
            .assertIsDisplayed()
        // The plain text token should be displayed
        composeTestRule.onNodeWithText("my-secret-token")
            .assertIsDisplayed()
    }

    @Test
    fun tappingToggleTwiceRehidesToken() {
        setContent()
        // Reveal
        composeTestRule.onNodeWithContentDescription("Show token")
            .performClick()
        // Hide again
        composeTestRule.onNodeWithContentDescription("Hide token")
            .performClick()
        // Back to "Show token" state
        composeTestRule.onNodeWithContentDescription("Show token")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("my-secret-token")
            .assertDoesNotExist()
    }
}
