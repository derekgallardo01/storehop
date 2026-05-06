package com.storehop.app.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import com.google.firebase.storage.FirebaseStorage
import com.storehop.app.data.util.UserSessionProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a local image URI, downscales to fit within [MAX_DIMENSION] px on the
 * long edge, re-encodes as JPEG at [JPEG_QUALITY], and uploads it to
 * `users/{uid}/items/{itemId}.jpg` in Firebase Storage. Returns the
 * `https://...` download URL the caller writes back to `Item.imageUrl`.
 *
 * Downscale + JPEG re-encode caps upload bandwidth and Storage cost. EXIF
 * rotation from the camera is preserved (BitmapFactory ignores orientation,
 * so we apply it manually before encoding).
 */
@Singleton
class ImageUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: FirebaseStorage,
    private val session: UserSessionProvider,
) {

    suspend fun upload(localUri: Uri, itemId: String): String = withContext(Dispatchers.IO) {
        val uid = session.userId.value
            ?: error("Cannot upload image: no signed-in user")

        val bytes = loadAndCompress(localUri)
        val ref = storage.reference.child("users/$uid/items/$itemId.jpg")
        ref.putBytes(bytes).await()
        ref.downloadUrl.await().toString()
    }

    private fun loadAndCompress(uri: Uri): ByteArray {
        // First pass: read just the size so we can pick a downsample factor
        // without paging the full bitmap through memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val (w, h) = bounds.outWidth to bounds.outHeight
        val sampleSize = computeInSampleSize(w, h, MAX_DIMENSION)

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Could not decode image at $uri")

        val rotated = applyExifRotation(uri, bitmap)
        val bounded = if (rotated.width > MAX_DIMENSION || rotated.height > MAX_DIMENSION) {
            scaleToFit(rotated, MAX_DIMENSION)
        } else {
            rotated
        }

        return ByteArrayOutputStream().use { buf ->
            bounded.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buf)
            buf.toByteArray()
        }
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun scaleToFit(bitmap: Bitmap, maxSide: Int): Bitmap {
        val ratio = minOf(maxSide.toFloat() / bitmap.width, maxSide.toFloat() / bitmap.height)
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun computeInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfW / sample >= maxSide && halfH / sample >= maxSide) {
            sample *= 2
        }
        return sample
    }

    companion object {
        private const val MAX_DIMENSION = 1024
        private const val JPEG_QUALITY = 85
    }
}
