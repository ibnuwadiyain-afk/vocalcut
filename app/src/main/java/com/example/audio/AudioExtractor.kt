package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Accelerated High-Fidelity Audio Extractor
 *
 * Decodes audio stream from video with hardware-accelerated MediaCodec
 * and streaming linear resampling directly to 16-bit PCM 44.1kHz mono WAV.
 */
class AudioExtractor(private val context: Context) {

    companion object {
        private const val TAG = "AudioExtractor"
        private const val TIMEOUT_US = 5000L
        private const val OUT_BUFFER_CAPACITY = 32768
    }

    suspend fun extractAudioToWav(
        videoUri: Uri,
        outputWavFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            if (videoUri.scheme == "content") {
                pfd = context.contentResolver.openFileDescriptor(videoUri, "r")
                if (pfd != null) {
                    extractor.setDataSource(pfd.fileDescriptor)
                } else {
                    extractor.setDataSource(context, videoUri, null)
                }
            } else if (videoUri.scheme == "file" || videoUri.path != null) {
                val path = videoUri.path ?: ""
                val file = File(path)
                if (file.exists()) {
                    extractor.setDataSource(file.absolutePath)
                } else {
                    extractor.setDataSource(context, videoUri, null)
                }
            } else {
                extractor.setDataSource(context, videoUri, null)
            }

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
                10_000_000L
            }

            var currentSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }

            var currentChannelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false

            var totalPcmBytesWritten = 0L
            val outByteBuffer = ByteArray(OUT_BUFFER_CAPACITY)
            val outByteBufferWrapper = ByteBuffer.wrap(outByteBuffer).order(ByteOrder.LITTLE_ENDIAN)

            var resamplePhase = 0.0
            var prevSample = 0.0f

            BufferedOutputStream(FileOutputStream(outputWavFile), 131072).use { wavOut ->
                WavAudioUtil.writeWavHeader(
                    out = wavOut,
                    totalAudioLen = 0,
                    totalDataLen = 36,
                    sampleRate = WavAudioUtil.SAMPLE_RATE_44K.toLong(),
                    channels = 1
                )

                while (!isOutputEOS) {
                    if (!isInputEOS) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)
                            buffer?.clear()

                            val sampleSize = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()

                                if (durationUs > 0) {
                                    val progress = (presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                    onProgress(progress * 0.95f)
                                }
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                currentSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                currentChannelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // Continue
                        }
                        outIndex >= 0 -> {
                            val outBuffer = codec.getOutputBuffer(outIndex)
                            if (outBuffer != null && info.size > 0) {
                                outBuffer.position(info.offset)
                                outBuffer.limit(info.offset + info.size)
                                outBuffer.order(ByteOrder.LITTLE_ENDIAN)

                                val shortBuffer = outBuffer.asShortBuffer()
                                val numShorts = shortBuffer.remaining()
                                val channels = currentChannelCount.coerceAtLeast(1)
                                val numFrames = numShorts / channels

                                val targetSampleRate = WavAudioUtil.SAMPLE_RATE_44K
                                val ratio = targetSampleRate.toDouble() / currentSampleRate.toDouble()

                                var outBufPos = 0

                                for (f in 0 until numFrames) {
                                    var sum = 0
                                    for (c in 0 until channels) {
                                        sum += shortBuffer.get()
                                    }
                                    val monoVal = (sum / channels).toFloat()

                                    if (currentSampleRate == targetSampleRate) {
                                        val s = monoVal.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                        outByteBufferWrapper.putShort(outBufPos, s)
                                        outBufPos += 2
                                        if (outBufPos >= outByteBuffer.size) {
                                            wavOut.write(outByteBuffer, 0, outBufPos)
                                            totalPcmBytesWritten += outBufPos
                                            outBufPos = 0
                                        }
                                    } else {
                                        while (resamplePhase < 1.0) {
                                            val interpolated = prevSample + resamplePhase.toFloat() * (monoVal - prevSample)
                                            val s = interpolated.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                            outByteBufferWrapper.putShort(outBufPos, s)
                                            outBufPos += 2
                                            if (outBufPos >= outByteBuffer.size) {
                                                wavOut.write(outByteBuffer, 0, outBufPos)
                                                totalPcmBytesWritten += outBufPos
                                                outBufPos = 0
                                            }
                                            resamplePhase += 1.0 / ratio
                                        }
                                        resamplePhase -= 1.0
                                        prevSample = monoVal
                                    }
                                }

                                if (outBufPos > 0) {
                                    wavOut.write(outByteBuffer, 0, outBufPos)
                                    totalPcmBytesWritten += outBufPos
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)

                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                isOutputEOS = true
                            }
                        }
                    }
                }
                wavOut.flush()
            }

            if (totalPcmBytesWritten <= 0) {
                Log.e(TAG, "No PCM bytes were written during extraction")
                return@withContext false
            }

            WavAudioUtil.updateWavHeaderLengths(outputWavFile, totalPcmBytesWritten)
            onProgress(1.0f)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Audio extraction error: ${t.message}", t)
            false
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
                pfd?.close()
            } catch (ex: Exception) {
                Log.w(TAG, "Error cleaning up codec/extractor", ex)
            }
        }
    }
}
