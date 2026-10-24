package org.francasio.sing.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.francasio.sing.provider.SingSettings
import java.util.concurrent.Executors

/** Owns creation and startup wiring for the audio processor inside Apple Music. */
class AudioProcessorBridge(
    private val resolveModuleApkPath: (Context) -> String?,
    private val resolveManagerInfo: () -> String?,
    private val onManagerInfoResolved: (String?) -> Unit,
    private val onEngineReady: () -> Unit,
    private val onSettingsChanged: () -> Unit,
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit
) {
    @Volatile
    var processor: SingAudioProcessor? = null
        private set

    private val initExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "AppleSing_XposedInit")
    }

    fun initializeWhenIdle(context: Context, mainHandler: Handler) {
        val appContext = context.applicationContext ?: context
        loadCachedUiSettings(appContext)

        val initAction = Runnable { initExecutor.execute { initialize(appContext) } }
        var executed = false
        val safeRunner = Runnable {
            if (!executed) {
                executed = true
                initAction.run()
            }
        }
        try {
            Looper.getMainLooper().queue.addIdleHandler {
                safeRunner.run()
                false
            }
            mainHandler.postDelayed(safeRunner, 1500L)
        } catch (_: Throwable) {
            safeRunner.run()
        }
    }

    private fun initialize(appContext: Context) {
        try {
            val path = resolveModuleApkPath(appContext)
            val manager = resolveManagerInfo()
            onManagerInfoResolved(manager)
            SingSettings.sendHeartbeat(appContext, manager)

            if (path == null) {
                logError("Failed to resolve module APK path", null)
                return
            }

            SingSettings.resolvedModulePath = path
            try {
                appContext.getSharedPreferences("apple_sing_cache", Context.MODE_PRIVATE)
                    .edit()
                    .putString("resolved_module_apk_path", path)
                    .apply()
            } catch (_: Throwable) {}

            val newProcessor = SingAudioProcessor(
                context = appContext,
                moduleApkPath = path,
                managerInfo = manager,
                onSettingsChanged = onSettingsChanged
            )
            newProcessor.onEngineReadyListener = onEngineReady
            processor = newProcessor
            if (newProcessor.isEngineReady) onEngineReady()
            logInfo("SingAudioProcessor initialized in background from $path (manager=$manager)")
        } catch (t: Throwable) {
            logError("Failed to initialize SingAudioProcessor", t)
        }
    }

    private fun loadCachedUiSettings(context: Context) {
        try {
            val prefs = context.getSharedPreferences("apple_sing_cache", Context.MODE_PRIVATE)
            SingSettings.allowZeroFloor = prefs.getBoolean(SingSettings.KEY_ALLOW_ZERO_FLOOR, true)
            SingSettings.twoWaySlider = prefs.getBoolean(SingSettings.KEY_TWO_WAY_SLIDER, false)
            SingSettings.preserveSide = prefs.getBoolean(SingSettings.KEY_PRESERVE_SIDE, false)
            SingSettings.forceSongEligibility = prefs.getBoolean(SingSettings.KEY_FORCE_SONG_ELIGIBILITY, true)
            SingSettings.centerGroupLyrics = prefs.getBoolean(SingSettings.KEY_CENTER_GROUP_LYRICS, true)
            SingSettings.blurUnfocusedLyrics = prefs.getBoolean(SingSettings.KEY_BLUR_UNFOCUSED_LYRICS, true)
            SingSettings.englishTranslationFallback = prefs.getBoolean(SingSettings.KEY_ENGLISH_TRANSLATION_FALLBACK, true)
        } catch (_: Throwable) {}
    }
}
