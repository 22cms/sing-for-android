package org.francasio.sing.hooks

import io.github.libxposed.api.XposedInterface
import org.francasio.sing.audio.SingAudioProcessor
import org.francasio.sing.util.ReflectionUtils
import java.lang.reflect.Executable
import java.nio.ByteBuffer

/** Registers Apple Music eligibility and ExoPlayer audio-pipeline hooks. */
class AudioPipelineHooks(
    private val audioProcessorProvider: () -> SingAudioProcessor?,
    private val onSingModeChanged: (Boolean) -> Unit,
    private val registerHook: (Executable, (XposedInterface.Chain) -> Any?) -> Unit,
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit
) {
    private val audioProcessor: SingAudioProcessor?
        get() = audioProcessorProvider()

    fun install(classLoader: ClassLoader) {
        hookVocalAttenuationState(classLoader)
        hookTrackEligibility(classLoader)
        hookAudioProcessorScaffolding(classLoader)
    }

    private fun hookMethod(
        executable: Executable,
        hooker: (XposedInterface.Chain) -> Any?
    ) = registerHook(executable, hooker)

    private fun isAllowedAudioFormat(mimeType: String?): Boolean {
        if (mimeType.isNullOrEmpty()) return false
        val mime = mimeType.trim().lowercase()
        if (mime.contains("ac3") || mime.contains("ac-3") || mime.contains("ec-3") || mime.contains("joc")) return false
        return mime == "audio/mp4a-latm" || mime == "audio/alac" || mime == "audio/aac" ||
            mime == "audio/he-aac" || mime == "audio/aac-he" || mime == "alac" ||
            mime.contains("alac") || mime.contains("mp4a") || mime.contains("aac")
    }

    private fun hookVocalAttenuationState(cl: ClassLoader) {
        try {
            val companionClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.player.VocalAttenuationStateProvider\$Companion",
                cl
            )
            if (companionClass != null) {
                ReflectionUtils.findMethod(companionClass, "isDeviceSupported")?.let { m ->
                    hookMethod(m) { true }
                }
                ReflectionUtils.findMethod(companionClass, "isCodecInputSupported", String::class.java)?.let { m ->
                    hookMethod(m) { chain ->
                        val mime = chain.getArg(0) as? String
                        val allowed = isAllowedAudioFormat(mime)
                        logInfo("isCodecInputSupported(mime=$mime) -> $allowed")
                        allowed
                    }
                }
                logInfo("Hooked VocalAttenuationStateProvider.Companion (device=true, codec allowed on AAC/ALAC/AAC-HE only)")
            }
        } catch (e: Throwable) {
            logError("Failed to hook VocalAttenuationStateProvider.Companion", e)
        }
    }

    /**
     * 2. Hook playback/queue items to report isVocalAttenuationAllowed based on forceSongEligibility setting
     */
    private fun hookTrackEligibility(cl: ClassLoader) {
        val mediaItemClasses = listOf(
            "com.apple.android.music.model.BasePlaybackItem",
            "com.apple.android.music.playback.model.StoreMediaItem",
            "com.apple.android.music.playback.model.LibraryMediaItem",
            "com.apple.android.music.playback.model.RadioMediaItem"
        )
        for (clsName in mediaItemClasses) {
            try {
                val cls = ReflectionUtils.findClass(clsName, cl)
                if (cls != null) {
                    val eligibilityMethod = ReflectionUtils.findMethod(cls, "isVocalAttenuationAllowed")
                    ?: ReflectionUtils.findMethodBySignature(cls, java.lang.Boolean.TYPE)
                eligibilityMethod?.let { m ->
                        hookMethod(m) { chain ->
                            val isReady = audioProcessor?.isEngineReady == true
                            if (isReady && org.francasio.sing.provider.SingSettings.forceSongEligibility) {
                                true
                            } else {
                                chain.proceed()
                            }
                        }
                        logInfo("Hooked isVocalAttenuationAllowed on $clsName (forceSongEligibility togglable, guarded by isEngineReady)")
                    }
                }
            } catch (e: Throwable) {
                logError("Could not hook isVocalAttenuationAllowed on $clsName", e)
            }
        }
    }

    /**
     * 3. Hook VocalsAudioProcessor:
     *    - Force FORCE_DISABLE to false
     *    - Route audio frames through SingAudioProcessor (ONNX + STFT)
     *    - Neutralize PremResampleAudioProcessor so 24-bit Lossless never throws UnhandledAudioFormatException
     *    - Filter SVAudioRendererV2.createAudioProcessors to return [VocalsAudioProcessor] only
     */
    private fun hookAudioProcessorScaffolding(cl: ClassLoader) {
        try {
            val resampleClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.processors.PremResampleAudioProcessor",
                cl
            )
            val audioFormatClass = ReflectionUtils.findClass(
                "com.google.android.exoplayer2.audio.AudioProcessor\$AudioFormat",
                cl
            )
            if (resampleClass != null && audioFormatClass != null) {
                val notSetFormat = ReflectionUtils.getStaticObjectField(audioFormatClass, "NOT_SET")
                val configureMethod = ReflectionUtils.findMethod(resampleClass, "configure", audioFormatClass)
                if (configureMethod != null) {
                    hookMethod(configureMethod) { notSetFormat }
                    logInfo("Successfully neutralized PremResampleAudioProcessor.configure")
                }
            }
        } catch (e: Throwable) {
            logError("Failed to neutralize PremResampleAudioProcessor", e)
        }

        try {
            val rendererClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.renderer.SVAudioRendererV2",
                cl
            )
            if (rendererClass != null) {
                ReflectionUtils.findMethod(rendererClass, "createAudioProcessors")?.let { m ->
                    hookMethod(m) { chain ->
                        val array = chain.proceed() as? Array<*>
                        val vocalsProcessor = array?.firstOrNull { it != null && it.javaClass.name.contains("VocalsAudioProcessor") }
                        if (vocalsProcessor != null) {
                            val audioProcessorClass = ReflectionUtils.findClass(
                                "com.google.android.exoplayer2.audio.AudioProcessor",
                                cl
                            )
                            if (audioProcessorClass != null) {
                                @Suppress("UNCHECKED_CAST")
                                val newArray = java.lang.reflect.Array.newInstance(audioProcessorClass, 1) as Array<Any>
                                newArray[0] = vocalsProcessor
                                logInfo("SVAudioRendererV2.createAudioProcessors filtered to [VocalsAudioProcessor] only")
                                return@hookMethod newArray
                            }
                        }
                        array
                    }
                    logInfo("Successfully hooked SVAudioRendererV2.createAudioProcessors()")
                }

                ReflectionUtils.findMethod(rendererClass, "onPositionReset", java.lang.Long.TYPE, java.lang.Boolean.TYPE)?.let { m ->
                    hookMethod(m) { chain ->
                        val res = chain.proceed()
                        logInfo("SVAudioRendererV2.onPositionReset triggered reset")
                        audioProcessor?.reset()
                        res
                    }
                    logInfo("Successfully hooked SVAudioRendererV2.onPositionReset")
                }

                ReflectionUtils.findMethod(rendererClass, "render", java.lang.Long.TYPE, java.lang.Long.TYPE)?.let { m ->
                    hookMethod(m) { chain ->
                        val res = chain.proceed()
                        val processor = audioProcessor
                        if (processor?.needsBurst == true) {
                            processor.maybeBurstDecode(chain.thisObject)
                        }
                        res
                    }
                    logInfo("Successfully hooked SVAudioRendererV2.render for burst decoding")
                }
            }
        } catch (e: Throwable) {
            logError("Failed to hook SVAudioRendererV2", e)
        }

        try {
            val sinkClass = ReflectionUtils.findClass(
                "com.google.android.exoplayer2.audio.DefaultAudioSink",
                cl
            )
            if (sinkClass != null) {
                sinkClass.declaredConstructors.forEach { ctor ->
                    ctor.isAccessible = true
                    hookMethod(ctor) { chain ->
                        val res = chain.proceed()
                        try {
                            val sinkObj = chain.thisObject
                            val toIntProcessors = ReflectionUtils.getObjectField(sinkObj, "toIntPcmAvailableAudioProcessors") as? Array<*>
                            val vocalsProcessor = toIntProcessors?.firstOrNull { it != null && it.javaClass.name.contains("VocalsAudioProcessor") }
                            if (vocalsProcessor != null) {
                                val toFloatProcessors = ReflectionUtils.getObjectField(sinkObj, "toFloatPcmAvailableAudioProcessors") as? Array<*>
                                val floatResampler = toFloatProcessors?.firstOrNull { it != null && it.javaClass.name.contains("FloatResamplingAudioProcessor") }
                                val audioProcessorClass = ReflectionUtils.findClass("com.google.android.exoplayer2.audio.AudioProcessor", cl)
                                if (audioProcessorClass != null) {
                                    @Suppress("UNCHECKED_CAST")
                                    val newToFloat = if (floatResampler != null) {
                                        val arr = java.lang.reflect.Array.newInstance(audioProcessorClass, 2) as Array<Any>
                                        arr[0] = floatResampler
                                        arr[1] = vocalsProcessor
                                        arr
                                    } else {
                                        val arr = java.lang.reflect.Array.newInstance(audioProcessorClass, 1) as Array<Any>
                                        arr[0] = vocalsProcessor
                                        arr
                                    }
                                    ReflectionUtils.setObjectField(sinkObj, "toFloatPcmAvailableAudioProcessors", newToFloat)
                                    logInfo("Successfully patched DefaultAudioSink.toFloatPcmAvailableAudioProcessors with [FloatResampler, VocalsAudioProcessor]")
                                }
                            }
                        } catch (t: Throwable) {
                            logError("Error patching DefaultAudioSink toFloat processors", t)
                        }
                        res
                    }
                }
                logInfo("Successfully hooked DefaultAudioSink constructors")
            }
        } catch (e: Throwable) {
            logError("Failed to hook DefaultAudioSink constructors", e)
        }

        val processorClass = ReflectionUtils.findClass(
            "com.apple.android.music.playback.processors.VocalsAudioProcessor",
            cl
        )
        if (processorClass == null) {
            logInfo("Could not find VocalsAudioProcessor")
            return
        }

        try {
            processorClass.declaredConstructors.forEach { ctor ->
                ctor.isAccessible = true
                hookMethod(ctor) { chain ->
                    val res = chain.proceed()
                    try {
                        ReflectionUtils.setBooleanField(chain.thisObject, "FORCE_DISABLE", false)
                        logInfo("Set VocalsAudioProcessor.FORCE_DISABLE = false")
                    } catch (e: Throwable) {
                        logError("Could not set FORCE_DISABLE", e)
                    }
                    res
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook VocalsAudioProcessor constructors", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "queueInput", ByteBuffer::class.java)?.let { m ->
                hookMethod(m) { chain ->
                    val processorObj = chain.thisObject
                    val inputBuffer = chain.getArg(0) as ByteBuffer
                    val processor = audioProcessor
                    if (processor != null) {
                        processor.processQueueInput(processorObj, inputBuffer)
                    } else {
                        SingAudioProcessor.passThrough(processorObj, inputBuffer)
                    }
                    null
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook queueInput", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "onFlush")?.let { m ->
                hookMethod(m) { chain ->
                    val res = chain.proceed()
                    audioProcessor?.reset()
                    res
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook onFlush", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "onQueueEndOfStream")?.let { m ->
                hookMethod(m) { chain ->
                    val res = chain.proceed()
                    audioProcessor?.onEndOfStream()
                    res
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook onQueueEndOfStream", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "getOutput")?.let { m ->
                hookMethod(m) { chain ->
                    val processor = audioProcessor
                    if (processor != null && processor.isEnabled && chain.thisObject.javaClass.name.contains("VocalsAudioProcessor")) {
                        val currentOutput = ReflectionUtils.getObjectField(chain.thisObject, "outputBuffer") as? ByteBuffer
                        if (currentOutput == null || !currentOutput.hasRemaining()) {
                            val drainBuf = processor.drainPendingOutput(chain.thisObject)
                            if (drainBuf != null) {
                                return@hookMethod chain.proceed()
                            }
                        }
                    }
                    chain.proceed()
                }
            }
            logInfo("Successfully hooked VocalsAudioProcessor.getOutput for stream draining")
        } catch (t: Throwable) {
            logError("Failed to hook getOutput", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "isEnded")?.let { m ->
                hookMethod(m) { chain ->
                    val processor = audioProcessor
                    if (processor != null && processor.isEnabled) {
                        processor.isEnded()
                    } else {
                        chain.proceed()
                    }
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook isEnded", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "setLevel", java.lang.Float.TYPE)?.let { m ->
                hookMethod(m) { chain ->
                    val rawLevel = chain.getArg(0) as Float
                    audioProcessor?.vocalLevel = rawLevel
                    logInfo("VocalsAudioProcessor.setLevel: $rawLevel")
                    chain.proceed()
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook setLevel", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "setMode", java.lang.Integer.TYPE)?.let { m ->
                hookMethod(m) { chain ->
                    val mode = chain.getArg(0) as Int
                    val enabled = (mode == 1)
                    val processor = audioProcessor
                    val wasSingActive = (processor?.isEnabled == true)
                    if (processor != null) {
                        processor.isEnabled = enabled
                    }
                    onSingModeChanged(enabled)
                    logInfo("VocalsAudioProcessor.setMode: mode=$mode (enabled=$enabled)")
                    chain.proceed()
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook setMode", t)
        }

        logInfo("Hooked VocalsAudioProcessor successfully")
    }

}
