package com.storehop.app.ui.items

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.storehop.app.R
import java.io.File

/**
 * Square photo tile for the item form. Shows the current image (remote URL or
 * staged local URI), a placeholder when neither is set, and an "uploading…"
 * spinner overlay while [isUploading] is true. Tapping opens a sheet with
 * Take photo / Choose from gallery / Remove.
 *
 * Caller owns the staged-local-URI state. We only emit callbacks:
 * - [onImagePicked] when the user picks from gallery or finishes a capture
 * - [onClearImage] when the user taps Remove
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePickerTile(
    imageUrl: String?,
    localUri: Uri?,
    isUploading: Boolean,
    onImagePicked: (Uri) -> Unit,
    onClearImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var sheetOpen by remember { mutableStateOf(false) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onImagePicked(uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (saved && uri != null) onImagePicked(uri)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.photo_section_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { sheetOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            val displayModel: Any? = localUri ?: imageUrl
            if (displayModel != null) {
                AsyncImage(
                    model = displayModel,
                    contentDescription = stringResource(R.string.photo_item),
                    modifier = Modifier.size(120.dp),
                )
            } else {
                Icon(
                    Icons.Filled.AddAPhoto,
                    contentDescription = stringResource(R.string.photo_add),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
            if (isUploading) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    text = stringResource(R.string.photo_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.photo_take)) },
                    leadingContent = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        val uri = createCaptureUri(context)
                        pendingCaptureUri = uri
                        cameraLauncher.launch(uri)
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.photo_choose_from_gallery)) },
                    leadingContent = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        galleryLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                if (imageUrl != null || localUri != null) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.photo_remove)) },
                        leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        modifier = Modifier.clickable {
                            sheetOpen = false
                            onClearImage()
                        },
                    )
                }
            }
        }
    }
}

private fun createCaptureUri(context: Context): Uri {
    val dir = context.externalCacheDir ?: context.cacheDir
    val file = File.createTempFile("capture_", ".jpg", dir)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}
