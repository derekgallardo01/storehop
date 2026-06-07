package com.storehop.app.data.storage

import android.content.Context
import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.storehop.app.testing.FakeSessionProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The bitmap-processing path inside [ImageUploader.upload] (decode, EXIF
 * rotate, JPEG re-encode) requires real Bitmap fixtures and isn't a
 * meaningful unit-test target without Robolectric resources -- skipped
 * for now.
 *
 * What IS unit-testable here is the auth guard: uploading without a
 * signed-in user must throw rather than silently writing under a path
 * with no owner. That's what keeps the photo from being orphaned in
 * Firebase Storage.
 */
class ImageUploaderTest {

    private val context: Context = mockk(relaxed = true)
    private val storage: FirebaseStorage = mockk(relaxed = true)

    @Test(expected = IllegalStateException::class)
    fun `upload throws when no user is signed in`() = runTest {
        val uploader = ImageUploader(
            context = context,
            storage = storage,
            session = FakeSessionProvider(initial = null),
        )
        uploader.upload(localUri = mockk<Uri>(), itemId = "any")
    }
}
