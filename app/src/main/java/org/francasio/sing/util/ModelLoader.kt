package org.francasio.sing.util

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import android.content.pm.ApplicationInfo
import org.francasio.sing.audio.backend.InferenceBackend
import org.francasio.sing.audio.backend.NativeInferenceBackend
import org.francasio.sing.audio.backend.MnnInferenceBackend
import org.francasio.sing.audio.backend.OnnxBackendFactory
import org.francasio.sing.provider.ENGINE_VERSION
import org.francasio.sing.provider.SingSettings
import org.json.JSONObject
import java.util.concurrent.Executors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

object ModelLoader {
    private const val TAG = "AppleSing_ModelLoader"
    private const val HUGGINGFACE_MODELS_JSON_URL =
        "https://huggingface.co/Francasio/sing-android-weights-db/resolve/main/models.json"
    private const val HUGGINGFACE_MODELS_BASE_URL =
        "https://huggingface.co/Francasio/sing-android-weights-db/resolve/main"

    /**
     * True if ONNXRuntime is bundled into this build (i.e. ai.onnxruntime classes are present).
     */
    val isOnnxRuntimeAvailable: Boolean by lazy {
        try {
            (ModelLoader::class.java.classLoader ?: Thread.currentThread().contextClassLoader)
                ?.loadClass("ai.onnxruntime.OrtEnvironment") != null
        } catch (_: Throwable) {
            false
        }
    }

    fun getJsonDownloadUrls(context: Context?): List<String> {
        val isDebug = SingSettings.isDebug(context)
        return if (isDebug) {
            listOf(
                "http://127.0.0.1:18200/models.json",     // adb-reverse tunnel (phone localhost -> laptop)
                "http://localhost:18200/models.json",
                "http://192.168.1.31:18200/models.json",  // laptop LAN IP
                "http://192.168.240.1:18200/models.json", // Waydroid host gateway
                "http://10.0.2.2:18200/models.json",      // Standard Android emulator host
                HUGGINGFACE_MODELS_JSON_URL
            )
        } else {
            listOf(HUGGINGFACE_MODELS_JSON_URL)
        }
    }

    fun getModelDownloadUrls(context: Context?, fileName: String, customUrl: String? = null): List<String> {
        val isDebug = SingSettings.isDebug(context)
        val custom = if (!customUrl.isNullOrBlank()) listOf(customUrl) else emptyList()
        return if (isDebug) {
            listOf(
                "http://127.0.0.1:18200/$fileName",
                "http://localhost:18200/$fileName",
                "http://192.168.1.31:18200/$fileName",  // laptop LAN IP
                "http://192.168.240.1:18200/$fileName", // Waydroid host gateway
                "http://10.0.2.2:18200/$fileName"       // Standard Android emulator host
            ) + custom + listOf("$HUGGINGFACE_MODELS_BASE_URL/$fileName")
        } else {
            custom + listOf("$HUGGINGFACE_MODELS_BASE_URL/$fileName")
        }
    }

    fun getModelDownloadUrl(context: Context?, fileName: String): String =
        getModelDownloadUrls(context, fileName).first()

    fun getModelDownloadUrl(fileName: String): String = "$HUGGINGFACE_MODELS_BASE_URL/$fileName"

    data class ModelDefinition(
        val id: String,
        val displayName: String,
        val targetStem: String, // "instrumental" or "vocal"
        val networkType: String, // "time_domain" or "stft"
        val sampleRate: Int = 44100,
        val blockSize: Int = 4096,
        val lookaheadSize: Int = 16384,
        val numBins: Int = 2048,
        val numFrames: Int = 256,
        val fftSize: Int = 6144,
        val hopSize: Int = 1024,
        val chunkSamples: Int = 261120,
        val crossfadeSamples: Int = 44100,
        val hopSamples: Int = 217020,
        val vocalSdr: Double? = null,
        val instSdr: Double? = null,
        val description: String = "",
        val isDefault: Boolean = false,
        val versions: List<ModelVersion> = emptyList(),
        val minVersion: String? = null,
        val keepsBackingVocals: Boolean = false,
        val family: String = ""
    ) {
        val isInstrumental: Boolean get() = targetStem.equals("instrumental", ignoreCase = true)
        val isTimeDomain: Boolean get() = networkType.equals("time_domain", ignoreCase = true)
        val isVr3Band: Boolean get() = networkType.equals("vr_3band", ignoreCase = true) || networkType.equals("vr", ignoreCase = true)
        val resolvedFamily: String get() = family.ifBlank {
            when {
                isVr3Band || displayName.contains("VR Arch", ignoreCase = true) -> "VR Arch"
                isTimeDomain || id == "apple_sing" || displayName.contains("Vi-NNet", ignoreCase = true) -> "Vi-NNet"
                displayName.contains("MDX", ignoreCase = true) || id.contains("mdx") || networkType == "stft" -> "MDX-Net"
                else -> "Other"
            }
        }
    }

    data class ModelVersion(
        val id: String,
        val displayName: String,
        val engine: String, // "onnx" or "litert"
        val precision: String, // "FP16" or "FP32"
        val fileName: String,
        val fileSizeDesc: String,
        val isRecommended: Boolean = false,
        val description: String = "",
        val minVersion: String? = null,
        val downloadUrl: String? = null,
        val keepsBackingVocals: Boolean = false
    )

    data class ModelInfo(
        val id: String,
        val modelId: String,
        val modelDisplayName: String,
        val displayName: String,
        val targetStem: String,
        val networkType: String,
        val sampleRate: Int = 44100,
        val blockSize: Int = 4096,
        val lookaheadSize: Int = 16384,
        val numBins: Int = 2048,
        val numFrames: Int = 256,
        val fftSize: Int = 6144,
        val hopSize: Int = 1024,
        val chunkSamples: Int = 261120,
        val crossfadeSamples: Int = 44100,
        val hopSamples: Int = 217020,
        val vocalSdr: Double? = null,
        val instSdr: Double? = null,
        val engine: String = SingSettings.ENGINE_ONNX,
        val precision: String = "FP16",
        val fileName: String,
        val fileSizeDesc: String,
        val description: String = "",
        val isRecommended: Boolean = false,
        val isSupported: Boolean = true,
        val minVersion: String? = null,
        val downloadUrl: String? = null,
        val keepsBackingVocals: Boolean = false,
        val family: String = ""
    ) {
        val isInstrumental: Boolean get() = targetStem.equals("instrumental", ignoreCase = true)
        val isTimeDomain: Boolean get() = networkType.equals("time_domain", ignoreCase = true)
        val isVr3Band: Boolean get() = networkType.equals("vr_3band", ignoreCase = true) || networkType.equals("vr", ignoreCase = true)
        val resolvedFamily: String get() = family.ifBlank {
            when {
                isVr3Band || modelDisplayName.contains("VR Arch", ignoreCase = true) -> "VR Arch"
                isTimeDomain || modelId == "apple_sing" || modelDisplayName.contains("Vi-NNet", ignoreCase = true) -> "Vi-NNet"
                modelDisplayName.contains("MDX", ignoreCase = true) || modelId.contains("mdx") || networkType == "stft" -> "MDX-Net"
                else -> "Other"
            }
        }
        val isViNNet: Boolean get() = engine.equals(SingSettings.ENGINE_NATIVE, ignoreCase = true) || modelId == "apple_sing"
        val isOnnx: Boolean get() = engine.equals(SingSettings.ENGINE_ONNX, ignoreCase = true)
        val isMnn: Boolean get() = engine.equals(SingSettings.ENGINE_MNN, ignoreCase = true)
        val runtimeBadge: String get() = when {
            engine.equals(SingSettings.ENGINE_NATIVE, ignoreCase = true) -> "Native"
            engine.equals(SingSettings.ENGINE_MNN, ignoreCase = true) -> "MNN"
            else -> "ONNX"
        }
        val precisionBadge: String get() = precision.uppercase()
        val dimensionsBadge: String get() = if (isTimeDomain) "Time-Domain" else "${numBins}×$numFrames"
    }

    @Volatile
    var MODEL_DEFINITIONS: List<ModelDefinition> = emptyList()
        private set

    @Volatile
    var AVAILABLE_MODELS: List<ModelInfo> = emptyList()
        private set

    private val dynamicModels = java.util.concurrent.ConcurrentHashMap<String, ModelInfo>()

    var catalogVersion by mutableIntStateOf(0)
        private set

    private const val SELECTED_MODEL_FILE = "selected_model.json"

    fun saveSelectedModel(context: Context, info: ModelInfo) {
        try {
            val json = JSONObject().apply {
                put("id", info.id)
                put("modelId", info.modelId)
                put("modelDisplayName", info.modelDisplayName)
                put("displayName", info.displayName)
                put("targetStem", info.targetStem)
                put("networkType", info.networkType)
                put("sampleRate", info.sampleRate)
                put("blockSize", info.blockSize)
                put("lookaheadSize", info.lookaheadSize)
                put("numBins", info.numBins)
                put("numFrames", info.numFrames)
                put("fftSize", info.fftSize)
                put("hopSize", info.hopSize)
                put("chunkSamples", info.chunkSamples)
                put("crossfadeSamples", info.crossfadeSamples)
                put("hopSamples", info.hopSamples)
                put("engine", info.engine)
                put("precision", info.precision)
                put("fileName", info.fileName)
                put("fileSizeDesc", info.fileSizeDesc)
                put("description", info.description)
                put("isRecommended", info.isRecommended)
                put("downloadUrl", info.downloadUrl ?: "")
                put("keepsBackingVocals", info.keepsBackingVocals)
                put("family", info.family)
            }
            File(context.filesDir, SELECTED_MODEL_FILE).writeText(json.toString())
            registerDynamicModel(info)
            Log.i(TAG, "Cached selected model ${info.id} to $SELECTED_MODEL_FILE")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed saving selected model JSON: ${t.message}")
        }
    }

    fun loadSelectedModel(context: Context): ModelInfo? {
        val file = File(context.filesDir, SELECTED_MODEL_FILE)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val str = file.readText()
            val obj = JSONObject(str)
            val id = obj.optString("id", "")
            val catalogModel = findCatalogModel(id)
            if (catalogModel != null) {
                return catalogModel
            }
            val engine = obj.optString("engine", SingSettings.ENGINE_ONNX)
            // Keep the cached selection even if ONNX native libs are not downloaded
            // yet; they will be fetched on demand when the backend is created.

            val precision = obj.optString("precision", "FP16")
            val isRecommended = obj.optBoolean("isRecommended", isVersionRecommended(engine, precision))
            val info = ModelInfo(
                id = obj.getString("id"),
                modelId = obj.getString("modelId"),
                modelDisplayName = obj.getString("modelDisplayName"),
                displayName = obj.getString("displayName"),
                targetStem = obj.optString("targetStem", "instrumental"),
                networkType = obj.optString("networkType", "time_domain"),
                sampleRate = obj.optInt("sampleRate", 44100),
                blockSize = obj.optInt("blockSize", 4096),
                lookaheadSize = obj.optInt("lookaheadSize", 16384),
                numBins = obj.optInt("numBins", 2048),
                numFrames = obj.optInt("numFrames", 256),
                fftSize = obj.optInt("fftSize", 6144),
                hopSize = obj.optInt("hopSize", 1024),
                chunkSamples = obj.optInt("chunkSamples", 261120),
                crossfadeSamples = obj.optInt("crossfadeSamples", 44100),
                hopSamples = obj.optInt("hopSamples", 217020),
                engine = engine,
                precision = precision,
                fileName = obj.getString("fileName"),
                fileSizeDesc = obj.optString("fileSizeDesc", ""),
                description = obj.optString("description", ""),
                isRecommended = isRecommended,
                isSupported = true,
                downloadUrl = obj.optString("downloadUrl", "").ifBlank { null },
                keepsBackingVocals = obj.optBoolean("keepsBackingVocals", false),
                family = obj.optString("family", "")
            )
            registerDynamicModel(info)
            info
        } catch (t: Throwable) {
            Log.w(TAG, "Failed loading selected model JSON: ${t.message}")
            null
        }
    }

    fun clearSelectedModel(context: Context) {
        try {
            val file = File(context.filesDir, SELECTED_MODEL_FILE)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Throwable) {}
    }

    private fun updateModelCatalog(defs: List<ModelDefinition>, infos: List<ModelInfo>) {
        MODEL_DEFINITIONS = defs
        val combined = infos.toMutableList()
        for (dyn in dynamicModels.values) {
            val catalogMatch = combined.find { it.id == dyn.id }
            val effectiveDyn = if (catalogMatch != null) {
                dyn.copy(isRecommended = catalogMatch.isRecommended)
            } else {
                dyn
            }
            combined.removeAll { it.id == dyn.id }
            combined.add(0, effectiveDyn)
        }
        AVAILABLE_MODELS = combined
        catalogVersion++
    }

    fun isVersionRecommended(engine: String, precision: String): Boolean {
        // No engine is hard-excluded here: the catalog's own isRecommended flag
        // (parsed above) takes precedence via optBoolean, and this is only the
        // fallback when the catalog omits it. Native Vi-NNet is treated like any
        // other engine instead of being forced to "not recommended".
        return if (DeviceHelper.isQualcommWithHexagonHtp && isOnnxRuntimeAvailable) {
            engine.equals(SingSettings.ENGINE_ONNX, ignoreCase = true) && precision.equals("FP16", ignoreCase = true)
        } else {
            engine.equals(SingSettings.ENGINE_MNN, ignoreCase = true) && precision.equals("FP16", ignoreCase = true)
        }
    }

    /**
     * Returns true if [current] semantic version is greater than or equal to [required].
     * e.g. isVersionAtLeast("1.1", "1.1") -> true, isVersionAtLeast("1.0", "1.1") -> false
     */
    fun isVersionAtLeast(current: String, required: String): Boolean {
        val cleanReq = required.trimStart('v', 'V').trim()
        if (cleanReq.isEmpty()) return true
        val cleanCur = current.trimStart('v', 'V').trim()
        if (cleanCur.isEmpty()) return false

        val curParts = cleanCur.split(".").map { it.toIntOrNull() ?: 0 }
        val reqParts = cleanReq.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(curParts.size, reqParts.size)
        for (i in 0 until maxLen) {
            val c = curParts.getOrElse(i) { 0 }
            val r = reqParts.getOrElse(i) { 0 }
            if (c < r) return false
            if (c > r) return true
        }
        return true
    }

    fun parseModelsJson(jsonStr: String, appVersion: String = ENGINE_VERSION): Pair<List<ModelDefinition>, List<ModelInfo>> {
        val root = JSONObject(jsonStr)
        val modelsArray = root.getJSONArray("models")
        val defs = mutableListOf<ModelDefinition>()
        val infos = mutableListOf<ModelInfo>()

        for (i in 0 until modelsArray.length()) {
            val mObj = modelsArray.getJSONObject(i)
            val mId = mObj.getString("id")
            val modelMinVersion = mObj.optString("minVersion", mObj.optString("minAppVersion", "")).ifBlank { null }
            if (modelMinVersion != null && !isVersionAtLeast(appVersion, modelMinVersion)) {
                Log.d(TAG, "Skipping model $mId: requires version >= $modelMinVersion, current is $appVersion")
                continue
            }

            val mDisplayName = mObj.getString("displayName")
            val targetStem = mObj.optString("targetStem", "vocal")
            val networkType = mObj.optString("networkType", "stft")
            val sampleRate = mObj.optInt("sampleRate", 44100)
            val blockSize = mObj.optInt("blockSize", 4096)
            val lookaheadSize = mObj.optInt("lookaheadSize", 16384)
            val numBins = mObj.optInt("numBins", 2048)
            val numFrames = mObj.optInt("numFrames", 256)
            val fftSize = mObj.optInt("fftSize", 6144)
            val hopSize = mObj.optInt("hopSize", 1024)
            val chunkSamples = mObj.optInt("chunkSamples", 261120)
            val crossfadeSamples = mObj.optInt("crossfadeSamples", 44100)
            val hopSamples = mObj.optInt("hopSamples", 217020)
            val vocalSdr = if (mObj.has("vocalSdr")) mObj.optDouble("vocalSdr") else null
            val instSdr = if (mObj.has("instSdr")) mObj.optDouble("instSdr") else null
            val description = mObj.optString("description", "")
            val isDefault = mObj.optBoolean("isDefault", false)
            val keepsBackingVocals = mObj.optBoolean("keepsBackingVocals", mObj.optBoolean("isKaraoke", mObj.optBoolean("disablePreserveSide", false)))
            val parsedFamily = mObj.optString("family", "").trim()
            val family = parsedFamily.ifBlank {
                when {
                    networkType.equals("vr_3band", ignoreCase = true) || networkType.equals("vr", ignoreCase = true) || mDisplayName.contains("VR Arch", ignoreCase = true) -> "VR Arch"
                    networkType.equals("time_domain", ignoreCase = true) || mId == "apple_sing" || mDisplayName.contains("Vi-NNet", ignoreCase = true) -> "Vi-NNet"
                    mDisplayName.contains("MDX", ignoreCase = true) || mId.contains("mdx") || networkType == "stft" -> "MDX-Net"
                    else -> "Other"
                }
            }

            val versionsList = mutableListOf<ModelVersion>()
            val vArray = mObj.optJSONArray("versions")
            if (vArray != null) {
                for (j in 0 until vArray.length()) {
                    val vObj = vArray.getJSONObject(j)
                    val vId = vObj.getString("id")
                    val vMinVersion = vObj.optString("minVersion", vObj.optString("minAppVersion", "")).ifBlank { null }
                    if (vMinVersion != null && !isVersionAtLeast(appVersion, vMinVersion)) {
                        Log.d(TAG, "Skipping version $vId of model $mId: requires version >= $vMinVersion, current is $appVersion")
                        continue
                    }

                    val vDisplayName = vObj.getString("displayName")
                    val engine = vObj.optString("engine", "onnx")
                    // LiteRT/GPU path was removed: the OpenCL delegate thrashes memory
                    // massively (>1.1 GB OpenCL heap on the measured device) and isn't
                    // faster than the CPU ONNX path. Drop any lingering LiteRT variant
                    // here so stale catalogs also stop surfacing it in the picker.
                    if (engine.equals(SingSettings.ENGINE_LITERT, ignoreCase = true)) continue

                    // ONNX models stay visible even when their native libs are not
                    // downloaded yet: selecting one triggers the on-demand download.

                    val precision = vObj.optString("precision", "FP16")
                    val fileName = vObj.getString("fileName")
                    val fileSizeDesc = vObj.optString("fileSizeDesc", "")
                    val isRecommended = vObj.optBoolean("isRecommended", isVersionRecommended(engine, precision))
                    val vDesc = vObj.optString("description", "")
                    val vDownloadUrl = vObj.optString("downloadUrl", "").ifBlank { null }
                    val vKeepsBackingVocals = vObj.optBoolean("keepsBackingVocals", keepsBackingVocals)

                    val version = ModelVersion(
                        id = vId,
                        displayName = vDisplayName,
                        engine = engine,
                        precision = precision,
                        fileName = fileName,
                        fileSizeDesc = fileSizeDesc,
                        isRecommended = isRecommended,
                        description = vDesc,
                        minVersion = vMinVersion ?: modelMinVersion,
                        downloadUrl = vDownloadUrl,
                        keepsBackingVocals = vKeepsBackingVocals
                    )
                    versionsList.add(version)

                    infos.add(
                        ModelInfo(
                            id = vId,
                            modelId = mId,
                            modelDisplayName = mDisplayName,
                            displayName = vDisplayName,
                            targetStem = targetStem,
                            networkType = networkType,
                            sampleRate = sampleRate,
                            blockSize = blockSize,
                            lookaheadSize = lookaheadSize,
                            numBins = numBins,
                            numFrames = numFrames,
                            fftSize = fftSize,
                            hopSize = hopSize,
                            chunkSamples = chunkSamples,
                            crossfadeSamples = crossfadeSamples,
                            hopSamples = hopSamples,
                            vocalSdr = vocalSdr,
                            instSdr = instSdr,
                            engine = engine,
                            precision = precision,
                            fileName = fileName,
                            fileSizeDesc = fileSizeDesc,
                            description = if (vDesc.isNotEmpty()) vDesc else description,
                            isRecommended = isRecommended,
                            isSupported = true,
                            minVersion = vMinVersion ?: modelMinVersion,
                            downloadUrl = vDownloadUrl,
                            keepsBackingVocals = vKeepsBackingVocals,
                            family = family
                        )
                    )
                }
            }

            if (versionsList.isNotEmpty()) {
                defs.add(
                    ModelDefinition(
                        id = mId,
                        displayName = mDisplayName,
                        targetStem = targetStem,
                        networkType = networkType,
                        sampleRate = sampleRate,
                        blockSize = blockSize,
                        lookaheadSize = lookaheadSize,
                        numBins = numBins,
                        numFrames = numFrames,
                        fftSize = fftSize,
                        hopSize = hopSize,
                        chunkSamples = chunkSamples,
                        crossfadeSamples = crossfadeSamples,
                        hopSamples = hopSamples,
                        vocalSdr = vocalSdr,
                        instSdr = instSdr,
                        description = description,
                        isDefault = isDefault,
                        versions = versionsList,
                        minVersion = modelMinVersion,
                        keepsBackingVocals = keepsBackingVocals,
                        family = family
                    )
                )
            }
        }
        dropRetiredViNNetVersions(defs, infos)
        return Pair(defs, infos)
    }

    private const val NATIVE_VERSION_ID = "apple_sing_native_fp32"
    private const val NATIVE_PLAN_FILE = "vi_nnet_fp32.vinet"

    /**
     * Vi-NNet (apple_sing) is native-only: its ONNX/LiteRT variants were retired.
     * A stale catalog (device cache, hand-pushed models.json) may still list them,
     * so drop those versions before they can ever reach the UI.
     */
    private fun dropRetiredViNNetVersions(
        defs: MutableList<ModelDefinition>,
        infos: MutableList<ModelInfo>
    ) {
        infos.removeAll { it.modelId == "apple_sing" && it.id != NATIVE_VERSION_ID }
        val idx = defs.indexOfFirst { it.id == "apple_sing" }
        if (idx >= 0 && defs[idx].versions.any { it.id != NATIVE_VERSION_ID }) {
            defs[idx] = defs[idx].copy(versions = defs[idx].versions.filter { it.id == NATIVE_VERSION_ID })
        }
    }

    private val tunnelCatalogUrls = listOf(
        "http://127.0.0.1:18200/models.json",
        "http://localhost:18200/models.json"
    )

    /**
     * Catalog straight from the dev server on the laptop, reached through the
     * adb-reverse tunnel (phone 127.0.0.1:18200 -> laptop :18200). Returns null
     * when the tunnel/server is absent (connection refused, ~instant), so the
     * caller falls back to the offline sources. Must be called off the main thread.
     */
    private fun fetchCatalogFromTunnel(context: Context): List<ModelDefinition>? {
        for (urlStr in tunnelCatalogUrls) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1500
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "AppleMusicSing-Android")
                }
                if (conn.responseCode in 200..299) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val parsed = parseModelsJson(jsonStr)
                    updateModelCatalog(parsed.first, parsed.second)
                    // Write-through: the offline cache mirrors the last live catalog,
                    // it is never a source of truth on its own.
                    try { File(context.filesDir, "models.json").writeText(jsonStr) } catch (_: Throwable) {}
                    Log.i(TAG, "Loaded models.json from $urlStr (live, adb tunnel)")
                    return MODEL_DEFINITIONS
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    fun loadModels(context: Context?): List<ModelDefinition> {
        if (context == null) return MODEL_DEFINITIONS
        loadSelectedModel(context)

        // 0. Live catalog from dev server via adb-reverse tunnel (debug only, off main thread)
        if (SingSettings.isDebug(context) && Looper.myLooper() != Looper.getMainLooper()) {
            fetchCatalogFromTunnel(context)?.let { return it }
        }

        // 1. Check shared /sdcard/Download/sing_models/models.json (for unrooted LSPatch)
        val sdcardJsonCandidates = listOf(
            File("/sdcard/Download/sing_models/models.json"),
            File("/storage/emulated/0/Download/sing_models/models.json")
        )
        for (f in sdcardJsonCandidates) {
            try {
                if (f.exists() && f.canRead() && f.length() > 0) {
                    val jsonStr = f.readText()
                    val parsed = parseModelsJson(jsonStr)
                    updateModelCatalog(parsed.first, parsed.second)
                    Log.i(TAG, "Loaded models.json from ${f.absolutePath}")
                    return MODEL_DEFINITIONS
                }
            } catch (_: Throwable) {}
        }

        // 2. Check cached models.json in filesDir
        val cached = File(context.filesDir, "models.json")
        if (cached.exists() && cached.length() > 0) {
            try {
                val jsonStr = cached.readText()
                val parsed = parseModelsJson(jsonStr)
                updateModelCatalog(parsed.first, parsed.second)
                Log.i(TAG, "Loaded models.json from ${cached.absolutePath}")
                return MODEL_DEFINITIONS
            } catch (t: Throwable) {
                Log.w(TAG, "Failed reading cached models.json: ${t.message}")
            }
        }

        // 3. Check ContentProvider
        try {
            val contentUri = Uri.parse("content://org.francasio.sing.provider/models/models.json")
            context.contentResolver.openInputStream(contentUri)?.use { input ->
                val jsonStr = input.bufferedReader().readText()
                val parsed = parseModelsJson(jsonStr)
                try { cached.writeText(jsonStr) } catch (_: Throwable) {}
                updateModelCatalog(parsed.first, parsed.second)
                Log.i(TAG, "Loaded models.json from ContentProvider")
                return MODEL_DEFINITIONS
            }
        } catch (_: Throwable) {}

        return MODEL_DEFINITIONS
    }

    private val refreshExecutor = Executors.newSingleThreadExecutor()

    fun refreshModels(context: Context, onComplete: ((List<ModelDefinition>) -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        refreshExecutor.execute {
            val urls = getJsonDownloadUrls(appContext)
            var fetchedJson: String? = null

            for (urlStr in urls) {
                try {
                    val url = URL(urlStr)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 5000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "AppleMusicSing-Android")
                    }
                    if (conn.responseCode in 200..299) {
                        fetchedJson = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        Log.i(TAG, "Successfully fetched models.json from $urlStr")
                        break
                    }
                    conn.disconnect()
                } catch (t: Throwable) {
                    Log.d(TAG, "Failed fetching models.json from $urlStr: ${t.message}")
                }
            }

            if (!fetchedJson.isNullOrBlank()) {
                try {
                    val parsed = parseModelsJson(fetchedJson)
                    updateModelCatalog(parsed.first, parsed.second)
                    val cached = File(appContext.filesDir, "models.json")
                    cached.writeText(fetchedJson)
                    Log.i(TAG, "Updated models.json with ${parsed.first.size} models and ${parsed.second.size} versions")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed parsing fetched models.json: ${t.message}")
                }
            }

            onComplete?.invoke(MODEL_DEFINITIONS)
        }
    }

    /**
     * Exact-id catalog lookup with NO fabricated fallback: returns null when the
     * catalog does not contain the id. Callers that publish model information
     * other components rely on must use this - [getModelInfo]'s native fallback
     * once put "vi_nnet_fp32.vinet / native" on broadcast replies for UVR/Kim
     * ids whenever the catalog was not loaded yet, and the module registered
     * that as a dynamic model and then used it for every selection (which made
     * Apple Music run Vi-NNet no matter what was selected).
     */
    fun findCatalogModel(id: String): ModelInfo? {
        if (id.isEmpty()) return null
        // Exact id first: the catalog's own versions must render as themselves.
        AVAILABLE_MODELS.find { it.id == id }?.let { return it }
        val sanitized = SingSettings.sanitizeModelId(id)
        if (sanitized.isNotEmpty()) {
            AVAILABLE_MODELS.find { it.id == sanitized }?.let { return it }
            AVAILABLE_MODELS.find { it.modelId == sanitized }?.let { return it }
            dynamicModels[id]?.let { return it }
            dynamicModels.values.find { it.id == sanitized }?.let { return it }
        }
        return null
    }

    private var currentThreadCount: Int = -1
    var currentTarget: String = SingSettings.TARGET_NPU
        private set
    var currentForceCpu: Boolean = false
        private set
    var currentModelId: String = ""
        private set

    private val sessionCloser = Executors.newSingleThreadScheduledExecutor()

    val defaultThreadCount: Int
        get() = minOf(6, maxOf(2, Runtime.getRuntime().availableProcessors() - 2))

    private var currentBackend: InferenceBackend? = null
    private val backendCreationLock = Any()
    private val catalogLock = Any()
    private val nativeLibsLock = Any()

    fun getOrCreateBackend(
        context: Context,
        moduleApkPath: String,
        threads: Int? = null,
        hardwareTarget: String = SingSettings.hardwareTarget,
        modelId: String = SingSettings.selectedModelId
    ): InferenceBackend? {
        if (modelId.isEmpty()) return null
        loadModels(context)
        val targetThreads = threads ?: if (currentThreadCount > 0) currentThreadCount else defaultThreadCount
        val modelInfo = findCatalogModel(modelId) ?: run {
            Log.w(TAG, "Cannot create backend: model '$modelId' not found in catalog")
            return null
        }
        val forceCpu = (hardwareTarget == SingSettings.TARGET_CPU)

        val cachedBackend = currentBackend
        if (cachedBackend != null &&
            (hardwareTarget == SingSettings.TARGET_NPU || currentThreadCount == targetThreads) &&
            currentTarget == hardwareTarget &&
            currentModelId == modelInfo.id
        ) {
            return cachedBackend
        }

        synchronized(backendCreationLock) {
            if (currentBackend != null &&
                (hardwareTarget == SingSettings.TARGET_NPU || currentThreadCount == targetThreads) &&
                currentTarget == hardwareTarget &&
                currentModelId == modelInfo.id
            ) {
                return currentBackend!!
            }

            val oldBackend = currentBackend

            ensureNativeLibrariesLoaded(context, moduleApkPath)
            val modelFile = ensureModelExtracted(context, moduleApkPath, modelInfo.fileName)

            val backend: InferenceBackend = if (modelInfo.engine == SingSettings.ENGINE_NATIVE) {
                Log.i(TAG, "Initializing native Vi-NNet backend for ${modelInfo.id} (${modelFile.name}, threads=$targetThreads)...")
                val native = NativeInferenceBackend(modelFile, targetThreads, modelInfo)
                activeProviderShort = native.shortProviderName
                activeProviderName = native.providerName
                native
            } else if (modelInfo.engine == SingSettings.ENGINE_MNN) {
                Log.i(TAG, "Initializing MNN backend for ${modelInfo.id} (${modelFile.name}, threads=$targetThreads, target=$hardwareTarget)...")
                if (hardwareTarget == SingSettings.TARGET_NPU || hardwareTarget == SingSettings.TARGET_NNAPI) {
                    try {
                        OnnxBackendFactory.ensureVendorNativeLibrariesLoaded(context, moduleApkPath) { label, progress ->
                            Log.i(TAG, "$label: ${(progress * 100).toInt()}%")
                        }
                        Log.i(TAG, "Preloaded vendor/QNN native libraries for MNN")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not preload vendor/QNN native libraries for MNN: ${t.message}")
                    }
                }
                val mnn = MnnInferenceBackend(modelFile, targetThreads, hardwareTarget, modelInfo)
                activeProviderShort = mnn.shortProviderName
                activeProviderName = mnn.providerName
                mnn
            } else {
                Log.i(TAG, "Initializing ONNX inference backend for ${modelInfo.id} (${modelFile.name}, target=$hardwareTarget)...")
                val onnx = OnnxBackendFactory.createBackend(
                    context,
                    modelFile,
                    targetThreads,
                    hardwareTarget,
                    modelInfo,
                    moduleApkPath
                )
                activeProviderShort = onnx.shortProviderName
                activeProviderName = onnx.providerName
                onnx
            }

            currentBackend = backend
            currentThreadCount = targetThreads
            currentTarget = hardwareTarget
            currentForceCpu = forceCpu
            currentModelId = modelInfo.id

            if (oldBackend != null && oldBackend !== backend) {
                sessionCloser.schedule({
                    try {
                        oldBackend.close()
                    } catch (_: Throwable) {}
                }, 30, java.util.concurrent.TimeUnit.SECONDS)
            }

            return backend
        }
    }

    fun registerDynamicModel(info: ModelInfo) {
        // Register ONNX models even before their native libs are downloaded;
        // the backend creation path will fetch them on demand.

        dynamicModels[info.id] = info
        synchronized(catalogLock) {
            val list = AVAILABLE_MODELS.toMutableList()
            list.removeAll { it.id == info.id }
            list.add(0, info)
            AVAILABLE_MODELS = list
            catalogVersion++
            Log.i(TAG, "Registered dynamic model: ${info.id} (${info.fileName}, stem=${info.targetStem}, type=${info.networkType}, bins=${info.numBins})")
        }
    }

    fun getCandidateModelFiles(context: Context, fileName: String): List<File> {
        return listOf(
            File(File(context.filesDir, "models"), fileName),
            File("/sdcard/Download/sing_models", fileName),
            File("/storage/emulated/0/Download/sing_models", fileName),
            File("/data/user/0/org.francasio.sing/files/models", fileName),
            File("/data/data/org.francasio.sing/files/models", fileName),
            File(context.cacheDir, fileName)
        )
    }

    private fun ensureModelExtracted(context: Context, moduleApkPath: String, fileName: String): File {
        val targetFile = File(context.cacheDir, fileName)
        if (targetFile.exists() && targetFile.length() > 1024 * 1024) {
            return targetFile
        }

        // 0. Check directly in module downloaded models dir or standard shared paths
        val directCandidates = getCandidateModelFiles(context, fileName)
        for (f in directCandidates) {
            try {
                if (f.exists() && f.canRead() && f.length() > 1024 * 1024) {
                    if (targetFile.exists() && targetFile.length() == f.length()) {
                        return targetFile
                    }
                    if (f.absolutePath == targetFile.absolutePath) {
                        return f
                    }
                    Log.i(TAG, "Copying model $fileName directly from ${f.absolutePath} to ${targetFile.absolutePath}...")
                    f.copyTo(targetFile, overwrite = true)
                    if (targetFile.length() == f.length()) {
                        return targetFile
                    }
                }
            } catch (_: Throwable) {}
        }

        // 1. Check ContentProvider (models downloaded via module app)
        try {
            val contentUri = Uri.parse("content://org.francasio.sing.provider/models/$fileName")
            context.contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd ->
                if (pfd.statSize > 1024 * 1024) {
                    if (targetFile.exists() && targetFile.length() == pfd.statSize) {
                        return targetFile
                    }
                    Log.i(TAG, "Streaming downloaded model $fileName (${pfd.statSize / 1024 / 1024} MB) from SingSettingsProvider...")
                    val tmpTarget = File(context.cacheDir, "$fileName.tmp")
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        FileOutputStream(tmpTarget).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tmpTarget.length() == pfd.statSize) {
                        if (targetFile.exists()) targetFile.delete()
                        tmpTarget.renameTo(targetFile)
                        Log.i(TAG, "Successfully extracted model from provider to ${targetFile.absolutePath}")
                        return targetFile
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Model $fileName not provided by the module app (${t.message}) - checking APK assets next")
        }

        val candidatePaths = mutableListOf<String>()
        if (moduleApkPath.isNotEmpty()) candidatePaths.add(moduleApkPath)
        val resolved = SingSettings.resolvedModulePath
        if (!resolved.isNullOrEmpty() && resolved != moduleApkPath) candidatePaths.add(resolved)
        candidatePaths.add(File(context.cacheDir, "lspatch_embedded_sing.apk").absolutePath)
        val lspatchDir = File(context.cacheDir, "lspatch/org.francasio.sing")
        if (lspatchDir.exists() && lspatchDir.isDirectory) {
            lspatchDir.listFiles { f -> f.extension == "apk" }?.forEach { apk ->
                if (!candidatePaths.contains(apk.absolutePath)) candidatePaths.add(apk.absolutePath)
            }
        }
        try {
            val pmApk = context.packageManager.getApplicationInfo("org.francasio.sing", 0).sourceDir
            if (!pmApk.isNullOrEmpty() && !candidatePaths.contains(pmApk)) candidatePaths.add(pmApk)
        } catch (_: Throwable) {}

        val assetPath = "assets/$fileName"
        for (path in candidatePaths) {
            val apkFile = File(path)
            if (apkFile.exists() && apkFile.canRead()) {
                try {
                    ZipFile(apkFile).use { zip ->
                        val entry = zip.getEntry(assetPath) ?: zip.getEntry(fileName)
                        if (entry != null) {
                            if (targetFile.exists() && targetFile.length() == entry.size &&
                                targetFile.lastModified() >= apkFile.lastModified()) {
                                return targetFile
                            }

                            Log.i(TAG, "Extracting model $fileName (${entry.size} bytes) from: ${apkFile.absolutePath}")
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(targetFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            Log.i(TAG, "Extracted model (${targetFile.length() / 1024 / 1024} MB) to ${targetFile.absolutePath}")
                            return targetFile
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed extracting $fileName from ${apkFile.absolutePath}: ${t.message}")
                }
            }
        }

        // Fallback: check assets directly
        val assetNames = listOf(fileName, "assets/$fileName")
        for (name in assetNames) {
            try {
                context.assets.open(name).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (targetFile.exists() && targetFile.length() > 0) {
                    Log.i(TAG, "Extracted model from assets/$name (${targetFile.length() / 1024 / 1024} MB)")
                    return targetFile
                }
            } catch (_: Throwable) {}
        }

        if (targetFile.exists() && targetFile.length() > 1024 * 1024) {
            return targetFile
        }

        // 4. Final Fallback: Download model directly to cacheDir via HTTP if missing
        val catalogInfo = AVAILABLE_MODELS.find { it.fileName == fileName }
        val candidateUrls = getModelDownloadUrls(context, fileName, catalogInfo?.downloadUrl)
        Log.i(TAG, "Model $fileName not yet in cache. Downloading directly to ${targetFile.absolutePath} from: $candidateUrls")
        val tmpTarget = File(context.cacheDir, "$fileName.tmp")
        for (urlStr in candidateUrls) {
            try {
                val url = java.net.URL(urlStr)
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AppleMusicSing-Android")
                }
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { input ->
                        FileOutputStream(tmpTarget).use { output ->
                            input.copyTo(output)
                        }
                    }
                    conn.disconnect()
                    if (tmpTarget.length() > 1024 * 1024) {
                        if (targetFile.exists()) targetFile.delete()
                        tmpTarget.renameTo(targetFile)
                        Log.i(TAG, "Successfully downloaded model $fileName (${targetFile.length() / 1024 / 1024} MB) to cacheDir")
                        return targetFile
                    }
                }
                conn.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "Direct download attempt failed for $urlStr: ${t.message}")
            }
        }
        tmpTarget.delete()

        if (targetFile.exists() && targetFile.length() > 1024 * 1024) {
            return targetFile
        }

        error("Model $fileName not found in $moduleApkPath, provider, or network")
    }

    val threadCount: Int
        get() = if (currentThreadCount > 0) currentThreadCount else defaultThreadCount

    var activeProviderName: String = "Unknown"
        private set
    var activeProviderShort: String = "Unknown"
        private set

    @Volatile
    private var nativeLibsLoaded = false

    fun ensureNativeLibrariesLoaded(context: Context, moduleApkPath: String = "") {
        if (nativeLibsLoaded) return
        synchronized(nativeLibsLock) {
            if (nativeLibsLoaded) return

            // Load local sing_dsp native audio processing library
            try {
                System.loadLibrary("sing_dsp")
                Log.i(TAG, "Successfully loaded libsing_dsp.so")
            } catch (t: Throwable) {
                Log.w(TAG, "Direct load of sing_dsp failed: ${t.message}")
            }

            nativeLibsLoaded = true
        }
    }
}
