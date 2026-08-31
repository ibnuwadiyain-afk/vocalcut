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
import com.example.data.AudioCacheManager
import com.example.export.ExportResult
import com.example.export.ExportType
import com.example.export.VideoExporter
import com.example.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AudioPlaybackMode {
    NATIVE_ORIGINAL, // Standard native original video audio (immediate playback, no buffering/separation)
    VOCAL_ONLY       // Isolated human vocal only with muted instruments (processed via Spleeter 2-Stem Neural Engine)
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
    val processingElapsedSeconds: Int = 0,
    val isSeparated: Boolean = false,
    val isCached: Boolean = false,
    val totalCacheSizeBytes: Long = 0L,
    val vocalVolume: Float = 1.0f,
    val bgmVolume: Float = 0.0f,
    val playbackSpeed: Float = 1.0f,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val isFullscreen: Boolean = false,
    val isAudioVisualizerActive: Boolean = true,
    val isBackgroundPlayEnabled: Boolean = true,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportStage: String = "",
    val exportElapsedSeconds: Int = 0,
    val lastExportedFileName: String? = null,
    val lastExportedFilePath: String? = null,
    val lastExportedUri: Uri? = null,
    val lastExportType: ExportType = ExportType.VIDEO_MP4,
    val showExportSuccessDialog: Boolean = false,
    val showExportOptionsSheet: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val context: Context get() = getApplication<Application>().applicationContext

    val playerController = PlayerController(context, viewModelScope)
    private val audioExtractor = AudioExtractor(context)
    private val audioSeparator = AudioSeparator(context)
    private val videoExporter = VideoExporter(context)
    private val cacheManager = AudioCacheManager(context)

    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    private var extractedWavFile: File? = null
    private var vocalWavFile: File? = null
    private var accompanimentWavFile: File? = null

    private var processingJob: Job? = null
    private var processingTimerJob: Job? = null
    private var exportJob: Job? = null
    private var exportTimerJob: Job? = null

    init {
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
                            isSeparated = true,
                            isCached = true,
                            playbackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
                            isVocalOnly = false,
                            isProcessing = false,
                            errorMessage = null,
                            infoMessage = "تم العثور على ملفات الصوت المعزول في الذاكرة المحلية (Cache)!"
                        )
                    }
                } else {
                    playerController.loadVideo(uri)

                    _uiState.update {
                        it.copy(
                            currentVideoUri = uri,
                            videoTitle = displayName,
                            isVideoLoaded = true,
                            isSeparated = false,
                            isCached = false,
                            playbackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
                            isVocalOnly = false,
                            isProcessing = false,
                            errorMessage = null,
                            infoMessage = "تم تحميل الفيديو! يمكنك تشغيل الصوت الأصلي فوراً أو اختيار 'صوت بشري فقط' لكتم الموسيقى."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error opening video: ${e.message}", e)
                _uiState.update {
                    it.copy(errorMessage = "حدث خطأ أثناء فتح الفيديو: ${e.localizedMessage}")
                }
            }
        }
    }

    fun selectPlaybackMode(mode: AudioPlaybackMode) {
        val currentUri = _uiState.value.currentVideoUri
        if (currentUri == null || !_uiState.value.isVideoLoaded) {
            _uiState.update { it.copy(errorMessage = "يرجى فتح ملف فيديو أولاً.") }
            return
        }

        when (mode) {
            AudioPlaybackMode.NATIVE_ORIGINAL -> {
                processingJob?.cancel()
                processingTimerJob?.cancel()
                playerController.setVocalOnlyMode(false)
                _uiState.update {
                    it.copy(
                        playbackMode = AudioPlaybackMode.NATIVE_ORIGINAL,
                        isVocalOnly = false,
                        isProcessing = false,
                        infoMessage = "تم التبديل إلى الصوت الأصلي المباشر (Native Audio)"
                    )
                }
            }
            AudioPlaybackMode.VOCAL_ONLY -> {
                if (_uiState.value.isSeparated && vocalWavFile?.exists() == true && accompanimentWavFile?.exists() == true) {
                    playerController.setVocalOnlyMode(true)
                    _uiState.update {
                        it.copy(
                            playbackMode = AudioPlaybackMode.VOCAL_ONLY,
                            isVocalOnly = true,
                            isProcessing = false,
                            infoMessage = "تم تفعيل عزل الصوت البشري (تم كتم الآلات الموسيقية بنجاح)"
                        )
                    }
                } else {
                    processAudioAndEnableVocalOnly(currentUri)
                }
            }
        }
    }

    fun toggleVocalOnly(enableVocalOnly: Boolean) {
        selectPlaybackMode(if (enableVocalOnly) AudioPlaybackMode.VOCAL_ONLY else AudioPlaybackMode.NATIVE_ORIGINAL)
    }

    private fun processAudioAndEnableVocalOnly(videoUri: Uri) {
        processingJob?.cancel()
        processingTimerJob?.cancel()

        // Start live elapsed timer
        _uiState.update { it.copy(processingElapsedSeconds = 0) }
        processingTimerJob = viewModelScope.launch(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                _uiState.update { it.copy(processingElapsedSeconds = elapsed) }
            }
        }

        processingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update {
                    it.copy(
                        isProcessing = true,
                        processingStage = "جارٍ استخراج الصوت وعزله بمحرك Spleeter السريع...",
                        processingProgress = 0.05f,
                        errorMessage = null
                    )
                }

                val tempDir = File(context.cacheDir, "vocal_temp_${System.currentTimeMillis()}").apply { mkdirs() }
                val rawWav = File(tempDir, "extracted_raw.wav")
                val vocalWav = File(tempDir, "vocal_isolated.wav")
                val accompanimentWav = File(tempDir, "accompaniment_isolated.wav")

                // Step 1: Extract Audio Track
                val isExtracted = if (videoUri.scheme == "file" && videoUri.path?.endsWith(".wav") == true) {
                    File(videoUri.path!!).copyTo(rawWav, overwrite = true)
                    true
                } else {
                    audioExtractor.extractAudioToWav(
                        videoUri = videoUri,
                        outputWavFile = rawWav,
                        onProgress = { progress ->
                            _uiState.update {
                                it.copy(
                                    processingStage = "استخراج صوت الفيديو (${(progress * 100).toInt()}%)...",
                                    processingProgress = progress * 0.35f
                                )
                            }
                        }
                    )
                }

                if (!isExtracted || !rawWav.exists()) {
                    processingTimerJob?.cancel()
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

                // Step 2: Separate Audio Stems with Spleeter Multi-core Engine
                _uiState.update {
                    it.copy(
                        processingStage = "عزل الصوت البشري بمحرك Spleeter المتعدد الأنوية...",
                        processingProgress = 0.38f
                    )
                }

                val separationResult = audioSeparator.separateAudio(
                    inputWavFile = rawWav,
                    outputVocalWav = vocalWav,
                    outputAccompanimentWav = accompanimentWav,
                    onProgress = { progress ->
                        _uiState.update {
                            it.copy(
                                processingStage = "معالجة وعزل الصوت البشري (${(progress * 100).toInt()}%)...",
                                processingProgress = 0.35f + (progress * 0.63f)
                            )
                        }
                    }
                )

                processingTimerJob?.cancel()

                when (separationResult) {
                    is SeparationResult.Success -> {
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
                                    infoMessage = "تم الانتهاء بنجاح! تم كتم الآلات الموسيقية وعزل الصوت البشري."
                                )
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
                processingTimerJob?.cancel()
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

    fun setVocalVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1.5f)
        _uiState.update { it.copy(vocalVolume = clamped) }
        playerController.setVocalVolume(clamped)
    }

    fun setBgmVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1.5f)
        _uiState.update { it.copy(bgmVolume = clamped) }
        playerController.setBgmVolume(clamped)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 2.0f)
        _uiState.update { it.copy(playbackSpeed = clamped) }
        playerController.setPlaybackSpeed(clamped)
    }

    fun toggleFullscreen(fullscreen: Boolean? = null) {
        _uiState.update {
            it.copy(isFullscreen = fullscreen ?: !it.isFullscreen)
        }
    }

    fun toggleAudioVisualizer() {
        _uiState.update {
            it.copy(isAudioVisualizerActive = !it.isAudioVisualizerActive)
        }
    }

    fun toggleBackgroundPlay(enabled: Boolean? = null) {
        _uiState.update {
            val newVal = enabled ?: !it.isBackgroundPlayEnabled
            it.copy(
                isBackgroundPlayEnabled = newVal,
                infoMessage = if (newVal) "تم تفعيل التشغيل في الخلفية (Background Play ON)" else "تم إيقاف التشغيل في الخلفية"
            )
        }
    }

    fun showExportOptions(show: Boolean) {
        _uiState.update { it.copy(showExportOptionsSheet = show) }
    }

    /**
     * Exports either:
     * 1. ExportType.VIDEO_MP4 (Original Video Muxed with Vocal Audio)
     * 2. ExportType.VOICE_ONLY_WAV (Vocal Audio file only)
     */
    fun startExport(type: ExportType) {
        val currentState = _uiState.value
        val videoUri = currentState.currentVideoUri
        if (videoUri == null || !currentState.isVideoLoaded) {
            _uiState.update { it.copy(errorMessage = "يرجى فتح واختيار فيديو أولاً لتصديره.") }
            return
        }

        if (currentState.isExporting) {
            _uiState.update { it.copy(errorMessage = "عملية التصدير جارية بالفعل...") }
            return
        }

        showExportOptions(false)
        exportJob?.cancel()
        exportTimerJob?.cancel()

        // Start elapsed timer for export
        _uiState.update { it.copy(exportElapsedSeconds = 0) }
        exportTimerJob = viewModelScope.launch(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                _uiState.update { it.copy(exportElapsedSeconds = elapsed) }
            }
        }

        exportJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update {
                    it.copy(
                        isExporting = true,
                        exportProgress = 0.05f,
                        exportStage = "جارٍ فحص المسار الصوتي المعزول...",
                        errorMessage = null
                    )
                }

                // Ensure vocal audio is isolated via Spleeter engine
                var activeVocalFile = vocalWavFile
                if (activeVocalFile == null || !activeVocalFile.exists()) {
                    val cachedEntry = cacheManager.getCachedEntry(videoUri)
                    if (cachedEntry != null && File(cachedEntry.vocalFilePath).exists()) {
                        activeVocalFile = File(cachedEntry.vocalFilePath)
                        vocalWavFile = activeVocalFile
                    }
                }

                if (activeVocalFile == null || !activeVocalFile.exists()) {
                    _uiState.update {
                        it.copy(
                            exportProgress = 0.1f,
                            exportStage = "عزل الصوت البشري بمحرك Spleeter قبل التصدير..."
                        )
                    }

                    val tempDir = File(context.cacheDir, "vocal_export_prep_${System.currentTimeMillis()}").apply { mkdirs() }
                    val rawWav = File(tempDir, "prep_raw.wav")
                    val vocalWav = File(tempDir, "prep_vocal.wav")
                    val accompanimentWav = File(tempDir, "prep_bgm.wav")

                    val extracted = audioExtractor.extractAudioToWav(
                        videoUri = videoUri,
                        outputWavFile = rawWav,
                        onProgress = { p ->
                            _uiState.update {
                                it.copy(
                                    exportProgress = 0.05f + (p * 0.15f),
                                    exportStage = "استخراج صوت الفيديو (${(p * 100).toInt()}%)..."
                                )
                            }
                        }
                    )

                    if (!extracted || !rawWav.exists()) {
                        exportTimerJob?.cancel()
                        _uiState.update {
                            it.copy(
                                isExporting = false,
                                errorMessage = "تعذر استخراج الصوت من الفيديو لإتمام التصدير."
                            )
                        }
                        return@launch
                    }

                    val sepResult = audioSeparator.separateAudio(
                        inputWavFile = rawWav,
                        outputVocalWav = vocalWav,
                        outputAccompanimentWav = accompanimentWav,
                        onProgress = { p ->
                            _uiState.update {
                                it.copy(
                                    exportProgress = 0.20f + (p * 0.15f),
                                    exportStage = "عزل الصوت البشري بمحرك Spleeter (${(p * 100).toInt()}%)..."
                                )
                            }
                        }
                    )

                    when (sepResult) {
                        is SeparationResult.Success -> {
                            activeVocalFile = sepResult.vocalFile
                            vocalWavFile = activeVocalFile
                        }
                        is SeparationResult.Error -> {
                            exportTimerJob?.cancel()
                            _uiState.update {
                                it.copy(
                                    isExporting = false,
                                    errorMessage = "فشل عزل الصوت للتصدير: ${sepResult.message}"
                                )
                            }
                            return@launch
                        }
                    }
                }

                val currentVocal = activeVocalFile
                if (currentVocal == null || !currentVocal.exists()) {
                    exportTimerJob?.cancel()
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            errorMessage = "ملف الصوت المعزول غير متوفر."
                        )
                    }
                    return@launch
                }

                // Proceed with Export based on selected type
                val exportResult = if (type == ExportType.VIDEO_MP4) {
                    videoExporter.exportMutedVideo(
                        sourceVideoUri = videoUri,
                        vocalWavFile = currentVocal,
                        baseFileName = currentState.videoTitle,
                        onProgress = { progress, stage ->
                            _uiState.update {
                                it.copy(
                                    exportProgress = 0.35f + (progress * 0.65f),
                                    exportStage = stage
                                )
                            }
                        }
                    )
                } else {
                    videoExporter.exportVoiceOnly(
                        vocalWavFile = currentVocal,
                        baseFileName = currentState.videoTitle,
                        onProgress = { progress, stage ->
                            _uiState.update {
                                it.copy(
                                    exportProgress = progress,
                                    exportStage = stage
                                )
                            }
                        }
                    )
                }

                exportTimerJob?.cancel()

                when (exportResult) {
                    is ExportResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isExporting = false,
                                exportProgress = 1.0f,
                                lastExportedFileName = exportResult.fileName,
                                lastExportedFilePath = exportResult.filePath,
                                lastExportedUri = exportResult.outputUri,
                                lastExportType = exportResult.exportType,
                                showExportSuccessDialog = true,
                                infoMessage = if (exportResult.exportType == ExportType.VIDEO_MP4)
                                    "تم تصدير وحفظ الفيديو بدون موسيقى بنجاح!"
                                else
                                    "تم حفظ ملف الصوت البشري المعزول بنجاح!"
                            )
                        }
                    }
                    is ExportResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isExporting = false,
                                errorMessage = exportResult.message
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                exportTimerJob?.cancel()
                Log.e(TAG, "Export error: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "حدث خطأ أثناء التصدير: ${e.localizedMessage}"
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
                        infoMessage = "تم مسح ملفات الذاكرة المؤقتة بنجاح."
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissInfo() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    private fun queryFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun cleanupCurrentMediaFiles() {
        try {
            extractedWavFile?.delete()
            vocalWavFile?.delete()
            accompanimentWavFile?.delete()
        } catch (_: Exception) {}
        extractedWavFile = null
        vocalWavFile = null
        accompanimentWavFile = null
    }

    fun cleanupTempFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = context.cacheDir
                cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("vocal_temp_") || file.name.startsWith("export_") || file.name.startsWith("vocal_export_prep_")) {
                        file.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Temp cleanup warning", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        processingJob?.cancel()
        processingTimerJob?.cancel()
        exportJob?.cancel()
        exportTimerJob?.cancel()
        playerController.release()
        audioSeparator.release()
        cleanupCurrentMediaFiles()
    }
}
