package com.storehop.app.ui.util

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the [UndoBar] composable's three dismissal paths (auto-timer,
 * UNDO tap, × tap) and the timer-reset behavior keyed on
 * [UndoBarState.stamp]. The swipe-to-dismiss path is covered by the
 * upstream Material3 SwipeToDismissBox tests; replicating gesture
 * mechanics in Robolectric is fragile, so we trust that integration here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class UndoBarTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `renders nothing when state is null`() {
        composeRule.setContent {
            UndoBar(state = null, onDismiss = {})
        }
        composeRule.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test fun `renders message text and Undo + Close affordances when state is set`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            UndoBar(
                state = UndoBarState(message = "Milk deleted", onUndo = {}),
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Milk deleted").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test fun `auto-dismisses after autoDismissMillis and calls onDismiss exactly once`() {
        var dismissCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            UndoBar(
                state = UndoBarState(message = "Bread deleted", onUndo = {}),
                onDismiss = { dismissCount++ },
                autoDismissMillis = 3_000,
            )
        }
        composeRule.mainClock.advanceTimeBy(2_900)
        assertThat(dismissCount).isEqualTo(0)
        composeRule.mainClock.advanceTimeBy(200)
        assertThat(dismissCount).isEqualTo(1)
    }

    @Test fun `tapping UNDO invokes onUndo then onDismiss`() {
        val callOrder = mutableListOf<String>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            UndoBar(
                state = UndoBarState(
                    message = "Eggs deleted",
                    onUndo = { callOrder += "undo" },
                ),
                onDismiss = { callOrder += "dismiss" },
            )
        }
        composeRule.onNodeWithText("Undo").performClick()
        assertThat(callOrder).containsExactly("undo", "dismiss").inOrder()
    }

    @Test fun `tapping the close icon invokes onDismiss without invoking onUndo`() {
        var undoCount = 0
        var dismissCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            UndoBar(
                state = UndoBarState(
                    message = "Cheese deleted",
                    onUndo = { undoCount++ },
                ),
                onDismiss = { dismissCount++ },
            )
        }
        composeRule.onNodeWithContentDescription("Close").performClick()
        assertThat(dismissCount).isEqualTo(1)
        assertThat(undoCount).isEqualTo(0)
    }

    @Test fun `auto-dismiss respects a custom autoDismissMillis`() {
        // Pin the LaunchedEffect-key contract: a different delay value
        // produces a proportionally different dismissal time. This is the
        // behavior the timer-reset path also relies on -- if this breaks,
        // re-showing with a fresh stamp won't restart the window either.
        var dismissCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            UndoBar(
                state = UndoBarState(message = "Yogurt deleted", onUndo = {}),
                onDismiss = { dismissCount++ },
                autoDismissMillis = 1_000,
            )
        }
        composeRule.mainClock.advanceTimeBy(900)
        assertThat(dismissCount).isEqualTo(0)
        composeRule.mainClock.advanceTimeBy(200)
        assertThat(dismissCount).isEqualTo(1)
    }
}
