package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.Action
import net.aprlflwrs.aunthillary.core.ActionLog
import net.aprlflwrs.aunthillary.core.AntColony
import net.aprlflwrs.aunthillary.core.HeadingDistribution
import net.aprlflwrs.aunthillary.core.LoggedAction
import net.aprlflwrs.aunthillary.core.Replayer
import net.aprlflwrs.aunthillary.core.TrailMetrics
import net.aprlflwrs.aunthillary.core.Voice
import net.aprlflwrs.aunthillary.core.World
import net.aprlflwrs.aunthillary.trails.DetectorInput
import net.aprlflwrs.aunthillary.trails.TrailDetector
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Runs the colony without a browser, as fast as the CPU allows, and writes what it did:
 * pheromone images and a metrics CSV (see [usage]). It has two modes:
 *
 *  - **run** (the default): one colony, from a seed and a script of actions (`--food`, `--set`,
 *    or a whole recorded `--replay` log).
 *  - **baseline**: the comparison from the plan's Timeline & Risks. The same script twice at
 *    the same fixed population, once as the colony and once with trail-following off, with
 *    both runs' metrics written side by side.
 *  - **lesion**: the lesion test from the plan. A colony learns where food is, the food is
 *    taken away, and after the trail has faded new food is placed in the same direction.
 *    The run is done twice from the same seed, identical up to the removal; in the second the
 *    heading distribution is flattened at that moment. No ant differs between the two then;
 *    any difference in how fast the colony brings the new food home is the symbol's.
 *
 * Run it with `./gradlew runHeadless --args="..."`.
 */
fun main(args: Array<String>) {
    val options = try {
        HeadlessOptions.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(usage)
        exitProcess(2)
    }
    if (options.help) { println(usage); return }
    when {
        options.lesion -> Lesion(options).run()
        options.baseline != null -> Baseline(options).run()
        else -> HeadlessRun(options).run()
    }
}

private val usage = """
    Usage: runHeadless [options]
      --seed N             random seed (default 1)
      --duration S         simulated seconds to run (default 600)
      --grid N             pheromone grid cells per side (default 512)
      --food X,Y[@S]       place a pile at (X, Y) at S seconds (default 0); repeatable
      --set KEY=VALUE      set a parameter before starting (then the colony resets); repeatable
      --replay FILE        replay a recorded action log instead of --food/--set
      --out DIR            output directory (default build/headless)
      --csv-every S        seconds between metrics rows (default 1)
      --png-every S        seconds between pheromone images, 0 for none (default 30)
      --link-threshold D   food density counted as trail when testing links (default ${TRAIL_THRESHOLD})
      --no-detector        don't run the trail detector (faster; the voice columns stay empty)
      --profile N          hold the population at N ants (fixed-population mode) to profile the tick
      --swing-window S     seconds of history behind the population swing columns (default 900)
      --baseline N         run the script twice at a fixed population of N, as the colony and with
                           trail-following off; writes DIR/colony and DIR/baseline
      --lesion             run the lesion test instead (options below)
      --seeds A,B,...      lesion: seeds to run (default 1,2,3)
      --lesion-build S     lesion: seconds with food before it is removed (default 300)
      --lesion-wait S      lesion: seconds after removal before new food appears (default 120)
      --lesion-deliveries K  lesion: deliveries from the new pile that count as found (default 100)
      --lesion-timeout S   lesion: give up measuring after this many seconds (default 300)
      --lesion-angle A     lesion: direction of the food from the nest, radians (default 0.785)
      --lesion-distance D  lesion: distance of the food from the nest (default 150)
      --no-lesion-sustain  lesion: no second pile opposite to keep the colony fed through the wait
      --lesion-population N  lesion: hold the population at N, 0 for the free economy (default 10000)
""".trimIndent()

/** Food pheromone density that counts as trail for the link metric (and in the tests). */
const val TRAIL_THRESHOLD = 0.05

data class HeadlessOptions(
    val help: Boolean = false,
    val seed: Long = 1,
    val duration: Double = 600.0,
    val grid: Int = 512,
    val food: List<Triple<Double, Double, Double>> = emptyList(),
    val set: List<Pair<String, Double>> = emptyList(),
    val replay: File? = null,
    val out: File = File("build/headless"),
    val csvEvery: Double = 1.0,
    val pngEvery: Double = 30.0,
    val linkThreshold: Double = TRAIL_THRESHOLD,
    val profileAnts: Int? = null,
    val detector: Boolean = true,
    val swingWindow: Double = 900.0,
    val baseline: Int? = null,
    val lesion: Boolean = false,
    val seeds: List<Long> = listOf(1, 2, 3),
    val lesionBuild: Double = 300.0,
    val lesionWait: Double = 120.0,
    val lesionDeliveries: Int = 100,
    val lesionTimeout: Double = 300.0,
    val lesionAngle: Double = PI / 4,
    val lesionDistance: Double = 150.0,
    val lesionSustain: Boolean = true,
    val lesionPopulation: Int = 10_000,
) {
    companion object {
        fun parse(args: Array<String>): HeadlessOptions {
            var o = HeadlessOptions()
            var i = 0
            fun value(): String {
                require(i + 1 < args.size) { "Missing value after ${args[i]}" }
                return args[++i]
            }
            fun num(s: String) = s.toDoubleOrNull() ?: throw IllegalArgumentException("Not a number: $s")
            while (i < args.size) {
                o = when (val a = args[i]) {
                    "--help", "-h" -> o.copy(help = true)
                    "--seed" -> o.copy(seed = num(value()).toLong())
                    "--duration" -> o.copy(duration = num(value()))
                    "--grid" -> o.copy(grid = num(value()).toInt())
                    "--food" -> {
                        val v = value()
                        val (pos, at) = if ('@' in v) v.substringBefore('@') to num(v.substringAfter('@')) else v to 0.0
                        val xy = pos.split(',')
                        require(xy.size == 2) { "--food takes X,Y[@S], got $v" }
                        o.copy(food = o.food + Triple(num(xy[0]), num(xy[1]), at))
                    }
                    "--set" -> {
                        val v = value()
                        require('=' in v) { "--set takes KEY=VALUE, got $v" }
                        o.copy(set = o.set + (v.substringBefore('=') to num(v.substringAfter('='))))
                    }
                    "--replay" -> o.copy(replay = File(value()))
                    "--out" -> o.copy(out = File(value()))
                    "--csv-every" -> o.copy(csvEvery = num(value()))
                    "--png-every" -> o.copy(pngEvery = num(value()))
                    "--link-threshold" -> o.copy(linkThreshold = num(value()))
                    "--profile" -> o.copy(profileAnts = num(value()).toInt())
                    "--no-detector" -> o.copy(detector = false)
                    "--swing-window" -> o.copy(swingWindow = num(value()))
                    "--baseline" -> o.copy(baseline = num(value()).toInt())
                    "--lesion" -> o.copy(lesion = true)
                    "--seeds" -> o.copy(seeds = value().split(',').map { num(it).toLong() })
                    "--lesion-build" -> o.copy(lesionBuild = num(value()))
                    "--lesion-wait" -> o.copy(lesionWait = num(value()))
                    "--lesion-deliveries" -> o.copy(lesionDeliveries = num(value()).toInt())
                    "--lesion-timeout" -> o.copy(lesionTimeout = num(value()))
                    "--lesion-angle" -> o.copy(lesionAngle = num(value()))
                    "--lesion-distance" -> o.copy(lesionDistance = num(value()))
                    "--no-lesion-sustain" -> o.copy(lesionSustain = false)
                    "--lesion-population" -> o.copy(lesionPopulation = num(value()).toInt())
                    else -> throw IllegalArgumentException("Unknown option $a")
                }
                i++
            }
            return o
        }
    }

    /** The script for a normal run: settings at tick 0 (then a reset, so start-up settings apply), then the food. */
    fun script(): List<LoggedAction> {
        replay?.let { return ActionLog.read(it) }
        val actions = mutableListOf<LoggedAction>()
        val settings = set.toMutableList()
        profileAnts?.let { n ->
            // The population stays at n for the whole run, and the piles never run out.
            settings += listOf("nest.initialAnts" to n.toDouble(), "nest.fixedPopulation" to n.toDouble(),
                "food.pileAmount" to 500_000.0)
        }
        for ((k, v) in settings) actions += LoggedAction(0, Action.SetParam(k, v))
        if (settings.isNotEmpty()) actions += LoggedAction(0, Action.Reset)
        for ((x, y, at) in food.sortedBy { it.third }) {
            actions += LoggedAction(seconds(at), Action.PlaceFood(x, y))
        }
        return actions
    }
}

fun seconds(s: Double): Long = Math.round(s * World.TICKS_PER_SECOND)

/** One headless run: replays the script, writing metrics and images as it goes. */
class HeadlessRun(private val o: HeadlessOptions) {
    /** What a finished run hands back, for the baseline comparison. */
    class Summary(
        val seconds: Int, val linkedAny: Int, val linkedTwo: Int, val delivered: Long, val swing: PopulationSwing,
        val voices: VoiceScore?, val starved: Long, val oldAge: Long,
    )

    fun run(): Summary {
        o.out.mkdirs()
        val colony = AntColony(o.seed, gridSize = o.grid)
        // Built before the replay starts: it declares its own parameters in the colony's
        // registry, so a logged change to one of them can be replayed.
        val voices = if (o.detector) HeadlessDetection(colony) else null
        val replayer = Replayer(colony, o.script())
        val steps = seconds(o.duration)
        val csvEvery = seconds(o.csvEvery).coerceAtLeast(1)
        val pngEvery = seconds(o.pngEvery)
        val metrics = MetricsWriter(File(o.out, "metrics.csv"), o.linkThreshold, o.swingWindow, voices?.score)
        val started = System.nanoTime()
        metrics.write(colony)
        var stepsDone = 0L
        replayer.run(steps) {
            stepsDone++
            voices?.afterStep()
            if (stepsDone % csvEvery == 0L) metrics.write(colony)
            if (pngEvery > 0 && stepsDone % pngEvery == 0L) {
                writePheromonePng(colony, File(o.out, "pheromone_%06d.png".format(stepsDone)))
            }
        }
        metrics.close()
        colony.log.write(File(o.out, "actions.log"))
        val wall = (System.nanoTime() - started) / 1e9
        val total = colony.profile
        val n = total.ticks.coerceAtLeast(1).toDouble()
        println("Simulated %.0f s in %.1f s (%.1f× real time). Final population %d, store %.0f."
            .format(Locale.ROOT, o.duration, wall, o.duration / wall, colony.population, colony.store))
        println("Average ms per tick: ants %.3f, pheromone %.3f, other %.3f"
            .format(Locale.ROOT, total.antsNanos / n / 1e6, total.pheromoneNanos / n / 1e6, total.otherNanos / n / 1e6))
        println("Wrote ${o.out}/metrics.csv, ${o.out}/actions.log" + if (pngEvery > 0) " and pheromone images" else "")
        voices?.score?.let { println("Voices: " + it.describe()) }
        if (colony.terrain.gateAngles.isNotEmpty()) {
            // So an experiment can place food in line with a gate of the nest ring.
            println("Ring gates (degrees): " + colony.terrain.gateAngles.joinToString(" ") {
                "%.1f".format(Locale.ROOT, Math.toDegrees(HeadingDistribution.wrap(it)))
            })
        }
        return Summary(metrics.rows, metrics.linkedAnyRows, metrics.linkedTwoRows, colony.totalDelivered, metrics.swing,
            voices?.score, colony.totalStarved, colony.totalDied - colony.totalStarved)
    }
}

/**
 * The trail detector, run in step with the ticks: every [DETECT_EVERY] ticks it takes a
 * snapshot of the colony, finds the voices, and hands them to `colony.voices`, the same way
 * the server's tick loop does (see `TickLoop.startDetection`), except that it waits for the
 * result instead of running it on another coroutine, so a fast-forwarded run never outpaces
 * it and the result is deterministic. After a reset or flood it clears the detector's memory
 * and the voices, as the server does. Nothing it produces feeds back into the colony.
 */
class HeadlessDetection(private val colony: AntColony) {
    private val detector = TrailDetector(colony.params)
    val score = VoiceScore()
    private var seenActions = 0

    fun afterStep() {
        // A reset or flood since the last step makes the detector's memory stale.
        val actions = colony.log.actions
        while (seenActions < actions.size) {
            val a = actions[seenActions++].action
            if (a == Action.Reset || a == Action.Flood) { detector.reset(); colony.voices = emptyList() }
        }
        if (colony.tick % DETECT_EVERY != 0L) return
        val state = colony.state()
        val input = DetectorInput.of(colony.tick, colony.pheromone(), colony.terrain(), state.nest, state.food, colony.p.displayMax.value)
        colony.voices = detector.detect(input)
        score.add(colony.voices)
    }

    companion object {
        /** Twice a second, as on the server. */
        const val DETECT_EVERY = 15L
    }
}

/**
 * The comparison's three numbers (see Timeline & Risks in the plan), counted over every
 * detection: the share of detections with at least one voice, the share with at least two,
 * and, over the detections with at least two, the average length of the opening that pairs of
 * voices share. That length is the matching prefix of the two voices' point lists, in world
 * units: points at the same index closer than [MATCH] count as matching, and the length is
 * the distance walked along the first voice through the last matching point.
 */
class VoiceScore {
    var detections = 0; private set
    var withOne = 0; private set
    var withTwo = 0; private set
    private var sharedSum = 0.0
    /** The latest detection's mean shared opening over its pairs, or NaN with fewer than two voices. */
    var lastShared = Double.NaN; private set
    var lastCount = 0; private set

    fun add(voices: List<Voice>) {
        detections++
        lastCount = voices.size
        if (voices.isNotEmpty()) withOne++
        lastShared = Double.NaN
        if (voices.size >= 2) {
            withTwo++
            var sum = 0.0; var pairs = 0
            for (a in voices.indices) for (b in a + 1 until voices.size) { sum += shared(voices[a], voices[b]); pairs++ }
            lastShared = sum / pairs
            sharedSum += lastShared
        }
    }

    val shareOne get() = withOne.toDouble() / detections.coerceAtLeast(1)
    val shareTwo get() = withTwo.toDouble() / detections.coerceAtLeast(1)
    /** Undefined (null) if there were never two voices at once, as the plan says: not zero. */
    val meanShared: Double? get() = if (withTwo == 0) null else sharedSum / withTwo

    fun describe() = "at least one %.1f%% of the time, at least two %.1f%%, shared opening %s"
        .format(Locale.ROOT, 100 * shareOne, 100 * shareTwo, meanShared?.let { "%.1f units".format(Locale.ROOT, it) } ?: "undefined")

    companion object {
        const val MATCH = 1.0

        fun shared(a: Voice, b: Voice): Double {
            val n = minOf(a.points.size, b.points.size)
            var length = 0.0
            for (k in 0 until n) {
                val pa = a.points[k]; val pb = b.points[k]
                if (StrictMath.hypot(pa[0] - pb[0], pa[1] - pb[1]) > MATCH) break
                if (k > 0) length += StrictMath.hypot(pa[0] - a.points[k - 1][0], pa[1] - a.points[k - 1][1])
            }
            return length
        }
    }
}

/**
 * Size and period of the population's swings over a trailing window: swing is
 * (max − min) / mean, and the period is the mean time between upward crossings of the mean
 * (with a hysteresis of 2% of the mean, so noise doesn't count as crossings). NaN when
 * there are fewer than two crossings in the window.
 */
class PopulationSwing(private val windowSeconds: Double) {
    private val times = ArrayDeque<Double>()
    private val pops = ArrayDeque<Int>()
    var mean = 0.0; private set
    var swing = 0.0; private set
    var period = Double.NaN; private set

    fun add(seconds: Double, population: Int) {
        times.addLast(seconds); pops.addLast(population)
        while (times.first() < seconds - windowSeconds) { times.removeFirst(); pops.removeFirst() }
        mean = pops.average()
        swing = if (mean > 0) (pops.max() - pops.min()) / mean else 0.0
        val band = 0.02 * mean
        var below = false
        val ups = mutableListOf<Double>()
        for (k in pops.indices) {
            if (pops[k] < mean - band) below = true
            else if (below && pops[k] > mean + band) { ups += times[k]; below = false }
        }
        period = if (ups.size >= 2) (ups.last() - ups.first()) / (ups.size - 1) else Double.NaN
    }
}

/**
 * The comparison baseline: the same seed and script twice, both at a fixed population of
 * `--baseline N` ants, once as the colony and once with trail-following off. A free-running
 * baseline starves down to the floor, so without the fixed size the comparison would be
 * "following" against "a colony 50 times smaller".
 */
class Baseline(private val o: HeadlessOptions) {
    fun run() {
        val n = o.baseline!!.toDouble()
        val fixed = listOf("nest.initialAnts" to n, "nest.fixedPopulation" to n)
        val colony = HeadlessRun(o.copy(out = File(o.out, "colony"), set = o.set + fixed)).run()
        val baseline = HeadlessRun(o.copy(out = File(o.out, "baseline"), set = o.set + fixed + ("ants.followTrails" to 0.0))).run()
        val line = { name: String, r: HeadlessRun.Summary ->
            "%-9s voices: %s; delivered %d, starved %d, died of age %d"
                .format(Locale.ROOT, name, r.voices?.describe() ?: "(detector off)", r.delivered, r.starved, r.oldAge)
        }
        println("Comparison at a fixed population of ${n.toInt()}:")
        println(line("colony", colony))
        println(line("baseline", baseline))
        println("Metrics in ${o.out}/colony/metrics.csv and ${o.out}/baseline/metrics.csv")
    }
}

/** The metrics CSV: one row per interval. Stage timings are averages over the interval. */
class MetricsWriter(
    file: File, private val linkThreshold: Double, swingWindow: Double = 900.0, private val voices: VoiceScore? = null,
) {
    private val w = file.bufferedWriter()
    private var last: net.aprlflwrs.aunthillary.core.StageProfile.Snapshot? = null
    val swing = PopulationSwing(swingWindow)
    var rows = 0; private set
    var linkedAnyRows = 0; private set
    var linkedTwoRows = 0; private set

    init {
        w.write("tick,seconds,population,store,mound,piles,food_left,delivered,empty_returns,piles_linked," +
            "symbols,symbol_directions,food_pheromone,home_pheromone,ms_ants,ms_pheromone,ms_other," +
            "hatched,starved,old_age,eating_rate,pop_mean,pop_swing,pop_period," +
            "voices,voice_share_1,voice_share_2,shared_opening,symbol_ids,symbol_strengths\n")
    }

    fun write(colony: AntColony) {
        val snap = colony.profile.snapshot()
        val ms = last?.let { snap.msPerTickSince(it) } ?: doubleArrayOf(0.0, 0.0, 0.0)
        last = snap
        val piles = colony.piles
        val linked = if (piles.isEmpty()) emptyList() else TrailMetrics.linked(
            colony.grid.food, colony.grid.size, colony.nestX, colony.nestY, colony.p.hubRadius.value,
            piles.map { it.x to it.y }, colony.p.scentRadius.value, linkThreshold,
        )
        val symbols = colony.symbols()
        val f = { v: Double -> "%.4f".format(Locale.ROOT, v) }
        val seconds = colony.tick * World.DT
        swing.add(seconds, colony.population)
        rows++
        if (linked.count { it } >= 1) linkedAnyRows++
        if (linked.count { it } >= 2) linkedTwoRows++
        w.write(listOf(
            colony.tick.toString(),
            f(seconds),
            colony.population.toString(),
            f(colony.store),
            f(colony.moundHeight),
            piles.joinToString(";") { "${it.id}:${"%.0f".format(Locale.ROOT, it.amount)}" },
            f(piles.sumOf { it.amount }),
            colony.totalDelivered.toString(),
            colony.totalEmptyReturns.toString(),
            piles.indices.filter { linked[it] }.joinToString(";") { piles[it].id.toString() },
            symbols.size.toString(),
            symbols.joinToString(";") { "%.2f".format(Locale.ROOT, it.direction) },
            f(TrailMetrics.total(colony.grid.food, colony.grid.size)),
            f(TrailMetrics.total(colony.grid.home, colony.grid.size)),
            f(ms[0]), f(ms[1]), f(ms[2]),
            colony.totalHatched.toString(),
            colony.totalStarved.toString(),
            (colony.totalDied - colony.totalStarved).toString(),
            f(colony.eatingRate),
            f(swing.mean), f(swing.swing), if (swing.period.isNaN()) "" else f(swing.period),
            voices?.lastCount?.toString() ?: "",
            voices?.let { f(it.shareOne) } ?: "",
            voices?.let { f(it.shareTwo) } ?: "",
            voices?.lastShared?.takeIf { !it.isNaN() }?.let { f(it) } ?: "",
            symbols.joinToString(";") { it.id.toString() },
            symbols.joinToString(";") { "%.3f".format(Locale.ROOT, it.strength) },
        ).joinToString(",") + "\n")
    }

    fun close() = w.close()
}

/**
 * A picture of the pheromone grid, north at the top: terrain in dark greys (rocks lighter),
 * home pheromone in blue, food pheromone in orange, the nest in white and piles in green.
 * Brightness is sqrt(value / displayMax), like the pheromone frame.
 */
fun writePheromonePng(colony: AntColony, file: File) {
    val n = colony.grid.size
    val img = BufferedImage(n, n, BufferedImage.TYPE_INT_RGB)
    val max = colony.p.displayMax.value
    val terrain = colony.terrain
    val maxHeight = (terrain.heights.maxOrNull() ?: 1f).coerceAtLeast(1e-3f)
    for (r in 0 until n) for (c in 0 until n) {
        val i = r * n + c
        val x = (c + 0.5) * colony.grid.cell
        val y = (r + 0.5) * colony.grid.cell
        val ground = if (terrain.isBlocked(x, y)) 90.0 else 15 + 30 * terrain.heightAt(x, y) / maxHeight
        val food = sqrt((colony.grid.food[i] / max).coerceIn(0.0, 1.0))
        val home = sqrt((colony.grid.home[i] / max).coerceIn(0.0, 1.0))
        val red = ground + 240 * food
        val green = ground + 140 * food + 60 * home
        val blue = ground + 220 * home
        img.setRGB(c, n - 1 - r, rgb(red, green, blue))
    }
    fun dot(x: Double, y: Double, radius: Double, color: Int) {
        val cx = x / colony.grid.cell; val cy = y / colony.grid.cell; val rr = radius / colony.grid.cell
        for (r in (cy - rr).toInt()..(cy + rr).toInt()) for (c in (cx - rr).toInt()..(cx + rr).toInt()) {
            if (r !in 0 until n || c !in 0 until n) continue
            val d = sqrt((c + 0.5 - cx) * (c + 0.5 - cx) + (r + 0.5 - cy) * (r + 0.5 - cy))
            if (abs(d - rr) < 0.8) img.setRGB(c, n - 1 - r, color)
        }
    }
    dot(colony.nestX, colony.nestY, colony.p.nestRadius.value + 1, 0xffffff)
    for (pile in colony.piles) dot(pile.x, pile.y, colony.p.pickupRadius.value + 2, if (pile.amount > 0) 0x40ff40 else 0x406040)
    ImageIO.write(img, "png", file)
}

private fun rgb(r: Double, g: Double, b: Double): Int {
    fun ch(v: Double) = v.toInt().coerceIn(0, 255)
    return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/**
 * The lesion test (see the file comment).
 *
 * Per seed, two colonies replay the same script: food in one direction, learned for
 * `--lesion-build` seconds, then removed. At that tick the two are bit-identical (checked by
 * fingerprint), and the second colony's heading distribution is flattened. Both then wait
 * `--lesion-wait` seconds (long enough for the old trail to fade, checked by the link metric)
 * and get new food at the same spot. The result is the time until `--lesion-deliveries` units
 * of the new food reach the nest.
 *
 * The flattening happens when the food is removed, not when the new food appears, because the
 * distribution acts only through the ants that draw a heading from it (newborns, and ants
 * recruited when they come home empty-handed): flattening at the new food would change only
 * ants that draw after that moment, which mostly haven't left the nest by the time the food is
 * found. So what is tested is the lingering peak during the wait, the colony's "belief in food
 * that is no longer there".
 *
 * At the new food, besides the distribution's share toward it and the symbol's strength, the
 * test reports two steps of how the belief reaches the ground: the share of all ants whose
 * preferred heading points into that bin, and the share of searching ants (outside the hub)
 * that actually stand in that bin's wedge of the map. An evenly spread colony has 1/bins of each.
 * It also reports when the new food was first picked up and first delivered.
 *
 * By default the population is held fixed (`--lesion-population`, 10,000), so that both runs
 * have the same number of ants: under the free hunger economy, removing a pile puts the colony
 * in deficit and it starves down to a new size, and how fast it does so then differs between
 * the runs. At a fixed size, every ant that dies is replaced by a newborn, and every ant that
 * comes home empty-handed is recruited; both draw their heading from the distribution.
 *
 * The summary pairs the runs by seed: the runs of one seed share everything up to the lesion,
 * so the noise that matters is the spread of the per-seed differences, not of the times.
 */
class Lesion(private val o: HeadlessOptions) {
    private class Run(
        val fingerprintAtLesion: Long,
        val shareAtPlacement: Double,
        val symbolAtPlacement: Double,
        val preferringAtPlacement: Double,
        val searchingThereAtPlacement: Double,
        val oldTrailLinked: Boolean,
        val firstPickup: Double?,
        val firstDelivery: Double?,
        val time: Double?,
    )

    fun run() {
        o.out.mkdirs()
        val px = World.SIZE / 2 + o.lesionDistance * StrictMath.cos(o.lesionAngle)
        val py = World.SIZE / 2 + o.lesionDistance * StrictMath.sin(o.lesionAngle)
        println(("Lesion test: food at (%.1f, %.1f) for %.0f s, removed, new food there %.0f s later; " +
            "found = %d deliveries. Even share per bin is %.4f.")
            .format(Locale.ROOT, px, py, o.lesionBuild, o.lesionWait, o.lesionDeliveries, 1.0 / HeadingDistribution.BINS))
        val csv = File(o.out, "lesion.csv").printWriter()
        csv.println("seed,identical_at_lesion,share_intact,share_flattened,symbol_intact,symbol_flattened," +
            "preferring_intact,preferring_flattened,searching_there_intact,searching_there_flattened," +
            "old_trail_linked_intact,old_trail_linked_flattened,first_pickup_intact,first_pickup_flattened," +
            "first_delivery_intact,first_delivery_flattened,seconds_intact,seconds_flattened")
        val pairs = mutableListOf<Triple<Double, Double, Boolean>>()
        val f4 = { v: Double -> "%.4f".format(Locale.ROOT, v) }
        for (seed in o.seeds) {
            val a = runOnce(seed, px, py, flatten = false)
            val b = runOnce(seed, px, py, flatten = true)
            val identical = a.fingerprintAtLesion == b.fingerprintAtLesion
            println(("  seed %d: found in %s s intact, %s s flattened (first pickup %s / %s s, first delivery %s / %s s). " +
                "At the new food, intact / flattened: share toward it %.4f / %.4f; symbol strength %.2f / %.2f; " +
                "ants preferring it %.4f / %.4f; searchers out there %.4f / %.4f; old trail %s / %s; identical at the lesion: %s")
                .format(Locale.ROOT, seed, fmt(a.time), fmt(b.time), fmt(a.firstPickup), fmt(b.firstPickup),
                    fmt(a.firstDelivery), fmt(b.firstDelivery), a.shareAtPlacement, b.shareAtPlacement,
                    a.symbolAtPlacement, b.symbolAtPlacement, a.preferringAtPlacement, b.preferringAtPlacement,
                    a.searchingThereAtPlacement, b.searchingThereAtPlacement, trail(a.oldTrailLinked), trail(b.oldTrailLinked), identical))
            csv.println(listOf(seed, identical, f4(a.shareAtPlacement), f4(b.shareAtPlacement),
                "%.3f".format(Locale.ROOT, a.symbolAtPlacement), "%.3f".format(Locale.ROOT, b.symbolAtPlacement),
                f4(a.preferringAtPlacement), f4(b.preferringAtPlacement), f4(a.searchingThereAtPlacement), f4(b.searchingThereAtPlacement),
                a.oldTrailLinked, b.oldTrailLinked, a.firstPickup ?: "", b.firstPickup ?: "", a.firstDelivery ?: "", b.firstDelivery ?: "",
                a.time ?: "", b.time ?: "").joinToString(","))
            if (a.time != null && b.time != null) pairs += Triple(a.time, b.time, !a.oldTrailLinked && !b.oldTrailLinked)
        }
        csv.close()
        fun summary(label: String, ps: List<Triple<Double, Double, Boolean>>) {
            if (ps.isEmpty()) return
            val diffs = ps.map { it.second - it.first } // positive: the intact colony was faster
            val mean = diffs.average()
            val sd = if (diffs.size > 1) StrictMath.sqrt(diffs.sumOf { (it - mean) * (it - mean) } / (diffs.size - 1)) else Double.NaN
            println(("%s: mean time to find %.1f s intact, %.1f s flattened; intact faster in %d of %d seeds; " +
                "paired difference (flattened − intact) %.1f s, standard deviation %.1f s, standard error %.1f s.")
                .format(Locale.ROOT, label, ps.sumOf { it.first } / ps.size, ps.sumOf { it.second } / ps.size,
                    ps.count { it.first < it.second }, ps.size, mean, sd, sd / StrictMath.sqrt(diffs.size.toDouble())))
        }
        summary("All seeds", pairs)
        // A seed where the old trail outlived the wait measures the pheromone, not the symbol.
        summary("Seeds whose old trail had faded in both runs", pairs.filter { it.third })
        println("Wrote ${o.out}/lesion.csv and each run's action log")
    }

    private fun runOnce(seed: Long, px: Double, py: Double, flatten: Boolean): Run {
        val colony = AntColony(seed, gridSize = o.grid)
        // The script both runs share, so they stay bit-identical until the lesion.
        val script = mutableListOf<LoggedAction>()
        val settings = o.set.toMutableList()
        if (o.lesionPopulation > 0) {
            val n = o.lesionPopulation.toDouble()
            settings += listOf("nest.initialAnts" to n, "nest.fixedPopulation" to n)
        }
        for ((k, v) in settings) script += LoggedAction(0, Action.SetParam(k, v))
        if (settings.isNotEmpty()) script += LoggedAction(0, Action.Reset)
        script += LoggedAction(0, Action.PlaceFood(px, py))
        if (o.lesionSustain) {
            // A second pile opposite, so the colony stays fed (and doesn't starve to its floor) while the
            // lesion pile is gone. It is placed after the lesion pile, so the lesion pile is pile 1.
            script += LoggedAction(0, Action.PlaceFood(World.SIZE - px, World.SIZE - py))
        }
        val removeTick = seconds(o.lesionBuild)
        Replayer(colony, script).run(removeTick)
        check(colony.piles.firstOrNull()?.let { it.x == px && it.y == py } == true) { "Food at ($px, $py) could not be placed (rock?) for seed $seed" }

        val fingerprint = colony.fingerprint()
        if (flatten) colony.flattenHeadings()
        colony.removeFood(colony.piles.first().id)
        repeat(seconds(o.lesionWait).toInt()) { colony.step() }

        val bin = HeadingDistribution.binOf(o.lesionAngle)
        val share = colony.headingShares()[bin]
        val symbol = colony.symbols().filter { angleBetween(it.direction, o.lesionAngle) < it.width / 2 + 0.2 }
            .maxOfOrNull { it.strength } ?: 0.0
        val linked = TrailMetrics.linked(colony.grid.food, colony.grid.size, colony.nestX, colony.nestY,
            colony.p.hubRadius.value, listOf(px to py), colony.p.scentRadius.value, o.linkThreshold)[0]
        val (preferring, searchingThere) = antsToward(colony, bin)

        colony.placeFood(px, py)
        val pile = colony.piles.last()
        val limit = seconds(o.lesionTimeout)
        var t = 0L
        var firstPickup: Double? = null
        var firstDelivery: Double? = null
        while (pile.delivered < o.lesionDeliveries && t < limit) {
            colony.step(); t++
            if (firstPickup == null && pile.pickedUp > 0) firstPickup = t * World.DT
            if (firstDelivery == null && pile.delivered > 0) firstDelivery = t * World.DT
        }
        colony.log.write(File(o.out, "lesion_seed${seed}_${if (flatten) "flattened" else "intact"}.log"))
        val time = if (pile.delivered >= o.lesionDeliveries) t * World.DT else null
        return Run(fingerprint, share, symbol, preferring, searchingThere, linked, firstPickup, firstDelivery, time)
    }

    /**
     * The share of all ants whose preferred heading falls in [bin], and the share of searching
     * ants outside the hub that stand in [bin]'s wedge of the map, seen from the nest.
     */
    private fun antsToward(colony: AntColony, bin: Int): Pair<Double, Double> {
        val ants = colony.ants
        val hub2 = colony.p.hubRadius.value * colony.p.hubRadius.value
        var preferring = 0
        var searching = 0
        var searchingThere = 0
        for (i in 0 until ants.count) {
            val pref = StrictMath.atan2(ants.prefSin[i].toDouble(), ants.prefCos[i].toDouble())
            if (HeadingDistribution.binOf(pref) == bin) preferring++
            if (ants.flags[i].toInt() and net.aprlflwrs.aunthillary.core.Ants.RETURNING != 0) continue
            val dx = ants.x[i] - colony.nestX
            val dy = ants.y[i] - colony.nestY
            if (dx * dx + dy * dy <= hub2) continue
            searching++
            if (HeadingDistribution.binOf(StrictMath.atan2(dy, dx)) == bin) searchingThere++
        }
        return preferring.toDouble() / ants.count.coerceAtLeast(1) to searchingThere.toDouble() / searching.coerceAtLeast(1)
    }

    private fun trail(linked: Boolean) = if (linked) "STILL LINKED" else "gone"

    private fun fmt(v: Double?) = v?.let { "%.1f".format(Locale.ROOT, it) } ?: "never"
}

/** Smallest angle between two headings, 0–π. */
fun angleBetween(a: Double, b: Double): Double {
    val d = abs(HeadingDistribution.wrap(a) - HeadingDistribution.wrap(b))
    return if (d > PI) 2 * PI - d else d
}
