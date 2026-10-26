package org.francasio.sing.audio

import android.content.Context
import android.util.Log
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.util.ModelLoader
import org.francasio.sing.util.ReflectionUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Real-time Vocal Attenuation Engine for Apple Music Sing.
 * Operates directly on ExoPlayer's VocalsAudioProcessor pipeline.
 * Fully supports Standard AAC, Apple Lossless (ALAC), Hi-Res Lossless (44.1, 48, 96, 192 kHz),
 * 16-bit, 24-bit, and 32-bit float/integer PCM encodings.
 */
class SingAudioProcessor(
    private val context: Context,
    private val moduleApkPath: String,
    private val managerInfo: String? = null,
    private val onSettingsChanged: () -> Unit = {}
) {
    companion object {
        private const val TAG = "AppleSing_Processor"

        // ExoPlayer Encoding Constants
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4
        const val ENCODING_PCM_24BIT = 0x20000000 // 536,870,912
        const val ENCODING_PCM_32BIT = 0x30000000 // 805,306,368

        fun getBytesPerSample(encoding: Int): Int = when (encoding) {
            ENCODING_PCM_16BIT -> 2
            ENCODING_PCM_FLOAT -> 4
            ENCODING_PCM_24BIT -> 3
            ENCODING_PCM_32BIT -> 4
            else -> 2
        }

        /**
         * Safe fallback pass-through supporting 16-bit, 24-bit, 32-bit int, and float PCM.
         * Guarantees ExoPlayer never starves for buffers and never outputs garbled noise.
         */
        @JvmStatic
        fun passThrough(processorObj: Any, inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return

            try {
                val inputFormat = ReflectionUtils.getObjectField(processorObj, "inputAudioFormat")
                val encoding = ReflectionUtils.getIntField(inputFormat, "encoding")
                val channelCount = ReflectionUtils.getIntField(inputFormat, "channelCount")
                val bytesPerSample = getBytesPerSample(encoding)

                val remainingBytes = inputBuffer.remaining()
                val frameCount = remainingBytes / (channelCount * bytesPerSample)
                if (frameCount <= 0) {
                    inputBuffer.position(inputBuffer.limit())
                    return
                }

                val totalSamples = frameCount * channelCount
                val outputBytes = totalSamples * 4 // Output is always 32-bit Float PCM
                val outputBuf = ReflectionUtils.callMethod(
                    processorObj,
                    "replaceOutputBuffer",
                    outputBytes
                ) as ByteBuffer

                outputBuf.clear()
                outputBuf.order(ByteOrder.LITTLE_ENDIAN)
                val outFloatBuf = outputBuf.asFloatBuffer()

                inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                when (encoding) {
                    ENCODING_PCM_16BIT -> {
                        val shortBuf = inputBuffer.asShortBuffer()
                        val factor = 1.0f / 32768.0f
                        for (i in 0 until totalSamples) {
                            outFloatBuf.put(shortBuf.get() * factor)
                        }
                    }
                    ENCODING_PCM_FLOAT -> {
                        outFloatBuf.put(inputBuffer.asFloatBuffer())
                    }
                    ENCODING_PCM_24BIT -> {
                        val startPos = inputBuffer.position()
                        val factor = 1.0f / 8388608.0f
                        for (i in 0 until totalSamples) {
                            val b0 = inputBuffer.get(startPos + i * 3).toInt() and 0xFF
                            val b1 = inputBuffer.get(startPos + i * 3 + 1).toInt() and 0xFF
                            val b2 = inputBuffer.get(startPos + i * 3 + 2).toInt() // signed high byte
                            val sampleInt = (b2 shl 16) or (b1 shl 8) or b0
                            outFloatBuf.put(sampleInt * factor)
                        }
                    }
                    ENCODING_PCM_32BIT -> {
                        val intBuf = inputBuffer.asIntBuffer()
                        val factor = 1.0f / 2147483648.0f
                        for (i in 0 until totalSamples) {
                            outFloatBuf.put(intBuf.get() * factor)
                        }
                    }
                    else -> {
                        val shortBuf = inputBuffer.asShortBuffer()
                        val factor = 1.0f / 32768.0f
                        for (i in 0 until totalSamples) {
                            outFloatBuf.put(shortBuf.get() * factor)
                        }
                    }
                }

                inputBuffer.position(inputBuffer.limit())
                outputBuf.position(outputBytes)
                outputBuf.flip()
            } catch (e: Throwable) {
                Log.e(TAG, "Error in passThrough: ${e.message}", e)
                inputBuffer.position(inputBuffer.limit())
            }
        }
    }

    @Volatile
    var vocalLevel: Float = 0.0f

    @Volatile
    var isEnabled: Boolean = false
        set(value) {
            field = value
            if (value && aiEngine == null) {
                ensureEngineInitialized()
            }
        }

    @Volatile
    var isEngineReady: Boolean = false
        private set

    var onEngineReadyListener: (() -> Unit)? = null

    val bufferedCushionMs: Long
        get() = aiEngine?.let { engine ->
            val cushion = engine.cushionFrames
            if (cushion > 0L) (cushion * 1000L) / 44100L else 0L
        } ?: 0L

    private var smoothedLevel: Float = 0.0f

    // Reusable arrays to prevent any GC churn on the audio playback thread
    private var inFloats = FloatArray(65536)
    private var outFloats = FloatArray(65536)
    private var inShorts = ShortArray(65536)

    // Resampler buffers for non-44.1 kHz tracks (Lossless 48k/96k/192k)
    private var resampledInFloats = FloatArray(65536)
    private var resampledOutFloats = FloatArray(65536)
    private var downResampler: StereoResampler? = null
    private var upResampler: StereoResampler? = null
    private var currentSampleRate: Int = -1

    private var logCounter = 0

    @Volatile
    private var aiEngine: AiStemSeparationEngine? = null
    private val bgExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "AppleSing_EngineInit")
    }

    init {
        // Initialize settings and register live update receiver
        SingSettings.initInAppleMusic(context, managerInfo) {
            val selectedModel = SingSettings.selectedModelId
            if (selectedModel.isEmpty()) {
                val old = aiEngine
                aiEngine = null
                old?.deactivate()
                Log.i(TAG, "No model selected. Neural Stem Separation Engine disabled (pass-through mode).")
                isEngineReady = true
                onEngineReadyListener?.invoke()
                onSettingsChanged()
                return@initInAppleMusic
            }

            val engine = aiEngine
            if (engine != null) {
                engine.preserveSide = SingSettings.preserveSide
                engine.twoWaySlider = SingSettings.twoWaySlider
                engine.ecoMode = SingSettings.ecoMode
                Log.i(TAG, "Updated live settings: preserveSide=${engine.preserveSide}, twoWaySlider=${engine.twoWaySlider}, ecoMode=${engine.ecoMode}")

                val currentThreads = ModelLoader.threadCount
                val currentTarget = ModelLoader.currentTarget
                val currentModel = ModelLoader.currentModelId
                if (SingSettings.threadCount != currentThreads || SingSettings.hardwareTarget != currentTarget || selectedModel != currentModel) {
                    if (selectedModel != currentModel) {
                        isEngineReady = false
                    }
                    bgExecutor.execute {
                        try {
                            val activePath = SingSettings.resolvedModulePath ?: moduleApkPath
                            val newBackend = ModelLoader.getOrCreateBackend(
                                context,
                                activePath,
                                SingSettings.threadCount,
                                SingSettings.hardwareTarget,
                                selectedModel
                            )
                            if (newBackend != null) {
                                engine.updateBackend(newBackend)
                                isEngineReady = true
                                onEngineReadyListener?.invoke()
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to update inference backend: ${t.message}")
                        }
                    }
                }
            } else {
                ensureEngineInitialized()
            }
            onSettingsChanged()
        }

        // Asynchronously warm up the engine in background executor
        ensureEngineInitialized()
    }

    fun ensureEngineInitialized() {
        val selectedModel = SingSettings.selectedModelId
        if (selectedModel.isEmpty()) {
            isEngineReady = true
            onEngineReadyListener?.invoke()
            return
        }
        val currentEngine = aiEngine
        if (currentEngine != null && ModelLoader.currentModelId == selectedModel) {
            isEngineReady = true
            return
        }

        bgExecutor.execute {
            val engine = aiEngine
            val targetModel = SingSettings.selectedModelId
            if (engine != null && ModelLoader.currentModelId == targetModel) {
                isEngineReady = true
                return@execute
            }
            try {
                val activePath = SingSettings.resolvedModulePath ?: moduleApkPath
                val backend = ModelLoader.getOrCreateBackend(
                    context,
                    activePath,
                    SingSettings.threadCount,
                    SingSettings.hardwareTarget,
                    targetModel
                )
                if (backend != null) {
                    if (engine != null) {
                        engine.updateBackend(backend)
                    } else {
                        val newEngine = AiStemSeparationEngine(backend)
                        newEngine.preserveSide = SingSettings.preserveSide
                        newEngine.twoWaySlider = SingSettings.twoWaySlider
                        newEngine.ecoMode = SingSettings.ecoMode
                        aiEngine = newEngine
                    }
                    isEngineReady = true
                    Log.i(TAG, "Neural Stem Separation Engine initialized (${ModelLoader.activeProviderName})")
                    SingSettings.sendHeartbeat(context, managerInfo, ModelLoader.activeProviderShort)
                    onEngineReadyListener?.invoke()
                } else {
                    Log.w(TAG, "Backend initialization returned null")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize AI Engine: ${e.message}", e)
            }
        }
    }

    private fun ensureCapacity(requiredSamples: Int) {
        if (inFloats.size < requiredSamples) {
            val newSize = maxOf(inFloats.size * 2, requiredSamples + 8192)
            inFloats = FloatArray(newSize)
            outFloats = FloatArray(newSize)
            inShorts = ShortArray(newSize)
            resampledInFloats = FloatArray(newSize)
            resampledOutFloats = FloatArray(newSize)
        }
    }

    /**
     * Replaces VocalsAudioProcessor.queueInput(ByteBuffer inputBuffer).
     * Runs synchronously on ExoPlayer's playback thread with < 0.1ms compute time,
     * delegating heavy neural network computation to the background AI streaming engine.
     */
    fun processQueueInput(processorObj: Any, inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }

        try {
            val singActive = isEnabled
            val engine = aiEngine

            // If Sing is OFF or AI engine uninitialized: direct pass-through with 0% CPU and 0 latency
            if (!singActive || engine == null || !isEngineReady) {
                if (engine?.active == true) {
                    engine.deactivate()
                    downResampler?.reset()
                    upResampler?.reset()
                }
                passThrough(processorObj, inputBuffer)
                try {
                    ReflectionUtils.setFloatField(processorObj, "level", 1.0f)
                    ReflectionUtils.setIntField(processorObj, "mode", 0)
                } catch (t: Throwable) {}
                return
            }

            // Sing is ON: ensure engine is activated
            if (!engine.active) {
                engine.activate()
            }

            val inputFormat = ReflectionUtils.getObjectField(processorObj, "inputAudioFormat")
            val encoding = ReflectionUtils.getIntField(inputFormat, "encoding")
            val channelCount = ReflectionUtils.getIntField(inputFormat, "channelCount")
            val sampleRate = ReflectionUtils.getIntField(inputFormat, "sampleRate")
            val bytesPerSample = getBytesPerSample(encoding)

            val remainingBytes = inputBuffer.remaining()
            val frameCount = remainingBytes / (channelCount * bytesPerSample)
            if (frameCount <= 0) {
                inputBuffer.position(inputBuffer.limit())
                return
            }

            val framesToAdd = if (sampleRate == 44100) frameCount else ((frameCount * 44100L) / sampleRate).toInt()

            val canAccept = engine.canAcceptInput(framesToAdd)
            if (!canAccept && !engine.isReady) {
                // Initial pre-roll: halt decoder without consuming inputBuffer while awaiting chunk 0
                engine.awaitProgress(20)
                val outputBuf = ReflectionUtils.callMethod(
                    processorObj,
                    "replaceOutputBuffer",
                    0
                ) as ByteBuffer
                outputBuf.position(0)
                outputBuf.flip()
                return
            }

            // Mono tracks are not supported by the stem engine: pass through
            // untouched instead of feeding stale buffers to the output path.
            if (channelCount < 2) {
                passThrough(processorObj, inputBuffer)
                try {
                    ReflectionUtils.setFloatField(processorObj, "level", smoothedLevel)
                    ReflectionUtils.setIntField(processorObj, "mode", 0)
                } catch (t: Throwable) {}
                return
            }

            val totalSamples = frameCount * channelCount
            ensureCapacity(maxOf(65536, totalSamples * 8))

            val targetLevel = vocalLevel
            smoothedLevel += 0.08f * (targetLevel - smoothedLevel)

            if (++logCounter % 200 == 1) {
                Log.i(TAG, "Audio frames active: count=$logCounter, frames=$frameCount, enc=$encoding, ch=$channelCount, rate=$sampleRate, level=$smoothedLevel, singActive=$singActive, preserveSide=${engine.preserveSide}, twoWay=${engine.twoWaySlider}")
                if (logCounter % 600 == 1) {
                    SingSettings.sendHeartbeat(context, managerInfo, ModelLoader.activeProviderShort)
                }
            }

            if (canAccept) {
                // Decode incoming PCM bytes to normalized floats [-1.0, 1.0]
                inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                when (encoding) {
                    ENCODING_PCM_16BIT -> {
                        val shortBuf = inputBuffer.asShortBuffer()
                        shortBuf.get(inShorts, 0, totalSamples)
                        val factor = 1.0f / 32768.0f
                        for (i in 0 until totalSamples) {
                            inFloats[i] = inShorts[i] * factor
                        }
                    }
                    ENCODING_PCM_FLOAT -> {
                        inputBuffer.asFloatBuffer().get(inFloats, 0, totalSamples)
                    }
                    ENCODING_PCM_24BIT -> {
                        val startPos = inputBuffer.position()
                        val factor = 1.0f / 8388608.0f
                        for (i in 0 until totalSamples) {
                            val b0 = inputBuffer.get(startPos + i * 3).toInt() and 0xFF
                            val b1 = inputBuffer.get(startPos + i * 3 + 1).toInt() and 0xFF
                            val b2 = inputBuffer.get(startPos + i * 3 + 2).toInt()
                            val sampleInt = (b2 shl 16) or (b1 shl 8) or b0
                            inFloats[i] = sampleInt * factor
                        }
                    }
                    ENCODING_PCM_32BIT -> {
                        val intBuf = inputBuffer.asIntBuffer()
                        val factor = 1.0f / 2147483648.0f
                        for (i in 0 until totalSamples) {
                            inFloats[i] = intBuf.get() * factor
                        }
                    }
                    else -> {
                        val shortBuf = inputBuffer.asShortBuffer()
                        shortBuf.get(inShorts, 0, totalSamples)
                        val factor = 1.0f / 32768.0f
                        for (i in 0 until totalSamples) {
                            inFloats[i] = inShorts[i] * factor
                        }
                    }
                }
                inputBuffer.position(inputBuffer.limit())

                if (channelCount >= 2) {
                    if (sampleRate == 44100) {
                        engine.pushInput(inFloats, frameCount)
                    } else {
                        if (sampleRate != currentSampleRate || downResampler == null) {
                            currentSampleRate = sampleRate
                            downResampler?.close()
                            upResampler?.close()
                            downResampler = StereoResampler(sampleRate, 44100)
                            upResampler = StereoResampler(44100, sampleRate)
                        }
                        val frames44k = downResampler!!.resample(inFloats, frameCount, resampledInFloats)
                        engine.pushInput(resampledInFloats, frames44k)
                    }
                }
            } else {
                // Backpressure: do not feed the engine, but still consume the
                // input buffer so ExoPlayer advances instead of reprocessing.
                inputBuffer.position(inputBuffer.limit())
            }

            if (channelCount >= 2) {
                val outputFrames: Int

                if (sampleRate == 44100) {
                    outputFrames = engine.popVocalAndBlend(outFloats, frameCount, smoothedLevel)
                } else {
                    if (sampleRate != currentSampleRate || upResampler == null) {
                        currentSampleRate = sampleRate
                        downResampler?.close()
                        upResampler?.close()
                        downResampler = StereoResampler(sampleRate, 44100)
                        upResampler = StereoResampler(44100, sampleRate)
                    }
                    val frames44k = if (sampleRate > 0) ((frameCount * 44100L) / sampleRate).toInt() else frameCount
                    val popped44k = engine.popVocalAndBlend(resampledOutFloats, frames44k, smoothedLevel)
                    outputFrames = upResampler!!.resample(resampledOutFloats, popped44k, outFloats)
                }

                val outputBytes = outputFrames * channelCount * 4
                val outputBuf = ReflectionUtils.callMethod(
                    processorObj,
                    "replaceOutputBuffer",
                    outputBytes
                ) as ByteBuffer

                outputBuf.clear()
                outputBuf.order(ByteOrder.LITTLE_ENDIAN)
                if (outputBytes > 0) {
                    outputBuf.asFloatBuffer().put(outFloats, 0, outputFrames * channelCount)
                }
                outputBuf.position(outputBytes)
                outputBuf.flip()
            } else {
                // Fallback pass-through for mono tracks
                val outputBytes = totalSamples * 4
                val outputBuf = ReflectionUtils.callMethod(
                    processorObj,
                    "replaceOutputBuffer",
                    outputBytes
                ) as ByteBuffer

                outputBuf.clear()
                outputBuf.order(ByteOrder.LITTLE_ENDIAN)
                outputBuf.asFloatBuffer().put(inFloats, 0, totalSamples)
                outputBuf.position(outputBytes)
                outputBuf.flip()
            }

            // Update VocalsAudioProcessor internal fields
            try {
                ReflectionUtils.setFloatField(processorObj, "level", smoothedLevel)
                ReflectionUtils.setIntField(processorObj, "mode", 1)
            } catch (t: Throwable) {}

        } catch (e: Throwable) {
            Log.e(TAG, "Error in processQueueInput: ${e.message}", e)
        }
    }

    /**
     * Reset state on track seeks or audio flushes.
     */
    fun reset() {
        aiEngine?.reset()
        downResampler?.reset()
        upResampler?.reset()
        java.util.Arrays.fill(inFloats, 0.0f)
        java.util.Arrays.fill(outFloats, 0.0f)
        java.util.Arrays.fill(inShorts, 0.toShort())
        java.util.Arrays.fill(resampledInFloats, 0.0f)
        java.util.Arrays.fill(resampledOutFloats, 0.0f)
        smoothedLevel = vocalLevel
    }

    fun onEndOfStream() {
        aiEngine?.onEndOfStream()
    }

    fun isEnded(): Boolean {
        return aiEngine?.isEnded() ?: true
    }

    val needsBurst: Boolean
        get() = isEnabled && (aiEngine?.needsInitialChunkBurst == true)

    /**
     * Bounded burst decoding on SVAudioRendererV2 to prime Chunk 0 in ~50-100ms
     * instead of waiting 5.92s of real-time audio playback.
     */
    fun maybeBurstDecode(rendererObj: Any) {
        val engine = aiEngine ?: return
        if (!isEnabled || !engine.needsInitialChunkBurst) return

        try {
            var burstCount = 0
            while (burstCount < 64 && engine.needsInitialChunkBurst) {
                val hasOutput = ReflectionUtils.callMethod(rendererObj, "consumeDecoderOutputBuffers") as? Boolean ?: false
                ReflectionUtils.callMethod(rendererObj, "consumeFromInputStream")
                if (!hasOutput) break
                burstCount++
            }
            if (burstCount > 0) {
                Log.d(TAG, "Burst-decoded $burstCount audio buffers for Chunk 0 priming")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error during burst decode: ${t.message}")
        }
    }

    /**
     * Called by VocalsAudioProcessor.getOutput() hook at end of stream
     * to drain the trailing audio frames so songs don't end prematurely.
     */
    fun drainPendingOutput(processorObj: Any): ByteBuffer? {
        val engine = aiEngine ?: return null
        if (!isEnabled || !engine.active || !engine.isEndOfStream) return null

        val inputFormat = try {
            ReflectionUtils.getObjectField(processorObj, "inputAudioFormat")
        } catch (t: Throwable) {
            return null
        }
        val channelCount = ReflectionUtils.getIntField(inputFormat, "channelCount")
        val sampleRate = ReflectionUtils.getIntField(inputFormat, "sampleRate")

        if (channelCount < 2) return null
        if (!engine.hasPendingOutput) return null

        val available44k = engine.availableVocalFrames
        if (available44k <= 0L) return null

        val drainFrames44k = minOf(2048L, available44k).toInt()
        ensureCapacity(maxOf(65536, drainFrames44k * 8))

        val outputFrames: Int
        if (sampleRate == 44100) {
            outputFrames = engine.popVocalAndBlend(outFloats, drainFrames44k, smoothedLevel)
        } else {
            val popped44k = engine.popVocalAndBlend(resampledOutFloats, drainFrames44k, smoothedLevel)
            if (popped44k == 0) return null
            outputFrames = upResampler?.resample(resampledOutFloats, popped44k, outFloats) ?: 0
        }

        if (outputFrames <= 0) return null

        val outputBytes = outputFrames * channelCount * 4
        val outputBuf = ReflectionUtils.callMethod(
            processorObj,
            "replaceOutputBuffer",
            outputBytes
        ) as ByteBuffer

        outputBuf.clear()
        outputBuf.order(ByteOrder.LITTLE_ENDIAN)
        outputBuf.asFloatBuffer().put(outFloats, 0, outputFrames * channelCount)
        outputBuf.position(outputBytes)
        outputBuf.flip()
        return outputBuf
    }

    fun release() {
        aiEngine?.release()
        downResampler?.close()
        upResampler?.close()
        downResampler = null
        upResampler = null
        bgExecutor.shutdown()
    }

    /**
     * Audiophile-grade bandlimited Blackman-windowed sinc resampler with continuous phase preservation across buffer boundaries.
     */
    class StereoResampler(fromRate: Int, toRate: Int) : AutoCloseable {
        private val nativeResampler = StftEngine.NativeSincResampler(fromRate, toRate)

        fun reset() {
            nativeResampler.reset()
        }

        fun resample(inFloats: FloatArray, inFrames: Int, outFloats: FloatArray): Int {
            return nativeResampler.resample(inFloats, inFrames, outFloats)
        }

        override fun close() {
            nativeResampler.close()
        }
    }
}
