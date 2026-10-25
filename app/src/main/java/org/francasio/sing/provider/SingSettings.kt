package org.francasio.sing.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log

object SingSettings {
    const val ACTION_SETTINGS_UPDATE = "org.francasio.sing.SETTINGS_UPDATE"
    const val ACTION_REQUEST_SETTINGS = "org.francasio.sing.REQUEST_SETTINGS"

    const val KEY_PRESERVE_SIDE = "preserve_side"
    const val KEY_TWO_WAY_SLIDER = "two_way_slider"
    const val KEY_CENTER_GROUP_LYRICS = "center_group_lyrics"
    const val KEY_BLUR_UNFOCUSED_LYRICS = "blur_unfocused_lyrics"
    const val KEY_ENGLISH_TRANSLATION_FALLBACK = "english_translation_fallback"
    const val KEY_THREAD_COUNT = "thread_count"
    const val KEY_ECO_MODE = "eco_mode"
    const val KEY_HARDWARE_TARGET = "hardware_target"
    const val KEY_FORCE_CPU = "force_cpu"
    const val KEY_SELECTED_MODEL = "selected_model"

    const val TARGET_NPU = "NPU"
    const val TARGET_GPU = "GPU"
    const val TARGET_NNAPI = "NNAPI"
    const val TARGET_CPU = "CPU"
    const val KEY_ALLOW_ZERO_FLOOR = "allow_zero_floor"
    const val KEY_FORCE_SONG_ELIGIBILITY = "force_song_eligibility"
    const val KEY_MATERIAL_YOU = "material_you"
    const val KEY_BACK_TIP_LEARNED = "back_tip_learned"
    var isDebugOverride: Boolean? = null

    fun isDebug(context: Context?): Boolean {
        isDebugOverride?.let { return it }
        if (context == null) return false
        return (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    val isArm64: Boolean
        get() = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true

    const val DEFAULT_MODEL_ID = ""

    fun sanitizeModelId(id: String?): String {
        return when (id) {
            "apple_sing", "apple_sing_fp16", "apple_sing_onnx_fp16", "apple_sing_onnx_fp32",
            "apple_sing_tflite", "apple_sing_litert", "apple_sing_litert_fp32" -> "apple_sing_native_fp32"
            "kim_inst", "kim_inst_fp16" -> "kim_inst_onnx_fp16"
            "kim_inst_tflite", "kim_inst_litert", "kim_inst_litert_fp32" -> "kim_inst_onnx_fp32"
            "onnx_fp16", "uvr_mdxnet_fp16", "uvr_mdxnet_mixed" -> "uvr_mdxnet_1_9703_onnx_fp16"
            "litert_fp32", "uvr_mdxnet_litert", "litert_fp16", "uvr_mdxnet_1_9703_litert_fp32" -> "uvr_mdxnet_1_9703_onnx_fp32"
            "onnx_fp32", "uvr_mdxnet_fp32" -> "uvr_mdxnet_1_9703_onnx_fp32"
            "onnx_voc_ft", "onnx_voc_ft_fp32", "litert_voc_ft" -> "apple_sing_native_fp32"
            null, "" -> ""
            else -> id
        }
    }

    const val ENGINE_ONNX = "onnx"
    const val ENGINE_LITERT = "litert"
    const val ENGINE_NATIVE = "native"
    const val ENGINE_MNN = "mnn"

    const val KEY_HEARTBEAT_TIME = "heartbeat_time"
    const val KEY_HEARTBEAT_MANAGER = "heartbeat_manager"
    private const val KEY_HEARTBEAT_ENGINE_VERSION = "heartbeat_engine_version"

    const val METHOD_GET_SETTINGS = "getSettings"
    const val METHOD_SET_SETTING = "setSetting"
    const val METHOD_HEARTBEAT = "heartbeat"

    private const val PREFS_MODULE = "apple_sing_settings"
    private const val PREFS_CACHE = "apple_sing_cache"
    private const val TAG = "AppleSing_Settings"

    @Volatile
    var preserveSide: Boolean = false

    @Volatile
    var twoWaySlider: Boolean = false

    @Volatile
    var centerGroupLyrics: Boolean = false

    @Volatile
    var blurUnfocusedLyrics: Boolean = false

    @Volatile
    var englishTranslationFallback: Boolean = true

    @Volatile
    var threadCount: Int = 4

    @Volatile
    var ecoMode: Boolean = true

    @Volatile
    var hardwareTarget: String = TARGET_NPU

    var forceCpu: Boolean
        get() = hardwareTarget == TARGET_CPU
        set(value) {
            hardwareTarget = if (value) TARGET_CPU else TARGET_NPU
        }

    @Volatile
    var selectedModelId: String = DEFAULT_MODEL_ID

    @Volatile
    var allowZeroFloor: Boolean = false

    @Volatile
    var forceSongEligibility: Boolean = false

    @Volatile
    var materialYou: Boolean = true

    @Volatile
    var backTipLearned: Boolean = false

    @Volatile
    var lastHeartbeatTime: Long = 0L

    @Volatile
    var lastHeartbeatManager: String? = null

    @Volatile
    var resolvedModulePath: String? = null

    // Called by MainActivity (module app)
    fun loadLocal(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE)
        preserveSide = prefs.getBoolean(KEY_PRESERVE_SIDE, false)
        twoWaySlider = prefs.getBoolean(KEY_TWO_WAY_SLIDER, false)
        centerGroupLyrics = prefs.getBoolean(KEY_CENTER_GROUP_LYRICS, false)
        blurUnfocusedLyrics = prefs.getBoolean(KEY_BLUR_UNFOCUSED_LYRICS, false)
        englishTranslationFallback = prefs.getBoolean(KEY_ENGLISH_TRANSLATION_FALLBACK, true)
        threadCount = prefs.getInt(KEY_THREAD_COUNT, 4)
        ecoMode = prefs.getBoolean(KEY_ECO_MODE, true)
        val savedTarget = prefs.getString(KEY_HARDWARE_TARGET, null)
        hardwareTarget = savedTarget ?: if (prefs.getBoolean(KEY_FORCE_CPU, false)) TARGET_CPU else TARGET_NPU
        selectedModelId = sanitizeModelId(prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL_ID))
        if (selectedModelId.isNotEmpty()) {
            org.francasio.sing.util.ModelLoader.loadSelectedModel(context)
        }
        allowZeroFloor = prefs.getBoolean(KEY_ALLOW_ZERO_FLOOR, false)
        forceSongEligibility = prefs.getBoolean(KEY_FORCE_SONG_ELIGIBILITY, false)
        materialYou = prefs.getBoolean(KEY_MATERIAL_YOU, true)
        backTipLearned = prefs.getBoolean(KEY_BACK_TIP_LEARNED, false)
        lastHeartbeatTime = prefs.getLong(KEY_HEARTBEAT_TIME, 0L)
        lastHeartbeatManager = prefs.getString(KEY_HEARTBEAT_MANAGER, null)
        moduleVersion = prefs.getString(KEY_HEARTBEAT_ENGINE_VERSION, null)
        activeProvider = prefs.getString(KEY_ACTIVE_PROVIDER, "") ?: ""
    }

    const val KEY_ACTIVE_PROVIDER = "active_provider"
    @Volatile
    var activeProvider: String = ""

    @Volatile
    var moduleReachable: Boolean = false

    @Volatile
    var moduleVersion: String? = null

    fun saveHeartbeat(
        context: Context,
        timestamp: Long,
        manager: String? = null,
        activeProvider: String? = null,
        engineVersion: String? = null
    ) {
        lastHeartbeatTime = timestamp
        moduleVersion = engineVersion?.takeIf { it.isNotBlank() }
        if (manager != null) {
            lastHeartbeatManager = manager
        }
        if (!activeProvider.isNullOrEmpty()) {
            this.activeProvider = activeProvider
        }
        try {
            val prefs = context.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE)
            val editor = prefs.edit().putLong(KEY_HEARTBEAT_TIME, timestamp)
                .putString(KEY_HEARTBEAT_ENGINE_VERSION, moduleVersion)
            if (manager != null) {
                editor.putString(KEY_HEARTBEAT_MANAGER, manager)
            }
            if (!activeProvider.isNullOrEmpty()) {
                editor.putString(KEY_ACTIVE_PROVIDER, activeProvider)
            }
            editor.apply()
        } catch (_: Throwable) {}
    }

    fun isHeartbeatActive(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (lastHeartbeatTime > 0 && (now - lastHeartbeatTime) < 120_000) return true
        val prefs = context.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_HEARTBEAT_TIME, 0L)
        if (ts > 0 && (now - ts) < 120_000) {
            lastHeartbeatTime = ts
            lastHeartbeatManager = prefs.getString(KEY_HEARTBEAT_MANAGER, null)
            return true
        }
        return false
    }

    fun getHeartbeatManagerInfo(context: Context): String? {
        val manager = lastHeartbeatManager ?: run {
            val prefs = context.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE)
            prefs.getString(KEY_HEARTBEAT_MANAGER, null)
        } ?: return null

        val ts = if (lastHeartbeatTime > 0) lastHeartbeatTime else {
            val prefs = context.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE)
            prefs.getLong(KEY_HEARTBEAT_TIME, 0L)
        }
        val isRecent = (System.currentTimeMillis() - ts) < 120_000 // 2 minutes
        return if (isRecent) {
            "$manager (Active)"
        } else {
            manager
        }
    }

    fun saveLocal(context: Context, key: String, value: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).apply()
        when (key) {
            KEY_PRESERVE_SIDE -> preserveSide = value
            KEY_TWO_WAY_SLIDER -> twoWaySlider = value
            KEY_CENTER_GROUP_LYRICS -> centerGroupLyrics = value
            KEY_BLUR_UNFOCUSED_LYRICS -> blurUnfocusedLyrics = value
            KEY_ENGLISH_TRANSLATION_FALLBACK -> englishTranslationFallback = value
            KEY_ECO_MODE -> ecoMode = value
            KEY_FORCE_CPU -> {
                forceCpu = value
                prefs.edit().putString(KEY_HARDWARE_TARGET, hardwareTarget).apply()
            }
            KEY_ALLOW_ZERO_FLOOR -> allowZeroFloor = value
            KEY_FORCE_SONG_ELIGIBILITY -> forceSongEligibility = value
            KEY_MATERIAL_YOU -> materialYou = value
            KEY_BACK_TIP_LEARNED -> backTipLearned = value
        }
    }

    fun setBackTipLearned(context: Context, learned: Boolean = true) {
        saveLocal(context, KEY_BACK_TIP_LEARNED, learned)
    }

    fun setMaterialYou(context: Context, enabled: Boolean) {
        saveLocal(context, KEY_MATERIAL_YOU, enabled)
    }

    fun saveLocalString(context: Context, key: String, value: String) {
        val prefs = context.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
        when (key) {
            KEY_SELECTED_MODEL -> {
                selectedModelId = value
                if (value.isNotEmpty()) {
                    val info = org.francasio.sing.util.ModelLoader.findCatalogModel(value)
                    if (info != null) {
                        org.francasio.sing.util.ModelLoader.saveSelectedModel(context, info)
                    }
                } else {
                    org.francasio.sing.util.ModelLoader.clearSelectedModel(context)
                }
            }
            KEY_HARDWARE_TARGET -> {
                hardwareTarget = value
                prefs.edit().putBoolean(KEY_FORCE_CPU, value == TARGET_CPU).apply()
            }
        }
    }

    fun saveLocalInt(context: Context, key: String, value: Int) {
        val prefs = context.getSharedPreferences(PREFS_MODULE, Context.MODE_PRIVATE)
        prefs.edit().putInt(key, value).apply()
        when (key) {
            KEY_THREAD_COUNT -> threadCount = value
        }
    }

    fun createSettingsUpdateIntent(context: Context, targetPackage: String? = null): Intent {
        val modelInfo = org.francasio.sing.util.ModelLoader.findCatalogModel(selectedModelId)
        return Intent(ACTION_SETTINGS_UPDATE).apply {
            if (targetPackage != null) {
                setPackage(targetPackage)
            }
            putExtra(KEY_PRESERVE_SIDE, preserveSide)
            putExtra(KEY_TWO_WAY_SLIDER, twoWaySlider)
            putExtra(KEY_CENTER_GROUP_LYRICS, centerGroupLyrics)
            putExtra(KEY_BLUR_UNFOCUSED_LYRICS, blurUnfocusedLyrics)
            putExtra(KEY_ENGLISH_TRANSLATION_FALLBACK, englishTranslationFallback)
            putExtra(KEY_THREAD_COUNT, threadCount)
            putExtra(KEY_ECO_MODE, ecoMode)
            putExtra(KEY_HARDWARE_TARGET, hardwareTarget)
            putExtra(KEY_FORCE_CPU, forceCpu)
            putExtra(KEY_SELECTED_MODEL, selectedModelId)
            putExtra(KEY_ALLOW_ZERO_FLOOR, allowZeroFloor)
            putExtra(KEY_FORCE_SONG_ELIGIBILITY, forceSongEligibility)
            putExtra("module_apk_path", context.applicationInfo.sourceDir)
            if (modelInfo != null) {
                putExtra("model_target_stem", modelInfo.targetStem)
                putExtra("model_network_type", modelInfo.networkType)
                putExtra("model_num_bins", modelInfo.numBins)
                putExtra("model_num_frames", modelInfo.numFrames)
                putExtra("model_fft_size", modelInfo.fftSize)
                putExtra("model_hop_size", modelInfo.hopSize)
                putExtra("model_chunk_samples", modelInfo.chunkSamples)
                putExtra("model_crossfade_samples", modelInfo.crossfadeSamples)
                putExtra("model_hop_samples", modelInfo.hopSamples)
                putExtra("model_block_size", modelInfo.blockSize)
                putExtra("model_lookahead_size", modelInfo.lookaheadSize)
                putExtra("model_file_name", modelInfo.fileName)
                putExtra("model_engine", modelInfo.engine)
                putExtra("model_precision", modelInfo.precision)
            }
        }
    }

    fun broadcastSettings(context: Context) {
        try {
            val baseUri = Uri.parse("content://org.francasio.sing.provider/")
            try {
                context.grantUriPermission(
                    "com.apple.android.music",
                    baseUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            } catch (_: Throwable) {}

            val intent = createSettingsUpdateIntent(context)
            context.sendBroadcast(intent)
            val modelInfo = org.francasio.sing.util.ModelLoader.findCatalogModel(selectedModelId)
            Log.i(TAG, "Broadcasted settings: preserveSide=$preserveSide, twoWaySlider=$twoWaySlider, centerGroupLyrics=$centerGroupLyrics, threads=$threadCount, ecoMode=$ecoMode, target=$hardwareTarget (forceCpu=$forceCpu), model=$selectedModelId (${modelInfo?.fileName ?: "catalog entry not found"}), allowZeroFloor=$allowZeroFloor, forceSongEligibility=$forceSongEligibility")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to broadcast settings: ${t.message}")
        }
    }

    private val asyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun syncSettingsFromProviderDirect(context: Context): Boolean {
        try {
            val uri = Uri.parse("content://org.francasio.sing.provider")
            val bundle = context.contentResolver.call(uri, METHOD_GET_SETTINGS, null, null)
            if (bundle != null) {
                preserveSide = bundle.getBoolean(KEY_PRESERVE_SIDE, preserveSide)
                twoWaySlider = bundle.getBoolean(KEY_TWO_WAY_SLIDER, twoWaySlider)
                centerGroupLyrics = bundle.getBoolean(KEY_CENTER_GROUP_LYRICS, centerGroupLyrics)
                blurUnfocusedLyrics = bundle.getBoolean(KEY_BLUR_UNFOCUSED_LYRICS, blurUnfocusedLyrics)
                englishTranslationFallback = bundle.getBoolean(KEY_ENGLISH_TRANSLATION_FALLBACK, englishTranslationFallback)
                threadCount = bundle.getInt(KEY_THREAD_COUNT, threadCount)
                ecoMode = bundle.getBoolean(KEY_ECO_MODE, ecoMode)
                val providerTarget = bundle.getString(KEY_HARDWARE_TARGET)
                hardwareTarget = providerTarget ?: if (bundle.getBoolean(KEY_FORCE_CPU, forceCpu)) TARGET_CPU else TARGET_NPU
                selectedModelId = sanitizeModelId(bundle.getString(KEY_SELECTED_MODEL, selectedModelId))
                allowZeroFloor = bundle.getBoolean(KEY_ALLOW_ZERO_FLOOR, allowZeroFloor)
                forceSongEligibility = bundle.getBoolean(KEY_FORCE_SONG_ELIGIBILITY, forceSongEligibility)

                val apkPath = bundle.getString("module_apk_path")
                if (!apkPath.isNullOrBlank() && java.io.File(apkPath).exists()) {
                    resolvedModulePath = apkPath
                    Log.i(TAG, "Updated resolvedModulePath from provider: $apkPath")
                }

                try {
                    val prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean(KEY_PRESERVE_SIDE, preserveSide)
                        .putBoolean(KEY_TWO_WAY_SLIDER, twoWaySlider)
                        .putBoolean(KEY_CENTER_GROUP_LYRICS, centerGroupLyrics)
                        .putBoolean(KEY_BLUR_UNFOCUSED_LYRICS, blurUnfocusedLyrics)
                        .putBoolean(KEY_ENGLISH_TRANSLATION_FALLBACK, englishTranslationFallback)
                        .putInt(KEY_THREAD_COUNT, threadCount)
                        .putBoolean(KEY_ECO_MODE, ecoMode)
                        .putString(KEY_HARDWARE_TARGET, hardwareTarget)
                        .putBoolean(KEY_FORCE_CPU, forceCpu)
                        .putString(KEY_SELECTED_MODEL, selectedModelId)
                        .putBoolean(KEY_ALLOW_ZERO_FLOOR, allowZeroFloor)
                        .putBoolean(KEY_FORCE_SONG_ELIGIBILITY, forceSongEligibility)
                        .apply()
                } catch (_: Throwable) {}

                Log.i(TAG, "Synced settings from provider: preserveSide=$preserveSide, twoWaySlider=$twoWaySlider, threads=$threadCount, ecoMode=$ecoMode, target=$hardwareTarget (forceCpu=$forceCpu), model=$selectedModelId, allowZeroFloor=$allowZeroFloor, forceSongEligibility=$forceSongEligibility")
                moduleReachable = true
                settingsSyncRetryCount = 0
                return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not sync settings from provider: ${t.message}")
        }
        return false
    }

    fun syncSettingsFromProvider(context: Context) {
        asyncExecutor.execute {
            val success = syncSettingsFromProviderDirect(context)
            if (success) {
                onChangeCallback?.invoke()
            } else if (settingsSyncRetryCount < 3) {
                settingsSyncRetryCount++
                try {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        syncSettingsFromProvider(context)
                    }, 2500L * settingsSyncRetryCount)
                } catch (_: Throwable) {}
            }
        }
    }

    fun sendHeartbeat(context: Context, managerInfo: String? = null, activeProvider: String? = null) {
        asyncExecutor.execute {
            try {
                val uri = Uri.parse("content://org.francasio.sing.provider")
                val extras = Bundle().apply {
                    putLong("timestamp", System.currentTimeMillis())
                    putString("engine_version", ENGINE_VERSION)
                    if (managerInfo != null) {
                        putString("manager_info", managerInfo)
                    }
                    if (activeProvider != null) {
                        putString("active_provider", activeProvider)
                    }
                }
                context.contentResolver.call(uri, METHOD_HEARTBEAT, null, extras)
                Log.i(TAG, "Sent heartbeat to org.francasio.sing.provider (activeProvider=$activeProvider)")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to send heartbeat via ContentResolver: ${t.message}")
            }

            try {
                val req = Intent(ACTION_REQUEST_SETTINGS).apply {
                    setPackage("org.francasio.sing")
                    putExtra("timestamp", System.currentTimeMillis())
                    putExtra("engine_version", ENGINE_VERSION)
                    putExtra("caller_package", context.packageName)
                    if (managerInfo != null) {
                        putExtra("manager_info", managerInfo)
                    }
                    if (activeProvider != null) {
                        putExtra("active_provider", activeProvider)
                    }
                }
                context.sendBroadcast(req)
                Log.i(TAG, "Sent ACTION_REQUEST_SETTINGS broadcast to org.francasio.sing")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to send ACTION_REQUEST_SETTINGS broadcast: ${t.message}")
            }
        }
    }

    private var onChangeCallback: (() -> Unit)? = null
    private var settingsSyncRetryCount = 0

    private fun handleSettingsUpdate(ctx: Context, intent: Intent) {
        val newPreserveSide = intent.getBooleanExtra(KEY_PRESERVE_SIDE, preserveSide)
        val newTwoWaySlider = intent.getBooleanExtra(KEY_TWO_WAY_SLIDER, twoWaySlider)
        val newCenterGroupLyrics = intent.getBooleanExtra(KEY_CENTER_GROUP_LYRICS, centerGroupLyrics)
        val newBlurUnfocusedLyrics = intent.getBooleanExtra(KEY_BLUR_UNFOCUSED_LYRICS, blurUnfocusedLyrics)
        val newEnglishTranslationFallback = intent.getBooleanExtra(KEY_ENGLISH_TRANSLATION_FALLBACK, englishTranslationFallback)
        val newThreadCount = intent.getIntExtra(KEY_THREAD_COUNT, threadCount)
        val newEcoMode = intent.getBooleanExtra(KEY_ECO_MODE, ecoMode)
        val newTarget = intent.getStringExtra(KEY_HARDWARE_TARGET) ?: (if (intent.getBooleanExtra(KEY_FORCE_CPU, forceCpu)) TARGET_CPU else TARGET_NPU)
        val newModelId = sanitizeModelId(intent.getStringExtra(KEY_SELECTED_MODEL) ?: selectedModelId)
        val newAllowZeroFloor = intent.getBooleanExtra(KEY_ALLOW_ZERO_FLOOR, allowZeroFloor)
        val newForceSongEligibility = intent.getBooleanExtra(KEY_FORCE_SONG_ELIGIBILITY, forceSongEligibility)
        val apkPath = intent.getStringExtra("module_apk_path")
        if (!apkPath.isNullOrEmpty()) {
            resolvedModulePath = apkPath
            Log.i(TAG, "Updated resolvedModulePath from broadcast: $apkPath")
        }
        preserveSide = newPreserveSide
        twoWaySlider = newTwoWaySlider
        centerGroupLyrics = newCenterGroupLyrics
        blurUnfocusedLyrics = newBlurUnfocusedLyrics
        englishTranslationFallback = newEnglishTranslationFallback
        threadCount = newThreadCount
        ecoMode = newEcoMode
        hardwareTarget = newTarget
        selectedModelId = newModelId
        allowZeroFloor = newAllowZeroFloor
        forceSongEligibility = newForceSongEligibility

        val modelFileName = intent.getStringExtra("model_file_name")
        val engineFromApp = intent.getStringExtra("model_engine") ?: "onnx"
        val infoIsConsistent = !(engineFromApp == "native" &&
            sanitizeModelId(newModelId) != "apple_sing_native_fp32")
        val existingCatalogModel = org.francasio.sing.util.ModelLoader.findCatalogModel(newModelId)
        if (existingCatalogModel != null) {
            // Model is already well-defined in models.json catalog - do not overwrite with fallback defaults
        } else if (newModelId.isNotEmpty() && !modelFileName.isNullOrEmpty() && infoIsConsistent) {
            val netType = intent.getStringExtra("model_network_type") ?: "time_domain"
            val isVr = netType.equals("vr_3band", ignoreCase = true) || netType.equals("vr", ignoreCase = true)
            val defaultNumFrames = if (isVr) 512 else 256
            val defaultNumBins = if (isVr) 769 else 2048
            val defaultFftSize = if (isVr) 768 else 6144
            val defaultHopSize = if (isVr) 512 else 1024
            val defaultChunkSamples = if (isVr) 261632 else 261120
            val defaultCrossfade = if (isVr) 0 else 44100
            val defaultHopSamples = if (isVr) 131072 else 217020
            val mInfo = org.francasio.sing.util.ModelLoader.ModelInfo(
                id = newModelId,
                modelId = newModelId.substringBefore("_onnx").substringBefore("_litert").substringBefore("_mnn").substringBefore("_native"),
                modelDisplayName = newModelId,
                displayName = intent.getStringExtra("model_precision") ?: "FP16",
                targetStem = intent.getStringExtra("model_target_stem") ?: "vocal",
                networkType = netType,
                numBins = intent.getIntExtra("model_num_bins", defaultNumBins),
                numFrames = intent.getIntExtra("model_num_frames", defaultNumFrames),
                fftSize = intent.getIntExtra("model_fft_size", defaultFftSize),
                hopSize = intent.getIntExtra("model_hop_size", defaultHopSize),
                chunkSamples = intent.getIntExtra("model_chunk_samples", defaultChunkSamples),
                crossfadeSamples = intent.getIntExtra("model_crossfade_samples", defaultCrossfade),
                hopSamples = intent.getIntExtra("model_hop_samples", defaultHopSamples),
                blockSize = intent.getIntExtra("model_block_size", 4096),
                lookaheadSize = intent.getIntExtra("model_lookahead_size", 16384),
                engine = intent.getStringExtra("model_engine") ?: "onnx",
                precision = intent.getStringExtra("model_precision") ?: "FP16",
                fileName = modelFileName,
                fileSizeDesc = ""
            )
            org.francasio.sing.util.ModelLoader.registerDynamicModel(mInfo)
        } else if (!modelFileName.isNullOrEmpty() && !infoIsConsistent) {
            Log.w(TAG, "Ignoring inconsistent model info from app for $newModelId (engine=$engineFromApp, file=$modelFileName) - resolving against local catalog")
        }

        try {
            val prefs = ctx.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_PRESERVE_SIDE, newPreserveSide)
                .putBoolean(KEY_TWO_WAY_SLIDER, newTwoWaySlider)
                .putBoolean(KEY_CENTER_GROUP_LYRICS, newCenterGroupLyrics)
                .putBoolean(KEY_BLUR_UNFOCUSED_LYRICS, newBlurUnfocusedLyrics)
                .putBoolean(KEY_ENGLISH_TRANSLATION_FALLBACK, newEnglishTranslationFallback)
                .putInt(KEY_THREAD_COUNT, newThreadCount)
                .putBoolean(KEY_ECO_MODE, newEcoMode)
                .putString(KEY_HARDWARE_TARGET, newTarget)
                .putBoolean(KEY_FORCE_CPU, newTarget == TARGET_CPU)
                .putString(KEY_SELECTED_MODEL, newModelId)
                .putBoolean(KEY_ALLOW_ZERO_FLOOR, newAllowZeroFloor)
                .putBoolean(KEY_FORCE_SONG_ELIGIBILITY, newForceSongEligibility)
                .apply()
        } catch (t: Throwable) {}

        Log.i(TAG, "Received settings broadcast: preserveSide=$preserveSide, twoWaySlider=$twoWaySlider, threads=$threadCount, ecoMode=$ecoMode, target=$hardwareTarget (forceCpu=$forceCpu), model=$selectedModelId, allowZeroFloor=$allowZeroFloor, forceSongEligibility=$forceSongEligibility")
        onChangeCallback?.invoke()
    }

    // Called by SingAudioProcessor (inside Apple Music process)
    fun initInAppleMusic(context: Context, managerInfo: String? = null, onChange: () -> Unit) {
        onChangeCallback = onChange
        // Send initial heartbeat
        sendHeartbeat(context, managerInfo)

        // 1. Read cached settings immediately
        try {
            val prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
            preserveSide = prefs.getBoolean(KEY_PRESERVE_SIDE, false)
            twoWaySlider = prefs.getBoolean(KEY_TWO_WAY_SLIDER, false)
            centerGroupLyrics = prefs.getBoolean(KEY_CENTER_GROUP_LYRICS, true)
            blurUnfocusedLyrics = prefs.getBoolean(KEY_BLUR_UNFOCUSED_LYRICS, true)
            englishTranslationFallback = prefs.getBoolean(KEY_ENGLISH_TRANSLATION_FALLBACK, true)
            threadCount = prefs.getInt(KEY_THREAD_COUNT, 4)
            ecoMode = prefs.getBoolean(KEY_ECO_MODE, true)
            val cachedTarget = prefs.getString(KEY_HARDWARE_TARGET, null)
            hardwareTarget = cachedTarget ?: if (prefs.getBoolean(KEY_FORCE_CPU, false)) TARGET_CPU else TARGET_NPU
            selectedModelId = sanitizeModelId(prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL_ID))
            allowZeroFloor = prefs.getBoolean(KEY_ALLOW_ZERO_FLOOR, false)
            forceSongEligibility = prefs.getBoolean(KEY_FORCE_SONG_ELIGIBILITY, false)
            Log.i(TAG, "Loaded cached settings: preserveSide=$preserveSide, twoWaySlider=$twoWaySlider, threads=$threadCount, ecoMode=$ecoMode, target=$hardwareTarget (forceCpu=$forceCpu), model=$selectedModelId, allowZeroFloor=$allowZeroFloor, forceSongEligibility=$forceSongEligibility")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not load cached settings: ${t.message}")
        }

        // 2. Query provider synchronously if already in background thread, or asynchronously if on main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            syncSettingsFromProviderDirect(context)
        } else {
            syncSettingsFromProvider(context)
        }

        // 3. Register dynamic receiver for live setting updates (offloaded via goAsync to avoid blocking UI)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_SETTINGS_UPDATE) {
                    val pending = goAsync()
                    asyncExecutor.execute {
                        try {
                            handleSettingsUpdate(ctx, intent)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Error handling settings update in background", t)
                        } finally {
                            try {
                                pending.finish()
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_SETTINGS_UPDATE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            Log.i(TAG, "Registered settings BroadcastReceiver in Apple Music")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register settings BroadcastReceiver: ${t.message}")
        }

        // 4. Request current settings from module
        try {
            val req = Intent(ACTION_REQUEST_SETTINGS).apply {
                setPackage("org.francasio.sing")
                if (managerInfo != null) {
                    putExtra("manager_info", managerInfo)
                }
            }
            context.sendBroadcast(req)
            Log.i(TAG, "Sent REQUEST_SETTINGS to org.francasio.sing")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send REQUEST_SETTINGS: ${t.message}")
        }
    }
}
