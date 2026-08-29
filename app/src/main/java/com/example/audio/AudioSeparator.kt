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

    private val spleeterEngine = SpleeterTfliteEngine(context)

    /**
     * Separates the input mixed audio WAV into Vocal and Accompaniment WAV files
     * using the pre-trained Spleeter Neural Network model with DSP fallback.
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

            var usedNeuralModel = false
            var durationMs = 0L

            // 1. Try Pretrained Spleeter Neural Model
            if (spleeterEngine.isModelLoaded()) {
                Log.i(TAG, "Running Spleeter neural model separation...")
                val neuralSuccess = spleeterEngine.separate(
                    inputWavFile = inputWavFile,
                    outputVocalWav = outputVocalWav,
                    outputAccompanimentWav = outputAccompanimentWav,
                    onProgress = { p -> onProgress(0.05f + (p * 0.95f)) }
                )

                if (neuralSuccess && outputVocalWav.exists() && outputVocalWav.length() > 44) {
                    usedNeuralModel = true
                    durationMs = (outputVocalWav.length() - 44) * 1000L / (WavAudioUtil.SAMPLE_RATE_44K * 2)
                    Log.i(TAG, "Spleeter neural separation completed successfully!")
                }
            }

            // 2. If Neural model is not loaded or failed, run DSP harmonic mask fallback
            if (!usedNeuralModel) {
                Log.i(TAG, "Running fallback streaming DSP separation...")
                durationMs = WavAudioUtil.separateWavStemsStreaming(
                    inputWavFile = inputWavFile,
                    outputVocalWav = outputVocalWav,
                    outputAccompanimentWav = outputAccompanimentWav,
                    sampleRate = WavAudioUtil.SAMPLE_RATE_44K,
                    onProgress = { stepProgress ->
                        val mapped = 0.05f + (stepProgress * 0.95f)
                        onProgress(mapped)
                    }
                )
            }

            if (!outputVocalWav.exists() || outputVocalWav.length() <= 44) {
                return@withContext SeparationResult.Error("فشل إنشاء ملف الصوت المفصول.")
            }

            onProgress(1.0f)
            SeparationResult.Success(
                vocalFile = outputVocalWav,
                accompanimentFile = outputAccompanimentWav,
                durationMs = durationMs
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Audio separation failed: ${t.message}", t)
            SeparationResult.Error("حدث خطأ أثناء فصل الصوت: ${t.localizedMessage ?: t.javaClass.simpleName}")
        }
    }

    fun release() {
        spleeterEngine.close()
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
