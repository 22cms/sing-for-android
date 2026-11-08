package org.francasio.sing.util

import android.content.Context
import android.util.Log
import org.francasio.sing.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

object NativeLibDownloader {
    private const val TAG = "AppleSing_NativeLibDownloader"

    private const val ONNX_RUNTIME_VERSION = "1.29.0"
    private const val ONNX_RUNTIME_CPU_VERSION = "1.30.0"
    private const val QNN_RUNTIME_VERSION = "2.51.0"
    private const val MAVEN_BASE = "https://repo1.maven.org/maven2"
    private const val ORT_CACHE_ID_FILE = "ort-runtime-id"

    internal fun runtimeVariant(context: Context): OnnxRuntimeVariant {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        if (!abi.startsWith("arm64") || !DeviceHelper.isQualcommWithHexagonHtp) {
            return OnnxRuntimeVariant.STANDARD
        }
        if (requiredSkeleton(context) != null) {
            return OnnxRuntimeVariant.QNN
        }
        val mode = org.francasio.sing.provider.SingSettings.getQnnUnmappedSocMode(context)
        return if (mode == org.francasio.sing.provider.SingSettings.QNN_UNMAPPED_ALL_SKELETONS) {
            OnnxRuntimeVariant.QNN
        } else {
            OnnxRuntimeVariant.STANDARD
        }
    }

    private fun runtimeCacheId(variant: OnnxRuntimeVariant): String = when (variant) {
        OnnxRuntimeVariant.QNN -> "qnn-$ONNX_RUNTIME_VERSION"
        OnnxRuntimeVariant.STANDARD -> "standard-$ONNX_RUNTIME_CPU_VERSION"
    }

    private val ONNX_AAR_URL =
        "$MAVEN_BASE/com/microsoft/onnxruntime/onnxruntime-android-qnn/$ONNX_RUNTIME_VERSION/onnxruntime-android-qnn-$ONNX_RUNTIME_VERSION.aar"
    private val ONNX_CPU_AAR_URL =
        "$MAVEN_BASE/com/microsoft/onnxruntime/onnxruntime-android/$ONNX_RUNTIME_CPU_VERSION/onnxruntime-android-$ONNX_RUNTIME_CPU_VERSION.aar"
    private val QNN_AAR_URL =
        "$MAVEN_BASE/com/qualcomm/qti/qnn-runtime/$QNN_RUNTIME_VERSION/qnn-runtime-$QNN_RUNTIME_VERSION.aar"

    @Volatile
    var currentLabel: String = ""
        private set

    @Volatile
    var currentProgress: Float = 0f
        private set

    fun getLibsDir(context: Context): File {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val dirName = if (abi.startsWith("arm64")) "arm64-v8a" else abi
        val dir = File(context.cacheDir, "ort_libs_$dirName")
        dir.mkdirs()
        return dir
    }

    fun isOnnxRuntimeInstalled(context: Context): Boolean {
        val dir = getLibsDir(context)
        val ort = File(dir, "libonnxruntime.so")
        val jni = File(dir, "libonnxruntime4j_jni.so")
        if (!ort.exists() || !jni.exists()) return false
        // ABI alone cannot distinguish the standard and QNN builds. Legacy
        // unmarked caches are downloaded once more to establish their identity.
        val cachedId = try {
            File(dir, ORT_CACHE_ID_FILE).readText().trim()
        } catch (_: Throwable) {
            return false
        }
        if (cachedId != runtimeCacheId(runtimeVariant(context))) return false
        // Invalidate stale caches (e.g. arm64 libs left on an x86_64 device).
        val wantMachine =
            if ((android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").startsWith("arm64")) 0x00B7 else 0x003E
        try {
            val buf = ByteArray(20)
            val bytesRead = ort.inputStream().use { it.read(buf) }
            if (bytesRead >= 20) {
                val hdr = buf
                val eMachine = (hdr[19].toInt() and 0xff) shl 8 or (hdr[18].toInt() and 0xff)
                if (eMachine != 0x0000 && eMachine != wantMachine) {
                    dir.listFiles()?.forEach { it.delete() }
                    return false
                }
            }
        } catch (_: Throwable) {
        }
        return true
    }

    fun isQnnRuntimeInstalled(context: Context): Boolean {
        val dir = getLibsDir(context)
        return File(dir, "libQnnHtp.so").exists()
    }

    fun mapSocToSkeleton(soc: String): String? {
        val s = soc.uppercase()
        return when {
            // V68: Snapdragon 888 / 888+, 778G, 780G, 8cx Gen 3, and early Auto/IoT
            s.startsWith("SM8350") || s.startsWith("SM7325") || s.startsWith("SM7350") ||
                    s.startsWith("SM7315") || s.startsWith("SM8295") || s.startsWith("QCM6490") ||
                    s.startsWith("QCS6490") || s.startsWith("SA8540") || s.startsWith("SC8280X") -> "V68"

            // V69: Snapdragon 8 Gen 1, 8+ Gen 1, 7 Gen 1, 7+ Gen 2, XR2 Gen 2
            s.startsWith("SM8450") || s.startsWith("SM8475") || s.startsWith("SM7475") ||
                    s.startsWith("SM7450") || s.startsWith("SM7425") || s.startsWith("SXR2230P") ||
                    s.startsWith("SXR2250P") -> "V69"

            // V73: Snapdragon 8 Gen 2, 8s Gen 3, 7+ Gen 3, 7 Gen 3, 7s Gen 2/3, 6 Gen 1, X Elite (PC)
            s.startsWith("SM8550") || s.startsWith("SM8635") ||
                    s.startsWith("SM7675") || s.startsWith("SM7635") || s.startsWith("SM7550") ||
                    s.startsWith("SM7435") || s.startsWith("SM6450") || s.startsWith("SC8380XP") ||
                    s.startsWith("SAR2130P") || s.startsWith("SA8255") || s.startsWith("QCS9100") -> "V73"

            // V75: Snapdragon 8 Gen 3 flagship, 8s Gen 4 & IoT equivalents
            s.startsWith("SM8650") || s.startsWith("SM8735") || s.startsWith("QCS8625") ||
                    s.startsWith("QCS8300") || s.startsWith("QCS8275") -> "V75"

            // V79: Snapdragon 8 Elite / 8 Gen 4
            s.startsWith("SM8750") || s.startsWith("SXR2330P") -> "V79"

            // V81: Snapdragon 8 Elite Gen 5, 8s Elite, Snapdragon X2 Elite, Wear W6
            s.startsWith("SM8850") || s.startsWith("SM8845") || s.startsWith("SC8480XP") ||
                    s.startsWith("SW6100") || s.startsWith("SA8797") || s.startsWith("SAR2230P") -> "V81"

            else -> null
        }
    }

    val KNOWN_SKELETONS = setOf("V68", "V69", "V73", "V75", "V79", "V81")

    fun isUnmappedQualcommHtp(context: Context): Boolean {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        if (!abi.startsWith("arm64")) return false
        if (org.francasio.sing.provider.SingSettings.getForceNoQnnSocState(context)) return false
        if (org.francasio.sing.provider.SingSettings.getForceUnknownQcomSocState(context)) return true
        return DeviceHelper.isQualcommWithHexagonHtp && requiredSkeleton(context) == null
    }

    fun requiredSkeleton(context: Context): String? {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        if (!abi.startsWith("arm64")) return null

        // Debug flag overrides
        if (org.francasio.sing.provider.SingSettings.getForceNoQnnSocState(context)) {
            Log.d(TAG, "FORCE_NO_HTP_SOC_STATE active -> returning null for skeleton")
            return null
        }
        if (org.francasio.sing.provider.SingSettings.getForceUnknownQcomSocState(context)) {
            Log.d(TAG, "FORCE_UNKNOWN_QCOM_SOC_STATE active -> returning null for skeleton")
            return null
        }

        // 1. First priority: Direct FastRPC query to CDSP for HEXAGON_CPU_ARCH
        try {
            val rpcArch = NativeEnv.detectHexagonArch()
            if (!rpcArch.isNullOrBlank()) {
                if (rpcArch in KNOWN_SKELETONS) {
                    Log.i(TAG, "FastRPC detected known Hexagon architecture: $rpcArch")
                    return rpcArch
                } else {
                    Log.w(TAG, "FastRPC returned Hexagon architecture $rpcArch not present in known map")
                    return null // Triggers unknown Qualcomm SoC pop-up
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "FastRPC architecture detection failed: ${t.message}")
        }

        // 2. Fallback: Fixed SoC string mapping table
        for (candidate in DeviceHelper.getSocCandidates()) {
            val skel = mapSocToSkeleton(candidate)
            if (skel != null) {
                Log.i(TAG, "Mapped SoC candidate '$candidate' to skeleton $skel (fallback)")
                return skel
            }
        }

        try {
            java.io.File("/proc/cpuinfo").readText().let { txt ->
                val m = Regex("SM\\d{4}").find(txt)
                if (m != null) {
                    val skel = mapSocToSkeleton(m.value)
                    if (skel != null) {
                        Log.i(TAG, "Mapped /proc/cpuinfo '${m.value}' to skeleton $skel (fallback)")
                        return skel
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return null
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val u = URL(url)
            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AppleMusicSing-Android")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $url")
                return false
            }
            val total = conn.contentLengthLong
            if (dest.exists()) dest.delete()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastUpdate = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200 || (total > 0 && downloaded == total)) {
                            lastUpdate = now
                            val p = if (total > 0) downloaded.toFloat() / total else 0f
                            onProgress(p)
                        }
                    }
                    output.flush()
                }
            }
            return dest.exists() && dest.length() > 0
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed: ${t.message}", t)
            dest.delete()
            return false
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractEntries(
        aarFile: File,
        destDir: File,
        wanted: Set<String>,
        extractAllSkeletons: Boolean = false
    ): Set<String> {
        val extracted = mutableSetOf<String>()
        ZipFile(aarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                val isSkelOrStub = extractAllSkeletons &&
                        name.startsWith("libQnnHtpV") &&
                        (name.endsWith("Stub.so") || name.endsWith("Skel.so"))
                if (name !in wanted && !isSkelOrStub) continue
                val abi = e.name.substringAfter("jni/").substringBefore('/')
                val wantAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val wantAbiName =
                    if (wantAbi.startsWith("arm64")) "arm64-v8a" else if (wantAbi.startsWith("x86_64")) "x86_64" else wantAbi
                if (abi != wantAbiName) continue
                val outFile = File(destDir, name)
                zip.getInputStream(e).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                outFile.setReadable(true, false)
                outFile.setExecutable(true, false)
                Log.i(TAG, "Extracted $name")
                extracted.add(name)
            }
        }
        return extracted
    }

    private fun safeGetString(context: Context, resId: Int, fallback: String): String {
        return try {
            context.getString(resId)
        } catch (_: Throwable) {
            fallback
        }
    }

    fun downloadOnnxRuntime(context: Context, onProgress: (String, Float) -> Unit): Boolean {
        currentLabel = safeGetString(context, R.string.downloading_onnx_runtime, "Downloading ONNX Runtime...")
        val dir = getLibsDir(context)
        val variant = runtimeVariant(context)
        val (url, tmpName) = if (variant == OnnxRuntimeVariant.QNN) {
            ONNX_AAR_URL to "onnxruntime-android-qnn.aar.tmp"
        } else {
            ONNX_CPU_AAR_URL to "onnxruntime-android.aar.tmp"
        }
        val tmpAar = File(context.cacheDir, tmpName)
        val ok = downloadFile(url, tmpAar) { p ->
            currentProgress = p * 0.7f
            onProgress(currentLabel, currentProgress)
        }
        if (!ok) {
            currentLabel = ""
            return false
        }
        currentLabel = safeGetString(context, R.string.extracting_onnx_runtime, "Extracting ONNX Runtime...")
        currentProgress = 0.75f
        onProgress(currentLabel, currentProgress)
        val wanted = setOf("libonnxruntime.so", "libonnxruntime4j_jni.so")
        File(dir, ORT_CACHE_ID_FILE).delete()
        val extracted = extractEntries(tmpAar, dir, wanted)
        tmpAar.delete()
        if (extracted != wanted || wanted.any { File(dir, it).length() == 0L }) {
            Log.w(TAG, "ONNX Runtime archive did not contain all required native libraries")
            currentLabel = ""
            return false
        }
        if (variant == OnnxRuntimeVariant.STANDARD) {
            dir.listFiles { _, name -> name.startsWith("libQnn") }?.forEach { it.delete() }
        }
        File(dir, ORT_CACHE_ID_FILE).writeText(runtimeCacheId(variant))
        currentProgress = 1f
        onProgress(currentLabel, currentProgress)
        currentLabel = ""
        return File(dir, "libonnxruntime.so").exists()
    }

    fun downloadQnnRuntime(context: Context, onProgress: (String, Float) -> Unit): Boolean {
        val skeleton = requiredSkeleton(context)
        val isAllSkeletons = skeleton == null &&
                org.francasio.sing.provider.SingSettings.getQnnUnmappedSocMode(context) ==
                org.francasio.sing.provider.SingSettings.QNN_UNMAPPED_ALL_SKELETONS
        if (skeleton == null && !isAllSkeletons) return false

        currentLabel = safeGetString(context, R.string.downloading_qnn_accelerator, "Downloading QNN Accelerator...")
        val dir = getLibsDir(context)
        val tmpAar = File(context.cacheDir, "qnn-runtime.aar.tmp")
        val ok = downloadFile(QNN_AAR_URL, tmpAar) { p ->
            currentProgress = p * 0.6f
            onProgress(currentLabel, currentProgress)
        }
        if (!ok) {
            currentLabel = ""
            return false
        }
        currentLabel = safeGetString(context, R.string.extracting_qnn_accelerator, "Extracting QNN Accelerator...")
        currentProgress = 0.65f
        onProgress(currentLabel, currentProgress)

        val wanted = mutableSetOf(
            "libc++.so", "ld-android.so", "libdl_android.so", "libbase.so",
            "libdmabufheap.so", "libcutils.so", "libvndksupport.so", "libutils.so",
            "libhardware.so", "libhidlbase.so", "libvmmem.so",
            "vendor.qti.hardware.dsp@1.0.so", "libcdsprpc.so",
            "libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so"
        )
        if (skeleton != null) {
            wanted.add("libQnnHtp${skeleton}Stub.so")
            wanted.add("libQnnHtp${skeleton}Skel.so")
        }
        extractEntries(tmpAar, dir, wanted, extractAllSkeletons = isAllSkeletons)
        // libdl_android.so fallback: if the real one failed to extract, the stub covers it
        if (!File(dir, "libdl_android.so").exists() && File(dir, "libdl_android_stub.so").exists()) {
            File(dir, "libdl_android_stub.so").copyTo(File(dir, "libdl_android.so"), overwrite = true)
        }
        tmpAar.delete()
        currentProgress = 1f
        onProgress(currentLabel, currentProgress)
        currentLabel = ""
        return File(dir, "libQnnHtp.so").exists()
    }

    fun ensureOnnxRuntime(context: Context, onProgress: (String, Float) -> Unit): Boolean {
        if (!isOnnxRuntimeInstalled(context)) {
            if (!downloadOnnxRuntime(context, onProgress)) return false
        }
        if (runtimeVariant(context) == OnnxRuntimeVariant.QNN && !isQnnRuntimeInstalled(context)) {
            if (!downloadQnnRuntime(context, onProgress)) return false
        }
        return true
    }
}
