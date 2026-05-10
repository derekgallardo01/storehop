package com.storehop.app.ui.util

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.storehop.app.R
import kotlinx.coroutines.launch

/**
 * Reusable add-category dialog. Used by both [ManageCategoriesScreen] and
 * the inline "+ New category" affordance on [ItemFormScreen]. The
 * `onAdd` callback returns null on success or a localized error string the
 * dialog renders inline (empty / duplicate / generic failure).
 *
 * Contract: the dialog dismisses itself on a successful add. The host is
 * responsible for any post-success effects (e.g., auto-selecting the new
 * id on the form). The id of the created category is not surfaced here --
 * if you need it, set the side effect inside the lambda you pass via
 * `onAdd` (which already runs inside the host's coroutine scope).
 */
@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onAdd: suspend (String) -> String?,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit = {
        if (name.isNotBlank() && !saving) {
            saving = true
            scope.launch {
                val result = onAdd(name)
                saving = false
                if (result == null) onDismiss()
                else error = result
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.add_category_dialog_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = { Text(stringResource(R.string.add_category_field_label)) },
                singleLine = true,
                keyboardOptions = WordCaps,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = name.isNotBlank() && !saving,
            ) {
                Text(stringResource(if (saving) R.string.action_adding else R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
