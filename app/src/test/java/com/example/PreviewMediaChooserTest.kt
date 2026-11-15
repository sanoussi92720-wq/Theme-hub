package com.example

import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PreviewMediaChooserTest {

    private fun fakeParams(
        acceptTypes: Array<String>,
        mode: Int = WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
    ): WebChromeClient.FileChooserParams = object : WebChromeClient.FileChooserParams() {
        override fun getMode(): Int = mode
        override fun getAcceptTypes(): Array<String> = acceptTypes
        override fun isCaptureEnabled(): Boolean = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
    }

    @Test
    fun `mixed image and video acceptTypes configures chooser for all supported preview formats`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val (intent, title) = activity.buildFileChooserIntent(
                    fakeParams(arrayOf("image/png,image/jpeg,image/webp,video/mp4,video/webm"))
                )
                assertEquals("*/*", intent.type)
                assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                assertArrayEquals(
                    arrayOf("image/png", "image/jpeg", "image/webp", "video/mp4", "video/webm"),
                    mimeTypes
                )
                assertEquals("اختر وسائط المعاينة (صور وفيديو)", title)
            }
        }
    }

    @Test
    fun `video-only acceptTypes configures video picker`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val (intent, title) = activity.buildFileChooserIntent(
                    fakeParams(arrayOf("video/mp4", "video/webm"), WebChromeClient.FileChooserParams.MODE_OPEN)
                )
                assertEquals("video/*", intent.type)
                assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                assertArrayEquals(arrayOf("video/mp4", "video/webm"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                assertEquals("اختر فيديو المعاينة", title)
            }
        }
    }

    @Test
    fun `theme archive acceptTypes preserves generic file chooser`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val (intent, title) = activity.buildFileChooserIntent(
                    fakeParams(arrayOf(".hwt", ".hnt", ".mtz"), WebChromeClient.FileChooserParams.MODE_OPEN)
                )
                assertEquals("*/*", intent.type)
                assertEquals("اختر ملف الثيم", title)
            }
        }
    }
}
