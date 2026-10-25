package org.francasio.sing.audio.backend

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Half
import android.util.Log
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import org.francasio.sing.audio.StftEngine
import org.francasio.sing.util.ModelLoader

class OnnxInferenceBackend(
    val env: OrtEnvironment,
    val session: OrtSession,
    override val providerName: String,
    override val shortProviderName: String,
    override val modelInfo: ModelLoader.ModelInfo
) : InferenceBackend {

    companion object {
        private const val TAG = "AppleSing_ONNX"
    }

    override val numBins: Int = if (modelInfo.isTimeDomain) {
        modelInfo.numBins
    } else {
        val inInfo = session.inputInfo.values.firstOrNull()?.info as? ai.onnxruntime.TensorInfo
        val inShape = inInfo?.shape
        if (inShape != null && inShape.size >= 4 && inShape[2] > 0) {
            inShape[2].toInt()
        } else {
            modelInfo.numBins
        }
    }

    // Determine tensor types dynamically from session
    private val isInputFp16: Boolean = run {
        val inInfo = session.inputInfo.values.firstOrNull()?.info as? ai.onnxruntime.TensorInfo
        inInfo?.type == OnnxJavaType.FLOAT16
    }
    private val isOutputFp16: Boolean = run {
        val outInfo = session.outputInfo.values.firstOrNull()?.info as? ai.onnxruntime.TensorInfo
        outInfo?.type == OnnxJavaType.FLOAT16
    }

    // STFT fields
    private val isVr = modelInfo.isVr3Band
    private val stftChannels = if (isVr) 2 else 4
    private val stftFrames = if (isVr) (if (modelInfo.numFrames > 256) modelInfo.numFrames else 512) else StftEngine.N_FRAMES
    private val vrNumBins = if (isVr && numBins <= 0) 769 else numBins
    private val floatCount = 1 * stftChannels * vrNumBins * stftFrames
    private val stftShape = longArrayOf(1, stftChannels.toLong(), vrNumBins.toLong(), stftFrames.toLong())
    private val stftShortBufferIn: ShortBuffer? = if (isInputFp16) {
        ByteBuffer.allocateDirect(floatCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    } else null

    // Time-domain fields
    private val inputNames: List<String>
    private val outputNames: List<String>
    private val isAudioInFp16: Boolean
    private val isAudioOutFp16: Boolean
    private val audioShape = longArrayOf(2, 1, 1, modelInfo.blockSize.toLong())
    private val inAudioFloatBuffer: FloatBuffer?
    private val inAudioShortBuffer: ShortBuffer?

    private val numStates: Int
    private val stateShapes: Array<LongArray>
    private val stateTypes: Array<OnnxJavaType>
    private val stateBuffersA: Array<Buffer>?
    private val stateBuffersB: Array<Buffer>?
    private val stateTensorsA: Array<OnnxTensor>?
    private val stateTensorsB: Array<OnnxTensor>?
    private var pingPong = 0
    private var supportsOutputMap = true

    init {
        if (modelInfo.isTimeDomain) {
            val audioInName = session.inputNames.find { it == "audio" || !it.contains("in_state") } ?: session.inputNames.first()
            val audioOutName = session.outputNames.find { it == "audio_out" || !it.contains("out_state") } ?: session.outputNames.first()

            val stateInNamesList = session.inputNames.filter { it != audioInName && it.contains("in_state") }.sorted()
            val stateOutNamesList = stateInNamesList.map { it.replace("__in_state", "__out_state") }

            inputNames = listOf(audioInName) + stateInNamesList
            outputNames = listOf(audioOutName) + stateOutNamesList

            val inAudioInfo = session.inputInfo[audioInName]?.info as? ai.onnxruntime.TensorInfo
            isAudioInFp16 = inAudioInfo?.type == OnnxJavaType.FLOAT16
            val outAudioInfo = session.outputInfo[audioOutName]?.info as? ai.onnxruntime.TensorInfo
            isAudioOutFp16 = outAudioInfo?.type == OnnxJavaType.FLOAT16

            val audioFloats = 2 * 1 * 1 * modelInfo.blockSize
            if (isAudioInFp16) {
                inAudioShortBuffer = ByteBuffer.allocateDirect(audioFloats * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                inAudioFloatBuffer = null
            } else {
                inAudioFloatBuffer = ByteBuffer.allocateDirect(audioFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                inAudioShortBuffer = null
            }

            numStates = stateInNamesList.size
            Log.i(TAG, "Configuring Time-Domain ONNX model: audioIn=$audioInName (fp16=$isAudioInFp16), audioOut=$audioOutName (fp16=$isAudioOutFp16), numStates=$numStates")

            if (numStates > 0) {
                val shapes = Array(numStates) { i ->
                    val info = session.inputInfo[stateInNamesList[i]]?.info as? ai.onnxruntime.TensorInfo
                    info?.shape ?: longArrayOf()
                }
                val types = Array(numStates) { i ->
                    val info = session.inputInfo[stateInNamesList[i]]?.info as? ai.onnxruntime.TensorInfo
                    info?.type ?: OnnxJavaType.FLOAT16
                }
                stateShapes = shapes
                stateTypes = types

                stateBuffersA = Array(numStates) { i ->
                    val elemCount = shapes[i].fold(1L) { acc, d -> acc * if (d > 0) d else 1L }.toInt()
                    val byteCount = elemCount * if (types[i] == OnnxJavaType.FLOAT16) 2 else 4
                    val bb = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
                    if (types[i] == OnnxJavaType.FLOAT16) bb.asShortBuffer() else bb.asFloatBuffer()
                }
                stateBuffersB = Array(numStates) { i ->
                    val elemCount = shapes[i].fold(1L) { acc, d -> acc * if (d > 0) d else 1L }.toInt()
                    val byteCount = elemCount * if (types[i] == OnnxJavaType.FLOAT16) 2 else 4
                    val bb = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
                    if (types[i] == OnnxJavaType.FLOAT16) bb.asShortBuffer() else bb.asFloatBuffer()
                }

                stateTensorsA = Array(numStates) { i ->
                    when (val b = stateBuffersA[i]) {
                        is ShortBuffer -> OnnxTensor.createTensor(env, b, shapes[i], OnnxJavaType.FLOAT16)
                        is FloatBuffer -> OnnxTensor.createTensor(env, b, shapes[i])
                        else -> throw IllegalStateException("Unsupported buffer type")
                    }
                }
                stateTensorsB = Array(numStates) { i ->
                    when (val b = stateBuffersB[i]) {
                        is ShortBuffer -> OnnxTensor.createTensor(env, b, shapes[i], OnnxJavaType.FLOAT16)
                        is FloatBuffer -> OnnxTensor.createTensor(env, b, shapes[i])
                        else -> throw IllegalStateException("Unsupported buffer type")
                    }
                }
            } else {
                stateShapes = emptyArray()
                stateTypes = emptyArray()
                stateBuffersA = null
                stateBuffersB = null
                stateTensorsA = null
                stateTensorsB = null
            }
        } else {
            inputNames = emptyList()
            outputNames = emptyList()
            isAudioInFp16 = false
            isAudioOutFp16 = false
            inAudioFloatBuffer = null
            inAudioShortBuffer = null
            numStates = 0
            stateShapes = emptyArray()
            stateTypes = emptyArray()
            stateBuffersA = null
            stateBuffersB = null
            stateTensorsA = null
            stateTensorsB = null
            Log.i(TAG, "Configured STFT ONNX model: numBins=$numBins, isInputFp16=$isInputFp16, isOutputFp16=$isOutputFp16")
        }
    }

    override fun run(stftInput: FloatArray, vocalOutput: FloatArray) {
        val inputName = session.inputNames.firstOrNull() ?: "input"
        val inputTensor = if (isInputFp16) {
            val sb = stftShortBufferIn ?: ByteBuffer.allocateDirect(floatCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            sb.rewind()
            for (i in 0 until floatCount) {
                sb.put(i, Half.toHalf(stftInput[i]))
            }
            sb.rewind()
            OnnxTensor.createTensor(env, sb, stftShape, OnnxJavaType.FLOAT16)
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(stftInput, 0, floatCount), stftShape)
        }

        try {
            val result = session.run(mapOf(inputName to inputTensor))
            try {
                val outputTensor = result.get(0) as OnnxTensor
                val outFloatCount = if (isVr) {
                    val roiFrames = if (stftFrames > 256) stftFrames - 256 else 256
                    1 * 2 * vrNumBins * roiFrames
                } else {
                    floatCount
                }
                if (isOutputFp16) {
                    val sb = outputTensor.shortBuffer
                    sb.rewind()
                    for (i in 0 until outFloatCount) {
                        vocalOutput[i] = Half.toFloat(sb.get(i))
                    }
                } else {
                    val fb = outputTensor.floatBuffer
                    fb.rewind()
                    fb.get(vocalOutput, 0, outFloatCount)
                }
            } finally {
                result.close()
            }
        } finally {
            inputTensor.close()
        }
    }

    override fun runTimeDomain(inPcm: FloatArray, outPcm: FloatArray, frameCount: Int) {
        val blockSize = modelInfo.blockSize
        val toCopy = minOf(frameCount, blockSize)

        val audioTensor = if (isAudioInFp16) {
            val inSb = inAudioShortBuffer ?: throw IllegalStateException("inAudioShortBuffer is null")
            inSb.rewind()
            for (i in 0 until toCopy) {
                inSb.put(i, Half.toHalf(inPcm[i * 2]))
                inSb.put(blockSize + i, Half.toHalf(inPcm[i * 2 + 1]))
            }
            for (i in toCopy until blockSize) {
                inSb.put(i, 0.toShort())
                inSb.put(blockSize + i, 0.toShort())
            }
            inSb.rewind()
            OnnxTensor.createTensor(env, inSb, audioShape, OnnxJavaType.FLOAT16)
        } else {
            val inFb = inAudioFloatBuffer ?: throw IllegalStateException("inAudioFloatBuffer is null")
            inFb.rewind()
            for (i in 0 until toCopy) {
                inFb.put(i, inPcm[i * 2])
                inFb.put(blockSize + i, inPcm[i * 2 + 1])
            }
            for (i in toCopy until blockSize) {
                inFb.put(i, 0.0f)
                inFb.put(blockSize + i, 0.0f)
            }
            inFb.rewind()
            OnnxTensor.createTensor(env, inFb, audioShape)
        }

        val inTensors = if (pingPong == 0) stateTensorsA else stateTensorsB
        val outBuffers = if (pingPong == 0) stateBuffersB else stateBuffersA

        val inputMap = HashMap<String, OnnxTensor>(1 + numStates)
        inputMap[inputNames[0]] = audioTensor

        if (numStates > 0 && inTensors != null) {
            for (i in 0 until numStates) {
                inputMap[inputNames[i + 1]] = inTensors[i]
            }
        }

        try {
            val result = session.run(inputMap)
            try {
                // Audio output
                val outAudioOpt = result.get(outputNames[0])
                val outAudioTensor = if (outAudioOpt.isPresent) outAudioOpt.get() as OnnxTensor else result.get(0) as OnnxTensor
                if (isAudioOutFp16) {
                    val outSb = outAudioTensor.shortBuffer
                    outSb.rewind()
                    for (i in 0 until toCopy) {
                        outPcm[i * 2] = Half.toFloat(outSb.get(i))
                        outPcm[i * 2 + 1] = Half.toFloat(outSb.get(blockSize + i))
                    }
                } else {
                    val outFb = outAudioTensor.floatBuffer
                    outFb.rewind()
                    for (i in 0 until toCopy) {
                        outPcm[i * 2] = outFb.get(i)
                        outPcm[i * 2 + 1] = outFb.get(blockSize + i)
                    }
                }

                // Update recurrent states in outBuffers
                if (numStates > 0 && outBuffers != null) {
                    for (i in 0 until numStates) {
                        val outName = outputNames[i + 1]
                        val outOpt = result.get(outName)
                        val outTensor = if (outOpt.isPresent) outOpt.get() as OnnxTensor else result.get(i + 1) as OnnxTensor
                        when (val dest = outBuffers[i]) {
                            is ShortBuffer -> {
                                dest.clear()
                                val src = outTensor.shortBuffer
                                src.rewind()
                                dest.put(src)
                                dest.rewind()
                            }
                            is FloatBuffer -> {
                                dest.clear()
                                val src = outTensor.floatBuffer
                                src.rewind()
                                dest.put(src)
                                dest.rewind()
                            }
                        }
                    }
                    pingPong = 1 - pingPong
                }
            } finally {
                result.close()
            }
        } finally {
            audioTensor.close()
        }
    }

    override fun resetStates() {
        if (numStates > 0 && stateBuffersA != null && stateBuffersB != null) {
            for (i in 0 until numStates) {
                when (val b = stateBuffersA[i]) {
                    is ShortBuffer -> {
                        b.clear()
                        while (b.hasRemaining()) b.put(0.toShort())
                        b.rewind()
                    }
                    is FloatBuffer -> {
                        b.clear()
                        while (b.hasRemaining()) b.put(0.0f)
                        b.rewind()
                    }
                }
                when (val b = stateBuffersB[i]) {
                    is ShortBuffer -> {
                        b.clear()
                        while (b.hasRemaining()) b.put(0.toShort())
                        b.rewind()
                    }
                    is FloatBuffer -> {
                        b.clear()
                        while (b.hasRemaining()) b.put(0.0f)
                        b.rewind()
                    }
                }
            }
            pingPong = 0
            Log.i(TAG, "Reset all $numStates ONNX states to zero")
        }
    }

    override fun close() {
        stateTensorsA?.forEach {
            try { it.close() } catch (_: Throwable) {}
        }
        stateTensorsB?.forEach {
            try { it.close() } catch (_: Throwable) {}
        }
        // Free the native ORT session (model weights + memory arenas). ModelLoader
        // defers close() by 30s after a backend swap, so no in-flight run() can be
        // using it. Without this, every model/settings switch leaked a full session
        // until the app hit multi-GB RSS and the lowmemorykiller killed it.
        try {
            session.close()
        } catch (_: Throwable) {}
    }
}
