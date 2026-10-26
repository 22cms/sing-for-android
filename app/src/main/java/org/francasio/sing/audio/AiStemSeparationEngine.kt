package org.francasio.sing.audio

import android.os.Process
import android.util.Log
import org.francasio.sing.audio.backend.InferenceBackend
import org.francasio.sing.clock.MediaClockSyncHook
import org.francasio.sing.util.ModelLoader
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Streaming Neural Stem Separation Engine.
 * Supports both STFT spectrogram models (UVR-MDX-NET, Kim Inst)
 * and direct Time-Domain streaming models (Apple Sing Vi-NNet).
 * Employs a non-blocking ring-buffer pipeline to isolate stems
 * with zero audio thread latency and robust seek synchronization.
 */
class AiStemSeparationEngine(
    initialBackend: InferenceBackend
) {
    companion object {
        private const val TAG = "AppleSing_AiEngine"
        const val CHUNK_SAMPLES = StftEngine.TOTAL_SAMPLES // 261,120 frames (~5.92s @ 44.1kHz)
        const val CROSSFADE_SAMPLES = 44100               // 44,100 frames (~1.0s crossfade between chunks)
        const val HOP_SAMPLES = CHUNK_SAMPLES - CROSSFADE_SAMPLES // 217,020 frames (~4.92s step, 1.4x+ real-time)
        const val VR_CROSSFADE_SAMPLES = 16384           // 16,384 frames (~0.37s smooth crossfade)
        const val PRE_ROLL_FRAMES = CHUNK_SAMPLES + HOP_SAMPLES // 478,140 frames (~10.84s initial buffer)
        const val MAX_CUSHION_FRAMES = CHUNK_SAMPLES + 2 * HOP_SAMPLES // 695,160 frames (~15.76s max cushion)
        const val ECO_TARGET_CUSHION_FRAMES = CHUNK_SAMPLES // 261,120 frames (~5.92s target buffer)
        private const val RING_SIZE = 1 shl 20             // 1,048,576 frames (~23.7s ring buffer)
        private const val RING_MASK = RING_SIZE - 1
    }

    private val stftEngine = StftEngine()
    private val vrDspEngine = VrDspEngine()

    // Circular ring buffers (stereo interleaved: 2 floats per frame)
    private val inRing = FloatArray(RING_SIZE * 2)
    @Volatile private var inWriteFrames = 0L
    @Volatile private var inProcessedFrames = 0L

    private val vocalRing = FloatArray(RING_SIZE * 2)
    private val centerRing = FloatArray(RING_SIZE * 2)
    @Volatile private var centerStartFrame = 0L
    @Volatile private var centerEndFrame = 0L
    @Volatile private var vocalWriteFrames = 0L
    @Volatile private var vocalReadFrames = 0L

    // Dedicated STFT worker buffers
    private val chunkInPcm = FloatArray(CHUNK_SAMPLES * 2)
    private val chunkStftTensor = FloatArray(StftEngine.MAX_TENSOR_SIZE)
    private val chunkVocalTensor = FloatArray(StftEngine.MAX_TENSOR_SIZE)
    private val chunkVocalPcm = FloatArray(CHUNK_SAMPLES * 2)
    private val chunkCenterPcm = FloatArray(CHUNK_SAMPLES * 2)
    private val chunkRawVocalPcm = FloatArray(CHUNK_SAMPLES * 2)

    // Dedicated VR 3-band worker buffers
    private val vrChunkInPcm = FloatArray(512 * 512 * 2)
    private val vrInputTensor = FloatArray(2 * VrDspEngine.VR_TOTAL_BINS * 512)
    private val vrOutputTensor = FloatArray(2 * VrDspEngine.VR_TOTAL_BINS * 256)
    private val vrVocalPcm = FloatArray(256 * 512 * 2)
    private val vrCenterPcm = FloatArray(256 * 512 * 2)

    // Dedicated Time-domain worker buffers (Apple Sing Vi-NNet)
    private val timeDomainInPcm = FloatArray(4096 * 2)
    private val timeDomainOutPcm = FloatArray(4096 * 2)
    private val timeDomainVocalPcm = FloatArray(4096 * 2)
    private val previousVocalPcm = FloatArray(4096 * 2)
    private val pendingVocalPcm = FloatArray(4096 * 2)
    private val centerContextPcm = FloatArray((4096 + 2048) * 2)
    private val centerContextOutPcm = FloatArray((4096 + 2048) * 2)
    private var pendingStartFrame = Long.MIN_VALUE
    private var pendingGeneration = -1L
    private var hasPreviousVocal = false

    private val lock = ReentrantLock()
    private val workCond = lock.newCondition()
    private val pacingCond = lock.newCondition()
    @Volatile private var running = true

    // Generation counter to immediately invalidate in-flight chunks on seek
    private val seekGeneration = AtomicLong(0L)

    private val workerThread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
        workerLoop()
    }, "AppleSing_AiWorker").apply {
        isDaemon = true
        start()
    }

    @Volatile var active: Boolean = false
    private var isBufferingInitialChunk = false
    @Volatile private var endOfStreamSignaled = false

    // Feature toggles
    @Volatile var preserveSide: Boolean = false
        set(value) {
            if (field != value) {
                centerStartFrame = 0L
                centerEndFrame = 0L
            }
            field = value
        }
    val isPreserveSideActive: Boolean
        get() = preserveSide && !currentBackend.modelInfo.keepsBackingVocals
    @Volatile var twoWaySlider: Boolean = false
    @Volatile var ecoMode: Boolean = true
        set(value) {
            val changed = (field != value)
            field = value
            if (changed && !value) {
                lock.withLock {
                    pacingCond.signalAll()
                }
            }
        }

    @Volatile private var currentBackend: InferenceBackend = initialBackend

    val isTimeDomain: Boolean
        get() = currentBackend.modelInfo.isTimeDomain

    val isVr3Band: Boolean
        get() = currentBackend.modelInfo.isVr3Band

    val isInstrumental: Boolean
        get() = currentBackend.modelInfo.isInstrumental

    private fun flushBuffersLocked() {
        java.util.Arrays.fill(inRing, 0.0f)
        java.util.Arrays.fill(vocalRing, 0.0f)
        java.util.Arrays.fill(centerRing, 0.0f)
        java.util.Arrays.fill(chunkInPcm, 0.0f)
        java.util.Arrays.fill(chunkStftTensor, 0.0f)
        java.util.Arrays.fill(chunkVocalTensor, 0.0f)
        java.util.Arrays.fill(chunkVocalPcm, 0.0f)
        java.util.Arrays.fill(chunkCenterPcm, 0.0f)
        java.util.Arrays.fill(chunkRawVocalPcm, 0.0f)
        java.util.Arrays.fill(vrChunkInPcm, 0.0f)
        java.util.Arrays.fill(vrInputTensor, 0.0f)
        java.util.Arrays.fill(vrOutputTensor, 0.0f)
        java.util.Arrays.fill(vrVocalPcm, 0.0f)
        java.util.Arrays.fill(vrCenterPcm, 0.0f)
        java.util.Arrays.fill(timeDomainInPcm, 0.0f)
        java.util.Arrays.fill(timeDomainOutPcm, 0.0f)
        java.util.Arrays.fill(timeDomainVocalPcm, 0.0f)
        java.util.Arrays.fill(previousVocalPcm, 0.0f)
        java.util.Arrays.fill(pendingVocalPcm, 0.0f)
        java.util.Arrays.fill(centerContextPcm, 0.0f)
        java.util.Arrays.fill(centerContextOutPcm, 0.0f)
        pendingStartFrame = Long.MIN_VALUE
        pendingGeneration = -1L
        hasPreviousVocal = false
    }

    fun updateBackend(newBackend: InferenceBackend) {
        lock.withLock {
            currentBackend = newBackend
            seekGeneration.incrementAndGet()
            inWriteFrames = 0L
            inProcessedFrames = 0L
            vocalWriteFrames = 0L
            vocalReadFrames = 0L
            centerStartFrame = 0L
            centerEndFrame = 0L
            endOfStreamSignaled = false
            isBufferingInitialChunk = active
            newBackend.resetStates()
            vrDspEngine.reset()
            flushBuffersLocked()
            workCond.signalAll()
            pacingCond.signalAll()
            Log.i(TAG, "AI engine updated with new inference backend (${newBackend.providerName}, timeDomain=${newBackend.modelInfo.isTimeDomain})")
        }
    }

    fun activate() {
        lock.withLock {
            if (!active) {
                active = true
                seekGeneration.incrementAndGet()
                isBufferingInitialChunk = true
                endOfStreamSignaled = false
                inWriteFrames = 0L
                inProcessedFrames = 0L
                vocalWriteFrames = 0L
                vocalReadFrames = 0L
                centerStartFrame = 0L
                centerEndFrame = 0L
                currentBackend.resetStates()
                vrDspEngine.reset()
                flushBuffersLocked()
                workCond.signalAll()
                pacingCond.signalAll()
                Log.i(TAG, "AI engine activated")
            }
        }
    }

    fun deactivate() {
        lock.withLock {
            if (active) {
                active = false
                seekGeneration.incrementAndGet()
                isBufferingInitialChunk = false
                endOfStreamSignaled = false
                inWriteFrames = 0L
                inProcessedFrames = 0L
                vocalWriteFrames = 0L
                vocalReadFrames = 0L
                centerStartFrame = 0L
                centerEndFrame = 0L
                currentBackend.resetStates()
                vrDspEngine.reset()
                flushBuffersLocked()
                workCond.signalAll()
                pacingCond.signalAll()
                Log.i(TAG, "AI engine deactivated (0% CPU)")
            }
        }
        MediaClockSyncHook.isBuffering = false
        MediaClockSyncHook.bufferingOffsetUs = 0L
        MediaClockSyncHook.latencyOffsetUs = 0L
    }

    /**
     * Called by ExoPlayer's audio thread to push incoming raw PCM floats.
     * Non-blocking, executes in < 0.02 ms.
     */
    fun pushInput(samples: FloatArray, frameCount: Int) {
        if (!active) return

        val totalFloats = frameCount * 2
        val wp = inWriteFrames
        for (i in 0 until totalFloats) {
            val idx = ((wp * 2 + i) and (RING_MASK * 2 + 1).toLong()).toInt()
            inRing[idx] = samples[i]
        }
        inWriteFrames = wp + frameCount

        lock.withLock {
            workCond.signal()
        }
    }

    val isReady: Boolean
        get() = active && vocalWriteFrames > 0L

    val isEndOfStream: Boolean
        get() = active && endOfStreamSignaled

    val hasPendingOutput: Boolean
        get() = active && endOfStreamSignaled && (vocalWriteFrames > vocalReadFrames)

    val availableVocalFrames: Long
        get() = if (active) maxOf(0L, vocalWriteFrames - vocalReadFrames) else 0L

    val cushionFrames: Long
        get() = if (active) maxOf(0L, inWriteFrames - vocalReadFrames) else 0L

    val targetCushionFrames: Long
        get() = when {
            isTimeDomain -> 88200L // ~2.0s
            isVr3Band -> vrDspEngine.chunkSamples.toLong() + 2L * (vrDspEngine.validRoiSamples - VR_CROSSFADE_SAMPLES) // 261,632 + 2 * 114,176 = 489,984 (~11.1s)
            else -> PRE_ROLL_FRAMES.toLong() // 478,140 (~10.8s)
        }

    val needsBurst: Boolean
        get() = active && !endOfStreamSignaled && (cushionFrames < targetCushionFrames)

    val needsInitialChunkBurst: Boolean
        get() = needsBurst

    val hasInitialChunkGathered: Boolean
        get() = when {
            isTimeDomain -> {
                active && ((inWriteFrames - inProcessedFrames) >= currentBackend.modelInfo.blockSize)
            }
            isVr3Band -> {
                active && ((inWriteFrames - inProcessedFrames) >= (vrDspEngine.chunkSamples - VrDspEngine.OFFSET_FRAMES * 512))
            }
            else -> {
                active && ((inWriteFrames - inProcessedFrames) >= CHUNK_SAMPLES)
            }
        }

    /**
     * Flow control check called before accepting new audio frames into the pipeline.
     */
    fun canAcceptInput(frameCount: Int): Boolean {
        if (!active) return true

        if (vocalWriteFrames == 0L) {
            val initialTarget = targetCushionFrames
            return (inWriteFrames + frameCount) <= (initialTarget + 8192)
        }

        // Steady-state backpressure: enforce max cushion to guarantee zero ring buffer wraparound
        val cushion = inWriteFrames - vocalReadFrames
        return (cushion + frameCount) < MAX_CUSHION_FRAMES
    }

    fun awaitProgress(timeoutMs: Long) {
        lock.withLock {
            try {
                workCond.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                // Thread interrupted
            }
        }
    }

    fun onEndOfStream() {
        lock.withLock {
            endOfStreamSignaled = true
            workCond.signalAll()
            pacingCond.signalAll()
            Log.i(TAG, "End of stream signaled to AI engine (inWrite=$inWriteFrames, inProcessed=$inProcessedFrames)")
        }
    }

    fun isEnded(): Boolean {
        return !active || (endOfStreamSignaled && inProcessedFrames >= inWriteFrames && vocalReadFrames >= vocalWriteFrames)
    }

    /**
     * Called by ExoPlayer's audio thread to blend neural stem with original audio.
     * Supports both instrumental and vocal models, Preserve-Side, and Two-Way Slider modes.
     */
    fun popVocalAndBlend(
        outSamples: FloatArray,
        frameCount: Int,
        vocalLevel: Float
    ): Int {
        if (!active) {
            return 0
        }

        if (vocalWriteFrames == 0L) {
            return 0
        }

        if (isBufferingInitialChunk) {
            isBufferingInitialChunk = false
            MediaClockSyncHook.isBuffering = false
            MediaClockSyncHook.bufferingOffsetUs = 0L
            MediaClockSyncHook.latencyOffsetUs = 0L
            Log.i(TAG, "AI pipeline primed! Zero-latency start. Synchronized lyrics clock.")
        }

        val availableVocal = (vocalWriteFrames - vocalReadFrames).toInt()
        val toBlend = minOf(frameCount, maxOf(0, availableVocal))
        var rp = vocalReadFrames

        val isPreserveSide = isPreserveSideActive
        val isTwoWay = twoWaySlider
        val isInst = isInstrumental

        // 1. Blend neural stem
        for (i in 0 until toBlend) {
            val idx = ((rp * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
            val inL = inRing[idx]
            val inR = inRing[idx + 1]
            val stemL = vocalRing[idx]
            val stemR = vocalRing[idx + 1]

            val rawInstL: Float
            val rawInstR: Float
            val rawVocalL: Float
            val rawVocalR: Float

            if (isInst) {
                rawInstL = stemL
                rawInstR = stemR
                rawVocalL = inL - stemL
                rawVocalR = inR - stemR
            } else {
                rawVocalL = stemL
                rawVocalR = stemR
                rawInstL = inL - stemL
                rawInstR = inR - stemR
            }

            val vocalL: Float
            val vocalR: Float
            val instL: Float
            val instR: Float

            val hasCenter = isPreserveSide && rp >= centerStartFrame && rp < centerEndFrame
            if (hasCenter) {
                vocalL = centerRing[idx]
                vocalR = centerRing[idx + 1]
                instL = inL - vocalL
                instR = inR - vocalR
            } else {
                vocalL = rawVocalL
                vocalR = rawVocalR
                instL = rawInstL
                instR = rawInstR
            }

            var outL: Float
            var outR: Float

            if (!isTwoWay) {
                // Standard Sing Mode:
                if (isInst && !hasCenter) {
                    // Direct instrumental volume mixing avoids phase artifacts
                    outL = instL + vocalLevel * (inL - instL)
                    outR = instR + vocalLevel * (inR - instR)
                } else {
                    val attenuation = 1.0f - vocalLevel
                    outL = inL - (attenuation * vocalL)
                    outR = inR - (attenuation * vocalR)
                }
            } else {
                // Two-Way Slider Mode:
                if (vocalLevel <= 0.5f) {
                    val alpha = vocalLevel / 0.5f
                    if (isInst && !hasCenter) {
                        outL = instL + alpha * (inL - instL)
                        outR = instR + alpha * (inR - instR)
                    } else {
                        val attenuation = 1.0f - alpha
                        outL = inL - (attenuation * vocalL)
                        outR = inR - (attenuation * vocalR)
                    }
                } else {
                    // Acapella Mode: vocal 100%, instrumental attenuated by (1 - beta)
                    val beta = ((vocalLevel - 0.5f) / 0.5f).coerceIn(0.0f, 1.0f)
                    val instGain = 1.0f - beta
                    outL = vocalL + (instGain * instL)
                    outR = vocalR + (instGain * instR)
                }
            }

            // Digital limiter to prevent clipping and sanitize against NaN/Inf
            if (outL.isNaN() || outL.isInfinite()) outL = inL
            if (outR.isNaN() || outR.isInfinite()) outR = inR
            if (outL > 1.0f) outL = 1.0f else if (outL < -1.0f) outL = -1.0f
            if (outR > 1.0f) outR = 1.0f else if (outR < -1.0f) outR = -1.0f

            outSamples[i * 2] = outL
            outSamples[i * 2 + 1] = outR
            rp++
        }

        // 2. Graceful underrun protection with smooth ramp
        if (toBlend < frameCount) {
            val missing = frameCount - toBlend
            val invMissing = 1.0f / missing.toFloat()
            for (i in toBlend until frameCount) {
                val idx = ((rp * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
                val rampToRaw = (i - toBlend) * invMissing
                val inL = inRing[idx]
                val inR = inRing[idx + 1]
                val lastOutL = if (toBlend > 0) outSamples[(toBlend - 1) * 2] else inL
                val lastOutR = if (toBlend > 0) outSamples[(toBlend - 1) * 2 + 1] else inR

                outSamples[i * 2] = (1.0f - rampToRaw) * lastOutL + rampToRaw * inL
                outSamples[i * 2 + 1] = (1.0f - rampToRaw) * lastOutR + rampToRaw * inR
                rp++
            }
        }

        vocalReadFrames = minOf(vocalWriteFrames, rp)
        return frameCount
    }

    fun reset() {
        lock.withLock {
            seekGeneration.incrementAndGet()
            inWriteFrames = 0L
            inProcessedFrames = 0L
            vocalWriteFrames = 0L
            vocalReadFrames = 0L
            centerStartFrame = 0L
            centerEndFrame = 0L
            endOfStreamSignaled = false
            isBufferingInitialChunk = active
            currentBackend.resetStates()
            vrDspEngine.reset()
            flushBuffersLocked()
            workCond.signalAll()
            pacingCond.signalAll()
        }
        MediaClockSyncHook.isBuffering = false
        MediaClockSyncHook.bufferingOffsetUs = 0L
        MediaClockSyncHook.latencyOffsetUs = 0L
        Log.i(TAG, "AI engine reset for new track/seek (generation ${seekGeneration.get()})")
    }

    private fun workerLoop() {
        Log.i(TAG, "AI Stem Separation Worker thread running")
        // Time-domain inference timing, logged every 100 blocks so real-device
        // throughput is always visible in logcat (budget: 92.9 ms / 4096-frame block)
        var tmBlocks = 0L
        var tmTotalMs = 0L
        var tmMinMs = Long.MAX_VALUE
        var tmMaxMs = 0L
        while (running) {
            if (isTimeDomain) {
                // Time-domain inference (Apple Sing Vi-NNet: 4096-frame blocks with lookahead)
                val blockSize = currentBackend.modelInfo.blockSize
                val lookaheadSize = currentBackend.modelInfo.lookaheadSize

                var myGen = 0L
                var startFrame = 0L
                var isFlushingDelay = false

                lock.withLock {
                    while (running && (!active || (
                        (inWriteFrames - inProcessedFrames) < blockSize && !endOfStreamSignaled
                    ))) {
                        try {
                            workCond.await()
                        } catch (e: InterruptedException) {
                            return
                        }
                    }
                    if (!running || !active) return@withLock

                    myGen = seekGeneration.get()
                    startFrame = inProcessedFrames
                    val available = inWriteFrames - startFrame

                    if (available >= blockSize) {
                        for (i in 0 until blockSize * 2) {
                            val idx = ((startFrame * 2 + i) and (RING_MASK * 2 + 1).toLong()).toInt()
                            timeDomainInPcm[i] = inRing[idx]
                        }
                        inProcessedFrames = startFrame + blockSize
                    } else if (endOfStreamSignaled && available > 0) {
                        val availCount = available.toInt()
                        for (i in 0 until availCount * 2) {
                            val idx = ((startFrame * 2 + i) and (RING_MASK * 2 + 1).toLong()).toInt()
                            timeDomainInPcm[i] = inRing[idx]
                        }
                        java.util.Arrays.fill(timeDomainInPcm, availCount * 2, blockSize * 2, 0.0f)
                        inProcessedFrames = inWriteFrames
                    } else if (endOfStreamSignaled && vocalWriteFrames < inWriteFrames) {
                        // Flushing remaining lookahead delay frames with zero padding
                        java.util.Arrays.fill(timeDomainInPcm, 0, blockSize * 2, 0.0f)
                        isFlushingDelay = true
                    } else {
                        return@withLock
                    }
                }

                if (!running || !active) continue

                try {
                    val nnT0 = System.nanoTime()
                    currentBackend.runTimeDomain(timeDomainInPcm, timeDomainOutPcm, blockSize)
                    val nnMs = (System.nanoTime() - nnT0) / 1_000_000
                    tmBlocks++
                    tmTotalMs += nnMs
                    if (nnMs < tmMinMs) tmMinMs = nnMs
                    if (nnMs > tmMaxMs) tmMaxMs = nnMs
                    if (tmBlocks % 100 == 0L) {
                        Log.d(TAG, "Time-domain NN: $tmBlocks blocks, avg ${tmTotalMs / tmBlocks} ms, min $tmMinMs ms, max $tmMaxMs ms (budget 92.9 ms/block, ${currentBackend.providerName})")
                    }

                    val makeCenter = isPreserveSideActive
                    if (makeCenter) {
                        for (i in 0 until blockSize) {
                            val framePos = startFrame - lookaheadSize + i
                            val idx = ((framePos * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
                            timeDomainVocalPcm[2 * i] = if (isInstrumental && framePos >= 0) inRing[idx] - timeDomainOutPcm[2 * i] else timeDomainOutPcm[2 * i]
                            timeDomainVocalPcm[2 * i + 1] = if (isInstrumental && framePos >= 0) inRing[idx + 1] - timeDomainOutPcm[2 * i + 1] else timeDomainOutPcm[2 * i + 1]
                        }
                        if (pendingGeneration != myGen || pendingStartFrame + blockSize != startFrame - lookaheadSize) {
                            pendingStartFrame = Long.MIN_VALUE
                            hasPreviousVocal = false
                        }
                        if (pendingStartFrame != Long.MIN_VALUE) {
                            // Analyze the pending block with real samples on either side.
                            // Reflect only at the beginning of a stream, never at each block edge.
                            for (i in 0 until 1024) {
                                val src = if (hasPreviousVocal) blockSize - 1024 + i else 1024 - i
                                centerContextPcm[2 * i] = if (hasPreviousVocal) previousVocalPcm[2 * src] else pendingVocalPcm[2 * src]
                                centerContextPcm[2 * i + 1] = if (hasPreviousVocal) previousVocalPcm[2 * src + 1] else pendingVocalPcm[2 * src + 1]
                            }
                            System.arraycopy(pendingVocalPcm, 0, centerContextPcm, 2048, blockSize * 2)
                            System.arraycopy(timeDomainVocalPcm, 0, centerContextPcm, (blockSize + 1024) * 2, 2048)
                            stftEngine.centerCutPcm(centerContextPcm, centerContextOutPcm, blockSize + 2048)
                        }
                    }

                    var isStale = false
                    lock.withLock {
                        if (!running || !active || myGen != seekGeneration.get()) {
                            isStale = true
                        } else {
                            // Model delay is lookaheadSize (16,384 samples)
                            val destFrame = startFrame - lookaheadSize
                            val targetEnd = destFrame + blockSize

                            for (i in 0 until blockSize) {
                                val framePos = destFrame + i
                                if (framePos in 0 until inWriteFrames) {
                                    val idx = ((framePos * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
                                    val sL = timeDomainOutPcm[i * 2]
                                    val sR = timeDomainOutPcm[i * 2 + 1]
                                    vocalRing[idx] = if (sL.isNaN() || sL.isInfinite()) 0.0f else sL
                                    vocalRing[idx + 1] = if (sR.isNaN() || sR.isInfinite()) 0.0f else sR
                                }
                            }

                            if (makeCenter && isPreserveSideActive && pendingStartFrame != Long.MIN_VALUE && pendingStartFrame + blockSize > 0) {
                                val centerStart = maxOf(0L, pendingStartFrame)
                                val centerEnd = minOf(inWriteFrames, pendingStartFrame + blockSize)
                                for (frame in centerStart until centerEnd) {
                                    val idx = ((frame * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
                                    val src = (frame - pendingStartFrame).toInt() + 1024
                                    centerRing[idx] = centerContextOutPcm[2 * src]
                                    centerRing[idx + 1] = centerContextOutPcm[2 * src + 1]
                                }
                                if (centerEndFrame != centerStart) centerStartFrame = centerStart
                                centerEndFrame = centerEnd
                            }

                            if (makeCenter && isPreserveSideActive) {
                                if (pendingStartFrame != Long.MIN_VALUE) {
                                    System.arraycopy(pendingVocalPcm, 0, previousVocalPcm, 0, blockSize * 2)
                                    hasPreviousVocal = true
                                }
                                System.arraycopy(timeDomainVocalPcm, 0, pendingVocalPcm, 0, blockSize * 2)
                                pendingStartFrame = destFrame
                                pendingGeneration = myGen
                            }

                            if (targetEnd > 0) {
                                val readyEnd = if (makeCenter && isPreserveSideActive) centerEndFrame else targetEnd
                                vocalWriteFrames = maxOf(vocalWriteFrames, minOf(inWriteFrames, readyEnd))
                            }

                            if (isFlushingDelay) {
                                inProcessedFrames += blockSize
                            }

                            workCond.signalAll()

                            // Eco-mode thermal pacing
                            val curCushion = maxOf(0L, vocalWriteFrames - vocalReadFrames)
                            val targetCushion = 88200L // ~2.0s target cushion for time-domain
                            if (ecoMode && !endOfStreamSignaled && curCushion > targetCushion) {
                                while (running && active && ecoMode && !endOfStreamSignaled && myGen == seekGeneration.get()) {
                                    val cushion = maxOf(0L, vocalWriteFrames - vocalReadFrames)
                                    if (cushion <= targetCushion) break
                                    val excess = cushion - targetCushion
                                    val waitMs = minOf(200L, maxOf(20L, (excess * 1000L) / 44100L))
                                    try {
                                        pacingCond.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                    } catch (e: InterruptedException) {
                                        return
                                    }
                                }
                            }
                        }
                    }
                    if (isStale) continue
                } catch (e: Throwable) {
                    Log.e(TAG, "Time-domain inference error: ${e.message}", e)
                }

            } else if (isVr3Band) {
                // VR 3-band architecture inference (12_SP-UVR-3B-44100, etc.)
                var myGen = 0L
                var destFrame = 0L
                val chunkSamples = vrDspEngine.chunkSamples
                val validSamples = vrDspEngine.validRoiSamples
                val crossfadeSamples = VR_CROSSFADE_SAMPLES
                val hopSamples = validSamples - crossfadeSamples
                val offsetSamples = VrDspEngine.OFFSET_FRAMES * 512L
                val minInputNeeded = chunkSamples - offsetSamples

                lock.withLock {
                    while (running && (!active || (
                        (inWriteFrames - inProcessedFrames) < minInputNeeded && !endOfStreamSignaled
                    ))) {
                        try {
                            workCond.await()
                        } catch (e: InterruptedException) {
                            return
                        }
                    }
                    if (!running || !active) return@withLock
                    if (endOfStreamSignaled && inProcessedFrames >= inWriteFrames) return@withLock

                    myGen = seekGeneration.get()
                    destFrame = inProcessedFrames
                    val chunkStartFrame = destFrame - offsetSamples

                    for (i in 0 until chunkSamples * 2) {
                        val sampleOffset = (i / 2).toLong()
                        val ch = i and 1
                        val framePos = chunkStartFrame + sampleOffset
                        val srcSample = if (framePos < 0) {
                            val srcFrame = -framePos
                            if (srcFrame < inWriteFrames) {
                                val idx = (((srcFrame * 2 + ch) and (RING_MASK * 2 + 1).toLong())).toInt()
                                inRing[idx]
                            } else {
                                0.0f
                            }
                        } else if (framePos >= inWriteFrames) {
                            0.0f
                        } else {
                            val idx = (((framePos * 2 + ch) and (RING_MASK * 2 + 1).toLong())).toInt()
                            inRing[idx]
                        }
                        vrChunkInPcm[i] = srcSample
                    }

                    if (endOfStreamSignaled && (inWriteFrames - destFrame) <= validSamples) {
                        inProcessedFrames = inWriteFrames
                    } else {
                        inProcessedFrames = destFrame + hopSamples
                    }
                }

                if (!running || !active) continue

                try {
                    val t0 = System.currentTimeMillis()
                    vrDspEngine.forward(vrChunkInPcm, vrInputTensor)
                    val tDspFwd = System.currentTimeMillis()

                    currentBackend.run(vrInputTensor, vrOutputTensor)
                    val tInf = System.currentTimeMillis()

                    // Extract vocal stem directly
                    vrDspEngine.inverse(vrOutputTensor, vrVocalPcm, isInstrumental = false)
                    val makeCenter = isPreserveSideActive
                    if (makeCenter) {
                        stftEngine.centerCutPcm(vrVocalPcm, vrCenterPcm, validSamples)
                    }
                    val t1 = System.currentTimeMillis()
                    Log.d(TAG, "VR 3-band chunk separated in ${t1 - t0} ms [fwd=${tDspFwd - t0}ms, nn=${tInf - tDspFwd}ms, inv=${t1 - tInf}ms] (valid: ${validSamples / 44100.0}s, hop: ${hopSamples / 44100.0}s, provider: ${currentBackend.providerName})")

                    var isStale = false
                    lock.withLock {
                        if (!running || !active || myGen != seekGeneration.get()) {
                            isStale = true
                        } else {
                            writeVrVocalWithOverlapAdd(vrVocalPcm, destFrame, validSamples, crossfadeSamples)
                            if (makeCenter && isPreserveSideActive) {
                                writeVrCenterWithOverlapAdd(vrCenterPcm, destFrame, validSamples, crossfadeSamples)
                            }

                            workCond.signalAll()

                            // Thermal pacing
                            val curCushion = maxOf(0L, vocalWriteFrames - vocalReadFrames)
                            val targetCushion = 262144L // ~5.9s (at least 2 full chunks cushion)
                            if (ecoMode && !endOfStreamSignaled && curCushion > targetCushion) {
                                while (running && active && ecoMode && !endOfStreamSignaled && myGen == seekGeneration.get()) {
                                    val cushion = maxOf(0L, vocalWriteFrames - vocalReadFrames)
                                    if (cushion <= targetCushion) break
                                    val excessFrames = cushion - targetCushion
                                    val waitMs = minOf(300L, maxOf(20L, (excessFrames * 1000L) / 44100L))
                                    try {
                                        pacingCond.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                    } catch (e: InterruptedException) {
                                        return
                                    }
                                }
                            }
                        }
                    }
                    if (isStale) continue
                } catch (e: Throwable) {
                    Log.e(TAG, "VR separation error: ${e.message}", e)
                }

            } else {
                // STFT spectrogram inference (UVR-MDX-NET / Kim Inst)
                var myGen = 0L
                var startFrame = 0L

                lock.withLock {
                    while (running && (!active || (
                        (inWriteFrames - inProcessedFrames) < CHUNK_SAMPLES && !endOfStreamSignaled
                    ))) {
                        try {
                            workCond.await()
                        } catch (e: InterruptedException) {
                            return
                        }
                    }
                    if (!running || !active) return@withLock

                    myGen = seekGeneration.get()
                    startFrame = inProcessedFrames
                    val available = inWriteFrames - startFrame

                    if (available >= CHUNK_SAMPLES) {
                        for (i in 0 until CHUNK_SAMPLES * 2) {
                            val idx = ((startFrame * 2 + i) and (RING_MASK * 2 + 1).toLong()).toInt()
                            chunkInPcm[i] = inRing[idx]
                        }
                        inProcessedFrames = startFrame + HOP_SAMPLES
                    } else if (endOfStreamSignaled && available > 0) {
                        val availCount = available.toInt()
                        for (i in 0 until availCount * 2) {
                            val idx = ((startFrame * 2 + i) and (RING_MASK * 2 + 1).toLong()).toInt()
                            chunkInPcm[i] = inRing[idx]
                        }
                        java.util.Arrays.fill(chunkInPcm, availCount * 2, CHUNK_SAMPLES * 2, 0.0f)
                        inProcessedFrames = inWriteFrames
                    } else {
                        return@withLock
                    }
                }

                if (!running || !active) continue

                try {
                    val t0 = System.currentTimeMillis()
                    val currentBins = currentBackend.numBins

                    stftEngine.forward(chunkInPcm, chunkStftTensor, currentBins)
                    val tStft = System.currentTimeMillis()

                    currentBackend.run(chunkStftTensor, chunkVocalTensor)
                    val tInf = System.currentTimeMillis()

                    val makeCenter = isPreserveSideActive
                    if (makeCenter && !isInstrumental) {
                        // The NN already produced a vocal spectrum: reuse its STFT
                        // rather than transforming the vocal PCM a second time.
                        System.arraycopy(chunkVocalTensor, 0, chunkStftTensor, 0, 4 * currentBins * StftEngine.N_FRAMES)
                        stftEngine.centerCutTensor(chunkStftTensor, currentBins)
                        stftEngine.inverse(chunkStftTensor, chunkCenterPcm, currentBins)
                    }
                    stftEngine.inverse(chunkVocalTensor, chunkVocalPcm, currentBins)
                    if (makeCenter && isInstrumental) {
                        for (i in 0 until CHUNK_SAMPLES * 2) {
                            chunkRawVocalPcm[i] = chunkInPcm[i] - chunkVocalPcm[i]
                        }
                        stftEngine.centerCutPcm(chunkRawVocalPcm, chunkCenterPcm, CHUNK_SAMPLES)
                    }
                    val t1 = System.currentTimeMillis()
                    Log.d(TAG, "STFT chunk separated in ${t1 - t0} ms [stft=${tStft - t0}ms, nn=${tInf - tStft}ms, istft=${t1 - tInf}ms] (window: 5.92s, step: 4.92s, gen: $myGen, provider: ${currentBackend.providerName})")

                    var isStale = false
                    lock.withLock {
                        if (!running || !active || myGen != seekGeneration.get()) {
                            isStale = true
                        } else {
                            writeVocalWithOverlapAdd(chunkVocalPcm, startFrame)
                            if (makeCenter && isPreserveSideActive) {
                                writeCenterWithOverlapAdd(chunkCenterPcm, startFrame)
                            }
                            workCond.signalAll()

                            val curCushion = maxOf(0L, vocalWriteFrames - vocalReadFrames)
                            if (ecoMode && !endOfStreamSignaled) {
                                var cushion = curCushion
                                if (cushion > ECO_TARGET_CUSHION_FRAMES) {
                                    while (running && active && ecoMode && !endOfStreamSignaled && myGen == seekGeneration.get()) {
                                        cushion = maxOf(0L, vocalWriteFrames - vocalReadFrames)
                                        if (cushion <= ECO_TARGET_CUSHION_FRAMES) {
                                            break
                                        }
                                        val excessFrames = cushion - ECO_TARGET_CUSHION_FRAMES
                                        val waitMs = minOf(500L, maxOf(20L, (excessFrames * 1000L) / 44100L))
                                        try {
                                            pacingCond.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                                        } catch (e: InterruptedException) {
                                            return
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (isStale) {
                        Log.i(TAG, "Discarded chunk from stale seek generation ($myGen vs ${seekGeneration.get()})")
                        continue
                    }

                } catch (e: Throwable) {
                    Log.e(TAG, "AI separation error: ${e.message}", e)
                }
            }
        }
    }

    private fun writeVrVocalWithOverlapAdd(
        vocalPcm: FloatArray,
        chunkDestFrame: Long,
        validSamples: Int,
        crossfadeSamples: Int
    ) {
        val targetEnd = chunkDestFrame + validSamples
        val actualEnd = if (endOfStreamSignaled && inProcessedFrames >= inWriteFrames) {
            minOf(inWriteFrames, targetEnd)
        } else {
            targetEnd
        }

        if (vocalWriteFrames == 0L) {
            for (i in 0 until validSamples) {
                val framePos = chunkDestFrame + i
                val idx = ((framePos * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
                val sL = vocalPcm[i * 2]
                val sR = vocalPcm[i * 2 + 1]
                vocalRing[idx] = if (sL.isNaN() || sL.isInfinite()) 0.0f else sL
                vocalRing[idx + 1] = if (sR.isNaN() || sR.isInfinite()) 0.0f else sR
            }
            vocalWriteFrames = actualEnd
        } else {
            val invCrossfade = 1.0f / crossfadeSamples.toFloat()
            val overlapEnd = minOf(crossfadeSamples, validSamples)
            for (i in 0 until overlapEnd) {
                val framePos = chunkDestFrame + i
                val idx = ((framePos * 2) and (RING_MASK * 2 + 1).toLong()).toInt()

                val ramp = i * invCrossfade
                val invRamp = 1.0f - ramp

                val prevL = vocalRing[idx]
                val prevR = vocalRing[idx + 1]
                val currL = vocalPcm[i * 2]
                val currR = vocalPcm[i * 2 + 1]

                val sL = if (currL.isNaN() || currL.isInfinite()) 0.0f else currL
                val sR = if (currR.isNaN() || currR.isInfinite()) 0.0f else currR

                vocalRing[idx] = invRamp * prevL + ramp * sL
                vocalRing[idx + 1] = invRamp * prevR + ramp * sR
            }

            for (i in overlapEnd until validSamples) {
                val framePos = chunkDestFrame + i
                val idx = ((framePos * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
                val sL = vocalPcm[i * 2]
                val sR = vocalPcm[i * 2 + 1]
                vocalRing[idx] = if (sL.isNaN() || sL.isInfinite()) 0.0f else sL
                vocalRing[idx + 1] = if (sR.isNaN() || sR.isInfinite()) 0.0f else sR
            }

            vocalWriteFrames = maxOf(vocalWriteFrames, actualEnd)
        }
    }

    private fun writeVrCenterWithOverlapAdd(
        centerPcm: FloatArray,
        chunkDestFrame: Long,
        validSamples: Int,
        crossfadeSamples: Int
    ) {
        val targetEnd = chunkDestFrame + validSamples
        val actualEnd = if (endOfStreamSignaled && inProcessedFrames >= inWriteFrames) {
            minOf(inWriteFrames, targetEnd)
        } else {
            targetEnd
        }
        val hasOverlap = centerEndFrame > chunkDestFrame
        val overlap = if (hasOverlap) minOf(crossfadeSamples.toLong(), centerEndFrame - chunkDestFrame).toInt() else 0
        val invCrossfade = if (overlap > 0) 1.0f / overlap.toFloat() else 1.0f

        for (i in 0 until validSamples) {
            val framePos = chunkDestFrame + i
            val idx = ((framePos * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
            val sL = centerPcm[2 * i]
            val sR = centerPcm[2 * i + 1]
            val cleanL = if (sL.isNaN() || sL.isInfinite()) 0.0f else sL
            val cleanR = if (sR.isNaN() || sR.isInfinite()) 0.0f else sR

            if (i < overlap) {
                val ramp = i * invCrossfade
                centerRing[idx] = (1.0f - ramp) * centerRing[idx] + ramp * cleanL
                centerRing[idx + 1] = (1.0f - ramp) * centerRing[idx + 1] + ramp * cleanR
            } else {
                centerRing[idx] = cleanL
                centerRing[idx + 1] = cleanR
            }
        }
        if (!hasOverlap) centerStartFrame = chunkDestFrame
        centerEndFrame = maxOf(centerEndFrame, actualEnd)
    }

    private fun writeVocalWithOverlapAdd(vocalPcm: FloatArray, chunkStartFrame: Long) {
        val targetEnd = chunkStartFrame + CHUNK_SAMPLES
        val actualEnd = if (endOfStreamSignaled && inProcessedFrames >= inWriteFrames) {
            minOf(inWriteFrames, targetEnd)
        } else {
            targetEnd
        }

        if (vocalWriteFrames == 0L) {
            for (i in 0 until CHUNK_SAMPLES) {
                val idx = (((chunkStartFrame + i) * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
                vocalRing[idx] = vocalPcm[i * 2]
                vocalRing[idx + 1] = vocalPcm[i * 2 + 1]
            }
            vocalWriteFrames = actualEnd
        } else {
            val invCrossfade = 1.0f / CROSSFADE_SAMPLES.toFloat()
            for (i in 0 until CROSSFADE_SAMPLES) {
                val framePos = chunkStartFrame + i
                val idx = ((framePos * 2) and (RING_MASK * 2 + 1).toLong()).toInt()

                val ramp = i * invCrossfade
                val invRamp = 1.0f - ramp

                val prevL = vocalRing[idx]
                val prevR = vocalRing[idx + 1]
                val currL = vocalPcm[i * 2]
                val currR = vocalPcm[i * 2 + 1]

                vocalRing[idx] = invRamp * prevL + ramp * currL
                vocalRing[idx + 1] = invRamp * prevR + ramp * currR
            }

            for (i in CROSSFADE_SAMPLES until CHUNK_SAMPLES) {
                val framePos = chunkStartFrame + i
                val idx = ((framePos * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
                vocalRing[idx] = vocalPcm[i * 2]
                vocalRing[idx + 1] = vocalPcm[i * 2 + 1]
            }

            vocalWriteFrames = actualEnd
        }
    }

    private fun writeCenterWithOverlapAdd(centerPcm: FloatArray, chunkStartFrame: Long) {
        val targetEnd = chunkStartFrame + CHUNK_SAMPLES
        val actualEnd = if (endOfStreamSignaled && inProcessedFrames >= inWriteFrames) {
            minOf(inWriteFrames, targetEnd)
        } else targetEnd
        val hasOverlap = centerEndFrame > chunkStartFrame
        val overlap = if (hasOverlap) minOf(CROSSFADE_SAMPLES.toLong(), centerEndFrame - chunkStartFrame).toInt() else 0
        for (i in 0 until CHUNK_SAMPLES) {
            val idx = (((chunkStartFrame + i) * 2) and (RING_MASK * 2 + 1).toLong()).toInt()
            val ramp = if (i < overlap) i.toFloat() / CROSSFADE_SAMPLES else 1.0f
            centerRing[idx] = (1.0f - ramp) * centerRing[idx] + ramp * centerPcm[2 * i]
            centerRing[idx + 1] = (1.0f - ramp) * centerRing[idx + 1] + ramp * centerPcm[2 * i + 1]
        }
        if (!hasOverlap) centerStartFrame = chunkStartFrame
        centerEndFrame = actualEnd
    }

    fun release() {
        running = false
        lock.withLock {
            workCond.signalAll()
            pacingCond.signalAll()
        }
        stftEngine.close()
    }
}
