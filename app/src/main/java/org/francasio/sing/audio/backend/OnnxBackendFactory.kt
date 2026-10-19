package org.francasio.sing.audio.backend

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import org.francasio.sing.audio.StftEngine
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.util.ModelLoader
import org.francasio.sing.util.NativeEnv

object OnnxBackendFactory {
    private const val TAG = "AppleSing_OnnxFactory"

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    @Volatile
    private var onnxNativeLibsLoaded = false

    /**
     * Diagnostic level (set via the benchmark intent extra "qnn_debug"): 0 off,
     * 1 = QNN detailed profiling, 2 = + move process working directory into the
     * cache dir (so profiler output is writable), 3 = + rpc_control_latency.
     * Never non-zero in normal operation.
     */
    @Volatile
    var qnnDebug = 0

    @JvmStatic
    @Synchronized
    fun createBackend(
        context: Context,
        modelFile: File,
        threads: Int,
        target: String,
        modelInfo: ModelLoader.ModelInfo,
        moduleApkPath: String
    ): InferenceBackend {
        ensureOnnxNativeLibrariesLoaded(context, moduleApkPath)

        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
        val session = createSessionWithBestProvider(
            context, env, modelFile.absolutePath, threads, target, modelInfo, moduleApkPath
        )
        ortSession = session

        return OnnxInferenceBackend(env, session, activeProviderName, activeProviderShort, modelInfo)
    }

    @JvmStatic
    @Synchronized
    fun createBackend(
        context: Context,
        modelFile: File,
        threads: Int,
        forceCpu: Boolean,
        modelInfo: ModelLoader.ModelInfo,
        moduleApkPath: String
    ): InferenceBackend = createBackend(
        context,
        modelFile,
        threads,
        if (forceCpu) org.francasio.sing.provider.SingSettings.TARGET_CPU else org.francasio.sing.provider.SingSettings.TARGET_NPU,
        modelInfo,
        moduleApkPath
    )

    var activeProviderName: String = "Unknown"
        private set
    var activeProviderShort: String = "Unknown"
        private set

    fun ensureOnnxNativeLibrariesLoaded(context: Context, moduleApkPath: String = "") {
        if (onnxNativeLibsLoaded) return
        synchronized(this) {
            if (onnxNativeLibsLoaded) return

            try {
                System.loadLibrary("sing_dsp")
            } catch (_: Throwable) {}
            try {
                System.loadLibrary("onnxruntime")
                System.loadLibrary("onnxruntime4j_jni")
                Log.i(TAG, "ONNX Native libraries loaded directly via System.loadLibrary")
            } catch (t: Throwable) {
                Log.i(TAG, "Direct System.loadLibrary for ONNX failed: ${t.message}, attempting extraction")
            }

            val primaryAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val abiDirName = if (primaryAbi.startsWith("arm64")) "arm64" else primaryAbi

            // Must happen before the FastRPC transport library is loaded below:
            // covers every path (extraction, nativeLibraryDir, module lib dir).
            setupFastRpcSearchPaths(context, moduleApkPath, primaryAbi)

            // Check if already extracted by Package Manager
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            if (File(nativeDir, "libQnnHtp.so").exists()) {
                Log.i(TAG, "Native QNN libraries already present in nativeLibraryDir: ${nativeDir.absolutePath}")
                onnxNativeLibsLoaded = true
                return
            }
            if (moduleApkPath.isNotEmpty() && File(File(moduleApkPath).parentFile, "lib/$abiDirName/libQnnHtp.so").exists()) {
                Log.i(TAG, "Native QNN libraries already present in module lib dir")
                onnxNativeLibsLoaded = true
                return
            }

            val targetDir = File(context.cacheDir, "ort_libs_$primaryAbi")
            targetDir.mkdirs()

            val htpLib = File(targetDir, "libQnnHtp.so")
            val ortLib = File(targetDir, "libonnxruntime.so")
            val alreadyExtracted = htpLib.exists() && htpLib.length() > 0 && ortLib.exists() && ortLib.length() > 0

            if (!alreadyExtracted) {
                val candidateApks = mutableListOf<String>()
                try {
                    context.applicationInfo?.sourceDir?.let { if (File(it).exists()) candidateApks.add(it) }
                } catch (_: Throwable) {}
                if (moduleApkPath.isNotEmpty() && !candidateApks.contains(moduleApkPath)) candidateApks.add(moduleApkPath)
                val resolved = SingSettings.resolvedModulePath
                if (!resolved.isNullOrEmpty() && !candidateApks.contains(resolved)) candidateApks.add(resolved)
                candidateApks.add(File(context.cacheDir, "lspatch_embedded_sing.apk").absolutePath)
                val lspatchDir = File(context.cacheDir, "lspatch/org.francasio.sing")
                if (lspatchDir.exists() && lspatchDir.isDirectory) {
                    lspatchDir.listFiles { f -> f.extension == "apk" }?.forEach { apk ->
                        if (!candidateApks.contains(apk.absolutePath)) candidateApks.add(apk.absolutePath)
                    }
                }
                try {
                    val pmApk = context.packageManager.getApplicationInfo("org.francasio.sing", 0).sourceDir
                    if (pmApk != null && !candidateApks.contains(pmApk)) candidateApks.add(pmApk)
                } catch (_: Throwable) {}

                for (apkPath in candidateApks) {
                    val apkFile = File(apkPath)
                    if (apkFile.exists() && apkFile.canRead()) {
                        try {
                            ZipFile(apkFile).use { zip ->
                                val entries = zip.entries()
                                while (entries.hasMoreElements()) {
                                    val entry = entries.nextElement()
                                    // Only extract libs matching the *actual* device ABI. Mixing arm64-v8a
                                    // with x86_64 (e.g. a universal APK extracted on top of a leftover arm
                                    // dir) previously caused ARM .so to overwrite x86 ones into the same
                                    // flat cache directory and broke ORT's UnsatisfiedLinkError resolution.
                                    if (entry.name.startsWith("lib/$primaryAbi/") && entry.name.endsWith(".so")) {
                                        val fileName = entry.name.substringAfterLast('/')
                                        val outFile = File(targetDir, fileName)
                                        // Evict stale libs from a previous hybrid extraction (e.g. ARM64 libs
                                        // that leaked into an x86_64 cache during an older build): drop any
                                        // cached .so if its ELF machine marker does not match the device ABI.
                                        if (outFile.exists()) {
                                            try {
                                                val hdr = outFile.inputStream().use { it.readNBytes(20) }
                                                if (hdr.size >= 20) {
                                                    val eMachine = (hdr[19].toInt() and 0xff) shl 8 or (hdr[18].toInt() and 0xff)
                                                    val want = if (primaryAbi.startsWith("x86")) 0x003E else 0x00B7
                                                    if (eMachine != 0x0000 && eMachine != want) outFile.delete()
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                        if (!outFile.exists() || outFile.length() != entry.size) {
                                            zip.getInputStream(entry).use { input ->
                                                FileOutputStream(outFile).use { output ->
                                                    input.copyTo(output)
                                                }
                                            }
                                        }
                                        outFile.setReadable(true, false)
                                        outFile.setExecutable(true, false)
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed extracting native libs from $apkPath: ${e.message}")
                        }
                    }
                }
            } else {
                Log.i(TAG, "ONNX/QNN native libraries already extracted in ${targetDir.absolutePath}")
            }

            // QNN HTP needs the vendor FastRPC transport:
            //   libQnnHtpV75Stub.so -> libcdsprpc.so -> { libhidlbase,
            //   libhardware, libutils, libcutils, vendor.qti.hardware.dsp@1.0,
            //   libdmabufheap, libvmmem, libc++ }
            // From an app linker namespace only public libraries (libc/liblog/
            // libdl/libm and the /vendor public list) are resolvable by SONAME,
            // and /vendor/lib64 itself is never searched, so every non-public
            // dependency ships inside this APK and is preloaded from the extracted
            // cache dir in dependency order: a library already loaded in the
            // process satisfies DT_NEEDED lookups by SONAME.
            //
            // libdl_android.so is special - it needs ld-android.so (the linker
            // itself), which only resolves via a bionic special case; if the real
            // one cannot load, the stub built as libdl_android_stub.so (SONAME
            // libdl_android.so) takes its place.
            val libsToLoad = listOf(
                "libc++.so",
                "ld-android.so",
                "libdl_android.so",
                "libbase.so",
                "libdmabufheap.so",
                "libcutils.so",
                "libvndksupport.so",
                "libutils.so",
                "libhardware.so",
                "libhidlbase.so",
                "libvmmem.so",
                "vendor.qti.hardware.dsp@1.0.so",
                "libcdsprpc.so",
                "libQnnSystem.so",
                "libQnnHtpV75Stub.so",
                "libQnnHtp.so",
                "libonnxruntime.so",
                "libonnxruntime4j_jni.so"
            )
            for (libName in libsToLoad) {
                val candidates = if (libName == "libdl_android.so")
                    listOf("libdl_android.so", "libdl_android_stub.so") else listOf(libName)
                var loaded = false
                for (candidate in candidates) {
                    val libFile = File(targetDir, candidate)
                    if (!libFile.exists()) continue
                    try {
                        System.load(libFile.absolutePath)
                        Log.i(TAG, "Successfully loaded ONNX/QNN native library: ${libFile.name}")
                        loaded = true
                        break
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed loading ${libFile.name}: ${t.message}")
                    }
                }
                if (!loaded && (libName == "libcdsprpc.so" || libName == "vendor.qti.hardware.dsp@1.0.so")) {
                    try {
                        val bareName = libName.removePrefix("lib").removeSuffix(".so")
                        System.loadLibrary(bareName)
                        Log.i(TAG, "Successfully loaded system $libName via loadLibrary")
                    } catch (t: Throwable) {
                        Log.w(TAG, "System.loadLibrary($libName) failed: ${t.message}")
                    }
                }
            }
            onnxNativeLibsLoaded = true
        }
    }

    /**
     * FastRPC (used by the QNN HTP stack) resolves DSP-side library requests on
     * the host filesystem using search paths taken from the process environment
     * (ADSP_LIBRARY_PATH, ";"-separated). Java has no setenv(), so this goes
     * through the JNI bridge in libsing_dsp.so. Must run before the FastRPC
     * transport library loads so the first FastRPC use sees the paths.
     */
    private fun setupFastRpcSearchPaths(context: Context, moduleApkPath: String, primaryAbi: String) {
        try {
            val dirs = mutableListOf<String>()
            val cacheLibs = File(context.cacheDir, "ort_libs_$primaryAbi")
            cacheLibs.mkdirs()
            dirs.add(cacheLibs.absolutePath)
            try {
                context.applicationInfo?.nativeLibraryDir?.let { dir ->
                    if (File(dir).exists()) dirs.add(dir)
                }
            } catch (_: Throwable) {}
            if (moduleApkPath.isNotEmpty()) {
                val abiDirName = if (primaryAbi.startsWith("arm64")) "arm64" else primaryAbi
                val moduleLibs = File(File(moduleApkPath).parentFile, "lib/$abiDirName")
                if (moduleLibs.exists()) dirs.add(moduleLibs.absolutePath)
            }
            for (envName in listOf("ADSP_LIBRARY_PATH", "DSP_LIBRARY_PATH")) {
                val existing = System.getenv(envName)
                val value = buildString {
                    dirs.forEach { append(it).append(';') }
                    if (!existing.isNullOrEmpty()) append(existing)
                }.trimEnd(';')
                val ok = NativeEnv.setenv(envName, value)
                Log.i(TAG, "FastRPC search path $envName set=$ok -> $value")
            }
            if ((qnnDebug and 2) != 0) {
                val chdirOk = try { NativeEnv.chdir(cacheLibs.absolutePath) } catch (t: Throwable) {
                    Log.w(TAG, "Debug chdir failed: ${t.message}"); false
                }
                Log.i(TAG, "Debug: process working directory moved to ${cacheLibs.absolutePath} ok=$chdirOk")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not set FastRPC search paths (${t.message}); QNN will not find its Skel libraries")
        }
    }

    /**
     * Debug helper: copy diagnostic files (QNN profiler output etc.) that were
     * written into the cache dir to the app external files dir, where adb can
     * pull them, and log what was found.
     */
    fun exportDebugArtifacts(context: Context) {
        try {
            val dest = context.getExternalFilesDir(null) ?: context.filesDir
            dest.mkdirs()
            val base = context.cacheDir
            val allFiles = base.walkTopDown().filter { it.isFile }.toList()
            // Log everything found so a missing artifact is distinguishable from a
            // filter mismatch (recursive: the QNN profile CSV lands in the
            // ort_libs_* subdirectory, not directly in cacheDir).
            for (f in allFiles.take(60)) {
                Log.i(TAG, "Debug cache file: ${f.relativeTo(base)} (${f.length()} bytes)")
            }
            val interesting = allFiles.filter { f ->
                !f.name.endsWith(".so") && (
                    f.name.contains("rofiling", ignoreCase = true) ||
                        f.name.contains("Qnn", ignoreCase = true) ||
                        f.name.endsWith(".csv") || f.name.endsWith(".json") ||
                        f.name.endsWith(".txt") || f.name.endsWith(".log")
                    )
            }
            if (interesting.isEmpty()) {
                Log.i(TAG, "Debug export: no profiling artifacts found in ${base.absolutePath}")
            }
            for (f in interesting) {
                val out = File(dest, f.name.substringAfterLast('/'))
                f.copyTo(out, overwrite = true)
                Log.i(TAG, "Debug exported: ${f.name} (${f.length()} bytes) -> ${out.absolutePath}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Debug export failed: ${t.message}")
        }
    }

    /**
     * This device's per-process log quota regularly drops our log lines during the
     * burst of native QNN/FastRPC initialization logs (observed: "LOGS OVER PROC
     * QUOTA" right when the QNN error line would have been written), which made
     * silent CPU fallbacks impossible to diagnose from logcat alone. Record the
     * full exception chain to a file instead; in debug runs a copy also goes to
     * the external files dir, where adb can pull it.
     */
    private fun writeQnnErrorReport(context: Context, modelId: String, e: Throwable) {
        try {
            val sb = StringBuilder()
            sb.append("model: $modelId\n")
            sb.append("time: ${java.util.Date()}\n")
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < 6) {
                sb.append("\n=== [$depth] ${t.javaClass.name}: ${t.message}\n")
                sb.append(t.stackTraceToString())
                t = t.cause
                depth++
            }
            val f = File(context.cacheDir, "QnnErrorReport.txt")
            f.writeText(sb.toString())
            val dest = context.getExternalFilesDir(null) ?: context.filesDir
            dest.mkdirs()
            File(dest, "QnnErrorReport.txt").writeText(sb.toString())
        } catch (_: Throwable) {}
    }

    private fun findOrExtractQnnBackend(context: Context, moduleApkPath: String): String? {
        if (!SingSettings.isArm64) return null
        ensureOnnxNativeLibrariesLoaded(context, moduleApkPath)

        val primaryAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val abiDirName = if (primaryAbi.startsWith("arm64")) "arm64" else primaryAbi

        // 1. Direct check in applicationInfo.nativeLibraryDir
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val directHtp = File(nativeDir, "libQnnHtp.so")
        if (directHtp.exists() && directHtp.canRead()) {
            Log.i(TAG, "Found libQnnHtp.so in nativeLibraryDir: ${directHtp.absolutePath}")
            return directHtp.absolutePath
        }

        // 2. Direct check in module APK's sibling lib directory (for Xposed / LSPatch)
        val candidatePaths = listOf(moduleApkPath, SingSettings.resolvedModulePath ?: "")
        for (apkPath in candidatePaths) {
            if (apkPath.isNotEmpty()) {
                val moduleLibDir = File(File(apkPath).parentFile, "lib/$abiDirName")
                val moduleHtp = File(moduleLibDir, "libQnnHtp.so")
                if (moduleHtp.exists() && moduleHtp.canRead()) {
                    Log.i(TAG, "Found libQnnHtp.so in module lib dir: ${moduleHtp.absolutePath}")
                    return moduleHtp.absolutePath
                }
            }
        }

        // 3. Fallback to cacheDir/ort_libs_$primaryAbi
        val qnnDir = File(context.cacheDir, "ort_libs_$primaryAbi")
        val cachedHtp = File(qnnDir, "libQnnHtp.so")
        if (cachedHtp.exists() && cachedHtp.length() > 0) {
            Log.i(TAG, "Found libQnnHtp.so in cacheDir: ${cachedHtp.absolutePath}")
            return cachedHtp.absolutePath
        }

        Log.w(TAG, "libQnnHtp.so not found")
        return null
    }

    private fun createSessionWithBestProvider(
        context: Context,
        env: OrtEnvironment,
        modelPath: String,
        threads: Int,
        target: String = org.francasio.sing.provider.SingSettings.TARGET_NPU,
        modelInfo: ModelLoader.ModelInfo,
        moduleApkPath: String = ""
    ): OrtSession {
        if (target == org.francasio.sing.provider.SingSettings.TARGET_CPU) {
            Log.i(TAG, "Target CPU selected. Configuring multi-threaded CPU inference for ${modelInfo.id}.")
            return createCpuSession(env, modelPath, threads, modelInfo)
        }

        if (target == org.francasio.sing.provider.SingSettings.TARGET_NNAPI || target == org.francasio.sing.provider.SingSettings.TARGET_GPU) {
            try {
                Log.i(TAG, "Trying NNAPI Execution Provider for ${modelInfo.id} (target=$target)...")
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
                    setLoggerId("AppleSing_ORT")
                    addNnapi()
                }
                val session = try {
                    env.createSession(modelPath, opts)
                } finally {
                    opts.close()
                }
                activeProviderShort = if (target == org.francasio.sing.provider.SingSettings.TARGET_GPU) "ONNX NNAPI (GPU)" else "ONNX NNAPI"
                activeProviderName = "${modelInfo.precision} via ONNX NNAPI"
                Log.i(TAG, "ONNX NNAPI session created successfully for ${modelInfo.id}")
                return session
            } catch (e: Throwable) {
                Log.w(TAG, "ONNX NNAPI unavailable for ${modelInfo.id} (${e.javaClass.simpleName}: ${e.message}) - falling back to CPU")
            }
        }

        // 1. Try Qualcomm QNN (NPU/HTP hardware acceleration)
        val qnnBackendPath = findOrExtractQnnBackend(context, moduleApkPath)
        if (qnnBackendPath != null) {
            try {
                Log.i(TAG, "Trying Qualcomm QNN EP (Hexagon HTP) for ${modelInfo.id} (backend: $qnnBackendPath)...")
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // INFO level floods the per-process log quota (thousands of QNN
                    // validation rows) and drops the lines worth reading, so keep WARNING.
                    setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
                    setLoggerId("AppleSing_ORT")
                    // ORT happily creates a session even when the QNN backend failed to
                    // initialize (e.g. missing vendor libs): every node then silently runs
                    // on the CPU EP, which produced runs mislabeled as "QNN (Hexagon HTP
                    // NPU)". This option makes session creation FAIL unless a non-CPU EP
                    // accepts the whole graph, so the provider name below is always honest.
                    // Debug bit 16 lifts it for experiments with partial offload.
                    if ((qnnDebug and 16) == 0) {
                        addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    }
                    // MDX-Net ONNX exports declare a dynamic "batch_size" dimension.
                    // HTP needs static shapes: without an override the QNN EP leaves
                    // shape-dependent nodes to the default CPU EP, and session
                    // creation then fails because CPU fallback is disabled (see
                    // above) - which silently degraded every run to CPU. The engine
                    // always runs batch 1.
                    try {
                        setSymbolicDimensionValue("batch_size", 1)
                        Log.i(TAG, "Pinned dynamic dimension batch_size=1 for QNN (static shapes for HTP)")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not pin batch_size for QNN: ${t.message}")
                    }
                    val qnnOptions = mutableMapOf(
                        "backend_path" to qnnBackendPath,
                        "htp_performance_mode" to "burst",
                        "enable_htp_fp16_precision" to "1"
                    )
                    if ((qnnDebug and 1) != 0) {
                        qnnOptions["profiling_level"] = "detailed"
                        qnnOptions["profiling_file_path"] =
                            File(File(qnnBackendPath).parentFile, "QnnProfile.csv").absolutePath
                    }
                    if ((qnnDebug and 4) != 0) qnnOptions["rpc_control_latency"] = "100"
                    if ((qnnDebug and 8) != 0) qnnOptions["htp_graph_finalization_optimization_mode"] = "3"
                    if ((qnnDebug and 32) != 0) qnnOptions["vtcm_mb"] = "8"
                    if ((qnnDebug and 64) != 0) qnnOptions["htp_performance_mode"] = "sustained_high_performance"
                    addQnn(qnnOptions)
                }
                val session = try {
                    env.createSession(modelPath, opts)
                } finally {
                    opts.close()
                }
                if ((qnnDebug and 16) != 0) {
                    activeProviderShort = "QNN+CPU (mixed, debug)"
                    activeProviderName = "${modelInfo.precision} via QNN (partial offload, CPU fallback allowed)"
                    Log.i(TAG, "QNN session created for ${modelInfo.id} (CPU fallback allowed - partial offload, debug mode)")
                } else {
                    activeProviderShort = "QNN (Hexagon HTP NPU)"
                    activeProviderName = "${modelInfo.precision} via QNN (Hexagon HTP NPU)"
                    Log.i(TAG, "QNN accepted the full ${modelInfo.id} graph (no CPU fallback) - running on Hexagon HTP")
                }
                return session
            } catch (e: Throwable) {
                Log.w(TAG, "QNN unavailable for ${modelInfo.id} (${e.javaClass.simpleName}: ${e.message}) - falling back to CPU EP")
                writeQnnErrorReport(context, modelInfo.id, e)
            }
        } else {
            Log.i(TAG, "QNN backend library not found - NPU not available for ${modelInfo.id}")
        }

        // 2. Fallback to CPU with multi-threading
        return createCpuSession(env, modelPath, threads, modelInfo)
    }

    private fun createCpuSession(
        env: OrtEnvironment,
        modelPath: String,
        threads: Int,
        modelInfo: ModelLoader.ModelInfo
    ): OrtSession {
        val useXnnpack = (modelInfo.id == "onnx_fp32" || modelInfo.id == "onnx_voc_ft_fp32" || modelInfo.id == "uvr_mdxnet_fp32" || modelInfo.id == "uvr_mdxnet_mixed")
        if (useXnnpack) {
            try {
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                    setIntraOpNumThreads(threads)
                    setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
                    setLoggerId("AppleSing_ORT")
                    try {
                        addXnnpack(mapOf("num_threads" to threads.toString()))
                        Log.i(TAG, "Configured XNNPACK Execution Provider for ${modelInfo.id} ($threads threads)")
                    } catch (e: Throwable) {
                        Log.w(TAG, "XNNPACK unavailable: ${e.message}")
                    }
                }
                val session = try {
                    env.createSession(modelPath, opts)
                } finally {
                    opts.close()
                }
                activeProviderShort = "ONNX Runtime XNNPACK, $threads threads"
                activeProviderName = "${modelInfo.precision} via ONNX Runtime XNNPACK (CPU, $threads threads)"
                return session
            } catch (e: Throwable) {
                Log.w(TAG, "Failed XNNPACK session: ${e.message}, falling back to Default CPU")
            }
        }

        val defaultOpts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
            setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
            setLoggerId("AppleSing_ORT")
        }
        val session = try {
            env.createSession(modelPath, defaultOpts)
        } finally {
            defaultOpts.close()
        }
        activeProviderShort = "ONNX Runtime CPU, $threads threads"
        activeProviderName = "${modelInfo.precision} via ONNX Runtime CPU ($threads threads)"
        return session
    }
}
