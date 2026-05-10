package com.storehop.app.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.repository.ImportExportRepository
import com.storehop.app.data.repository.ImportResult
import com.storehop.app.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Pins the busy-flag state machine and error surfacing of [ImportExportViewModel].
 * The repository itself is exhaustively covered in ImportExportRepositoryImplTest --
 * here we only verify the VM's flow plumbing (busy true -> false, latestImport
 * captured for the undo sheet, exportError captured on failures, undoLastImport
 * clears the captured result).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportExportViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val repo: ImportExportRepository = mockk(relaxed = true)

    private val outputBuffer = ByteArrayOutputStream()
    private val resolver: ContentResolver = mockk {
        every { openOutputStream(any<Uri>(), any()) } returns outputBuffer
        every { openInputStream(any<Uri>()) } answers {
            ByteArrayInputStream(latestInputBytes)
        }
    }
    private var latestInputBytes: ByteArray = ByteArray(0)

    private val context: Context = mockk(relaxed = true) {
        every { contentResolver } returns resolver
    }

    private val uri: Uri = mockk(relaxed = true)

    private fun newVm() = ImportExportViewModel(context, repo)

    @Test fun `exportItemsTo writes csv to uri and toggles busy`() = runTest {
        coEvery { repo.exportItemsCsv() } returns "name,brand\nMilk,Mimosa\n"

        val vm = newVm()
        vm.busy.test {
            assertThat(awaitItem()).isFalse()  // initial
            vm.exportItemsTo(uri)
            assertThat(awaitItem()).isTrue()   // in flight
            assertThat(awaitItem()).isFalse()  // done
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(outputBuffer.toString()).isEqualTo("name,brand\nMilk,Mimosa\n")
        assertThat(vm.exportError.value).isNull()
    }

    @Test fun `exportItemsTo on repo failure surfaces exportError and clears busy`() = runTest {
        coEvery { repo.exportItemsCsv() } throws IllegalStateException("disk full")

        val vm = newVm()
        vm.exportItemsTo(uri)
        advanceUntilIdle()
        assertThat(vm.busy.value).isFalse()
        assertThat(vm.exportError.value).isEqualTo("disk full")
    }

    @Test fun `importItemsFrom captures the result for the undo sheet`() = runTest {
        latestInputBytes = "name,brand\nBread,Sara Lee\n".toByteArray()
        val result = ImportResult.Empty.copy(
            itemsImported = 1,
            importedItemIds = listOf("itm-1"),
        )
        coEvery { repo.importItemsCsv(any()) } returns result

        val vm = newVm()
        vm.importItemsFrom(uri)
        advanceUntilIdle()

        assertThat(vm.busy.value).isFalse()
        assertThat(vm.latestImport.value).isEqualTo(result)
    }

    @Test fun `importItemsFrom on parse failure surfaces error inside latestImport`() = runTest {
        latestInputBytes = "this isn't a csv".toByteArray()
        coEvery { repo.importItemsCsv(any()) } throws IllegalArgumentException("bad header")

        val vm = newVm()
        vm.importItemsFrom(uri)
        advanceUntilIdle()

        assertThat(vm.busy.value).isFalse()
        val captured = vm.latestImport.value!!
        assertThat(captured.itemsImported).isEqualTo(0)
        assertThat(captured.errors).containsExactly("bad header")
    }

    @Test fun `undoLastImport calls repo undoImport then clears latestImport`() = runTest {
        latestInputBytes = "name\nMilk\n".toByteArray()
        val result = ImportResult.Empty.copy(
            itemsImported = 1,
            importedItemIds = listOf("itm-1"),
        )
        coEvery { repo.importItemsCsv(any()) } returns result
        val undone = slot<ImportResult>()
        coEvery { repo.undoImport(capture(undone)) } returns Unit

        val vm = newVm()
        vm.importItemsFrom(uri)
        advanceUntilIdle()
        vm.undoLastImport()
        advanceUntilIdle()

        assertThat(undone.captured).isEqualTo(result)
        assertThat(vm.latestImport.value).isNull()
        assertThat(vm.busy.value).isFalse()
    }
}
