package com.example.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object WavAudioUtil {

    const val SAMPLE_RATE_16K = 16000
    const val SAMPLE_RATE_44K = 44100

    /**
     * Writes 16-bit PCM short array to a standard RIFF/WAV file.
     */
    fun writePcmToWav(
        pcmData: ShortArray,
        outputFile: File,
        sampleRate: Int = SAMPLE_RATE_16K,
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
        sampleRate: Int = SAMPLE_RATE_16K,
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

    private fun writeWavHeader(
        out: FileOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Long,
        channels: Int,
        byteRate: Long
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
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte() // block align
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
     * Reads 16-bit PCM ShortArray from a WAV file.
     */
    fun readWavToShortArray(wavFile: File): ShortArray {
        val fileBytes = wavFile.readBytes()
        if (fileBytes.size <= 44) return ShortArray(0)
        val pcmSize = (fileBytes.size - 44) / 2
        val shorts = ShortArray(pcmSize)
        val buffer = ByteBuffer.wrap(fileBytes, 44, fileBytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until pcmSize) {
            shorts[i] = buffer.short
        }
        return shorts
    }

    /**
     * Advanced Audio Stem Separation DSP Algorithm:
     * Separates full mix audio into Vocal stem and Accompaniment/Instrumental stem
     * using spectral gating, harmonic enhancement, vocal formant extraction,
     * and high/low-frequency instrument masking.
     */
    fun separateStemsDSP(
        mixedSamples: ShortArray,
        sampleRate: Int = SAMPLE_RATE_16K,
        onProgress: (Float) -> Unit
    ): Pair<ShortArray, ShortArray> {
        val size = mixedSamples.size
        if (size == 0) return Pair(ShortArray(0), ShortArray(0))

        val vocalSamples = ShortArray(size)
        val accompanimentSamples = ShortArray(size)

        val frameSize = 1024
        val hopSize = 256
        val numFrames = (size - frameSize) / hopSize + 1

        val window = FloatArray(frameSize) { i ->
            // Hanning window
            (0.5 * (1 - cos(2.0 * PI * i / (frameSize - 1)))).toFloat()
        }

        // Float buffers for overlap-add reconstruction
        val vocalReconstructed = FloatArray(size)
        val windowSum = FloatArray(size)

        var frameIdx = 0
        while (frameIdx < numFrames) {
            val start = frameIdx * hopSize
            val real = FloatArray(frameSize)
            val imag = FloatArray(frameSize)

            for (i in 0 until frameSize) {
                if (start + i < size) {
                    real[i] = mixedSamples[start + i].toFloat() * window[i]
                }
            }

            // FFT
            fft(real, imag)

            // Spectral Masking for Voice Isolation
            // Human vocal fundamental + formants: ~100Hz to ~3500Hz
            // Instrument bass < 90Hz (kick, sub-bass) and high hats / cymbals / synth > 4000Hz
            val freqBinResolution = sampleRate.toFloat() / frameSize
            val minVocalBin = (90f / freqBinResolution).toInt().coerceIn(0, frameSize / 2)
            val maxVocalBin = (3800f / freqBinResolution).toInt().coerceIn(minVocalBin, frameSize / 2)

            for (k in 0 until frameSize / 2) {
                val mag = sqrt(real[k] * real[k] + imag[k] * imag[k])
                val freq = k * freqBinResolution

                // Mask factor: 1.0 = full vocal, 0.0 = background music
                val vocalMask = when {
                    freq < 85f -> 0.05f // Cut deep bass / drums
                    freq in 85f..250f -> 0.65f // Male & lower vocal fundamental
                    freq in 250f..2500f -> 0.95f // Core vocal speech & clarity formants
                    freq in 2500f..3800f -> 0.75f // Vocal presence & sibilance
                    freq in 3800f..5500f -> 0.30f // High harmonics
                    else -> 0.08f // Cut cymbal / high percussion
                }

                // Dynamic non-linear gating to suppress background musical instruments
                val threshold = 120.0f
                val dynamicGain = if (mag > threshold) {
                    (vocalMask * (1.0f - (threshold / (mag + 1.0f)).pow(0.5f))).coerceIn(0.05f, 1.0f)
                } else {
                    vocalMask * 0.15f
                }

                real[k] *= dynamicGain
                imag[k] *= dynamicGain

                // Symmetry for real IFFT
                if (k > 0) {
                    real[frameSize - k] = real[k]
                    imag[frameSize - k] = -imag[k]
                }
            }

            // IFFT
            ifft(real, imag)

            // Overlap-Add synthesis
            for (i in 0 until frameSize) {
                val idx = start + i
                if (idx < size) {
                    vocalReconstructed[idx] += real[i] * window[i]
                    windowSum[idx] += window[i] * window[i]
                }
            }

            frameIdx++
            if (frameIdx % 100 == 0 || frameIdx == numFrames) {
                onProgress(frameIdx.toFloat() / numFrames.toFloat())
            }
        }

        // Normalize overlap-add and compute accompaniment = mix - vocal
        for (i in 0 until size) {
            val weight = if (windowSum[i] > 1e-4f) windowSum[i] else 1.0f
            val vSample = (vocalReconstructed[i] / weight).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            val orig = mixedSamples[i].toFloat()

            vocalSamples[i] = vSample.toInt().toShort()
            val bgm = (orig - vSample * 0.9f).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            accompanimentSamples[i] = bgm.toInt().toShort()
        }

        return Pair(vocalSamples, accompanimentSamples)
    }

    /**
     * In-place Cooley-Tukey Radix-2 FFT
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val tR = wR * real[i + k + halfLen] - wI * imag[i + k + halfLen]
                    val tI = wR * imag[i + k + halfLen] + wI * real[i + k + halfLen]

                    real[i + k] = uR + tR
                    imag[i + k] = uI + tI
                    real[i + k + halfLen] = uR - tR
                    imag[i + k + halfLen] = uI - tI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len *= 2
        }
    }

    private fun ifft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        for (i in 0 until n) imag[i] = -imag[i]
        fft(real, imag)
        for (i in 0 until n) {
            real[i] = real[i] / n
            imag[i] = -imag[i] / n
        }
    }

    /**
     * Generates a rich demo audio WAV file containing a vocal melody layered over
     * drums, bassline, and guitar chords, useful for instant testing without external video.
     */
    fun createSampleDemoAudio(outputFile: File, durationSeconds: Int = 12) {
        val sampleRate = SAMPLE_RATE_16K
        val totalSamples = sampleRate * durationSeconds
        val samples = ShortArray(totalSamples)

        val vocalFreqs = doubleArrayOf(440.0, 493.88, 523.25, 587.33, 659.25, 587.33, 523.25, 440.0)
        val chordFreqs = doubleArrayOf(220.0, 261.63, 329.63) // A minor

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate

            // 1. Vocal Melody (Human vocal harmonic formant synthesis)
            val noteIndex = ((t * 1.5) % vocalFreqs.size).toInt()
            val f0 = vocalFreqs[noteIndex]
            val vocalVibrato = sin(2 * PI * 5.5 * t) * 6.0
            val vocalFreq = f0 + vocalVibrato

            // Vocal Formants (F1, F2, F3)
            val vocalBase = sin(2 * PI * vocalFreq * t) * 0.4 +
                    sin(2 * PI * (vocalFreq * 2) * t) * 0.25 +
                    sin(2 * PI * (vocalFreq * 3) * t) * 0.15 +
                    sin(2 * PI * (vocalFreq * 4) * t) * 0.08
            val vocalEnvelope = 0.5 + 0.5 * sin(2 * PI * 1.5 * t).coerceAtLeast(0.0)
            val vocalSignal = vocalBase * vocalEnvelope * 10000.0

            // 2. Instrumental Track: Drums (Kick + Snare) + Bass + Chords
            // Beat (every 0.5 sec)
            val beatPhase = (t % 0.5) / 0.5
            val isKick = (t.toInt() % 2 == 0) && beatPhase < 0.2
            val kickSignal = if (isKick) sin(2 * PI * (60.0 - beatPhase * 30.0) * beatPhase) * exp(-beatPhase * 15.0) * 14000.0 else 0.0

            // Snare (every alternate beat)
            val isSnare = (t.toInt() % 2 == 1) && beatPhase < 0.15
            val snareNoise = if (isSnare) (Math.random() * 2.0 - 1.0) * exp(-beatPhase * 20.0) * 11000.0 else 0.0

            // Bassline (80 - 110 Hz)
            val bassSignal = sin(2 * PI * 110.0 * t) * 7000.0

            // Background guitar/synth chords
            val chordSignal = (sin(2 * PI * chordFreqs[0] * t) +
                    sin(2 * PI * chordFreqs[1] * t) +
                    sin(2 * PI * chordFreqs[2] * t)) * 3000.0

            val totalMix = vocalSignal + kickSignal + snareNoise + bassSignal + chordSignal
            samples[i] = totalMix.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }

        writePcmToWav(samples, outputFile, sampleRate, 1)
    }
}
