package com.storehop.app.ui.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped, single-delivery channel for undo prompts that need to
 * cross screens. Used today for the "Item deleted -- UNDO" snackbar:
 * `ItemFormViewModel` emits after a successful softDelete, and the items
 * list screen (which the form pops back to) collects + shows the snackbar.
 *
 * Why a Channel rather than SharedFlow: each undo prompt should be shown
 * exactly once. Channel.BUFFERED + receiveAsFlow gives us at-most-once
 * delivery -- a second collector wouldn't see events the first already
 * consumed. Within-screen undo (mark purchased, delete store) doesn't go
 * through here; those screens own both the action and the snackbar so
 * local state suffices.
 */
@Singleton
class UndoEventBus @Inject constructor() {

    private val channel = Channel<UndoEvent>(capacity = Channel.BUFFERED)

    val events: Flow<UndoEvent> = channel.receiveAsFlow()

    suspend fun emit(event: UndoEvent) {
        channel.send(event)
    }
}

sealed class UndoEvent {
    /** Item soft-deleted from the form. The list screen offers UNDO. */
    data class ItemDeleted(
        val itemId: String,
        val itemName: String,
    ) : UndoEvent()
}
