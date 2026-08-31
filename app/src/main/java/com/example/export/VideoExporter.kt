package com.example.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.audio.WavAudioUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

enum class ExportType {
    VIDEO_MP4,
    VOICE_ONLY_WAV
}

sealed class ExportResult {
    data class Success(
        val outputUri: Uri,
        val filePath: String,
        val fileName: String,
        val sizeBytes: Long,
        val exportType: ExportType
    ) : ExportResult()

    data class Error(val message: String) : ExportResult()
}

class VideoExporter(private val context: Context) {

    companion object {
        private const val TAG = "VideoExporter"
        private const val TIMEOUT_US = 5000L
        private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AAC_SAMPLE_RATE = 44100
        private const val AAC_BIT_RATE = 160000
    }

    /**
     * Muxes the original video stream with the isolated vocal audio track (WAV),
     * encoding the vocal audio to AAC and packaging into an MP4 video file.
     * The resulting video has the background music/instruments completely muted.
     */
    suspend fun exportMutedVideo(
        sourceVideoUri: Uri,
        vocalWavFile: File,
        baseFileName: String,
        onProgress: (Float, String) -> Unit
    ): ExportResult = withContext(Dispatchers.IO) {
        var videoExtractor: MediaExtractor? = null
        var audioCodec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null

        val cleanTitle = baseFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40).ifBlank { "video" }
        val finalFileName = "VocalKeep_${cleanTitle}_${System.currentTimeMillis()}.mp4"
        val tempExportFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        try {
            onProgress(0.05f, "جارٍ تجهيز ملف الفيديو الأصلي...")

            videoExtractor = MediaExtractor()
            if (sourceVideoUri.scheme == "content") {
                pfd = context.contentResolver.openFileDescriptor(sourceVideoUri, "r")
                if (pfd != null) {
                    videoExtractor.setDataSource(pfd.fileDescriptor)
                } else {
                    videoExtractor.setDataSource(context, sourceVideoUri, null)
                }
            } else {
                val path = sourceVideoUri.path ?: ""
                val f = File(path)
                if (f.exists()) {
                    videoExtractor.setDataSource(f.absolutePath)
                } else {
                    videoExtractor.setDataSource(context, sourceVideoUri, null)
                }
            }

            // 1. Locate video track in source video
            var sourceVideoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var videoDurationUs = 0L

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    sourceVideoTrackIndex = i
                    videoFormat = format
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        videoDurationUs = format.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }

            if (sourceVideoTrackIndex < 0 || videoFormat == null) {
                return@withContext ExportResult.Error("تعذر العثور على مسار فيديو صالح في الملف المصدر.")
            }

            videoExtractor.selectTrack(sourceVideoTrackIndex)

            // 2. Prepare AAC audio encoder for the isolated vocal WAV
            onProgress(0.15f, "تهيئة مشفر الصوت عالي الأداء...")

            val aacFormat = MediaFormat.createAudioFormat(AAC_MIME, AAC_SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }

            audioCodec = MediaCodec.createEncoderByType(AAC_MIME)
            audioCodec.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec.start()

            // 3. Initialize MediaMuxer
            muxer = MediaMuxer(tempExportFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrackIndex = muxer.addTrack(videoFormat)
            var muxerAudioTrackIndex = -1
            var isMuxerStarted = false

            val audioBufferInfo = MediaCodec.BufferInfo()
            val videoBufferInfo = MediaCodec.BufferInfo()

            // Preallocate reusable video direct buffer
            val maxVideoBufSize = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            val videoDirectBuffer = ByteBuffer.allocateDirect(maxVideoBufSize)

            // Prepare WAV PCM reader (skip 44 bytes header)
            val wavInputStream = BufferedInputStream(FileInputStream(vocalWavFile), 65536)
            wavInputStream.skip(44)

            val pcmChunk = ByteArray(8192)
            var isPcmEof = false
            var totalPcmBytesRead = 0L
            val bytesPerSec = AAC_SAMPLE_RATE * 2L // 44100 * 2 bytes (16-bit mono)

            onProgress(0.25f, "دمج مسار الفيديو والصوت المعزول...")

            var isAudioEncodingDone = false
            var isVideoCopyDone = false

            while (!isAudioEncodingDone || !isVideoCopyDone) {
                // Feed PCM to audio encoder
                if (!isPcmEof) {
                    val inIndex = audioCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = audioCodec.getInputBuffer(inIndex)
                        inputBuffer?.clear()

                        val bytesRead = wavInputStream.read(pcmChunk)
                        if (bytesRead <= 0) {
                            audioCodec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                (totalPcmBytesRead * 1_000_000L / bytesPerSec),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isPcmEof = true
                        } else {
                            inputBuffer?.put(pcmChunk, 0, bytesRead)
                            val presentationTimeUs = totalPcmBytesRead * 1_000_000L / bytesPerSec
                            totalPcmBytesRead += bytesRead
                            audioCodec.queueInputBuffer(inIndex, 0, bytesRead, presentationTimeUs, 0)
                        }
                    }
                }

                // Check audio encoder output
                val outIndex = audioCodec.dequeueOutputBuffer(audioBufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerAudioTrackIndex < 0) {
                            val newAudioFormat = audioCodec.outputFormat
                            muxerAudioTrackIndex = muxer.addTrack(newAudioFormat)
                            muxer.start()
                            isMuxerStarted = true
                        }
                    }
                    outIndex >= 0 -> {
                        val encodedBuffer = audioCodec.getOutputBuffer(outIndex)
                        if (encodedBuffer != null && isMuxerStarted) {
                            if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && audioBufferInfo.size > 0) {
                                encodedBuffer.position(audioBufferInfo.offset)
                                encodedBuffer.limit(audioBufferInfo.offset + audioBufferInfo.size)
                                muxer.writeSampleData(muxerAudioTrackIndex, encodedBuffer, audioBufferInfo)
                            }
                        }
                        audioCodec.releaseOutputBuffer(outIndex, false)

                        if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isAudioEncodingDone = true
                        }
                    }
                }

                // Copy video samples quickly with preallocated buffer
                if (isMuxerStarted && !isVideoCopyDone) {
                    videoDirectBuffer.clear()

                    val sampleSize = videoExtractor.readSampleData(videoDirectBuffer, 0)
                    if (sampleSize < 0) {
                        isVideoCopyDone = true
                    } else {
                        videoBufferInfo.offset = 0
                        videoBufferInfo.size = sampleSize
                        videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        videoBufferInfo.flags = videoExtractor.sampleFlags

                        muxer.writeSampleData(muxerVideoTrackIndex, videoDirectBuffer, videoBufferInfo)
                        videoExtractor.advance()

                        if (videoDurationUs > 0) {
                            val vProg = (videoBufferInfo.presentationTimeUs.toFloat() / videoDurationUs.toFloat()).coerceIn(0f, 1f)
                            val combinedProg = 0.25f + (vProg * 0.65f)
                            onProgress(combinedProg, "جارٍ دمج الفيديو والصوت المعزول (${(vProg * 100).toInt()}%)...")
                        }
                    }
                }
            }

            wavInputStream.close()

            onProgress(0.92f, "جارٍ حفظ الفيديو في مساحة التخزين...")

            try {
                if (isMuxerStarted) {
                    muxer.stop()
                    muxer.release()
                    muxer = null
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Muxer stop warning", ex)
            }

            val savedLocation = saveVideoToStorage(tempExportFile, finalFileName)

            onProgress(1.0f, "تم تصدير وحفظ الفيديو بنجاح!")

            ExportResult.Success(
                outputUri = savedLocation.first,
                filePath = savedLocation.second,
                fileName = finalFileName,
                sizeBytes = tempExportFile.length(),
                exportType = ExportType.VIDEO_MP4
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Video export failed: ${t.message}", t)
            ExportResult.Error("فشل تصدير الفيديو: ${t.localizedMessage ?: t.javaClass.simpleName}")
        } finally {
            try {
                audioCodec?.stop()
                audioCodec?.release()
                videoExtractor?.release()
                pfd?.close()
                if (muxer != null) {
                    try { muxer.release() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup exception", e)
            }
        }
    }

    /**
     * Exports only the isolated human voice audio track (WAV) directly to storage.
     * Extremely fast since it writes the isolated voice file to Music/VocalKeep.
     */
    suspend fun exportVoiceOnly(
        vocalWavFile: File,
        baseFileName: String,
        onProgress: (Float, String) -> Unit
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            onProgress(0.1f, "جارٍ تجهيز ملف الصوت المعزول...")

            if (!vocalWavFile.exists() || vocalWavFile.length() <= 44) {
                return@withContext ExportResult.Error("ملف الصوت البشري المعزول غير متوفر.")
            }

            val cleanTitle = baseFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40).ifBlank { "audio" }
            val finalFileName = "VocalKeep_Voice_${cleanTitle}_${System.currentTimeMillis()}.wav"

            onProgress(0.5f, "جارٍ حفظ الصوت البشري في مساحة التخزين...")

            val savedLocation = saveAudioToStorage(vocalWavFile, finalFileName)

            onProgress(1.0f, "تم حفظ ملف الصوت بنجاح!")

            ExportResult.Success(
                outputUri = savedLocation.first,
                filePath = savedLocation.second,
                fileName = finalFileName,
                sizeBytes = vocalWavFile.length(),
                exportType = ExportType.VOICE_ONLY_WAV
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Voice export failed: ${t.message}", t)
            ExportResult.Error("فشل تصدير الصوت: ${t.localizedMessage ?: t.javaClass.simpleName}")
        }
    }

    private fun MediaFormat.getInteger(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
    }

    private fun saveVideoToStorage(sourceFile: File, fileName: String): Pair<Uri, String> {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VocalKeep")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    BufferedInputStream(FileInputStream(sourceFile), 65536).use { input ->
                        input.copyTo(out)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                val displayPath = "${Environment.DIRECTORY_MOVIES}/VocalKeep/$fileName"
                return Pair(uri, displayPath)
            }
        }

        // Fallback for app external files directory
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val targetDir = File(moviesDir, "VocalKeep").apply { mkdirs() }
        val targetFile = File(targetDir, fileName)

        sourceFile.copyTo(targetFile, overwrite = true)

        val fileUri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
        } catch (e: Exception) {
            Uri.fromFile(targetFile)
        }

        return Pair(fileUri, targetFile.absolutePath)
    }

    private fun saveAudioToStorage(sourceFile: File, fileName: String): Pair<Uri, String> {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/VocalKeep")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    BufferedInputStream(FileInputStream(sourceFile), 65536).use { input ->
                        input.copyTo(out)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                val displayPath = "${Environment.DIRECTORY_MUSIC}/VocalKeep/$fileName"
                return Pair(uri, displayPath)
            }
        }

        // Fallback for app external files directory
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val targetDir = File(musicDir, "VocalKeep").apply { mkdirs() }
        val targetFile = File(targetDir, fileName)

        sourceFile.copyTo(targetFile, overwrite = true)

        val fileUri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
        } catch (e: Exception) {
            Uri.fromFile(targetFile)
        }

        return Pair(fileUri, targetFile.absolutePath)
    }
}
