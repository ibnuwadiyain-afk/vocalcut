package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioExtractor
import com.example.audio.AudioSeparator
import com.example.audio.SeparationResult
import com.example.audio.WavAudioUtil
import com.example.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class VideoPlayerUiState(
    val currentVideoUri: Uri? = null,
    val videoTitle: String = "",
    val isVideoLoaded: Boolean = false,
    val isVocalOnly: Boolean = false,
    val isProcessing: Boolean = false,
    val processingStage: String = "",
    val processingProgress: Float = 0f,
    val isSeparated: Boolean = false,
    val vocalVolume: Float = 1.0f,
    val bgmVolume: Float = 0.0f,
    val playbackSpeed: Float = 1.0f,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val isFullscreen: Boolean = false,
    val isAudioVisualizerActive: Boolean = true
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val context: Context get() = getApplication<Application>().applicationContext

    val playerController = PlayerController(context, viewModelScope)
    private val audioExtractor = AudioExtractor(context)
    private val audioSeparator = AudioSeparator(context)

    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    private var extractedWavFile: File? = null
    private var vocalWavFile: File? = null
    private var accompanimentWavFile: File? = null

    private var processingJob: Job? = null

    init {
        // Clean up any stale temp files from previous sessions
        cleanupTempFiles()
    }

    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                val displayName = queryFileName(uri) ?: "فيديو محدد"
                cleanupCurrentMediaFiles()

                _uiState.update {
                    it.copy(
                        currentVideoUri = uri,
                        videoTitle = displayName,
                        isVideoLoaded = true,
                        isVocalOnly = false,
                        isProcessing = false,
                        isSeparated = false,
                        errorMessage = null,
                        infoMessage = "تم تحميل الفيديو بنجاح: $displayName"
                    )
                }

                playerController.loadVideo(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading video: ${e.message}", e)
                _uiState.update {
                    it.copy(errorMessage = "تعذر تحميل الفيديو: ${e.localizedMessage}")
                }
            }
        }
    }

    fun loadDemoSample() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update {
                    it.copy(
                        isProcessing = true,
                        processingStage = "جارٍ إنشاء المقطع التوضيحي (Demo)...",
                        processingProgress = 0.1f,
                        errorMessage = null
                    )
                }

                val tempDir = File(context.cacheDir, "vocal_keep_demo").apply { mkdirs() }
                val demoAudioWav = File(tempDir, "demo_mixed_audio.wav")
                WavAudioUtil.createSampleDemoAudio(demoAudioWav, durationSeconds = 16)

                val demoUri = Uri.fromFile(demoAudioWav)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            currentVideoUri = demoUri,
                            videoTitle = "مقطع توضيحي تجريبي (Demo Audio/Video)",
                            isVideoLoaded = true,
                            isVocalOnly = false,
                            isProcessing = false,
                            isSeparated = false,
                            infoMessage = "تم تحميل المقطع التجريبي. اضغط على 'إخفاء الموسيقى' لفصل الصوت!"
                        )
                    }
                    playerController.loadVideo(demoUri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating demo: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "تعذر إنشاء المقطع التجريبي: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun toggleVocalOnly(enableVocalOnly: Boolean) {
        val currentState = _uiState.value
        if (!currentState.isVideoLoaded || currentState.currentVideoUri == null) {
            _uiState.update { it.copy(errorMessage = "يرجى اختيار ملف فيديو أولاً.") }
            return
        }

        if (enableVocalOnly) {
            if (currentState.isSeparated && vocalWavFile != null && vocalWavFile!!.exists()) {
                // Already processed, just switch player mode
                _uiState.update { it.copy(isVocalOnly = true) }
                playerController.setVocalOnlyMode(true)
            } else {
                // Need to process audio first
                processAudioAndEnableVocalOnly(currentState.currentVideoUri)
            }
        } else {
            _uiState.update { it.copy(isVocalOnly = false) }
            playerController.setVocalOnlyMode(false)
        }
    }

    private fun processAudioAndEnableVocalOnly(videoUri: Uri) {
        processingJob?.cancel()
        processingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update {
                    it.copy(
                        isProcessing = true,
                        processingStage = "استخراج الصوت من الفيديو...",
                        processingProgress = 0.05f,
                        errorMessage = null
                    )
                }

                val tempDir = File(context.cacheDir, "vocal_temp_${System.currentTimeMillis()}").apply { mkdirs() }
                val rawWav = File(tempDir, "extracted_raw.wav")
                val vocalWav = File(tempDir, "vocal_isolated.wav")
                val accompanimentWav = File(tempDir, "accompaniment_isolated.wav")

                // Step 1: Extract Audio
                val isExtracted = if (videoUri.scheme == "file" && videoUri.path?.endsWith(".wav") == true) {
                    // Already WAV (e.g. demo)
                    File(videoUri.path!!).copyTo(rawWav, overwrite = true)
                    true
                } else {
                    audioExtractor.extractAudioToWav(
                        videoUri = videoUri,
                        outputWavFile = rawWav,
                        onProgress = { progress ->
                            _uiState.update {
                                it.copy(
                                    processingStage = "استخراج الصوت من الفيديو (${(progress * 100).toInt()}%)...",
                                    processingProgress = progress * 0.45f
                                )
                            }
                        }
                    )
                }

                if (!isExtracted || !rawWav.exists()) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            errorMessage = "فشل في استخراج الصوت من ملف الفيديو المحدد."
                        )
                    }
                    return@launch
                }

                // Step 2: Separate Audio (Spleeter 2-stem model)
                _uiState.update {
                    it.copy(
                        processingStage = "جارٍ فصل الصوت وعزل الموسيقى (Spleeter AI)...",
                        processingProgress = 0.5f
                    )
                }

                val separationResult = audioSeparator.separateAudio(
                    inputWavFile = rawWav,
                    outputVocalWav = vocalWav,
                    outputAccompanimentWav = accompanimentWav,
                    onProgress = { progress ->
                        _uiState.update {
                            it.copy(
                                processingStage = "جارٍ عزل الصوت البشري (${(progress * 100).toInt()}%)...",
                                processingProgress = 0.45f + (progress * 0.50f)
                            )
                        }
                    }
                )

                when (separationResult) {
                    is SeparationResult.Success -> {
                        extractedWavFile = rawWav
                        vocalWavFile = separationResult.vocalFile
                        accompanimentWavFile = separationResult.accompanimentFile

                        withContext(Dispatchers.Main) {
                            playerController.setupIsolatedTracks(
                                vocalFile = separationResult.vocalFile,
                                accompanimentFile = separationResult.accompanimentFile
                            )
                            playerController.setVocalOnlyMode(true)

                            _uiState.update {
                                it.copy(
                                    isProcessing = false,
                                    processingProgress = 1.0f,
                                    isSeparated = true,
                                    isVocalOnly = true,
                                    infoMessage = "تم فصل الصوت بنجاح! تم كتم الموسيقى والإبقاء على الصوت البشري فقط."
                                )
                            }
                        }
                    }
                    is SeparationResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                errorMessage = separationResult.message
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio processing failed: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "فشلت المعالجة: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun setVocalVolume(volume: Float) {
        _uiState.update { it.copy(vocalVolume = volume) }
        playerController.setVocalVolume(volume)
    }

    fun setBgmVolume(volume: Float) {
        _uiState.update { it.copy(bgmVolume = volume) }
        playerController.setBgmVolume(volume)
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
        playerController.setPlaybackSpeed(speed)
    }

    fun toggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissInfo() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        return cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot get display name from cursor", e)
            }
        }
        return uri.lastPathSegment
    }

    private fun cleanupCurrentMediaFiles() {
        try {
            extractedWavFile?.delete()
            vocalWavFile?.delete()
            accompanimentWavFile?.delete()
            extractedWavFile = null
            vocalWavFile = null
            accompanimentWavFile = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning temp files", e)
        }
    }

    fun cleanupTempFiles() {
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("vocal_temp_") || file.name.startsWith("vocal_keep_")) {
                    file.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in bulk temp cleanup", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        processingJob?.cancel()
        playerController.release()
        audioSeparator.release()
        cleanupCurrentMediaFiles()
        cleanupTempFiles()
    }
}
