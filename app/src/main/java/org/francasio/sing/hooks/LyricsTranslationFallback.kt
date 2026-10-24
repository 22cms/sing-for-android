package org.francasio.sing.hooks

import android.os.Handler
import io.github.libxposed.api.XposedInterface
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.util.ReflectionUtils
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Falls back to Apple's English lyrics localization without depending on the
 * obfuscated Media API implementation classes.
 *
 * Apple Music requests the locale stored by PlayerLyricsViewModel when
 * loadLyrics() starts. Once its stable native TTML parser returns SongInfo, we
 * can tell whether that locale was delivered. If not, loadLyrics() is repeated
 * once with en-US; the exact English locale returned by the server is then used.
 */
class LyricsTranslationFallback(
    private val mainHandler: Handler,
    private val registerHook: (Executable, (XposedInterface.Chain) -> Any?) -> Unit,
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit
) {
    private data class LoadState(
        val viewModel: WeakReference<Any>,
        val playbackItem: WeakReference<Any>,
        val itemKey: String,
        val originalLocale: String,
        var fallbackAttempted: Boolean
    )

    private val stateLock = Any()
    private val invokingFallback = ThreadLocal<Boolean>()
    private var activeLoad: LoadState? = null
    private var localeField: Field? = null
    private var loadLyricsMethod: Method? = null

    fun install(classLoader: ClassLoader) {
        try {
            val viewModelClass = ReflectionUtils.findClass(
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
                classLoader
            ) ?: return
            val playbackItemClass = ReflectionUtils.findClass(
                "com.apple.android.music.model.PlaybackItem",
                classLoader
            ) ?: return
            val parserClass = ReflectionUtils.findClass(
                "com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative",
                classLoader
            ) ?: return
            val songInfoPtrClass = ReflectionUtils.findClass(
                "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr",
                classLoader
            ) ?: return

            val loadMethod = ReflectionUtils.findMethodBySignature(
                viewModelClass,
                Void.TYPE,
                playbackItemClass
            ) ?: return
            val parseMethod = ReflectionUtils.findMethodBySignature(
                parserClass,
                songInfoPtrClass,
                String::class.java
            ) ?: return
            loadLyricsMethod = loadMethod

            registerHook(loadMethod) { chain ->
                val viewModel = chain.thisObject
                val playbackItem = chain.getArg(0)
                val isFallback = invokingFallback.get() == true
                val systemLocale = resolveSystemLyricsLocale(classLoader)
                val field = resolveLocaleField(viewModel, systemLocale)

                if (!SingSettings.englishTranslationFallback) {
                    synchronized(stateLock) { activeLoad = null }
                    return@registerHook chain.proceed()
                }

                if (!isFallback) {
                    if (field != null && systemLocale != null) field.set(viewModel, systemLocale)
                    val locale = systemLocale ?: field?.get(viewModel) as? String ?: "en-US"
                    synchronized(stateLock) {
                        activeLoad = LoadState(
                            WeakReference(viewModel),
                            WeakReference(playbackItem),
                            itemKey(playbackItem),
                            locale,
                            fallbackAttempted = false
                        )
                    }
                }
                chain.proceed()
            }

            registerHook(parseMethod) { chain ->
                val songInfoPtr = chain.proceed()
                if (songInfoPtr != null) handleParsedSongInfo(songInfoPtr)
                songInfoPtr
            }

            logInfo("Installed locale-independent English lyrics translation fallback")
        } catch (t: Throwable) {
            logError("Failed to install English lyrics translation fallback", t)
        }
    }

    fun onSettingsChanged() {
        if (SingSettings.englishTranslationFallback) return
        val state = synchronized(stateLock) {
            activeLoad.also { activeLoad = null }
        } ?: return
        val viewModel = state.viewModel.get() ?: return
        try {
            localeField?.set(viewModel, state.originalLocale)
        } catch (t: Throwable) {
            logError("Failed to cancel English lyrics translation fallback", t)
        }
    }

    private fun handleParsedSongInfo(songInfoPtr: Any) {
        val state = synchronized(stateLock) { activeLoad } ?: return
        val viewModel = state.viewModel.get() ?: return clearState(state)
        val playbackItem = state.playbackItem.get() ?: return clearState(state)

        if (!SingSettings.englishTranslationFallback) {
            localeField?.set(viewModel, state.originalLocale)
            clearState(state)
            return
        }

        try {
            val songInfo = ReflectionUtils.callMethod(songInfoPtr, "get") ?: return
            val field = localeField ?: resolveLocaleField(viewModel, state.originalLocale) ?: return
            val requestedLocale = field.get(viewModel) as? String ?: state.originalLocale

            if (hasTranslation(songInfo, requestedLocale)) {
                clearState(state)
                return
            }

            val availableEnglish = findEnglishTranslation(songInfo)
            if (availableEnglish != null) {
                field.set(viewModel, availableEnglish)
                logInfo(
                    "Using Apple lyrics translation fallback $availableEnglish " +
                        "for ${state.itemKey} (requested $requestedLocale)"
                )
                clearState(state)
                return
            }

            if (state.fallbackAttempted) {
                field.set(viewModel, state.originalLocale)
                logInfo("No Apple English lyrics translation available for ${state.itemKey}")
                clearState(state)
                return
            }

            state.fallbackAttempted = true
            mainHandler.post {
                val currentState = synchronized(stateLock) { activeLoad }
                if (currentState !== state) return@post
                if (!SingSettings.englishTranslationFallback) {
                    field.set(viewModel, state.originalLocale)
                    clearState(state)
                    return@post
                }
                try {
                    field.set(viewModel, ENGLISH_REQUEST_LOCALE)
                    invokingFallback.set(true)
                    loadLyricsMethod?.invoke(viewModel, playbackItem)
                    logInfo(
                        "Requested Apple English lyrics translation for ${state.itemKey} " +
                            "after $requestedLocale was unavailable"
                    )
                } catch (t: Throwable) {
                    field.set(viewModel, state.originalLocale)
                    clearState(state)
                    logError("Failed to request English lyrics translation fallback", t)
                } finally {
                    invokingFallback.remove()
                }
            }
        } catch (t: Throwable) {
            clearState(state)
            logError("Failed to inspect lyrics translations", t)
        }
    }

    private fun hasTranslation(songInfo: Any, locale: String): Boolean =
        try {
            ReflectionUtils.callMethod(songInfo, "hasTranslation", locale) as? Boolean == true
        } catch (_: Throwable) {
            false
        }

    private fun findEnglishTranslation(songInfo: Any): String? {
        val languages = try {
            ReflectionUtils.callMethod(songInfo, "getTranslationLanguages")
        } catch (_: Throwable) {
            null
        } ?: return null
        val size = try {
            (ReflectionUtils.callMethod(languages, "size") as? Number)?.toLong() ?: 0L
        } catch (_: Throwable) {
            0L
        }
        for (index in 0 until size) {
            val locale = try {
                ReflectionUtils.callMethod(languages, "get", index) as? String
            } catch (_: Throwable) {
                null
            } ?: continue
            val language = locale.substringBefore('-').substringBefore('_')
            if (language.equals("en", ignoreCase = true)) return locale
        }
        return null
    }

    private fun resolveLocaleField(viewModel: Any, expectedLocale: String?): Field? {
        localeField?.let { return it }
        ReflectionUtils.findField(viewModel.javaClass, "currentSystemLyricsLanguage")?.let {
            localeField = it
            return it
        }
        var current: Class<*>? = viewModel.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) && field.type == String::class.java && run {
                    field.isAccessible = true
                    expectedLocale != null && field.get(viewModel) == expectedLocale
                }
            }?.let {
                localeField = it
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun resolveSystemLyricsLocale(classLoader: ClassLoader): String? = try {
        val localeUtil = ReflectionUtils.findClass(
            "com.apple.android.music.playback.util.LocaleUtil",
            classLoader
        ) ?: return null
        val method = localeUtil.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.returnType == String::class.java &&
                it.parameterTypes.isEmpty() &&
                it.name == "getSystemLyricsLanguage"
        } ?: return null
        method.isAccessible = true
        method.invoke(null) as? String
    } catch (_: Throwable) {
        null
    }

    private fun itemKey(playbackItem: Any): String = try {
        ReflectionUtils.callMethod(playbackItem, "getId")?.toString()
            ?: "item@${System.identityHashCode(playbackItem)}"
    } catch (_: Throwable) {
        "item@${System.identityHashCode(playbackItem)}"
    }

    private fun clearState(state: LoadState) {
        synchronized(stateLock) {
            if (activeLoad === state) activeLoad = null
        }
    }

    companion object {
        private const val ENGLISH_REQUEST_LOCALE = "en-US"
    }
}
