package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioExtractor(private val context: Context) {

    companion object {
        private const val TAG = "AudioExtractor"
        private const val TIMEOUT_US = 10000L
    }

    /**
     * Extracts audio track from video Uri, decodes it to 16-bit PCM 16kHz mono WAV file.
     */
    suspend fun extractAudioToWav(
        videoUri: Uri,
        outputWavFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, videoUri, null)

            // Find audio track
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            var mime: String? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val trackMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (trackMime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    mime = trackMime
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null || mime == null) {
                Log.e(TAG, "No audio track found in video URI: $videoUri")
                return@withContext false
            }

            extractor.selectTrack(audioTrackIndex)

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                10_000_000L // 10s default estimate
            }

            val inputSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }

            val inputChannelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isEOS = false
            val pcmBufferStream = ByteArrayOutputStream()

            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    buffer?.clear()

                    val sampleSize = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()

                        if (durationUs > 0) {
                            val progress = (presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress * 0.9f)
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                while (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && info.size > 0) {
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)

                        val chunk = ByteArray(info.size)
                        outBuffer.get(chunk)
                        pcmBufferStream.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }

            val rawPcmBytes = pcmBufferStream.toByteArray()
            if (rawPcmBytes.isEmpty()) {
                Log.e(TAG, "Extracted PCM bytes is empty")
                return@withContext false
            }

            // Resample / convert channels to 16kHz mono ShortArray
            val mono16kShorts = resampleAndDownmixTo16kMono(
                rawPcmBytes,
                inputSampleRate,
                inputChannelCount
            )

            WavAudioUtil.writePcmToWav(
                mono16kShorts,
                outputWavFile,
                sampleRate = WavAudioUtil.SAMPLE_RATE_16K,
                channels = 1
            )

            onProgress(1.0f)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction error: ${e.message}", e)
            false
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (ex: Exception) {
                Log.w(TAG, "Error cleaning up codec/extractor", ex)
            }
        }
    }

    private fun resampleAndDownmixTo16kMono(
        rawBytes: ByteArray,
        srcSampleRate: Int,
        srcChannels: Int
    ): ShortArray {
        val totalShorts = rawBytes.size / 2
        val srcShortBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val rawShorts = ShortArray(totalShorts)
        srcShortBuffer.get(rawShorts)

        // 1. Downmix multi-channel to mono
        val monoCount = totalShorts / srcChannels.coerceAtLeast(1)
        val monoShorts = ShortArray(monoCount)

        if (srcChannels == 1) {
            System.arraycopy(rawShorts, 0, monoShorts, 0, monoCount)
        } else {
            for (i in 0 until monoCount) {
                var sum = 0
                for (ch in 0 until srcChannels) {
                    val idx = i * srcChannels + ch
                    if (idx < totalShorts) {
                        sum += rawShorts[idx]
                    }
                }
                monoShorts[i] = (sum / srcChannels).toShort()
            }
        }

        // 2. Resample to 16,000 Hz if needed
        val targetSampleRate = WavAudioUtil.SAMPLE_RATE_16K
        if (srcSampleRate == targetSampleRate || srcSampleRate <= 0) {
            return monoShorts
        }

        val ratio = targetSampleRate.toDouble() / srcSampleRate.toDouble()
        val targetLength = (monoCount * ratio).toInt()
        val resampled = ShortArray(targetLength)

        for (i in 0 until targetLength) {
            val srcIndex = i / ratio
            val index0 = srcIndex.toInt().coerceIn(0, monoCount - 1)
            val index1 = (index0 + 1).coerceIn(0, monoCount - 1)
            val frac = (srcIndex - index0).toFloat()

            val s0 = monoShorts[index0].toFloat()
            val s1 = monoShorts[index1].toFloat()
            val interpolated = s0 + frac * (s1 - s0)
            resampled[i] = interpolated.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return resampled
    }
}
