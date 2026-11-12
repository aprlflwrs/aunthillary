package net.aprlflwrs.aunthillary.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import net.aprlflwrs.aunthillary.core.AntBuffer
import net.aprlflwrs.aunthillary.core.Colony
import net.aprlflwrs.aunthillary.core.Voice
import net.aprlflwrs.aunthillary.core.World
import net.aprlflwrs.aunthillary.trails.DetectorInput
import net.aprlflwrs.aunthillary.trails.TrailDetector
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

/**
 * The trail detector and where its voices go. [deliver] is called on the tick thread.
 */
class TrailHook(val detector: TrailDetector, val deliver: (List<Voice>) -> Unit)

/**
 * Owns the colony. Only this loop ever touches it: browser messages wait in [actions] and are
 * applied at the start of the next tick, so the colony needs no locks.
 *
 * After each tick it hands the browsers whatever is due: ants every tick, pheromone a few
 * times a second, state twice a second, and terrain and params only when they change.
 *
 * With a [trails] hook it also runs the trail detector every [DETECT_EVERY] ticks. The snapshot
 * is taken here, on the tick thread, at a fixed tick; the search itself runs on another thread
 * so a slow search never stalls a tick, and its voices are handed back at the start of a later
 * tick. Nothing the detector produces feeds back into the colony, so this can't affect a replay.
 */
class TickLoop(private val colony: Colony, private val trails: TrailHook? = null) {
    private val log = LoggerFactory.getLogger(TickLoop::class.java)

    /** Browser messages waiting for the next tick. Unlimited, so a WebSocket handler never blocks. */
    val actions = Channel<ClientMessage>(Channel.UNLIMITED)

    private val outboxes = CopyOnWriteArraySet<Outbox>()

    // The latest message of each rarely-sent type, so a browser that connects mid-run gets them at once.
    @Volatile private var lastParams: Outgoing? = null
    @Volatile private var lastTerrain: Outgoing? = null
    @Volatile private var lastState: Outgoing? = null

    private var paused = false
    private var stepsRequested = 0
    private var speed = 1.0

    private val antBuffer = AntBuffer()
    private var sentParamsVersion = -1
    private var sentTerrainVersion = -1
    private var lastTerrainSendTick = Long.MIN_VALUE

    // ---- trail detection ----
    private val detectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Detection(val generation: Int, val voices: List<Voice>)
    private val finished = AtomicReference<Detection?>(null)
    @Volatile private var detecting = false
    /** Bumped by reset and flood, so a search started before them is thrown away. */
    private var generation = 0
    /** The generation the detector last ran in; when it changes, the detector's memory is cleared first. */
    private var detectorGeneration = 0

    fun connect(outbox: Outbox) {
        outboxes.add(outbox)
        lastParams?.let { outbox.offer(Outbox.Slot.PARAMS, it) }
        lastTerrain?.let { outbox.offer(Outbox.Slot.TERRAIN, it) }
        lastState?.let { outbox.offer(Outbox.Slot.STATE, it) }
    }

    fun disconnect(outbox: Outbox) {
        outboxes.remove(outbox)
    }

    /** Runs until cancelled. Call it from a coroutine on a single thread. */
    suspend fun run() {
        log.info("Tick loop started")
        publish(forceAll = true)
        var nextTickAt = System.nanoTime()
        while (currentCoroutineContext().isActive) {
            applyActions()
            if (!paused || stepsRequested > 0) {
                if (stepsRequested > 0) stepsRequested--
                colony.step()
                collectDetection()
                if (trails != null && colony.tick % DETECT_EVERY == 0L) startDetection(trails)
                publish(forceAll = false)
            } else {
                // Paused: still pick up parameter changes so the panel stays in sync.
                publishParamsIfChanged()
            }

            // Schedule against a running target, so ticks don't drift; if we fall far behind
            // (a long GC pause, or the machine is too slow at high speed), skip ahead instead
            // of rushing to catch up.
            val period = (1_000_000_000.0 / (World.TICKS_PER_SECOND * speed)).toLong()
            nextTickAt += period
            val now = System.nanoTime()
            if (now - nextTickAt > 250_000_000L) nextTickAt = now
            val waitNanos = nextTickAt - now
            // When behind, still give way (delay(0) would return at once without suspending).
            if (waitNanos >= 1_000_000) delay(waitNanos / 1_000_000) else yield()
        }
    }

    private fun applyActions() {
        while (true) {
            val action = actions.tryReceive().getOrNull() ?: return
            when (action) {
                is ClientMessage.PlaceFood -> colony.placeFood(action.x, action.y)
                is ClientMessage.SetParam -> colony.params.set(action.key, action.value)
                ClientMessage.Flood -> { colony.flood(); forgetTrails() }
                is ClientMessage.Control -> when (action.action) {
                    "pause" -> paused = true
                    "resume" -> paused = false
                    "step" -> stepsRequested++
                    "reset" -> { colony.reset(); forgetTrails(); publish(forceAll = true) }
                    "speed" -> action.value?.let { speed = it.coerceIn(0.1, 64.0) }
                    else -> log.warn("Unknown control action '{}'", action.action)
                }
            }
        }
    }

    /** Starts a search on a snapshot of this tick, unless one is still running (then this tick is skipped). */
    private fun startDetection(hook: TrailHook) {
        if (detecting) return
        val state = colony.state()
        val displayMax = colony.params["pheromone.displayMax"]?.value ?: 1.0
        val input = DetectorInput.of(colony.tick, colony.pheromone(), colony.terrain(), state.nest, state.food, displayMax)
        val gen = generation
        val clearFirst = gen != detectorGeneration
        detectorGeneration = gen
        detecting = true
        detectorScope.launch {
            try {
                if (clearFirst) hook.detector.reset()
                finished.set(Detection(gen, hook.detector.detect(input)))
            } catch (e: Exception) {
                log.error("Trail detection failed", e)
            } finally {
                detecting = false
            }
        }
    }

    /** Hands the latest finished search's voices to the colony, unless a reset or flood made them stale. */
    private fun collectDetection() {
        val result = finished.getAndSet(null) ?: return
        if (result.generation == generation) trails?.deliver?.invoke(result.voices)
    }

    /** After a reset or flood: drop the voices, ignore any search in progress, and clear the detector's memory. */
    private fun forgetTrails() {
        if (trails == null) return
        generation++
        finished.set(null)
        trails.deliver(emptyList())
    }

    /** Stops the detector's thread pool work. */
    fun close() {
        detectorScope.cancel()
    }

    private fun publish(forceAll: Boolean) {
        val tick = colony.tick

        colony.writeAnts(antBuffer)
        broadcast(Outbox.Slot.ANTS, Outgoing.Binary(Protocol.encodeAnts(tick, antBuffer)))

        if (forceAll || tick % PHEROMONE_EVERY == 0L) {
            val displayMax = colony.params["pheromone.displayMax"]?.value ?: 1.0
            broadcast(Outbox.Slot.PHEROMONE, Outgoing.Binary(Protocol.encodePheromone(tick, colony.pheromone(), displayMax)))
        }

        if (forceAll || tick % STATE_EVERY == 0L) {
            val message = Outgoing.Text(Protocol.encodeState(colony.state()))
            lastState = message
            broadcast(Outbox.Slot.STATE, message)
        }

        val terrainDue = colony.terrainVersion != sentTerrainVersion &&
            tick - lastTerrainSendTick >= World.TICKS_PER_SECOND
        if (forceAll || terrainDue) {
            sentTerrainVersion = colony.terrainVersion
            lastTerrainSendTick = tick
            val message = Outgoing.Binary(Protocol.encodeTerrain(colony.terrain()))
            lastTerrain = message
            broadcast(Outbox.Slot.TERRAIN, message)
        }

        if (forceAll) sentParamsVersion = -1
        publishParamsIfChanged()
    }

    private fun publishParamsIfChanged() {
        if (colony.params.version == sentParamsVersion) return
        sentParamsVersion = colony.params.version
        val message = Outgoing.Text(Protocol.encodeParams(colony.params.specs()))
        lastParams = message
        broadcast(Outbox.Slot.PARAMS, message)
    }

    private fun broadcast(slot: Outbox.Slot, message: Outgoing) {
        for (outbox in outboxes) outbox.offer(slot, message)
    }

    companion object {
        /** 30 / 6 = 5 pheromone frames a second. */
        const val PHEROMONE_EVERY = 6L

        /** 30 / 15 = 2 state messages a second. */
        const val STATE_EVERY = 15L

        /** 30 / 15 = 2 trail detections a second, on snapshots taken at these exact ticks. */
        const val DETECT_EVERY = 15L
    }
}
