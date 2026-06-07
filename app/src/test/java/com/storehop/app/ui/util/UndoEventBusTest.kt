package com.storehop.app.ui.util

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pin the at-most-once delivery contract of the undo bus. The Channel-based
 * impl is intentional: a SharedFlow would replay events on subsequent
 * collection, double-showing the snackbar after a back-nav. If someone
 * "fixes" the impl by switching to SharedFlow, this test catches it.
 */
class UndoEventBusTest {

    @Test fun `emit + collect delivers the event once`() = runTest {
        val bus = UndoEventBus()
        bus.events.test {
            bus.emit(UndoEvent.ItemDeleted(itemId = "milk", itemName = "Milk"))
            val event = awaitItem()
            assertThat(event).isInstanceOf(UndoEvent.ItemDeleted::class.java)
            assertThat((event as UndoEvent.ItemDeleted).itemId).isEqualTo("milk")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `events are delivered at most once across collectors`() = runTest {
        val bus = UndoEventBus()
        // Emit before any collector subscribes -- the event is buffered in
        // the channel, so the FIRST collector that comes along consumes it.
        bus.emit(UndoEvent.ItemDeleted(itemId = "milk", itemName = "Milk"))

        bus.events.test {
            assertThat((awaitItem() as UndoEvent.ItemDeleted).itemId).isEqualTo("milk")
            cancelAndIgnoreRemainingEvents()
        }

        // A second collector AFTER the first consumed the event must not see
        // a replay. SharedFlow with replay > 0 would fail this test.
        bus.events.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
