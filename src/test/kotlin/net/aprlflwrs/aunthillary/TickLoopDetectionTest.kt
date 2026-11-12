package net.aprlflwrs.aunthillary

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import net.aprlflwrs.aunthillary.core.Voice
import net.aprlflwrs.aunthillary.server.ClientMessage
import net.aprlflwrs.aunthillary.server.FakeColony
import net.aprlflwrs.aunthillary.server.TickLoop
import net.aprlflwrs.aunthillary.server.TrailHook
import net.aprlflwrs.aunthillary.trails.TrailDetector
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The tick loop's trail-detection plumbing: snapshots taken on the tick thread, the search run
 * elsewhere, and its voices delivered back. The fake colony's pheromone has real-looking trails,
 * so the real detector should find voices in it without the simulation.
 */
class TickLoopDetectionTest {
    @Test
    fun `detected voices reach the colony, and a flood clears them`() = runBlocking {
        val colony = FakeColony(1)
        val delivered = mutableListOf<List<Voice>>()
        val loop = TickLoop(colony, TrailHook(TrailDetector(colony.params)) { delivered += it })
        loop.actions.trySend(ClientMessage.Control("speed", 32.0))
        val job = launch { loop.run() }

        // Wait (in real time) until a detection with voices has come back.
        val found = withTimeoutOrNull(15_000) {
            while (delivered.none { it.isNotEmpty() }) yield()
            true
        }
        assertTrue(found == true, "no voices were delivered; deliveries: ${delivered.map { it.size }}")

        loop.actions.trySend(ClientMessage.Flood)
        val cleared = withTimeoutOrNull(5_000) {
            val before = delivered.size
            while (delivered.drop(before).none { it.isEmpty() }) yield()
            true
        }
        assertTrue(cleared == true, "a flood should deliver an empty voice list")

        job.cancel()
        loop.close()
    }
}
