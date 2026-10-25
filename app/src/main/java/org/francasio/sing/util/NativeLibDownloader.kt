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
        // Invalidate stale caches (e.g. arm64 libs left on an x86_64 device).
        val wantMachine = if ((android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").startsWith("arm64")) 0x00B7 else 0x003E
        try {
            val hdr = ort.inputStream().use { it.readNBytes(20) }
            if (hdr.size >= 20) {
                val eMachine = (hdr[19].toInt() and 0xff) shl 8 or (hdr[18].toInt() and 0xff)
                if (eMachine != 0x0000 && eMachine != wantMachine) {
                    dir.listFiles()?.forEach { it.delete() }
                    return false
                }
            }
        } catch (_: Throwable) {}
        return true
    }

    fun isQnnRuntimeInstalled(context: Context): Boolean {
        val dir = getLibsDir(context)
        return File(dir, "libQnnHtp.so").exists()
    }

    fun requiredSkeleton(context: Context): String? {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        if (!abi.startsWith("arm64")) return null
        var soc = android.os.Build.SOC_MODEL.uppercase()
        if (soc.isBlank() || soc == "UNKNOWN") {
            try {
                java.io.File("/proc/cpuinfo").readText().let { txt ->
                    val m = Regex("SM\\d{4}").find(txt)
                    if (m != null) soc = m.value.uppercase()
                }
            } catch (_: Throwable) {}
        }
        if (soc.isBlank()) {
            soc = android.os.Build.HARDWARE.uppercase()
        }
        return when {
            soc.startsWith("SM8350") || soc.startsWith("SM8295") || soc.startsWith("QCM6490") || soc.startsWith("QCS6490") || soc.startsWith("SA8540") -> "V68"
            soc.startsWith("SM8450") || soc.startsWith("SM8475") || soc.startsWith("SXR2230P") -> "V69"
            soc.startsWith("SM8550") || soc.startsWith("SM8635") || soc.startsWith("SM7675") || soc.startsWith("SM8650") == false && soc.startsWith("SM86") -> "V73"
            soc.startsWith("SM8650") -> "V75"
            soc.startsWith("SM8750") || soc.startsWith("SXR2330P") -> "V79"
            soc.startsWith("SM8845") || soc.startsWith("SM8850") || soc.startsWith("SC8480XP") || soc.startsWith("SW6100") || soc.startsWith("SA8797") || soc.startsWith("SAR2230P") -> "V81"
            else -> null
        }
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

    private fun extractEntries(aarFile: File, destDir: File, wanted: Set<String>) {
        ZipFile(aarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (name !in wanted) continue
                val abi = e.name.substringAfter("jni/").substringBefore('/')
                val wantAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val wantAbiName = if (wantAbi.startsWith("arm64")) "arm64-v8a" else if (wantAbi.startsWith("x86_64")) "x86_64" else wantAbi
                if (abi != wantAbiName) continue
                val outFile = File(destDir, name)
                zip.getInputStream(e).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                outFile.setReadable(true, false)
                outFile.setExecutable(true, false)
                Log.i(TAG, "Extracted $name")
            }
        }
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
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val (url, tmpName) = if (abi.startsWith("arm64")) {
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
        extractEntries(tmpAar, dir, setOf("libonnxruntime.so", "libonnxruntime4j_jni.so"))
        tmpAar.delete()
        currentProgress = 1f
        onProgress(currentLabel, currentProgress)
        currentLabel = ""
        return File(dir, "libonnxruntime.so").exists()
    }

    fun downloadQnnRuntime(context: Context, onProgress: (String, Float) -> Unit): Boolean {
        val skeleton = requiredSkeleton(context) ?: return false
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
        wanted.add("libQnnHtp${skeleton}Stub.so")
        wanted.add("libQnnHtp${skeleton}Skel.so")
        extractEntries(tmpAar, dir, wanted)
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
        if (DeviceHelper.isQualcommWithHexagonHtp && !isQnnRuntimeInstalled(context)) {
            if (requiredSkeleton(context) != null) {
                if (!downloadQnnRuntime(context, onProgress)) return false
            }
        }
        return true
    }
}
