package net.aprlflwrs.aunthillary.trails

import kotlin.math.ceil
import kotlin.math.exp

/** Separable Gaussian blur of a square grid, edges clamped. */
internal object Blur {
    /**
     * Blurs [src] into [dst] with a standard deviation of [sigmaCells] cells, using [scratch] for
     * the intermediate pass. A sigma below a tenth of a cell just copies.
     */
    fun gaussian(src: FloatArray, dst: FloatArray, scratch: FloatArray, size: Int, sigmaCells: Double) {
        if (sigmaCells < 0.1) {
            src.copyInto(dst)
            return
        }
        val radius = ceil(3 * sigmaCells).toInt()
        val kernel = FloatArray(2 * radius + 1) { k ->
            val d = (k - radius).toDouble()
            exp(-d * d / (2 * sigmaCells * sigmaCells)).toFloat()
        }
        val total = kernel.sum()
        for (k in kernel.indices) kernel[k] /= total

        // Rows, into scratch.
        for (j in 0 until size) {
            val row = j * size
            for (i in 0 until size) {
                var acc = 0f
                for (k in -radius..radius) {
                    val ii = (i + k).coerceIn(0, size - 1)
                    acc += kernel[k + radius] * src[row + ii]
                }
                scratch[row + i] = acc
            }
        }
        // Columns, into dst.
        for (j in 0 until size) {
            for (i in 0 until size) {
                var acc = 0f
                for (k in -radius..radius) {
                    val jj = (j + k).coerceIn(0, size - 1)
                    acc += kernel[k + radius] * scratch[jj * size + i]
                }
                dst[j * size + i] = acc
            }
        }
    }
}
