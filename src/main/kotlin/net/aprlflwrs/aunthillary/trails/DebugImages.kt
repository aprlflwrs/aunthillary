package net.aprlflwrs.aunthillary.trails

import net.aprlflwrs.aunthillary.core.FoodPile
import net.aprlflwrs.aunthillary.core.Voice
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Writes one PNG per detector stage, named `<tick>-<stage>.png` (tick zero-padded so they sort):
 *
 * - `threshold`: cells at or above the bar in white, weaker pheromone in dim gray (square-root
 *   scaled, like the pheromone frame), blocked cells dark red, the hub dark blue.
 * - `cost`: the search's cost from the hub's edge, bright near the hub and darker farther out (log
 *   scale), unreached cells dark red, the search's sources blue.
 * - `routes`: every traced route over the dimmed pheromone, green if accepted and red if
 *   rejected; live piles yellow, ghosts magenta.
 * - `voices`: the smoothed voice curves in their slot's color over the dimmed pheromone, with a
 *   dot on every point.
 *
 * North is up (the grid's first row is the image's bottom row). Grids smaller than 512 are scaled
 * up by a whole factor so the images stay easy to look at. Pixels are set directly rather than
 * through Graphics2D, so nothing initializes the AWT toolkit.
 */
internal object DebugImages {
    class Route(val foodId: Int, val cells: IntArray, val accepted: Boolean)

    class Frame(
        val tick: Long,
        val size: Int,
        val cell: Double,
        val strength: FloatArray,
        val impassable: BooleanArray,
        val hub: BooleanArray,
        val sources: IntArray,
        val threshold: Double,
        val dist: DoubleArray,
        val routes: List<Route>,
        val voices: List<Voice>,
        val piles: List<FoodPile>,
        val ghosts: List<FoodPile>,
    )

    /** Slot colors 0–3. Only for these images; the client picks its own. */
    private val SLOT_COLORS = intArrayOf(0xff7f0e, 0x1f9bff, 0x2ecc40, 0xe040fb)

    fun write(dir: File, f: Frame) {
        dir.mkdirs()
        val prefix = "%08d".format(f.tick)
        ImageIO.write(threshold(f), "png", File(dir, "$prefix-threshold.png"))
        ImageIO.write(cost(f), "png", File(dir, "$prefix-cost.png"))
        ImageIO.write(routes(f), "png", File(dir, "$prefix-routes.png"))
        ImageIO.write(voices(f), "png", File(dir, "$prefix-voices.png"))
    }

    private fun threshold(f: Frame) = canvas(f) { c ->
        when {
            f.impassable[c] -> 0x6a1010
            f.hub[c] -> 0x10206a
            f.strength[c] >= f.threshold -> 0xffffff
            else -> gray((sqrt(f.strength[c].toDouble()) * 140).roundToInt())
        }
    }

    private fun cost(f: Frame): BufferedImage {
        var maxLog = 0.0
        for (d in f.dist) if (d.isFinite()) maxLog = max(maxLog, ln(1 + d))
        val img = canvas(f) { c ->
            val d = f.dist[c]
            when {
                f.hub[c] -> 0x10206a
                d.isInfinite() -> 0x6a1010
                else -> gray((255 * (1 - ln(1 + d) / max(maxLog, 1e-9))).roundToInt())
            }
        }
        val p = Painter(img, f)
        for (s in f.sources) p.cell(s, 0x3080ff)
        return img
    }

    private fun routes(f: Frame): BufferedImage {
        val img = dimmed(f)
        val p = Painter(img, f)
        for (r in f.routes) for (c in r.cells) p.cell(c, if (r.accepted) 0x30e030 else 0xe03030)
        for (pile in f.piles) p.dot(pile.x, pile.y, 0xffe000)
        for (pile in f.ghosts) p.dot(pile.x, pile.y, 0xff40ff)
        return img
    }

    private fun voices(f: Frame): BufferedImage {
        val img = dimmed(f)
        val p = Painter(img, f)
        for (v in f.voices) {
            val color = SLOT_COLORS[v.slot % SLOT_COLORS.size]
            for (k in 1 until v.points.size) {
                val a = v.points[k - 1]
                val b = v.points[k]
                p.line(a[0], a[1], b[0], b[1], color)
            }
            for (pt in v.points) p.dot(pt[0], pt[1], color, radius = 1)
        }
        return img
    }

    private fun dimmed(f: Frame) = canvas(f) { c ->
        when {
            f.impassable[c] -> 0x401010
            f.hub[c] -> 0x101840
            else -> gray((sqrt(f.strength[c].toDouble()) * 90).roundToInt())
        }
    }

    private fun gray(v: Int): Int {
        val g = v.coerceIn(0, 255)
        return (g shl 16) or (g shl 8) or g
    }

    private fun scaleOf(size: Int) = max(1, 512 / size)

    /** An image with one block of pixels per cell, colored by [color] of the cell's index. */
    private inline fun canvas(f: Frame, color: (Int) -> Int): BufferedImage {
        val s = scaleOf(f.size)
        val w = f.size * s
        val img = BufferedImage(w, w, BufferedImage.TYPE_INT_RGB)
        for (j in 0 until f.size) for (i in 0 until f.size) {
            val rgb = color(j * f.size + i)
            val py = (f.size - 1 - j) * s
            for (dy in 0 until s) for (dx in 0 until s) img.setRGB(i * s + dx, py + dy, rgb)
        }
        return img
    }

    /** Draws in world coordinates onto an image made by [canvas]. */
    private class Painter(val img: BufferedImage, val f: Frame) {
        val s = scaleOf(f.size)
        val pixelsPerUnit = s / f.cell

        fun cell(c: Int, rgb: Int) {
            val i = c % f.size
            val j = c / f.size
            val py = (f.size - 1 - j) * s
            for (dy in 0 until s) for (dx in 0 until s) img.setRGB(i * s + dx, py + dy, rgb)
        }

        private fun px(x: Double) = (x * pixelsPerUnit).toInt()
        private fun py(y: Double) = img.height - 1 - (y * pixelsPerUnit).toInt()

        private fun plot(x: Int, y: Int, rgb: Int) {
            if (x in 0 until img.width && y in 0 until img.height) img.setRGB(x, y, rgb)
        }

        fun dot(x: Double, y: Double, rgb: Int, radius: Int = 3) {
            val cx = px(x)
            val cy = py(y)
            for (dy in -radius..radius) for (dx in -radius..radius) plot(cx + dx, cy + dy, rgb)
        }

        /** Bresenham's line between two world positions. */
        fun line(x0: Double, y0: Double, x1: Double, y1: Double, rgb: Int) {
            var x = px(x0)
            var y = py(y0)
            val xe = px(x1)
            val ye = py(y1)
            val dx = abs(xe - x)
            val dy = -abs(ye - y)
            val sx = if (x < xe) 1 else -1
            val sy = if (y < ye) 1 else -1
            var err = dx + dy
            while (true) {
                plot(x, y, rgb)
                if (x == xe && y == ye) break
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; x += sx }
                if (e2 <= dx) { err += dx; y += sy }
            }
        }
    }
}
