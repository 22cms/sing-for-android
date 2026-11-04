package org.francasio.sing

import android.content.Context
import android.net.Uri
import android.os.Bundle
import org.francasio.sing.audio.VrChunking
import org.francasio.sing.provider.SingSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException

class ProviderAccessTest {
    companion object {
        lateinit var appContext: Context
        private val provider = Uri.parse("content://org.francasio.sing.provider")
    }

    @Test fun sameUidCanReadSettings() {
        val result = appContext.contentResolver.call(provider, SingSettings.METHOD_GET_SETTINGS, null, null)
        assertNotNull(result)
        assertTrue(result!!.containsKey(SingSettings.KEY_SELECTED_MODEL))
    }

    @Test fun invalidPreferenceWriteIsRejectedWithoutChangingSettings() {
        val before = appContext.contentResolver.call(provider, SingSettings.METHOD_GET_SETTINGS, null, null)!!
        try {
            appContext.contentResolver.call(provider, SingSettings.METHOD_SET_SETTING, null, Bundle().apply {
                putString("key", SingSettings.KEY_THREAD_COUNT)
                putBoolean("value", true)
            })
            fail("Preference type corruption must be rejected")
        } catch (_: IllegalArgumentException) {}
        val after = appContext.contentResolver.call(provider, SingSettings.METHOD_GET_SETTINGS, null, null)!!
        assertEquals(before.getInt(SingSettings.KEY_THREAD_COUNT), after.getInt(SingSettings.KEY_THREAD_COUNT))
    }

    @Test fun encodedPathTraversalIsRejected() {
        try {
            appContext.contentResolver.openFileDescriptor(
                Uri.parse("content://org.francasio.sing.provider/models/..%2f..%2fshared_prefs%2fapple_sing_settings.xml"), "r"
            )?.close()
            fail("Encoded path traversal must be rejected")
        } catch (_: FileNotFoundException) {}
    }

    @Test fun benchmarkLabelShowsUsefulAudioInsteadOfHardcodedInputDuration() {
        val label = appContext.getString(
            R.string.benchmark_processing_new_audio, 1000L, VrChunking.hopSamples() * 1000.0 / 44100.0
        )
        assertTrue(label.contains("2868"))
        assertFalse(label.contains("5921"))
    }
}
