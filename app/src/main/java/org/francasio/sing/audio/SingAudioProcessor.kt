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

        private fun isSupportedEncoding(encoding: Int): Boolean = when (encoding) {
            ENCODING_PCM_16BIT, ENCODING_PCM_FLOAT, ENCODING_PCM_24BIT, ENCODING_PCM_32BIT -> true
            else -> false
        }

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
                val outputBytes = inputBuffer.remaining()
                val outputBuf = ReflectionUtils.callMethod(
                    processorObj,
                    "replaceOutputBuffer",
                    outputBytes
                ) as ByteBuffer

                outputBuf.clear()
                PcmOutput.copy(inputBuffer, outputBuf)

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
    @set:Synchronized
    var isEnabled: Boolean = false
        set(value) {
            val wasEnabled = field
            field = value
            if (value && !wasEnabled) {
                if (aiEngine == null) {
                    ensureEngineInitialized()
                }
                // Activation/reset belongs to the playback thread, not the UI.
            } else if (!value && wasEnabled) {
                aiEngine?.deactivate()
                downResampler?.reset()
                upResampler?.reset()
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
    private var sourceFrames = 0L
    private var outputFramesWritten = 0L
    private var sourceEnded = false
    private var outputResamplerDrained = false
    @Volatile private var released = false
    private val initializationGeneration = java.util.concurrent.atomic.AtomicLong()

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
            ensureEngineInitialized()
            onSettingsChanged()
        }

        // Asynchronously warm up the engine in background executor
        ensureEngineInitialized()
    }

    @Synchronized
    fun ensureEngineInitialized() {
        if (released) return
        val generation = initializationGeneration.incrementAndGet()
        val selectedModel = SingSettings.selectedModelId
        val threads = SingSettings.threadCount
        val target = SingSettings.hardwareTarget
        if (ModelLoader.currentModelId != selectedModel || ModelLoader.currentTarget != target ||
            (target != SingSettings.TARGET_NPU && ModelLoader.threadCount != threads)) {
            isEngineReady = false
        }
        bgExecutor.execute {
            if (released || generation != initializationGeneration.get()) return@execute
            try {
                if (selectedModel.isEmpty()) {
                    val old = synchronized(this) {
                        aiEngine.also {
                            aiEngine = null
                            isEngineReady = false
                        }
                    }
                    old?.release()
                    onEngineReadyListener?.invoke()
                    return@execute
                }
                val activePath = SingSettings.resolvedModulePath ?: moduleApkPath
                val backend = ModelLoader.getOrCreateBackend(
                    context,
                    activePath,
                    threads,
                    target,
                    selectedModel
                )
                if (backend != null) {
                    val engine = synchronized(this) {
                        if (released || generation != initializationGeneration.get()) return@execute
                        aiEngine.also {
                            if (it == null || !it.usesBackend(backend)) isEngineReady = false
                        }
                    }
                    val changed = engine == null || !engine.usesBackend(backend)
                    // Waiting for inference must not hold the playback monitor.
                    engine?.updateBackend(backend)
                    val backendChanged = synchronized(this) {
                        if (released || generation != initializationGeneration.get()) return@execute
                        if (engine == null) aiEngine = AiStemSeparationEngine(backend)
                        if (changed) resetResampling()
                        aiEngine?.apply {
                            preserveSide = SingSettings.preserveSide
                            twoWaySlider = SingSettings.twoWaySlider
                            ecoMode = SingSettings.ecoMode
                        }
                        isEngineReady = true
                        changed
                    }
                    Log.i(TAG, "Neural Stem Separation Engine initialized (${ModelLoader.activeProviderName})")
                    // A heartbeat causes a settings reply. Do not create a reply/heartbeat loop.
                    if (backendChanged) SingSettings.sendHeartbeat(context, managerInfo, ModelLoader.activeProviderShort)
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

    private fun configureResampling(sampleRate: Int) {
        if (sampleRate == currentSampleRate && downResampler != null && upResampler != null) return
        downResampler?.close()
        upResampler?.close()
        downResampler = StereoResampler(sampleRate, 44100)
        upResampler = StereoResampler(44100, sampleRate)
        currentSampleRate = sampleRate
    }

    private fun resetResampling() {
        downResampler?.close()
        upResampler?.close()
        downResampler = null
        upResampler = null
        currentSampleRate = -1
        sourceFrames = 0L
        outputFramesWritten = 0L
        sourceEnded = false
        outputResamplerDrained = false
    }

    private fun writePcmOutput(processorObj: Any, frames: Int, encoding: Int): Int {
        // A round trip through two rational sample rates may produce a final extra
        // frame. The source stream, not either resampler's rounded length, is authoritative.
        val count = minOf(frames.toLong(), maxOf(0L, sourceFrames - outputFramesWritten)).toInt()
        val bytes = count * 2 * getBytesPerSample(encoding)
        val output = ReflectionUtils.callMethod(processorObj, "replaceOutputBuffer", bytes) as ByteBuffer
        output.clear()
        output.order(ByteOrder.LITTLE_ENDIAN)
        if (count > 0) PcmOutput.write(output, outFloats, count * 2, encoding)
        output.position(bytes)
        output.flip()
        outputFramesWritten += count
        return count
    }

    private fun writeAvailableOutput(
        processorObj: Any, engine: AiStemSeparationEngine, sampleRate: Int, encoding: Int, frames44k: Int
    ): Int {
        val outputCapacity = ((frames44k * sampleRate.toLong() + 44099) / 44100 + 2).toInt()
        ensureCapacity(maxOf(65536, maxOf(frames44k, outputCapacity) * 2))
        val produced = if (sampleRate == 44100) {
            engine.popVocalAndBlend(outFloats, frames44k, smoothedLevel)
        } else {
            configureResampling(sampleRate)
            val popped = engine.popVocalAndBlend(resampledOutFloats, frames44k, smoothedLevel)
            upResampler!!.resample(resampledOutFloats, popped, outFloats)
        }
        return writePcmOutput(processorObj, produced, encoding)
    }

    /**
     * Replaces VocalsAudioProcessor.queueInput(ByteBuffer inputBuffer).
     * Runs synchronously on ExoPlayer's playback thread,
     * delegating heavy neural network computation to the background AI streaming engine.
     */
    @Synchronized
    fun processQueueInput(processorObj: Any, inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }

        try {
            val singActive = isEnabled
            val engine = aiEngine

            // If Sing is OFF or AI engine uninitialized: byte-for-byte PCM bypass.
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

            val inputFormat = ReflectionUtils.getObjectField(processorObj, "inputAudioFormat")
            val encoding = ReflectionUtils.getIntField(inputFormat, "encoding")
            val channelCount = ReflectionUtils.getIntField(inputFormat, "channelCount")
            val sampleRate = ReflectionUtils.getIntField(inputFormat, "sampleRate")
            val bytesPerSample = getBytesPerSample(encoding)

            // This pipeline is stereo-only. Leave all other formats byte-for-byte intact.
            if (channelCount != 2 || sampleRate !in 8000..384000 || !isSupportedEncoding(encoding)) {
                engine.deactivate()
                resetResampling()
                passThrough(processorObj, inputBuffer)
                return
            }
            if (!engine.active) {
                engine.activate()
                resetResampling()
            }

            val remainingBytes = inputBuffer.remaining()
            val frameCount = remainingBytes / (channelCount * bytesPerSample)
            if (frameCount <= 0) {
                inputBuffer.position(inputBuffer.limit())
                return
            }

            val framesToAdd = if (sampleRate == 44100) frameCount else ((frameCount * 44100L + sampleRate - 1) / sampleRate).toInt() + 1

            val canAccept = engine.canAcceptInput(framesToAdd)
            if (!canAccept) {
                // Emit already-decoded output without accepting more input. The host retries
                // this same input after consuming the output, avoiding a full-ring deadlock.
                if (writeAvailableOutput(processorObj, engine, sampleRate, encoding, framesToAdd) > 0) return
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

            // Decode incoming PCM bytes to normalized floats [-1.0, 1.0].
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
            }
            inputBuffer.position(inputBuffer.limit())
            sourceFrames += frameCount

            val frames44k = if (sampleRate == 44100) {
                engine.pushInput(inFloats, frameCount)
                frameCount
            } else {
                configureResampling(sampleRate)
                val produced = downResampler!!.resample(inFloats, frameCount, resampledInFloats)
                engine.pushInput(resampledInFloats, produced)
                produced
            }
            // Use the actual resampler count, never independently round each buffer.
            writeAvailableOutput(processorObj, engine, sampleRate, encoding, frames44k)

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
    @Synchronized
    fun reset() {
        aiEngine?.reset()
        resetResampling()
        java.util.Arrays.fill(inFloats, 0.0f)
        java.util.Arrays.fill(outFloats, 0.0f)
        java.util.Arrays.fill(inShorts, 0.toShort())
        java.util.Arrays.fill(resampledInFloats, 0.0f)
        java.util.Arrays.fill(resampledOutFloats, 0.0f)
        smoothedLevel = vocalLevel
    }

    @Synchronized
    fun onEndOfStream() {
        if (sourceEnded) return
        sourceEnded = true
        val engine = aiEngine
        if (isEnabled && engine?.active == true && currentSampleRate != 44100) {
            val tail = downResampler?.finish(resampledInFloats) ?: 0
            if (tail > 0) engine.pushInput(resampledInFloats, tail)
        }
        aiEngine?.onEndOfStream()
    }

    @Synchronized
    fun isEnded(): Boolean {
        val engine = aiEngine ?: return true
        if (!isEnabled || !engine.active) return true
        return engine.isEnded() && (upResampler == null || outputResamplerDrained)
    }

    val needsBurst: Boolean
        get() = isEnabled && (aiEngine?.needsBurst == true)

    /**
     * Bounded burst decoding on SVAudioRendererV2 to prime initial chunks in ~50-100ms
     * and maintain a healthy cushion of processed audio.
     */
    fun maybeBurstDecode(rendererObj: Any) {
        val engine = aiEngine ?: return
        if (!isEnabled || !engine.needsBurst) return

        try {
            var burstCount = 0
            while (burstCount < 32 && engine.needsBurst) {
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
    @Synchronized
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
        val encoding = ReflectionUtils.getIntField(inputFormat, "encoding")

        if (channelCount != 2) return null

        val available44k = engine.availableVocalFrames
        val written = if (available44k > 0L) {
            writeAvailableOutput(processorObj, engine, sampleRate, encoding, minOf(2048L, available44k).toInt())
        } else if (engine.isEnded() && sampleRate != 44100 && !outputResamplerDrained) {
            val tail = upResampler?.finish(outFloats) ?: 0
            outputResamplerDrained = true
            writePcmOutput(processorObj, tail, encoding)
        } else 0
        return if (written > 0) ReflectionUtils.getObjectField(processorObj, "outputBuffer") as? ByteBuffer else null
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        initializationGeneration.incrementAndGet()
        aiEngine?.release()
        aiEngine = null
        downResampler?.close()
        upResampler?.close()
        downResampler = null
        upResampler = null
        bgExecutor.shutdown()
    }

    /**
     * Stateful SpeexDSP stereo resampler (quality 8), including explicit EOS draining.
     */
    class StereoResampler(fromRate: Int, toRate: Int) : AutoCloseable {
        private val nativeResampler = StftEngine.NativeSpeexResampler(fromRate, toRate)

        fun reset() {
            nativeResampler.reset()
        }

        fun resample(inFloats: FloatArray, inFrames: Int, outFloats: FloatArray): Int {
            return nativeResampler.resample(inFloats, inFrames, outFloats)
        }

        fun finish(outFloats: FloatArray): Int = nativeResampler.finish(outFloats)

        override fun close() {
            nativeResampler.close()
        }
    }
}
