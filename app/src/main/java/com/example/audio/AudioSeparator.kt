package com.example.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AudioSeparator(private val context: Context) {

    companion object {
        private const val TAG = "AudioSeparator"
    }

    /**
     * Separates the input mixed audio WAV into Vocal and Accompaniment WAV files
     * using on-device high-performance DSP spectral masking.
     */
    suspend fun separateAudio(
        inputWavFile: File,
        outputVocalWav: File,
        outputAccompanimentWav: File,
        onProgress: (Float) -> Unit
    ): SeparationResult = withContext(Dispatchers.Default) {
        try {
            if (!inputWavFile.exists() || inputWavFile.length() <= 44) {
                return@withContext SeparationResult.Error("ملف الصوت المستخرج غير صالح أو فارغ.")
            }

            onProgress(0.05f)
            val mixedSamples = WavAudioUtil.readWavToShortArray(inputWavFile)
            if (mixedSamples.isEmpty()) {
                return@withContext SeparationResult.Error("فشل قراءة بيانات الصوت.")
            }

            onProgress(0.15f)

            // Perform separation using DSP spectral masking engine
            val (vocalSamples, accompanimentSamples) = WavAudioUtil.separateStemsDSP(
                mixedSamples = mixedSamples,
                sampleRate = WavAudioUtil.SAMPLE_RATE_16K,
                onProgress = { stepProgress ->
                    // Map progress from 0.15 to 0.90
                    val mapped = 0.15f + (stepProgress * 0.75f)
                    onProgress(mapped)
                }
            )

            onProgress(0.92f)

            // Write separated stems to WAV files
            WavAudioUtil.writePcmToWav(
                pcmData = vocalSamples,
                outputFile = outputVocalWav,
                sampleRate = WavAudioUtil.SAMPLE_RATE_16K,
                channels = 1
            )

            WavAudioUtil.writePcmToWav(
                pcmData = accompanimentSamples,
                outputFile = outputAccompanimentWav,
                sampleRate = WavAudioUtil.SAMPLE_RATE_16K,
                channels = 1
            )

            onProgress(1.0f)
            SeparationResult.Success(
                vocalFile = outputVocalWav,
                accompanimentFile = outputAccompanimentWav,
                durationMs = (mixedSamples.size.toLong() * 1000L) / WavAudioUtil.SAMPLE_RATE_16K
            )
        } catch (e: Exception) {
            Log.e(TAG, "Audio separation failed: ${e.message}", e)
            SeparationResult.Error("حدث خطأ أثناء فصل الصوت: ${e.localizedMessage}")
        }
    }

    fun release() {
        // No resources to release
    }
}

sealed class SeparationResult {
    data class Success(
        val vocalFile: File,
        val accompanimentFile: File,
        val durationMs: Long
    ) : SeparationResult()

    data class Error(val message: String) : SeparationResult()
}

