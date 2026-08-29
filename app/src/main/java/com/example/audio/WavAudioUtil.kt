package com.example.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object WavAudioUtil {

    const val SAMPLE_RATE_44K = 44100
    const val SAMPLE_RATE_16K = 16000

    /**
     * Writes standard 44-byte WAV header with given parameters to an OutputStream.
     */
    fun writeWavHeader(
        out: java.io.OutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        sampleRate: Long = SAMPLE_RATE_44K.toLong(),
        channels: Int = 1,
        byteRate: Long = sampleRate * channels * 2
    ) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 2).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte() // 'data' chunk
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }

    /**
     * Updates data length fields in WAV file header via RandomAccessFile.
     */
    fun updateWavHeaderLengths(wavFile: File, totalPcmBytes: Long) {
        val totalDataLen = totalPcmBytes + 36
        RandomAccessFile(wavFile, "rw").use { raf ->
            // RIFF chunk size at offset 4
            raf.seek(4)
            raf.write((totalDataLen and 0xff).toInt())
            raf.write((totalDataLen shr 8 and 0xff).toInt())
            raf.write((totalDataLen shr 16 and 0xff).toInt())
            raf.write((totalDataLen shr 24 and 0xff).toInt())

            // Data chunk size at offset 40
            raf.seek(40)
            raf.write((totalPcmBytes and 0xff).toInt())
            raf.write((totalPcmBytes shr 8 and 0xff).toInt())
            raf.write((totalPcmBytes shr 16 and 0xff).toInt())
            raf.write((totalPcmBytes shr 24 and 0xff).toInt())
        }
    }

    /**
     * Writes 16-bit PCM short array to a standard RIFF/WAV file.
     */
    fun writePcmToWav(
        pcmData: ShortArray,
        outputFile: File,
        sampleRate: Int = SAMPLE_RATE_44K,
        channels: Int = 1
    ) {
        val byteData = ByteArray(pcmData.size * 2)
        val buffer = ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcmData) {
            buffer.putShort(sample)
        }
        writePcmBytesToWav(byteData, outputFile, sampleRate, channels)
    }

    /**
     * Writes raw 16-bit PCM bytes to a WAV file with header.
     */
    fun writePcmBytesToWav(
        pcmBytes: ByteArray,
        outputFile: File,
        sampleRate: Int = SAMPLE_RATE_44K,
        channels: Int = 1
    ) {
        val totalAudioLen = pcmBytes.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * 16 / 8).toLong()

        FileOutputStream(outputFile).use { fos ->
            writeWavHeader(fos, totalAudioLen, totalDataLen, sampleRate.toLong(), channels, byteRate)
            fos.write(pcmBytes)
        }
    }

    /**
     * Reads 16-bit PCM ShortArray from a WAV file.
     */
    fun readWavToShortArray(wavFile: File): ShortArray {
        val length = wavFile.length()
        if (length <= 44) return ShortArray(0)
        val pcmSize = ((length - 44) / 2).toInt()
        val shorts = ShortArray(pcmSize)
        FileInputStream(wavFile).use { fis ->
            fis.skip(44) // Skip header
            val buffer = ByteArray(4096)
            val byteBuf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            var shortIdx = 0
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1 && shortIdx < pcmSize) {
                byteBuf.position(0)
                byteBuf.limit(bytesRead)
                val shortsInChunk = bytesRead / 2
                for (s in 0 until shortsInChunk) {
                    if (shortIdx < pcmSize) {
                        shorts[shortIdx++] = byteBuf.short
                    }
                }
            }
        }
        return shorts
    }
}

