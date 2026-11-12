package net.aprlflwrs.aunthillary.trails

import net.aprlflwrs.aunthillary.core.Params
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures one detection on a 512² grid. Gradle hides test output, so the numbers are written to
 * build/trail-timing.txt. The assertion is deliberately loose (a slow CI machine must not fail
 * `check`); the plan only needs a detection to finish well inside the 15-tick (half-second)
 * interval it runs at.
 */
class TrailDetectorTimingTest {
    @Test
    fun `a 512 squared detection takes well under half a second`() {
        val grid = SyntheticGrid(512)
            .trail(256.0 to 256.0, 330.0 to 260.0, 420.0 to 340.0)
            .trail(330.0 to 260.0, 420.0 to 180.0)
            .trail(256.0 to 256.0, 120.0 to 400.0)
            .trail(256.0 to 256.0, 100.0 to 100.0)
            .block(300.0, 280.0, 320.0, 300.0)
            .noise(7)
        val piles = listOf(pile(1, 420.0, 340.0), pile(2, 420.0, 180.0), pile(3, 120.0, 400.0), pile(4, 100.0, 100.0))
        val detector = TrailDetector(Params())
        val input = grid.input(0, piles)

        val coldStart = System.nanoTime()
        detector.detect(input)
        val cold = (System.nanoTime() - coldStart) / 1e6
        repeat(10) { detector.detect(input) } // JIT warm-up
        val search = ArrayList<Double>()
        val detect = ArrayList<Double>()
        repeat(20) {
            detector.detect(input)
            search += detector.lastSearchNanos / 1e6
            detect += detector.lastDetectNanos / 1e6
        }
        val report = "512² trail detection (ms), 4 voices: first call %.1f; warm median: search %.1f, whole detect %.1f; warm min search %.1f, max search %.1f\n"
            .format(cold, search.sorted()[search.size / 2], detect.sorted()[detect.size / 2], search.min(), search.max())
        File("build").mkdirs()
        File("build/trail-timing.txt").writeText(report)
        println(report)
        assertTrue(detect.sorted()[detect.size / 2] < 500.0, report)
    }
}
