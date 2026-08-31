package com.example.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelStatus(
    val engine: SeparationEngine,
    val modelName: String,
    val isInstalled: Boolean,
    val fileSizeBytes: Long = 0L,
    val downloadProgress: Float = 0f,
    val isDownloading: Boolean = false,
    val downloadError: String? = null
)

/**
 * Manages On-Device Neural Network Models for offline Spleeter and UVR engines.
 * Supports:
 * 1. Checking model existence in internal storage.
 * 2. In-app direct download from verified neural model repositories with real-time progress.
 * 3. Importing custom .tflite model files directly from user phone storage.
 */
class NeuralModelManager(private val context: Context) {

    companion object {
        private const val TAG = "NeuralModelManager"

        const val SPLEETER_MODEL_FILENAME = "spleeter_2stem.tflite"
        const val UVR_MODEL_FILENAME = "uvr_mdxnet.tflite"

        // Reliable official/community hosted quantized mobile models
        const val SPLEETER_MODEL_URL = "https://huggingface.co/antimatter15/spleeter-tflite/resolve/main/spleeter_2stem.tflite"
        const val UVR_MODEL_URL = "https://huggingface.co/seanghay/uvr-mdx-net-tflite/resolve/main/uvr_mdxnet_vocals.tflite"
    }

    private val modelsDir: File
        get() {
            val dir = File(context.filesDir, "neural_models")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val _spleeterStatus = MutableStateFlow(getModelStatus(SeparationEngine.SPLEETER_FAST))
    val spleeterStatus: StateFlow<ModelStatus> = _spleeterStatus.asStateFlow()

    private val _uvrStatus = MutableStateFlow(getModelStatus(SeparationEngine.UVR_MDXNET))
    val uvrStatus: StateFlow<ModelStatus> = _uvrStatus.asStateFlow()

    init {
        refreshModelStatuses()
    }

    fun getModelFile(engine: SeparationEngine): File {
        val fileName = when (engine) {
            SeparationEngine.SPLEETER_FAST -> SPLEETER_MODEL_FILENAME
            SeparationEngine.UVR_MDXNET -> UVR_MODEL_FILENAME
        }
        return File(modelsDir, fileName)
    }

    fun isModelReady(engine: SeparationEngine): Boolean {
        val file = getModelFile(engine)
        // A valid TFLite model is at least 1MB in size
        return file.exists() && file.length() > 1024 * 1024
    }

    fun refreshModelStatuses() {
        _spleeterStatus.value = getModelStatus(SeparationEngine.SPLEETER_FAST)
        _uvrStatus.value = getModelStatus(SeparationEngine.UVR_MDXNET)
    }

    private fun getModelStatus(engine: SeparationEngine): ModelStatus {
        val file = getModelFile(engine)
        val isInstalled = file.exists() && file.length() > 1024 * 1024
        val name = when (engine) {
            SeparationEngine.SPLEETER_FAST -> "Spleeter 2-Stem Neural (TFLite)"
            SeparationEngine.UVR_MDXNET -> "UVR MDX-Net Studio (TFLite)"
        }
        return ModelStatus(
            engine = engine,
            modelName = name,
            isInstalled = isInstalled,
            fileSizeBytes = if (file.exists()) file.length() else 0L
        )
    }

    /**
     * Downloads the official quantized neural model weights to device storage.
     */
    suspend fun downloadModel(engine: SeparationEngine): Boolean = withContext(Dispatchers.IO) {
        val urlString = when (engine) {
            SeparationEngine.SPLEETER_FAST -> SPLEETER_MODEL_URL
            SeparationEngine.UVR_MDXNET -> UVR_MODEL_URL
        }
        val targetFile = getModelFile(engine)
        val tempFile = File(modelsDir, "${targetFile.name}.download")

        val stateFlow = when (engine) {
            SeparationEngine.SPLEETER_FAST -> _spleeterStatus
            SeparationEngine.UVR_MDXNET -> _uvrStatus
        }

        try {
            stateFlow.value = stateFlow.value.copy(
                isDownloading = true,
                downloadProgress = 0.05f,
                downloadError = null
            )

            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw Exception("HTTP Error: $responseCode - ${connection.responseMessage}")
            }

            val totalLength = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (totalLength > 0) {
                            val progress = (downloadedBytes.toFloat() / totalLength).coerceIn(0f, 1f)
                            stateFlow.value = stateFlow.value.copy(downloadProgress = progress)
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.exists() && tempFile.length() > 500 * 1024) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)

                stateFlow.value = ModelStatus(
                    engine = engine,
                    modelName = stateFlow.value.modelName,
                    isInstalled = true,
                    fileSizeBytes = targetFile.length(),
                    isDownloading = false,
                    downloadProgress = 1f
                )
                Log.i(TAG, "Successfully downloaded neural model for $engine: ${targetFile.length()} bytes")
                true
            } else {
                tempFile.delete()
                throw Exception("الملف المحمل غير مكتمل أو تالف")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model for $engine", e)
            tempFile.delete()
            stateFlow.value = stateFlow.value.copy(
                isDownloading = false,
                downloadError = e.localizedMessage ?: "فشل التحميل، يرجى المحاولة لاحقاً"
            )
            false
        }
    }

    /**
     * Imports a user-selected .tflite model file directly into app storage.
     */
    suspend fun importModelFromUri(engine: SeparationEngine, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(engine)
        val tempFile = File(modelsDir, "${targetFile.name}.temp")

        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) return@withContext false

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            if (tempFile.exists() && tempFile.length() > 1024 * 1024) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                refreshModelStatuses()
                Log.i(TAG, "Imported model successfully: ${targetFile.length()} bytes")
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error importing model from URI", e)
            tempFile.delete()
            false
        }
    }

    /**
     * Deletes model file to free storage.
     */
    fun deleteModel(engine: SeparationEngine): Boolean {
        val file = getModelFile(engine)
        val deleted = file.delete()
        refreshModelStatuses()
        return deleted
    }
}
