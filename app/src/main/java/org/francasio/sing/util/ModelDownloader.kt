package org.francasio.sing.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.francasio.sing.provider.SingSettings

object ModelDownloader {
    private const val TAG = "AppleSing_Downloader"

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    val downloadStates = mutableStateMapOf<String, DownloadState>()

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getDownloadedModelFile(context: Context, fileName: String): File? {
        // Only copies this process can actually READ count as "downloaded". The
        // legacy /sdcard/Download/sing_models files can be owned by an older
        // install (different uid): they are stat-able (exists()/length() succeed
        // via FUSE) but open() fails with EACCES. Counting those made deleted
        // models reappear as "installed" after every restart, and gave the
        // module a false "model available" signal through the provider.
        val candidates = listOf(
            File("/sdcard/Download/sing_models", fileName),
            File("/storage/emulated/0/Download/sing_models", fileName),
            File(getModelsDir(context), fileName)
        )
        for (candidate in candidates) {
            if (candidate.exists() && candidate.canRead() && candidate.length() > 1024 * 1024) {
                return candidate
            }
        }
        return null
    }

    fun isModelDownloaded(context: Context, model: ModelLoader.ModelInfo): Boolean {
        if (getDownloadedModelFile(context, model.fileName) != null) {
            return true
        }
        // Fallback: check host/module assets for embedded models
        return try {
            context.assets.open(model.fileName).use { true }
        } catch (_: Throwable) {
            try {
                context.assets.open("assets/${model.fileName}").use { true }
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun getDownloadState(context: Context, model: ModelLoader.ModelInfo): DownloadState {
        val state = downloadStates[model.id]
        if (state != null) return state
        return if (isModelDownloaded(context, model)) {
            DownloadState.Completed
        } else {
            DownloadState.Idle
        }
    }

    fun deleteModel(context: Context, model: ModelLoader.ModelInfo): Boolean {
        val modelsDir = getModelsDir(context)
        val candidates = listOf(
            File(modelsDir, model.fileName),
            File(modelsDir, "${model.fileName}.tmp"),
            File(context.cacheDir, model.fileName),
            File(context.cacheDir, "${model.fileName}.tmp"),
            File("/sdcard/Download/sing_models", model.fileName),
            File("/storage/emulated/0/Download/sing_models", model.fileName)
        )
        var deletedAny = false
        for (f in candidates) {
            if (!f.exists()) continue
            val ok = f.delete()
            if (ok) deletedAny = true
            Log.i(TAG, "Delete ${f.absolutePath}: $ok")
        }
        // If a copy is still readable somewhere, keep reporting it as installed
        // instead of lying until the next process restart.
        val stillAvailable = getDownloadedModelFile(context, model.fileName) != null
        downloadStates[model.id] = if (stillAvailable) DownloadState.Completed else DownloadState.Idle
        Log.i(TAG, "Deleted model ${model.fileName}: deleted=$deletedAny, stillAvailable=$stillAvailable")
        return !stillAvailable
    }

    fun startDownload(
        context: Context,
        model: ModelLoader.ModelInfo,
        onComplete: (() -> Unit)? = null
    ) {
        val currentState = downloadStates[model.id]
        if (currentState is DownloadState.Downloading) {
            Log.w(TAG, "Download already in progress for ${model.id}")
            return
        }

        val appContext = context.applicationContext ?: context
        downloadStates[model.id] = DownloadState.Downloading(0f, 0L, 0L)
        val candidateUrls = ModelLoader.getModelDownloadUrls(appContext, model.fileName)
        Log.i(TAG, "Starting download for ${model.id}, candidate URLs: $candidateUrls")

        executor.execute {
            val modelsDir = getModelsDir(appContext)
            val finalFile = File(modelsDir, model.fileName)
            val tmpFile = File(modelsDir, "${model.fileName}.tmp")

            var connection: HttpURLConnection? = null
            var lastError: Throwable? = null

            for (initialUrl in candidateUrls) {
                var currentUrl = initialUrl
                var redirects = 0
                val maxRedirects = 6
                try {
                    while (redirects < maxRedirects) {
                        val url = URL(currentUrl)
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout = 30000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", "AppleMusicSing-Android")
                        }

                        val code = conn.responseCode
                        if (code in 300..399) {
                            val newLocation = conn.getHeaderField("Location")
                                ?: throw IllegalStateException("Redirected with no Location header")
                            currentUrl = if (newLocation.startsWith("http://") || newLocation.startsWith("https://")) {
                                newLocation
                            } else {
                                URL(URL(currentUrl), newLocation).toString()
                            }
                            conn.disconnect()
                            redirects++
                            Log.i(TAG, "Following redirect ($code) -> $currentUrl")
                        } else if (code in 200..299) {
                            connection = conn
                            break
                        } else {
                            conn.disconnect()
                            throw IllegalStateException("HTTP error $code: ${conn.responseMessage}")
                        }
                    }
                    if (connection != null) {
                        Log.i(TAG, "Successfully connected to $currentUrl for ${model.id}")
                        break
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed connection to $initialUrl for ${model.id}: ${t.message}")
                    lastError = t
                    connection?.disconnect()
                    connection = null
                }
            }

            try {
                val conn = connection ?: throw IllegalStateException(lastError?.message ?: "Could not connect to any download server")
                val totalBytes = conn.contentLengthLong

                if (tmpFile.exists()) {
                    tmpFile.delete()
                }

                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        var lastProgressUpdate = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 100 || downloadedBytes == totalBytes) {
                                lastProgressUpdate = now
                                val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                                mainHandler.post {
                                    downloadStates[model.id] = DownloadState.Downloading(progress, downloadedBytes, totalBytes)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (finalFile.exists()) {
                    finalFile.delete()
                }

                if (!tmpFile.renameTo(finalFile)) {
                    throw IllegalStateException("Failed to move temporary file to final destination")
                }

                try {
                    appContext.filesDir.setReadable(true, false)
                    appContext.filesDir.setExecutable(true, false)
                    modelsDir.setReadable(true, false)
                    modelsDir.setExecutable(true, false)
                    finalFile.setReadable(true, false)
                } catch (_: Throwable) {}
                Log.i(TAG, "Download completed successfully: ${finalFile.absolutePath} (${finalFile.length()} bytes)")

                mainHandler.post {
                    downloadStates[model.id] = DownloadState.Completed
                    onComplete?.invoke()
                }

            } catch (t: Throwable) {
                Log.e(TAG, "Download failed for ${model.id}: ${t.message}", t)
                tmpFile.delete()
                mainHandler.post {
                    downloadStates[model.id] = DownloadState.Error(t.message ?: "Download failed")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }
}

