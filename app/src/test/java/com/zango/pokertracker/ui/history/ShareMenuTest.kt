package com.zango.pokertracker.ui.history

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zango.pokertracker.ui.theme.PokerTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The share button's two options say what they move and which way, so a host can tell
 * "Import" from "Export" without having to try them - on screen and when read out by TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareMenuTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var imported = 0
    private var exported = 0

    private val importSpoken = "Import. Add finished games history from other phone"
    private val exportSpoken = "Export. Send finished games history to another phone"

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            PokerTrackerTheme {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun showMenu(expanded: Boolean) = show {
        ShareMenu(
            expanded = expanded,
            onToggle = {},
            onImport = { imported++ },
            onExport = { exported++ },
        )
    }

    @Test
    fun `each option shows its description under its label`() {
        showMenu(expanded = true)

        for (text in listOf("Import", "Add finished games history from other phone", "Export", "Send finished games history to another phone")) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    /**
     * Material hides an extended button's text from accessibility, so without its own name an
     * option is read out as just "Button".
     */
    @Test
    fun `talkback reads each option's label and description as one button`() {
        showMenu(expanded = true)

        compose.onNodeWithContentDescription(importSpoken).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription(exportSpoken).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `each option does what it says`() {
        showMenu(expanded = true)

        compose.onNodeWithContentDescription(exportSpoken).performClick()
        assertEquals(1 to 0, exported to imported)

        compose.onNodeWithContentDescription(importSpoken).performClick()
        assertEquals(1 to 1, exported to imported)
    }

    @Test
    fun `tapping the description line still picks the option`() {
        showMenu(expanded = true)

        compose.onNodeWithText("Send finished games history to another phone", useUnmergedTree = true).performClick()

        assertEquals(1, exported)
    }

    @Test
    fun `a closed menu shows neither option nor description`() {
        showMenu(expanded = false)

        compose.onAllNodesWithText("Add finished games history from other phone", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(importSpoken).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(exportSpoken).assertCountEquals(0)
    }

    @Test
    fun `opening the menu brings the options in`() {
        var expanded by mutableStateOf(false)
        show {
            ShareMenu(expanded = expanded, onToggle = { expanded = !expanded }, onImport = {}, onExport = {})
        }
        compose.onAllNodesWithContentDescription(exportSpoken).assertCountEquals(0)

        compose.onNodeWithContentDescription("Share").performClick()

        compose.onNodeWithContentDescription(exportSpoken).assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel").assertIsDisplayed()
    }

    /** The new-game menu shares the option button, so it gets names the same way. */
    @Test
    fun `the new game button and its options are named for talkback too`() {
        var expanded by mutableStateOf(false)
        show {
            NewGameMenu(expanded = expanded, onToggle = { expanded = !expanded }, onCreate = {}, onLoad = {})
        }

        compose.onNodeWithContentDescription("New game").assertHasClickAction().performClick()

        compose.onNodeWithContentDescription("Create new game").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Load a game").assertIsDisplayed().assertHasClickAction()
    }
}
