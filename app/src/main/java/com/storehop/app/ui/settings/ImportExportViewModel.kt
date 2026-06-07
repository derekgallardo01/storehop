package com.storehop.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storehop.app.data.repository.ImportExportRepository
import com.storehop.app.data.repository.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridges the SAF-picked `Uri` to [ImportExportRepository] and surfaces the
 * resulting [ImportResult] for the import snackbar / Undo flow.
 *
 * State design:
 *  - [latestImport] holds the most recent import result so the screen can
 *    show "Imported X items, skipped Y. [Undo]" and still know the ids to
 *    soft-delete if the user taps Undo.
 *  - [busy] is a coarse-grained "in flight" flag the screen uses to disable
 *    the Import / Export buttons while an operation is running.
 */
@HiltViewModel
class ImportExportViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: ImportExportRepository,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _latestImport = MutableStateFlow<ImportResult?>(null)
    val latestImport: StateFlow<ImportResult?> = _latestImport.asStateFlow()

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    fun exportItemsTo(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _exportError.value = null
            try {
                val csv = repository.exportItemsCsv()
                writeAt(uri, csv)
            } catch (e: Exception) {
                _exportError.value = e.message ?: "Export failed"
            } finally {
                _busy.value = false
            }
        }
    }

    fun exportCategoriesTo(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _exportError.value = null
            try {
                val csv = repository.exportCategoriesCsv()
                writeAt(uri, csv)
            } catch (e: Exception) {
                _exportError.value = e.message ?: "Export failed"
            } finally {
                _busy.value = false
            }
        }
    }

    fun importItemsFrom(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val content = readAt(uri)
                val result = repository.importItemsCsv(content)
                _latestImport.value = result
            } catch (e: Exception) {
                _latestImport.value = ImportResult.Empty.copy(
                    errors = listOf(e.message ?: "Import failed"),
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun importCategoriesFrom(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val content = readAt(uri)
                val result = repository.importCategoriesCsv(content)
                _latestImport.value = result
            } catch (e: Exception) {
                _latestImport.value = ImportResult.Empty.copy(
                    errors = listOf(e.message ?: "Import failed"),
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun undoLastImport() {
        val result = _latestImport.value ?: return
        viewModelScope.launch {
            _busy.value = true
            try {
                repository.undoImport(result)
            } finally {
                _latestImport.value = null
                _busy.value = false
            }
        }
    }

    /** Caller has consumed the snackbar; clear the state so it's not shown again. */
    fun consumeLatestImport() { _latestImport.value = null }
    fun consumeExportError() { _exportError.value = null }

    private fun writeAt(uri: Uri, csv: String) {
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray()) }
            ?: throw IllegalStateException("Could not open destination for writing")
    }

    private fun readAt(uri: Uri): String =
        appContext.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            ?: throw IllegalStateException("Could not open source for reading")
}
