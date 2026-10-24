package org.francasio.sing.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class SingSettingsProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            SingSettings.loadLocal(ctx)
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            SingSettings.METHOD_GET_SETTINGS -> {
                SingSettings.loadLocal(ctx)
                Bundle().apply {
                    putBoolean(SingSettings.KEY_PRESERVE_SIDE, SingSettings.preserveSide)
                    putBoolean(SingSettings.KEY_TWO_WAY_SLIDER, SingSettings.twoWaySlider)
                    putBoolean(SingSettings.KEY_CENTER_GROUP_LYRICS, SingSettings.centerGroupLyrics)
                    putBoolean(SingSettings.KEY_BLUR_UNFOCUSED_LYRICS, SingSettings.blurUnfocusedLyrics)
                    putBoolean(SingSettings.KEY_ENGLISH_TRANSLATION_FALLBACK, SingSettings.englishTranslationFallback)
                    putInt(SingSettings.KEY_THREAD_COUNT, SingSettings.threadCount)
                    putBoolean(SingSettings.KEY_ECO_MODE, SingSettings.ecoMode)
                    putString(SingSettings.KEY_HARDWARE_TARGET, SingSettings.hardwareTarget)
                    putBoolean(SingSettings.KEY_FORCE_CPU, SingSettings.forceCpu)
                    putString(SingSettings.KEY_SELECTED_MODEL, SingSettings.selectedModelId)
                    putBoolean(SingSettings.KEY_ALLOW_ZERO_FLOOR, SingSettings.allowZeroFloor)
                    putBoolean(SingSettings.KEY_FORCE_SONG_ELIGIBILITY, SingSettings.forceSongEligibility)
                    // Expose the module APK path over the provider so the host
                    // process can resolve native libs without relying on the
                    // (fragile) broadcast reply.
                    putString("module_apk_path", ctx.applicationInfo.sourceDir)
                }
            }
            SingSettings.METHOD_HEARTBEAT -> {
                val ts = extras?.getLong("timestamp", System.currentTimeMillis()) ?: System.currentTimeMillis()
                val manager = extras?.getString("manager_info")
                val activeProvider = extras?.getString("active_provider")
                val engineVersion = extras?.getString("engine_version")
                SingSettings.saveHeartbeat(ctx, ts, manager, activeProvider, engineVersion)
                Bundle().apply { putBoolean("success", true) }
            }
            SingSettings.METHOD_SET_SETTING -> {
                if (extras != null) {
                    val key = extras.getString("key")
                    if (key != null) {
                        if (key == SingSettings.KEY_SELECTED_MODEL || key == SingSettings.KEY_HARDWARE_TARGET) {
                            val strVal = extras.getString("value")
                            if (strVal != null) {
                                SingSettings.saveLocalString(ctx, key, strVal)
                            }
                        } else if (key == SingSettings.KEY_THREAD_COUNT) {
                            SingSettings.saveLocalInt(ctx, key, extras.getInt("value", SingSettings.threadCount))
                        } else {
                            val value = extras.getBoolean("value", false)
                            SingSettings.saveLocal(ctx, key, value)
                        }
                    }
                }
                Bundle().apply { putBoolean("success", true) }
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val fileName = uri.lastPathSegment ?: throw FileNotFoundException("No file name in URI: $uri")
        val ctx = context ?: throw FileNotFoundException("Context is null")
        if (fileName == "models.json") {
            val jsonFile = File(ctx.filesDir, "models.json")
            if (jsonFile.exists() && jsonFile.length() > 0L) {
                return ParcelFileDescriptor.open(jsonFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val candidates = org.francasio.sing.util.ModelLoader.getCandidateModelFiles(ctx, fileName)
        for (f in candidates) {
            if (f.exists() && f.length() > 0L) {
                try {
                    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (t: Throwable) {
                    // A legacy /sdcard copy owned by an old install fails with
                    // EACCES here; keep trying the remaining candidates instead
                    // of failing the whole request.
                    android.util.Log.w("AppleSing_Provider", "Cannot open ${f.absolutePath}: ${t.message}")
                }
            }
        }
        throw FileNotFoundException("Model file $fileName does not exist")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vnd.org.francasio.sing.settings"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
