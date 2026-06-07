package com.storehop.app.ui.util

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.storehop.app.R
import kotlinx.coroutines.delay

/**
 * State payload for [UndoBar]. Caller mutates a `MutableState<UndoBarState?>`:
 * setting it shows the bar, clearing it (or letting the auto-dismiss timer
 * fire) hides it.
 *
 * `stamp` defaults to a per-construction value so consecutive shows always
 * restart the auto-dismiss timer in [UndoBar]'s `LaunchedEffect`, even when
 * the message text is identical.
 */
data class UndoBarState(
    val message: String,
    val onUndo: () -> Unit,
    val stamp: Long = System.nanoTime(),
)

/**
 * Bottom-anchored "X done · UNDO · ×" bar. Replaces the Material3
 * `SnackbarHost` we were using in v0.5.7 — that didn't auto-dismiss
 * reliably across devices (accessibility scaling on `SnackbarDuration`,
 * `Indefinite`-duration interactions), and didn't expose swipe + close
 * affordances out of the box.
 *
 * Three ways to dismiss:
 *  1. Wait [autoDismissMillis] (default 3s) — driven by a `LaunchedEffect`
 *     keyed on [UndoBarState.stamp] so retaps reset the timer.
 *  2. Tap × — invokes [onDismiss] directly.
 *  3. Swipe horizontally — Material3's `SwipeToDismissBox` confirms.
 *
 * Tapping UNDO calls [UndoBarState.onUndo] then [onDismiss], so callers
 * don't need to clear the state themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UndoBar(
    state: UndoBarState?,
    onDismiss: () -> Unit,
    autoDismissMillis: Long = 3_000,
) {
    LaunchedEffect(state?.stamp) {
        if (state != null) {
            delay(autoDismissMillis)
            onDismiss()
        }
    }
    if (state == null) return
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onDismiss()
                true
            } else false
        },
    )
    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = { /* No reveal -- the bar just slides off. */ },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(
                    start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    state.onUndo()
                    onDismiss()
                }) {
                    Text(
                        text = stringResource(R.string.action_undo),
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }
        }
    }
}
