package net.aprlflwrs.aunthillary.core

/**
 * Every tunable number of the real colony, declared once in the [Params] registry so the
 * tuning panel gets a slider for each. Code reads `colonyParams.speed.value` and so on.
 *
 * Units: distances are world units (about one ant length), times are seconds, rates are per
 * second, angles are radians. Pheromone is a density: amount per square world unit, so a
 * value means the same thing at any grid resolution.
 *
 * The defaults are a first working set: two default piles 150 units out grow two clear trails
 * and a colony that settles near 10,000 ants (about 3,700 for one pile, about 20,000 for two
 * piles 80 units out). Real tuning happens in Phase 3.
 */
class ColonyParams(val params: Params) {
    private fun p(key: String, label: String, group: String, min: Double, max: Double, step: Double, default: Double) =
        params.declare(key, label, group, min, max, step, default)

    // ---- Ants: movement and senses ----
    val speed = p("ants.speed", "Speed (units/s)", "Ants", 1.0, 40.0, 0.5, 12.0)
    /** Random turning, as the standard deviation of the heading after one second of wandering. */
    val wander = p("ants.wander", "Wander (rad/√s)", "Ants", 0.0, 5.0, 0.05, 1.2)
    val turnRate = p("ants.turnRate", "Steering toward scent (rad/s)", "Ants", 0.0, 20.0, 0.1, 5.0)
    val sensorDistance = p("ants.sensorDistance", "Sensor distance (units)", "Ants", 0.5, 20.0, 0.1, 5.0)
    val sensorAngle = p("ants.sensorAngle", "Sensor cone angle (rad)", "Ants", 0.05, 1.5, 0.01, 0.6)
    /** Below this density a cone counts as empty, so ants don't chase faint noise. */
    val followThreshold = p("ants.followThreshold", "Following threshold (density)", "Ants", 0.0, 1.0, 0.001, 0.01)
    /**
     * 1 = normal; 0 = the comparison baseline (see Timeline & Risks in the plan): ants still
     * wander, lay pheromone and carry food, but never steer by pheromone of either kind.
     * The food scent and the pull toward the nest still work, so the baseline still forages.
     */
    val followTrails = p("ants.followTrails", "Follow trails (0 = baseline)", "Ants", 0.0, 1.0, 1.0, 1.0)
    /** How fast a searcher drifts back toward its preferred heading when nothing else steers it. */
    val headingPull = p("ants.headingPull", "Pull toward preferred heading (rad/s)", "Ants", 0.0, 5.0, 0.05, 0.3)
    /** The returning ant's weak sense of where the nest is (standing in for path integration). */
    val nestPull = p("ants.nestPull", "Pull toward nest when returning (rad/s)", "Ants", 0.0, 5.0, 0.05, 1.0)
    val giveUpTime = p("ants.giveUpTime", "Give up searching after (s)", "Ants", 5.0, 300.0, 1.0, 45.0)
    /** Pheromone laid falls off as exp(−t / this), t = time since leaving the nest or picking up food. */
    val depositFade = p("ants.depositFade", "Deposit fall-off time (s)", "Ants", 1.0, 300.0, 1.0, 40.0)
    val homeDeposit = p("ants.homeDeposit", "Home pheromone laid (per s)", "Ants", 0.0, 10.0, 0.01, 1.0)
    val foodDeposit = p("ants.foodDeposit", "Food pheromone laid (per s)", "Ants", 0.0, 10.0, 0.01, 1.0)
    /** Speed is divided by (1 + this × |grade|), grade being rise over run along the ant's heading. */
    val slopePenalty = p("ants.slopePenalty", "Slope slowdown", "Ants", 0.0, 20.0, 0.1, 3.0)
    /** Old age. Long, so that a fed colony's ants mostly die of age and its membership keeps turning over. */
    val lifespan = p("ants.lifespan", "Lifespan (s)", "Ants", 10.0, 100_000.0, 10.0, 600.0)
    val lifespanJitter = p("ants.lifespanJitter", "Lifespan spread (±fraction)", "Ants", 0.0, 0.9, 0.01, 0.2)

    // ---- Hunger ----
    // An ant's energy runs from 1 (full) to 0 (starved). It falls with distance walked and, much
    // more slowly, with time, and is refilled from the store whenever the ant is at the nest.
    /** Distance a full ant can walk before starving (time costs come on top). */
    val range = p("hunger.range", "Walking range on a full stomach (units)", "Hunger", 50.0, 10_000.0, 10.0, 1200.0)
    /** How long a full ant can stand still before starving. */
    val fastingTime = p("hunger.fastingTime", "Survival without walking (s)", "Hunger", 10.0, 10_000.0, 10.0, 600.0)
    /**
     * A searching ant turns home to eat when its remaining range falls below
     * returnSafety × (distance to the nest) + returnMargin, so ants rarely starve on the way back.
     */
    val returnSafety = p("hunger.returnSafety", "Turn home when range < this × distance home", "Hunger", 1.0, 5.0, 0.05, 2.0)
    val returnMargin = p("hunger.returnMargin", "… plus this (units)", "Hunger", 0.0, 500.0, 1.0, 50.0)
    /** Food it takes to fill an empty ant. */
    val mealSize = p("hunger.mealSize", "Food per full meal", "Hunger", 0.01, 10.0, 0.01, 0.75)

    // ---- Castes ----
    val scoutShare = p("castes.scoutShare", "Scout share", "Castes", 0.0, 1.0, 0.01, 0.15)
    val scoutFidelity = p("castes.scoutFidelity", "Scout fidelity", "Castes", 0.0, 1.0, 0.01, 0.1)
    val foragerFidelity = p("castes.foragerFidelity", "Forager fidelity", "Castes", 0.0, 1.0, 0.01, 0.9)
    val fidelityJitter = p("castes.fidelityJitter", "Fidelity spread (±)", "Castes", 0.0, 0.5, 0.01, 0.1)

    // ---- Headings (the caste distribution of preferred directions) ----
    /** Share of newborns given a uniformly random heading, so exploration never stops. */
    val randomShare = p("headings.randomShare", "Random share of newborns", "Headings", 0.0, 1.0, 0.01, 0.3)
    /** Time constant over which the distribution forgets past returns. */
    val headingMemory = p("headings.memory", "Memory (s)", "Headings", 1.0, 600.0, 1.0, 60.0)
    /** How much one empty-handed return counts against its direction (one food delivery counts 1). */
    val emptyWeight = p("headings.emptyWeight", "Empty-handed return weight", "Headings", 0.0, 10.0, 0.05, 0.2)
    /**
     * Recruitment at meals: the share of ants coming home without food that draw a fresh
     * preferred heading from the distribution (as a newborn does) when they eat. Foragers that
     * bring food keep theirs. 0 makes the distribution act through newborns only.
     */
    val recruitShare = p("headings.recruitShare", "Share recruited at meals", "Headings", 0.0, 1.0, 0.01, 1.0)
    /** Pseudo-returns spread evenly over all directions, so a handful of returns can't make a peak. */
    val headingPrior = p("headings.prior", "Even prior (returns)", "Headings", 0.0, 10_000.0, 10.0, 200.0)

    // ---- Symbols ----
    val symbolAppear = p("symbols.appear", "Appear above (× even share)", "Symbols", 1.0, 5.0, 0.05, 1.6)
    val symbolDisappear = p("symbols.disappear", "Disappear below (× even share)", "Symbols", 1.0, 5.0, 0.05, 1.25)

    // ---- Food ----
    val pileAmount = p("food.pileAmount", "New pile size", "Food", 10.0, 500_000.0, 10.0, 40_000.0)
    /**
     * How many units per second ants can cut from one pile, however many are there. This is what
     * makes more food support more ants: deliveries stop growing with the population once every
     * pile is worked at this rate, so the colony settles where they balance what it eats.
     */
    val pileYield = p("food.pileYield", "Yield per pile (units/s)", "Food", 1.0, 2000.0, 1.0, 30.0)
    val pickupRadius = p("food.pickupRadius", "Pickup radius (units)", "Food", 0.5, 20.0, 0.1, 3.0)
    val scentRadius = p("food.scentRadius", "Scent radius (units)", "Food", 0.0, 60.0, 0.5, 12.0)
    val scentTurnRate = p("food.scentTurnRate", "Steering toward scent of food (rad/s)", "Food", 0.0, 30.0, 0.1, 8.0)
    /** An empty pile stays in the state message this long (so the detector can let its voice fade). */
    val emptyLinger = p("food.emptyLinger", "Empty piles listed for (s)", "Food", 0.0, 600.0, 1.0, 60.0)

    // ---- Nest and economy ----
    val nestRadius = p("nest.radius", "Nest entrance radius (units)", "Nest", 1.0, 20.0, 0.5, 4.0)
    /** The crowded area around the nest: ants don't steer by pheromone inside it, and the detector ignores it. */
    val hubRadius = p("nest.hubRadius", "Hub radius (units)", "Nest", 2.0, 60.0, 0.5, 12.0)
    val initialAnts = p("nest.initialAnts", "Ants at start (on reset)", "Nest", 0.0, 100_000.0, 100.0, 3000.0)
    val initialStore = p("nest.initialStore", "Store at start (on reset)", "Nest", 0.0, 100_000.0, 10.0, 1000.0)
    /** Food per new ant. Newborns hatch full (an egg is cheaper than a meal, so this is below mealSize). */
    val hatchCost = p("nest.hatchCost", "Food per new ant", "Nest", 0.0, 50.0, 0.05, 0.5)
    /** The store the nest keeps back before hatching: this many seconds of what the colony is eating. */
    val reserveTime = p("nest.reserveTime", "Reserve kept (s of eating)", "Nest", 0.0, 600.0, 1.0, 30.0)
    /** Surplus beyond the reserve is turned into ants over about this long (a rate, not a cap). */
    val hatchTime = p("nest.hatchTime", "Time to hatch the surplus (s)", "Nest", 0.1, 600.0, 0.1, 20.0)
    /** Time constant of the running average of how fast the colony eats. */
    val eatingAverage = p("nest.eatingAverage", "Eating-rate averaging (s)", "Nest", 1.0, 600.0, 1.0, 30.0)
    /**
     * Non-zero holds the population at exactly this many ants, topping up and culling regardless
     * of food (births from surplus stop; ants still eat and starve, and are replaced). Used for
     * the comparison baseline, so both runs have the same number of ants.
     */
    val fixedPopulation = p("nest.fixedPopulation", "Fixed population (0 = off)", "Nest", 0.0, 100_000.0, 100.0, 0.0)
    val populationFloor = p("nest.populationFloor", "Population floor", "Nest", 0.0, 5000.0, 10.0, 300.0)
    /** A safety limit for the renderer only; ordinary feeding never comes near it. */
    val populationCap = p("nest.populationCap", "Population cap", "Nest", 100.0, 100_000.0, 100.0, 100_000.0)

    // ---- Mound ----
    val moundGrowth = p("mound.growth", "Growth per food delivered (units)", "Mound", 0.0, 0.01, 0.00001, 0.0001)
    val moundMax = p("mound.maxHeight", "Max height (units)", "Mound", 0.0, 40.0, 0.1, 6.0)
    val moundRadius = p("mound.radius", "Radius (units)", "Mound", 2.0, 100.0, 1.0, 25.0)
    val floodKeep = p("mound.floodKeep", "Height kept after a flood", "Mound", 0.0, 1.0, 0.01, 0.5)

    // ---- Pheromone ----
    val evaporation = p("pheromone.evaporation", "Evaporation (per second)", "Pheromone", 0.0, 1.0, 0.001, 0.06)
    /** Diffusion coefficient in square world units per second. */
    val diffusion = p("pheromone.diffusion", "Diffusion (units²/s)", "Pheromone", 0.0, 20.0, 0.01, 0.4)
    /** Densities below this are set to zero, so faded trails really vanish (and floats never go subnormal). */
    val cutoff = p("pheromone.cutoff", "Cutoff (density)", "Pheromone", 0.0, 0.01, 0.00001, 0.0001)
    /** Read by the server when it encodes the pheromone frame (key fixed by the protocol). */
    val displayMax = p("pheromone.displayMax", "Display max", "Pheromone", 0.01, 50.0, 0.01, 10.0)

    // ---- Terrain (applies on reset) ----
    val hillHeight = p("terrain.hillHeight", "Hill height (units, on reset)", "Terrain", 0.0, 50.0, 0.5, 10.0)
    val hillScale = p("terrain.hillScale", "Hill spacing (units, on reset)", "Terrain", 16.0, 512.0, 1.0, 128.0)
    val rockCount = p("terrain.rockCount", "Rocks (on reset)", "Terrain", 0.0, 200.0, 1.0, 25.0)
    val rockRadius = p("terrain.rockRadius", "Largest rock radius (units, on reset)", "Terrain", 1.0, 40.0, 0.5, 8.0)
    /** No rocks within this distance of the nest. */
    val rockClearance = p("terrain.rockClearance", "Rock-free radius round the nest (on reset)", "Terrain", 0.0, 200.0, 1.0, 40.0)

    // ---- Flood ----
    val floodScatter = p("flood.scatter", "Flood scatter distance (units)", "Flood", 0.0, 512.0, 1.0, 120.0)
}
