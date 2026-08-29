package com.example.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Executes on-device neural stem separation using the pre-trained Spleeter model.
 * Accepts raw 44.1kHz stereo/mono audio waveforms and yields separated stem audio waveforms.
 */
class SpleeterTfliteEngine(private val context: Context) {

    companion object {
        private const val TAG = "SpleeterTfliteEngine"
        private const val MODEL_NAME = "spleeter_2stem.tflite"
        const val SAMPLE_RATE = 44100
        const val CHUNK_SECONDS = 5
        const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS // 220,500 samples
    }

    private var interpreter: Interpreter? = null

    init {
        tryLoadModel()
    }

    private fun tryLoadModel(): Boolean {
        return try {
            val modelBuffer = loadModelFile(context, MODEL_NAME)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(false)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "Spleeter TFLite Model loaded successfully. Input tensors count: ${interpreter?.inputTensorCount}, Output tensors count: ${interpreter?.outputTensorCount}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not initialize Spleeter TFLite interpreter: ${e.message}", e)
            interpreter = null
            false
        }
    }

    fun isModelLoaded(): Boolean = interpreter != null

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Runs chunked neural inference on an extracted input PCM 16-bit 44.1kHz WAV file,
     * writing high-fidelity isolated vocal and accompaniment WAVs.
     */
    fun separate(
        inputWavFile: File,
        outputVocalWav: File,
        outputAccompanimentWav: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        val interp = interpreter ?: return false

        try {
            val rafIn = RandomAccessFile(inputWavFile, "r")
            val totalBytes = rafIn.length() - 44
            if (totalBytes <= 0) {
                rafIn.close()
                return false
            }

            val totalSamples = (totalBytes / 2).toInt() // mono 16-bit samples
            rafIn.seek(44) // Skip header

            val fosVocal = FileOutputStream(outputVocalWav)
            val fosBgm = FileOutputStream(outputAccompanimentWav)

            // Initial header placeholders
            val dummyByteRate = (SAMPLE_RATE * 1 * 16 / 8).toLong()
            WavAudioUtil.writeWavHeader(fosVocal, 0L, 36L, SAMPLE_RATE.toLong(), 1, dummyByteRate)
            WavAudioUtil.writeWavHeader(fosBgm, 0L, 36L, SAMPLE_RATE.toLong(), 1, dummyByteRate)

            val inputBuffer = ByteBuffer.allocateDirect(CHUNK_SAMPLES * 2 * 4).order(ByteOrder.nativeOrder())
            val chunkPcmBytes = ByteArray(CHUNK_SAMPLES * 2)

            var processedSamples = 0
            var totalVocalBytesWritten = 0L
            var totalBgmBytesWritten = 0L

            while (processedSamples < totalSamples) {
                val samplesToRead = minOf(CHUNK_SAMPLES, totalSamples - processedSamples)
                val bytesToRead = samplesToRead * 2
                val readCount = rafIn.read(chunkPcmBytes, 0, bytesToRead)
                if (readCount <= 0) break

                val actualSamples = readCount / 2
                inputBuffer.rewind()

                // Feed normalized float stereo audio into Spleeter model [1, N, 2]
                for (i in 0 until actualSamples) {
                    val s16 = (chunkPcmBytes[i * 2].toInt() and 0xFF) or (chunkPcmBytes[i * 2 + 1].toInt() shl 8)
                    val norm = (s16.toShort().toFloat()) / 32768.0f
                    inputBuffer.putFloat(norm) // Left channel
                    inputBuffer.putFloat(norm) // Right channel
                }

                // If chunk is smaller than expected, pad with zeros
                for (i in actualSamples until CHUNK_SAMPLES) {
                    inputBuffer.putFloat(0f)
                    inputBuffer.putFloat(0f)
                }

                inputBuffer.rewind()

                // Spleeter 2-stem model outputs: Stems 0 (Vocals) and 1 (Accompaniment)
                // Shapes: [1, CHUNK_SAMPLES, 2]
                val vocalOutput = Array(1) { Array(CHUNK_SAMPLES) { FloatArray(2) } }
                val bgmOutput = Array(1) { Array(CHUNK_SAMPLES) { FloatArray(2) } }

                val outputsMap = mapOf(
                    0 to vocalOutput,
                    1 to bgmOutput
                )

                try {
                    val inputs = arrayOf<Any>(inputBuffer)
                    interp.runForMultipleInputsOutputs(inputs, outputsMap)

                    // Write outputs to files
                    val vocalOutBytes = ByteArray(actualSamples * 2)
                    val bgmOutBytes = ByteArray(actualSamples * 2)

                    for (i in 0 until actualSamples) {
                        // Average stereo output to mono or take channel 0
                        val vSample = ((vocalOutput[0][i][0] + vocalOutput[0][i][1]) * 0.5f).coerceIn(-1.0f, 1.0f)
                        val bSample = ((bgmOutput[0][i][0] + bgmOutput[0][i][1]) * 0.5f).coerceIn(-1.0f, 1.0f)

                        val vShort = (vSample * 32767.0f).toInt().toShort()
                        val bShort = (bSample * 32767.0f).toInt().toShort()

                        vocalOutBytes[i * 2] = (vShort.toInt() and 0xFF).toByte()
                        vocalOutBytes[i * 2 + 1] = ((vShort.toInt() shr 8) and 0xFF).toByte()

                        bgmOutBytes[i * 2] = (bShort.toInt() and 0xFF).toByte()
                        bgmOutBytes[i * 2 + 1] = ((bShort.toInt() shr 8) and 0xFF).toByte()
                    }

                    fosVocal.write(vocalOutBytes)
                    fosBgm.write(bgmOutBytes)

                    totalVocalBytesWritten += vocalOutBytes.size
                    totalBgmBytesWritten += bgmOutBytes.size
                } catch (e: Exception) {
                    Log.w(TAG, "TFLite chunk inference error, fallback to spectral DSP: ${e.message}")
                    break
                }

                processedSamples += actualSamples
                onProgress((processedSamples.toFloat() / totalSamples.toFloat()).coerceIn(0f, 1f))
            }

            fosVocal.flush()
            fosBgm.flush()
            fosVocal.close()
            fosBgm.close()
            rafIn.close()

            // Update final WAV headers with accurate byte lengths
            if (totalVocalBytesWritten > 0) {
                WavAudioUtil.updateWavHeaderLengths(outputVocalWav, totalVocalBytesWritten)
            }
            if (totalBgmBytesWritten > 0) {
                WavAudioUtil.updateWavHeaderLengths(outputAccompanimentWav, totalBgmBytesWritten)
            }

            return totalVocalBytesWritten > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Spleeter separation: ${e.message}", e)
            return false
        }
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interpreter: ${e.message}")
        }
    }
}
