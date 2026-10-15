package com.storehop.app.ui.shop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.entity.Store
import com.storehop.app.data.repository.StorePickerRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class StorePickerColorUiTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `StorePickerCard overflow menu exposes Store color action and invokes onChangeColor`() {
        var colorClicked = false
        val row = sampleRow(name = "Lidl", colorArgb = 0xFF1565C0.toInt(), critical = listOf("Milk", "Eggs"))

        composeRule.setContent {
            StorePickerCard(
                row = row,
                isDragging = false,
                onClick = {},
                onRename = {},
                onChangeColor = { colorClicked = true },
                onDelete = {},
                onEditAisles = {},
                onToggleOneOff = {},
            )
        }

        composeRule.onNodeWithText("Lidl").assertIsDisplayed()
        composeRule.onNodeWithText("L").assertIsDisplayed()
        composeRule.onNodeWithText("2 items").assertIsDisplayed()
        composeRule.onNodeWithText("⚠ 2").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Store options").performClick()
        composeRule.onNodeWithText("Store color").assertIsDisplayed().performClick()
        assertThat(colorClicked).isTrue()
    }

    @Test fun `StorePickerCard renders with bright light custom color and critical chip`() {
        val row = sampleRow(name = "Glovo", colorArgb = 0xFFFFF176.toInt(), critical = listOf("Bread"))

        composeRule.setContent {
            StorePickerCard(
                row = row,
                isDragging = false,
                onClick = {},
                onRename = {},
                onChangeColor = {},
                onDelete = {},
                onEditAisles = {},
                onToggleOneOff = {},
            )
        }

        composeRule.onNodeWithText("Glovo").assertIsDisplayed()
        composeRule.onNodeWithText("⚠ 1").assertIsDisplayed()
    }

    @Test fun `StoreColorDialog selects preset swatch, updates hex field, and saves`() {
        var savedColor: Int? = -1
        var saveCalled = false

        composeRule.setContent {
            StoreColorDialog(
                storeName = "Lidl",
                currentColorArgb = null,
                onDismiss = {},
                onSave = {
                    saveCalled = true
                    savedColor = it
                },
            )
        }

        // Initially Default is selected and all 12 palette swatches are unselected but accessible.
        composeRule.onNodeWithContentDescription("Default").assertIsSelected()
        composeRule.onNodeWithContentDescription("#1565C0").assertIsNotSelected()

        // Tap the Brand Blue swatch (#1565C0).
        composeRule.onNodeWithContentDescription("#1565C0").performClick()
        composeRule.onNodeWithContentDescription("#1565C0").assertIsSelected()
        composeRule.onNodeWithContentDescription("Default").assertIsNotSelected()
        composeRule.onNodeWithText("#1565C0").assertIsDisplayed()

        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertThat(saveCalled).isTrue()
        assertThat(savedColor).isEqualTo(0xFF1565C0.toInt())
    }

    @Test fun `StoreColorDialog clears stale swatch selection and disables Save on invalid hex input`() {
        var savedColor: Int? = null
        var saveCalled = false

        composeRule.setContent {
            StoreColorDialog(
                storeName = "Continente",
                currentColorArgb = 0xFF1565C0.toInt(),
                onDismiss = {},
                onSave = {
                    saveCalled = true
                    savedColor = it
                },
            )
        }

        composeRule.onNodeWithContentDescription("#1565C0").assertIsSelected()

        // Edit hex input to an incomplete/invalid value -> neither #1565C0 nor Default may remain selected.
        composeRule.onNodeWithText("#1565C0").performTextReplacement("#1565")
        composeRule.onNodeWithContentDescription("#1565C0").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Default").assertIsNotSelected()
        composeRule.onNodeWithText("Enter a 6-digit hex color like #1565C0").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()

        // Tap Default swatch -> resets to blank, Default is selected, Save is enabled.
        composeRule.onNodeWithContentDescription("Default").performClick()
        composeRule.onNodeWithContentDescription("Default").assertIsSelected()
        composeRule.onNodeWithText("Save").assertIsEnabled()

        // Type a valid custom hex outside the 12 presets (#FFCC00) -> Default unselects, Save emits 0xFFFFCC00.
        composeRule.onNodeWithText("Custom hex (#RRGGBB)").performTextReplacement("#FFCC00")
        composeRule.onNodeWithContentDescription("Default").assertIsNotSelected()
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertThat(saveCalled).isTrue()
        assertThat(savedColor).isEqualTo(0xFFFFCC00.toInt())
    }

    @Test fun `AddStoreDialog forwards selected brand color to onAdd`() {
        var addedName: String? = null
        var addedOneOff: Boolean? = null
        var addedColor: Int? = null

        composeRule.setContent {
            AddStoreDialog(
                onDismiss = {},
                onAdd = { name, isOneOff, colorArgb ->
                    addedName = name
                    addedOneOff = isOneOff
                    addedColor = colorArgb
                    null
                },
            )
        }

        composeRule.onNodeWithText("Store name").performTextReplacement("Pingo Doce")
        composeRule.onNodeWithContentDescription("#2E7D32").performClick()
        composeRule.onNodeWithContentDescription("#2E7D32").assertIsSelected()
        composeRule.onNodeWithText("Add").assertIsEnabled().performClick()

        assertThat(addedName).isEqualTo("Pingo Doce")
        assertThat(addedOneOff).isFalse()
        assertThat(addedColor).isEqualTo(0xFF2E7D32.toInt())
    }

    @Test fun `StoreColorDialog interactive HSV color picker updates hex field and saves custom color`() {
        var savedColor: Int? = null
        var saveCalled = false

        composeRule.setContent {
            StoreColorDialog(
                storeName = "Aldi",
                currentColorArgb = null,
                onDismiss = {},
                onSave = {
                    saveCalled = true
                    savedColor = it
                },
            )
        }

        composeRule.onNodeWithContentDescription("Default").assertIsSelected()

        // Open the visual color picker from the hex field button, then tap Hue and Saturation/brightness.
        composeRule.onNodeWithContentDescription("Color picker").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Hue").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Saturation and brightness").assertIsDisplayed().performClick()

        // Default should now be unselected because a custom hex color was populated.
        composeRule.onNodeWithContentDescription("Default").assertIsNotSelected()
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertThat(saveCalled).isTrue()
        assertThat(savedColor).isNotNull()
        assertThat(savedColor!! ushr 24).isEqualTo(0xFF)
    }

    private fun sampleRow(
        name: String,
        colorArgb: Int?,
        critical: List<String> = emptyList(),
    ) = StorePickerRow(
        store = Store(
            id = "store_${name.lowercase()}",
            name = name,
            colorArgb = colorArgb,
            isArchived = false,
            isSeeded = false,
            userId = "u",
            createdAt = 1L,
            updatedAt = 1L,
            deletedAt = null,
        ),
        neededCount = critical.size,
        pickedUpInSessionCount = 0,
        criticalItemNames = critical,
        buyTodayItemNames = emptyList(),
    )
}
