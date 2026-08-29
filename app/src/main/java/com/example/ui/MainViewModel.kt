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
import com.example.data.AudioCacheManager
import com.example.data.CachedAudioEntry
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

enum class AudioPlaybackMode {
    NATIVE_ORIGINAL, // Standard native original video audio (immediate playback, no buffering/separation)
    VOCAL_ONLY       // Isolated human vocal only with muted instruments (buffered/processed via AI DSP)
}

data class VideoPlayerUiState(
    val currentVideoUri: Uri? = null,
    val videoTitle: String = "",
    val isVideoLoaded: Boolean = false,
    val playbackMode: AudioPlaybackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
    val isVocalOnly: Boolean = false,
    val isProcessing: Boolean = false,
    val processingStage: String = "",
    val processingProgress: Float = 0f,
    val isSeparated: Boolean = false,
    val isCached: Boolean = false,
    val delayPlaybackUntilBuffer: Boolean = true,
    val totalCacheSizeBytes: Long = 0L,
    val vocalVolume: Float = 1.0f,
    val bgmVolume: Float = 0.0f,
    val playbackSpeed: Float = 1.0f,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportStage: String = "",
    val exportedVideoUri: Uri? = null,
    val exportedVideoName: String? = null,
    val showExportSuccessDialog: Boolean = false,
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
    private val videoExportMuxer = com.example.audio.VideoExportMuxer(context)
    private val cacheManager = AudioCacheManager(context)

    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    private var extractedWavFile: File? = null
    private var vocalWavFile: File? = null
    private var accompanimentWavFile: File? = null

    private var processingJob: Job? = null
    private var exportJob: Job? = null

    init {
        // Clean up any stale temp files from previous sessions
        cleanupTempFiles()
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val size = cacheManager.getTotalCacheSizeBytes()
            _uiState.update { it.copy(totalCacheSizeBytes = size) }
        }
    }

    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                val displayName = queryFileName(uri) ?: "فيديو محدد"
                cleanupCurrentMediaFiles()

                // Check local cache first
                val cachedEntry = cacheManager.getCachedEntry(uri)
                if (cachedEntry != null) {
                    val cachedVocal = File(cachedEntry.vocalFilePath)
                    val cachedBgm = File(cachedEntry.accompanimentFilePath)
                    vocalWavFile = cachedVocal
                    accompanimentWavFile = cachedBgm
                    extractedWavFile = cachedEntry.rawWavFilePath?.let { File(it) }

                    playerController.loadVideo(uri)
                    playerController.setupIsolatedTracks(cachedVocal, cachedBgm)

                    _uiState.update {
                        it.copy(
                            currentVideoUri = uri,
                            videoTitle = displayName,
                            isVideoLoaded = true,
                            playbackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
                            isVocalOnly = false,
                            isProcessing = false,
                            isSeparated = true,
                            isCached = true,
                            errorMessage = null,
                            infoMessage = "تم العثور على الصوت المعالج في الذاكرة المؤقتة (جاهز فوراً بدون انتظار)"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            currentVideoUri = uri,
                            videoTitle = displayName,
                            isVideoLoaded = true,
                            playbackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
                            isVocalOnly = false,
                            isProcessing = false,
                            isSeparated = false,
                            isCached = false,
                            errorMessage = null,
                            infoMessage = "تم تحميل الفيديو بنجاح: $displayName"
                        )
                    }
                    playerController.loadVideo(uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading video: ${e.message}", e)
                _uiState.update {
                    it.copy(errorMessage = "تعذر تحميل الفيديو: ${e.localizedMessage}")
                }
            }
        }
    }

    fun selectPlaybackMode(mode: AudioPlaybackMode) {
        val currentState = _uiState.value
        if (!currentState.isVideoLoaded || currentState.currentVideoUri == null) {
            _uiState.update { it.copy(errorMessage = "يرجى اختيار ملف فيديو أولاً.") }
            return
        }

        when (mode) {
            AudioPlaybackMode.NATIVE_ORIGINAL -> {
                // Instantly switch back to native original audio
                _uiState.update {
                    it.copy(
                        playbackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
                        isVocalOnly = false
                    )
                }
                playerController.setVocalOnlyMode(false)
            }
            AudioPlaybackMode.VOCAL_ONLY -> {
                if (currentState.isSeparated && vocalWavFile != null && vocalWavFile!!.exists()) {
                    // Already processed and cached, instant switch
                    _uiState.update {
                        it.copy(
                            playbackMode = AudioPlaybackMode.VOCAL_ONLY,
                            isVocalOnly = true
                        )
                    }
                    playerController.setVocalOnlyMode(true)
                } else {
                    // Process audio & isolate vocal with buffering/progress feedback
                    _uiState.update {
                        it.copy(
                            playbackMode = AudioPlaybackMode.VOCAL_ONLY,
                            isVocalOnly = true
                        )
                    }
                    processAudioAndEnableVocalOnly(currentState.currentVideoUri)
                }
            }
        }
    }

    fun toggleVocalOnly(enableVocalOnly: Boolean) {
        selectPlaybackMode(if (enableVocalOnly) AudioPlaybackMode.VOCAL_ONLY else AudioPlaybackMode.NATIVE_ORIGINAL)
    }

    fun toggleDelayPlayback(enabled: Boolean) {
        _uiState.update { it.copy(delayPlaybackUntilBuffer = enabled) }
    }

    private fun processAudioAndEnableVocalOnly(videoUri: Uri) {
        processingJob?.cancel()

        // If user wants to delay playback until buffer cache starts, pause video during processing
        val shouldDelay = _uiState.value.delayPlaybackUntilBuffer
        if (shouldDelay) {
            playerController.pause()
        }

        processingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update {
                    it.copy(
                        isProcessing = true,
                        processingStage = if (shouldDelay)
                            "تم إيقاف التشغيل مؤقتاً... جارٍ التخزين المؤقت وعزل الصوت (سيبدأ تلقائياً فور الاكتمال)"
                        else
                            "جارٍ استخراج الصوت وعزله (قد يستغرق بعض الوقت للتخزين المؤقت)...",
                        processingProgress = 0.05f,
                        errorMessage = null
                    )
                }

                val tempDir = File(context.cacheDir, "vocal_temp_${System.currentTimeMillis()}").apply { mkdirs() }
                val rawWav = File(tempDir, "extracted_raw.wav")
                val vocalWav = File(tempDir, "vocal_isolated.wav")
                val accompanimentWav = File(tempDir, "accompaniment_isolated.wav")

                // Step 1: Extract Audio Track
                val isExtracted = audioExtractor.extractAudioToWav(
                    videoUri = videoUri,
                    outputWavFile = rawWav,
                    onProgress = { progress ->
                        _uiState.update {
                            it.copy(
                                processingStage = "استخراج الصوت من الفيديو (${(progress * 100).toInt()}%)...",
                                processingProgress = progress * 0.40f
                            )
                        }
                    }
                )

                if (!isExtracted || !rawWav.exists()) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            playbackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
                            isVocalOnly = false,
                            errorMessage = "فشل في استخراج الصوت من ملف الفيديو المحدد."
                        )
                    }
                    return@launch
                }

                // Step 2: Separate Audio Stems (Vocal vs Instrumental)
                _uiState.update {
                    it.copy(
                        processingStage = "جارٍ عزل الصوت البشري بالذكاء الاصطناعي وكتم الآلات الموسيقية...",
                        processingProgress = 0.45f
                    )
                }

                val separationResult = audioSeparator.separateAudio(
                    inputWavFile = rawWav,
                    outputVocalWav = vocalWav,
                    outputAccompanimentWav = accompanimentWav,
                    onProgress = { progress ->
                        _uiState.update {
                            it.copy(
                                processingStage = "معالجة وتخزين الصوت البشري (${(progress * 100).toInt()}%)...",
                                processingProgress = 0.40f + (progress * 0.58f)
                            )
                        }
                    }
                )

                when (separationResult) {
                    is SeparationResult.Success -> {
                        // Persist to local cache so user never needs to process again!
                        val savedEntry = cacheManager.saveToCache(
                            uri = videoUri,
                            videoTitle = _uiState.value.videoTitle,
                            vocalSourceFile = separationResult.vocalFile,
                            accompanimentSourceFile = separationResult.accompanimentFile,
                            rawWavSourceFile = rawWav,
                            durationMs = separationResult.durationMs
                        )

                        val finalVocalFile = if (savedEntry != null) File(savedEntry.vocalFilePath) else separationResult.vocalFile
                        val finalBgmFile = if (savedEntry != null) File(savedEntry.accompanimentFilePath) else separationResult.accompanimentFile

                        extractedWavFile = rawWav
                        vocalWavFile = finalVocalFile
                        accompanimentWavFile = finalBgmFile

                        withContext(Dispatchers.Main) {
                            playerController.setupIsolatedTracks(
                                vocalFile = finalVocalFile,
                                accompanimentFile = finalBgmFile
                            )
                            playerController.setVocalOnlyMode(true)

                            _uiState.update {
                                it.copy(
                                    isProcessing = false,
                                    processingProgress = 1.0f,
                                    isSeparated = true,
                                    isCached = (savedEntry != null),
                                    playbackMode = AudioPlaybackMode.VOCAL_ONLY,
                                    isVocalOnly = true,
                                    infoMessage = "تم الانتهاء وتخزين الصوت مؤقتاً بنجاح! تم كتم الموسيقى."
                                )
                            }

                            // Start playback automatically if delayed playback was active
                            if (shouldDelay) {
                                playerController.play()
                            }

                            refreshCacheSize()
                        }
                    }
                    is SeparationResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                playbackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
                                isVocalOnly = false,
                                errorMessage = separationResult.message
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Audio processing failed: ${t.message}", t)
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        playbackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
                        isVocalOnly = false,
                        errorMessage = "فشلت المعالجة: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    /**
     * Saves / exports the processed video with muted instruments (vocal only)
     * directly into the phone's public Movies / Storage folder.
     */
    fun exportProcessedVideo() {
        val currentState = _uiState.value
        val videoUri = currentState.currentVideoUri
        val vocalFile = vocalWavFile

        if (videoUri == null || vocalFile == null || !vocalFile.exists()) {
            _uiState.update { it.copy(errorMessage = "يرجى معالجة الصوت البشري أولاً قبل التصدير.") }
            return
        }

        exportJob?.cancel()
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update {
                    it.copy(
                        isExporting = true,
                        exportProgress = 0.05f,
                        exportStage = "جارٍ إعداد تصدير الفيديو المكتوم الموسيقى...",
                        errorMessage = null
                    )
                }

                val title = currentState.videoTitle.ifBlank { "Video" }
                val exportResult = videoExportMuxer.exportMutedMusicVideo(
                    videoUri = videoUri,
                    vocalWavFile = vocalFile,
                    baseOutputName = title,
                    onProgress = { progress ->
                        _uiState.update {
                            it.copy(
                                exportProgress = progress,
                                exportStage = when {
                                    progress < 0.25f -> "جارٍ تهيئة مسارات الوسائط وتشفير الصوت البشري AAC..."
                                    progress < 0.60f -> "جارٍ كتابة الصوت المكتوم الموسيقى (${(progress * 100).toInt()}%)..."
                                    progress < 0.95f -> "جارٍ دمج مسار الفيديو الأصلي بدون فقدان للجودة (${(progress * 100).toInt()}%)..."
                                    else -> "جارٍ حفظ ملف الفيديو في مجلد الأفلام (Movies/VocalKeep)..."
                                }
                            )
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    when (exportResult) {
                        is com.example.audio.ExportResult.Success -> {
                            _uiState.update {
                                it.copy(
                                    isExporting = false,
                                    exportProgress = 1.0f,
                                    exportedVideoUri = exportResult.fileUri,
                                    exportedVideoName = exportResult.filePath,
                                    showExportSuccessDialog = true,
                                    infoMessage = "تم حفظ الفيديو المكتوم الموسيقى في ذاكرة الهاتف بنجاح!"
                                )
                            }
                        }
                        is com.example.audio.ExportResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    isExporting = false,
                                    errorMessage = exportResult.message
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export error: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "فشل تصدير الفيديو: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun dismissExportDialog() {
        _uiState.update { it.copy(showExportSuccessDialog = false) }
    }

    fun clearAllAudioCache() {
        viewModelScope.launch {
            val success = cacheManager.clearCache()
            if (success) {
                refreshCacheSize()
                _uiState.update {
                    it.copy(
                        isCached = false,
                        infoMessage = "تم مسح جميع ملفات الذاكرة المؤقتة بنجاح."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(errorMessage = "حدث خطأ أثناء مسح الذاكرة المؤقتة.")
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
