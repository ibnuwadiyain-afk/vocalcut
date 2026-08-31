package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ultra-Fast Precomputed Radix-2 Fast Fourier Transform
 *
 * Accelerates STFT / iSTFT spectral processing by:
 * 1. Precomputing bit-reversal permutations.
 * 2. Precomputing trigonometric sine/cosine twiddle factor lookup tables.
 * 3. Eliminating runtime trigonometric math calls (`sin`/`cos`) and allocations.
 */
class FastFourierTransform(val n: Int = 2048) {

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of 2, was $n" }
    }

    private val bitRev: IntArray = IntArray(n)
    private val cosTable: FloatArray = FloatArray(n / 2)
    private val sinTable: FloatArray = FloatArray(n / 2)

    init {
        // 1. Precompute bit-reversal indices
        val numBits = 31 - Integer.numberOfLeadingZeros(n)
        for (i in 0 until n) {
            var rev = 0
            var temp = i
            for (b in 0 until numBits) {
                rev = (rev shl 1) or (temp and 1)
                temp = temp shr 1
            }
            bitRev[i] = rev
        }

        // 2. Precompute W_N^k table: exp(-2*pi*i*k/N)
        for (k in 0 until n / 2) {
            val angle = -2.0 * PI * k / n
            cosTable[k] = cos(angle).toFloat()
            sinTable[k] = sin(angle).toFloat()
        }
    }

    /**
     * In-place forward Fast Fourier Transform with precomputed twiddle factors.
     */
    fun fft(real: FloatArray, imag: FloatArray) {
        // 1. Bit-reversal permutation using precomputed table
        for (i in 0 until n) {
            val j = bitRev[i]
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR

                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
        }

        // 2. Cooley-Tukey Radix-2 butterfly passes with precomputed trigonometric tables
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val step = n / len

            var i = 0
            while (i < n) {
                var tableIndex = 0
                for (k in 0 until halfLen) {
                    val wR = cosTable[tableIndex]
                    val wI = sinTable[tableIndex]
                    tableIndex += step

                    val idx2 = i + k + halfLen
                    val idx1 = i + k

                    val r2 = real[idx2]
                    val i2 = imag[idx2]

                    val tR = wR * r2 - wI * i2
                    val tI = wR * i2 + wI * r2

                    val uR = real[idx1]
                    val uI = imag[idx1]

                    real[idx1] = uR + tR
                    imag[idx1] = uI + tI
                    real[idx2] = uR - tR
                    imag[idx2] = uI - tI
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * In-place inverse Fast Fourier Transform (iFFT)
     */
    fun ifft(real: FloatArray, imag: FloatArray) {
        for (i in 0 until n) {
            imag[i] = -imag[i]
        }
        fft(real, imag)
        val scale = 1.0f / n
        for (i in 0 until n) {
            real[i] = real[i] * scale
            imag[i] = -imag[i] * scale
        }
    }
}
