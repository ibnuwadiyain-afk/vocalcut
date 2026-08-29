package com.example.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class ExportResult {
    data class Success(val fileUri: Uri, val filePath: String, val durationMs: Long) : ExportResult()
    data class Error(val message: String) : ExportResult()
}

/**
 * Multiplexes the original video stream (losslessly copied) with the newly isolated vocal audio WAV
 * (encoded to standard AAC-LC) into an MP4 file, and exports it to the device's public video storage.
 */
class VideoExportMuxer(private val context: Context) {

    companion object {
        private const val TAG = "VideoExportMuxer"
        private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AAC_BITRATE = 160000 // 160 kbps
        private const val SAMPLE_RATE = 44100
        private const val TIMEOUT_US = 10000L
    }

    /**
     * Exports the processed video with muted instruments / vocal-only audio.
     */
    suspend fun exportMutedMusicVideo(
        videoUri: Uri,
        vocalWavFile: File,
        baseOutputName: String,
        onProgress: (Float) -> Unit
    ): ExportResult = withContext(Dispatchers.IO) {
        val tempExportFile = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}.mp4")
        var videoExtractor: MediaExtractor? = null
        var pfd: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        var aacEncoder: MediaCodec? = null

        try {
            onProgress(0.05f)

            // Step 1: Open Video Extractor to read the video track
            videoExtractor = MediaExtractor()
            if (videoUri.scheme == "content") {
                pfd = context.contentResolver.openFileDescriptor(videoUri, "r")
                if (pfd != null) {
                    videoExtractor.setDataSource(pfd.fileDescriptor)
                } else {
                    videoExtractor.setDataSource(context, videoUri, null)
                }
            } else if (videoUri.scheme == "file" || videoUri.path != null) {
                val path = videoUri.path ?: ""
                val file = File(path)
                if (file.exists()) {
                    videoExtractor.setDataSource(file.absolutePath)
                } else {
                    videoExtractor.setDataSource(context, videoUri, null)
                }
            } else {
                videoExtractor.setDataSource(context, videoUri, null)
            }

            // Find video track
            var videoTrackIndexInExtractor = -1
            var videoFormat: MediaFormat? = null
            var videoDurationUs = 0L

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndexInExtractor = i
                    videoFormat = format
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        videoDurationUs = format.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }

            // If input is an audio-only file (like demo audio), generate a simple black MP4 or audio-only MP4 container
            val hasVideoTrack = (videoTrackIndexInExtractor >= 0 && videoFormat != null)
            if (hasVideoTrack) {
                videoExtractor.selectTrack(videoTrackIndexInExtractor)
            }

            // Setup MediaMuxer
            muxer = MediaMuxer(tempExportFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var muxerVideoTrackIndex = -1
            if (hasVideoTrack && videoFormat != null) {
                muxerVideoTrackIndex = muxer.addTrack(videoFormat)
            }

            // Step 2: Setup AAC Audio Encoder for Vocal Track
            val audioFormat = MediaFormat.createAudioFormat(AAC_MIME, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            aacEncoder = MediaCodec.createEncoderByType(AAC_MIME)
            aacEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aacEncoder.start()

            // Read the WAV header to determine audio data offset & length
            val vocalRaf = RandomAccessFile(vocalWavFile, "r")
            val wavDataSize = (vocalRaf.length() - 44).coerceAtLeast(0L)
            val audioDurationUs = (wavDataSize * 1000000L) / (SAMPLE_RATE * 2L) // 16-bit mono
            val targetDurationUs = if (videoDurationUs > 0) videoDurationUs else audioDurationUs

            vocalRaf.seek(44) // Skip standard 44-byte WAV header

            // Add audio track to Muxer once encoder format is determined or using output format
            var muxerAudioTrackIndex = -1
            var isMuxerStarted = false

            // We need encoder output format to add audio track to muxer. We can pump first buffer or wait for INFO_OUTPUT_FORMAT_CHANGED
            val bufferInfo = MediaCodec.BufferInfo()
            val audioPcmBuffer = ByteArray(4096)
            var audioBytesReadTotal = 0L
            var audioPresentationTimeUs = 0L
            var isAudioInputEOS = false
            var isAudioOutputEOS = false

            // Store encoded audio packets temporarily until muxer is started
            data class EncodedPacket(val data: ByteArray, val info: MediaCodec.BufferInfo)
            val pendingAudioPackets = mutableListOf<EncodedPacket>()

            // Prime the audio encoder until output format is available
            while (!isMuxerStarted && !isAudioOutputEOS) {
                // Feed audio encoder input
                if (!isAudioInputEOS) {
                    val inIdx = aacEncoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inputBuffer = aacEncoder.getInputBuffer(inIdx)
                        inputBuffer?.clear()
                        val bytesRead = vocalRaf.read(audioPcmBuffer)
                        if (bytesRead <= 0) {
                            aacEncoder.queueInputBuffer(inIdx, 0, 0, audioPresentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isAudioInputEOS = true
                        } else {
                            inputBuffer?.put(audioPcmBuffer, 0, bytesRead)
                            val samplesRead = bytesRead / 2 // 16-bit mono
                            aacEncoder.queueInputBuffer(inIdx, 0, bytesRead, audioPresentationTimeUs, 0)
                            audioPresentationTimeUs += (samplesRead * 1000000L) / SAMPLE_RATE
                            audioBytesReadTotal += bytesRead
                        }
                    }
                }

                // Check encoder output
                val outIdx = aacEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = aacEncoder.outputFormat
                    muxerAudioTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    isMuxerStarted = true
                    Log.i(TAG, "Muxer started with videoTrack=$muxerVideoTrackIndex, audioTrack=$muxerAudioTrackIndex")
                } else if (outIdx >= 0) {
                    val outBuf = aacEncoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outBuf.get(bytes)

                        val packetInfo = MediaCodec.BufferInfo().apply {
                            set(0, bytes.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        pendingAudioPackets.add(EncodedPacket(bytes, packetInfo))
                    }
                    aacEncoder.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isAudioOutputEOS = true
                    }
                }
            }

            if (!isMuxerStarted) {
                muxer.start()
                isMuxerStarted = true
            }

            // Write any pending encoded audio packets
            for (packet in pendingAudioPackets) {
                val byteBuffer = ByteBuffer.wrap(packet.data)
                muxer.writeSampleData(muxerAudioTrackIndex, byteBuffer, packet.info)
            }
            pendingAudioPackets.clear()

            onProgress(0.20f)

            // Step 3: Stream remaining audio packets through encoder into Muxer
            while (!isAudioOutputEOS) {
                if (!isAudioInputEOS) {
                    val inIdx = aacEncoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inputBuffer = aacEncoder.getInputBuffer(inIdx)
                        inputBuffer?.clear()
                        val bytesRead = vocalRaf.read(audioPcmBuffer)
                        if (bytesRead <= 0) {
                            aacEncoder.queueInputBuffer(inIdx, 0, 0, audioPresentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isAudioInputEOS = true
                        } else {
                            inputBuffer?.put(audioPcmBuffer, 0, bytesRead)
                            val samplesRead = bytesRead / 2
                            aacEncoder.queueInputBuffer(inIdx, 0, bytesRead, audioPresentationTimeUs, 0)
                            audioPresentationTimeUs += (samplesRead * 1000000L) / SAMPLE_RATE
                            audioBytesReadTotal += bytesRead
                        }
                    }
                }

                val outIdx = aacEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    val outBuf = aacEncoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 && muxerAudioTrackIndex >= 0) {
                        muxer.writeSampleData(muxerAudioTrackIndex, outBuf, bufferInfo)
                    }
                    aacEncoder.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isAudioOutputEOS = true
                    }
                }

                val audioProg = if (wavDataSize > 0) (audioBytesReadTotal.toFloat() / wavDataSize).coerceIn(0f, 1f) else 1f
                onProgress(0.20f + (audioProg * 0.35f))
            }
            vocalRaf.close()

            onProgress(0.60f)

            // Step 4: Losslessly copy Video Frames from Extractor to Muxer
            if (hasVideoTrack && muxerVideoTrackIndex >= 0) {
                val videoBuffer = ByteBuffer.allocateDirect(1024 * 1024) // 1MB buffer for video frames
                val videoBufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    videoBuffer.clear()
                    val sampleSize = videoExtractor.readSampleData(videoBuffer, 0)
                    if (sampleSize < 0) {
                        break
                    }

                    videoBufferInfo.offset = 0
                    videoBufferInfo.size = sampleSize
                    videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
                    videoBufferInfo.flags = videoExtractor.sampleFlags

                    muxer.writeSampleData(muxerVideoTrackIndex, videoBuffer, videoBufferInfo)

                    val videoProg = if (videoDurationUs > 0) {
                        (videoBufferInfo.presentationTimeUs.toFloat() / videoDurationUs).coerceIn(0f, 1f)
                    } else 0.5f

                    onProgress(0.60f + (videoProg * 0.30f))
                    videoExtractor.advance()
                }
            }

            onProgress(0.92f)

            // Finish Muxer & Codecs
            try {
                aacEncoder.stop()
                aacEncoder.release()
                aacEncoder = null
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping encoder: ${e.message}")
            }

            try {
                muxer.stop()
                muxer.release()
                muxer = null
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping muxer: ${e.message}")
            }

            videoExtractor.release()
            videoExtractor = null
            pfd?.close()
            pfd = null

            onProgress(0.95f)

            // Step 5: Save exported video into device public storage (Movies/VocalKeep or MediaStore)
            val cleanTitle = baseOutputName.replace("[^a-zA-Z0-9_\\-\\u0600-\\u06FF]".toRegex(), "_")
            val outputFileName = "VocalOnly_${cleanTitle}_${System.currentTimeMillis()}.mp4"

            val finalUri = saveToPublicStorage(tempExportFile, outputFileName)
            onProgress(1.0f)

            if (finalUri != null) {
                ExportResult.Success(
                    fileUri = finalUri,
                    filePath = outputFileName,
                    durationMs = targetDurationUs / 1000L
                )
            } else {
                ExportResult.Error("فشل حفظ الفيديو في ذاكرة الهاتف.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            ExportResult.Error("خطأ أثناء تصدير الفيديو: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            try {
                videoExtractor?.release()
                muxer?.release()
                aacEncoder?.release()
                pfd?.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun saveToPublicStorage(sourceFile: File, fileName: String): Uri? {
        return try {
            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VocalKeep")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = contentResolver.insert(collection, contentValues)
            if (itemUri != null) {
                contentResolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(itemUri, contentValues, null, null)
                }
                Log.i(TAG, "Successfully exported video to MediaStore: $itemUri")
                itemUri
            } else {
                // Fallback to app-accessible files dir
                val publicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VocalKeep").apply { mkdirs() }
                val targetFile = File(publicDir, fileName)
                sourceFile.copyTo(targetFile, overwrite = true)
                Uri.fromFile(targetFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving to MediaStore: ${e.message}", e)
            // Fallback to internal storage file Uri
            val publicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VocalKeep").apply { mkdirs() }
            val targetFile = File(publicDir, fileName)
            sourceFile.copyTo(targetFile, overwrite = true)
            Uri.fromFile(targetFile)
        }
    }
}
