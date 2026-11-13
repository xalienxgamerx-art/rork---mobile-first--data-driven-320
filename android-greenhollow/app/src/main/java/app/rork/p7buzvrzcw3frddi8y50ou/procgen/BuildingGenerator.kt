package app.rork.p7buzvrzcw3frddi8y50ou.procgen

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Kotlin port of the supplied TypeScript medieval 2D tile-based building generator, kept
 * faithful to it: every floor is a top-down plan on a shared tile grid, floors stack so tile
 * (x, y) on floor 1 sits directly above tile (x, y) on floor 0.
 *
 * Layout: a timber-frame "bay" system. Posts (Beam tiles) sit on bay lines, a central hallway
 * runs the full length of every floor, rooms sit in a north band and a south band, and one
 * staircase block lives at the hallway's west end on every floor.
 *
 * Variances (upper floors only): jetties (overhang), setbacks with roof terraces, cantilevered
 * balconies and recessed balconies (loggias).
 *
 * Soundness is enforced, not hoped for: every generated building is run through
 * [validateBuilding], and [generateBuilding] retries until it passes.
 *
 * World scale: one tile is [TILE_SIZE] world units on a side, the unit every consumer
 * (geometry, collision) shares.
 */
const val TILE_SIZE = 1f

/** World units one storey rises; shared by the exterior and interior geometry emitters. */
const val STORY = 3.2f

/** Height of a door opening. */
const val DOOR_H = 2.3f

// ---------------------------------------------------------------------------
// Tiles
// ---------------------------------------------------------------------------

object Tile {
    const val EMPTY = 0 // open air / ground outside the building
    const val FLOOR = 1 // walkable interior floor
    const val WALL = 2 // solid wall
    const val DOOR = 3 // door (also used for balcony / terrace doors)
    const val WINDOW = 4 // window (exterior walls)
    const val BEAM = 5 // vertical support post / timber frame member
    const val STAIRS = 6 // staircase connecting floors
    const val BALCONY = 7 // open-air walkable floor (balcony, loggia, roof terrace)
    const val RAILING = 8 // balustrade around open-air floors
}

val TILE_NAMES = arrayOf(
    "empty", "floor", "wall", "door", "window", "beam", "stairs", "balcony", "railing",
)

val TILE_GLYPHS = charArrayOf(' ', '.', '#', '+', 'o', 'B', '=', ',', '~')

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

data class Room(
    val id: Int,
    val floor: Int,
    val kind: String,
    /** "north" or "south". */
    val band: String,
    /** Original room rectangle (a recessed balcony can carve into it). */
    val rect: Rect,
    /** Usable floor tiles inside the rect: walls, posts and doorways excluded. */
    val usableTiles: Int = 0,
)

class FloorPlan(
    val level: Int,
    /** tiles[y][x], holding Tile constants. */
    val tiles: Array<IntArray>,
    /** Bounding rectangle of the enclosed part of the floor (walls included). */
    val rect: Rect,
    /** "stone" or "timber". */
    val material: String,
    val rooms: List<Room>,
    /** Human-readable notes, e.g. "jetty (south)". */
    val features: List<String>,
) {
    override fun equals(other: Any?): Boolean = other is FloorPlan &&
        level == other.level && rect == other.rect && material == other.material &&
        rooms == other.rooms && features == other.features &&
        tiles.size == other.tiles.size && tiles.indices.all { y -> tiles[y].contentEquals(other.tiles[y]) }

    override fun hashCode(): Int = level * 31 + rect.hashCode()
}

class Building(
    val seed: Long,
    val attempts: Int,
    /** Tile-grid width (ground floor width plus margins). */
    val width: Int,
    val height: Int,
    val floors: List<FloorPlan>,
    /** Footprint of the staircase block, identical on every floor. */
    val stairs: Rect,
) {
    override fun equals(other: Any?): Boolean = other is Building &&
        seed == other.seed && attempts == other.attempts && width == other.width &&
        height == other.height && stairs == other.stairs &&
        floors.size == other.floors.size && floors.zip(other.floors).all { (a, b) -> a == b }

    override fun hashCode(): Int = (seed.hashCode() * 31 + floors.size) * 31 + stairs.hashCode()
}

data class BuildingOptions(
    /** When null, a random seed is drawn (the game always passes one, so worlds are reproducible). */
    val seed: Long? = null,
    /** Number of floors; null draws from the profile's range. */
    val floors: Int? = null,
    /** Ground floor width in tiles; null draws from the profile's range. */
    val width: Int? = null,
    /** Ground floor depth in tiles; null draws from the profile's range. */
    val depth: Int? = null,
    val maxAttempts: Int? = null,
    /** The architectural profile: footprint, floors, rooms, halls and features. Null = default. */
    val profile: ArchProfile? = null,
)

// ---------------------------------------------------------------------------
// Seeded RNG (mulberry32)
// ---------------------------------------------------------------------------

class Rng(seed: Long) {
    private var state: Int = seed.toInt()

    fun next(): Double {
        state += 0x6d2b79f5 // wraps, like >>> 0
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor ((t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }

    /** Inclusive integer range. */
    fun int(min: Int, max: Int): Int = min + (next() * (max - min + 1.0)).toInt()

    fun chance(p: Double): Boolean = next() < p

    fun <T> pick(items: List<T>): T = items[(next() * items.size).toInt()]

    fun <T> shuffle(items: List<T>): List<T> {
        val out = items.toMutableList()
        for (i in out.size - 1 downTo 1) {
            val j = (next() * (i + 1)).toInt()
            val tmp = out[i]
            out[i] = out[j]
            out[j] = tmp
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private const val MARGIN = 4 // empty tiles around the ground footprint (room for jetties/balconies)
private const val MAX_SPAN = 6 // longest clear floor/wall span timber can cross without support

private class Extra(val side: String, val bay: Int)

private class Terrace(val xNew: Int, val xOld: Int)

/** Internal per-floor blueprint, derived from the floor below. */
private class Spec(
    val level: Int,
    val x0: Int,
    val x1: Int,
    val y0: Int,
    val y1: Int,
    /** Interior bay lines (x of posts/partitions), excluding x0 and x1. */
    val cuts: List<Int>,
    /** Partition walls removed (rooms merged) in each band. Posts remain. */
    val dropN: MutableSet<Int>,
    val dropS: MutableSet<Int>,
    val material: String,
    val balconies: MutableList<Extra>,
    val notches: MutableList<Extra>,
    var terrace: Terrace? = null,
    var frontBay: Int = -1,
    var backBay: Int = -1,
    val features: MutableList<String> = mutableListOf(),
)

private class Layout(val x0: Int, val yHN: Int, val yh0: Int, val yh1: Int, val yHS: Int, val stairX: Int, val hallW: Int)

// ---------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------

fun generateBuilding(opts: BuildingOptions = BuildingOptions()): Building {
    val seed = opts.seed ?: Random.nextInt(0, Int.MAX_VALUE).toLong()
    val maxAttempts = opts.maxAttempts ?: 300
    val declared = opts.profile ?: ArchitecturalProfiles.DEFAULT
    // An explicit floor count wins over the profile's range (clamped up to its minimum,
    // never down from a deliberate tall order).
    val prof = if (opts.floors != null && opts.floors > declared.floorsMax) {
        declared.copy(floorsMax = opts.floors)
    } else {
        declared
    }
    var lastIssues: List<String> = emptyList()

    for (attempt in 0 until maxAttempts) {
        try {
            val building = buildOnce(seed + attempt * 7919L, opts, seed, attempt + 1, prof)
            val issues = validateBuilding(building, prof)
            if (issues.isEmpty()) return building
            lastIssues = issues
        } catch (err: Throwable) {
            lastIssues = listOf(err.toString())
        }
    }
    // Deterministic fallback: one last plan with the variations stripped (no merged halls,
    // no connecting doors, no upper-floor features), held to the same room minimums. The
    // recorded attempt count names it as the fallback.
    try {
        val fallback = buildOnce(seed + maxAttempts * 7919L, opts, seed, maxAttempts + 1, prof, minimal = true)
        if (validateBuilding(fallback, prof).isEmpty()) return fallback
    } catch (err: Throwable) {
        lastIssues = listOf(err.toString())
    }
    throw IllegalStateException(
        "Could not generate a sound ${prof.name}: ${lastIssues.take(3).joinToString("; ")}",
    )
}

private fun buildOnce(
    seed: Long,
    opts: BuildingOptions,
    outSeed: Long,
    attempts: Int,
    baseProf: ArchProfile,
    minimal: Boolean = false,
): Building {
    val prof = if (minimal) {
        baseProf.copy(
            groundOpenChance = 0.0, groundOpenNorth = 0.0, upperOpenChance = 0.0,
            connectorChance = 0.0, allowFeatures = false,
        )
    } else {
        baseProf
    }
    val rng = Rng(seed)
    val nFloors = maxOf(prof.floorsMin, opts.floors ?: rng.int(prof.floorsMin, prof.floorsMax))
    val W = (opts.width ?: rng.int(prof.wMin, prof.wMax)).coerceIn(prof.wCapMin, prof.wCapMax)
    val D = (opts.depth ?: rng.int(prof.dMin, prof.dMax)).coerceIn(prof.dMin, prof.dMax)

    // Quality pass: how is this plan organised? The profile declares its strategy; a compact
    // declared footprint on a fallback-allowed profile changes strategy rather than growing
    // rooms too shallow to be usable.
    if (prof.style == LayoutStyle.OPEN_PLAN ||
        (prof.allowOpenPlanFallback && D - 4 - prof.hallW < 2 * prof.bandMin)
    ) {
        return buildOpenPlan(rng, nFloors, W, D, outSeed, attempts, prof)
    }

    // Vertical layout: wall | north rooms | wall | hall | wall | south rooms | wall.
    // The hall width and the room-band depths are profile traits: a manor keeps the classic
    // two-tile hall; small homes never take this path at all (see [buildOpenPlan]).
    val sum = D - 4 - prof.hallW
    val hi = minOf(prof.bandMax, sum - prof.bandMin).coerceAtLeast(1)
    val lo = maxOf(prof.bandMin, sum - prof.bandMax).coerceAtMost(hi)
    val northDepth = rng.int(lo, hi)
    val southDepth = sum - northDepth
    val x0 = MARGIN
    val y0 = MARGIN
    val x1 = x0 + W - 1
    val yHN = y0 + northDepth + 1
    val yh0 = yHN + 1
    val yh1 = yh0 + prof.hallW - 1
    val yHS = yh1 + 1
    val y1 = yHS + southDepth + 1
    val lay = Layout(x0, yHN, yh0, yh1, yHS, x0 + 1, prof.hallW)
    val gridW = W + 2 * MARGIN
    val gridH = D + 2 * MARGIN

    // Bay lines for the ground floor. First bay >= 5 so the west room's hall
    // door clears the staircase; last bay between 4 and 8.
    val cuts = ArrayList<Int>()
    var pos = x0
    while (x1 - pos > 8) {
        pos += rng.int(5, min(7, x1 - pos - 4))
        cuts.add(pos)
    }

    val ground = Spec(
        level = 0, x0 = x0, x1 = x1, y0 = y0, y1 = y1, cuts = cuts,
        dropN = mutableSetOf(), dropS = mutableSetOf(),
        material = "stone", balconies = mutableListOf(), notches = mutableListOf(),
    )

    // Front door bay: south bay nearest the middle of the facade (never the west bay).
    val lines0 = listOf(x0) + cuts + x1
    val nb0 = lines0.size - 1
    val mid = (x0 + x1) / 2.0
    var front = if (nb0 >= 2) 1 else 0
    for (i in 1 until nb0) {
        if (abs((lines0[i] + lines0[i + 1]) / 2.0 - mid) <
            abs((lines0[front] + lines0[front + 1]) / 2.0 - mid)
        ) {
            front = i
        }
    }
    ground.frontBay = front
    ground.features.add("front door (south)")
    if (prof.backDoor) {
        ground.backBay = rng.int(0, nb0 - 1)
        ground.features.add("back door (north)")
    }
    val openGround = rng.chance(prof.groundOpenChance)
    if (openGround && cuts.isNotEmpty()) {
        if (prof.groundOpenChance >= 1.0) {
            ground.dropS.addAll(cuts) // warehouses: the whole south band is one floor of storage
            ground.features.add("open hall")
        } else {
            ground.dropS.add(if (front < nb0 - 1) cuts[front] else cuts[front - 1]) // open great hall
            ground.features.add("great hall with interior posts")
        }
    }
    if (prof.groundOpenNorth > 0.0 && cuts.isNotEmpty() && rng.chance(prof.groundOpenNorth)) {
        if (prof.groundOpenNorth >= 1.0) {
            ground.dropN.addAll(cuts)
            ground.features.add("open north band")
        } else {
            ground.dropN.add(rng.pick(cuts))
        }
    }

    val specs = ArrayList<Spec>()
    specs.add(ground)
    var extN = 0
    var extS = 0

    for (n in 1 until nFloors) {
        val prev = specs[n - 1]
        var op = "same"
        val r = if (prof.allowFeatures) rng.next() else 1.0
        if (r < 0.38 && extS < 2 && prev.balconies.none { it.side == "S" }) op = "jettyS"
        else if (r < 0.5 && extN < 2 && prev.balconies.none { it.side == "N" }) op = "jettyN"
        else if (r < 0.72) op = "setbackE"

        var newX1 = prev.x1
        if (op == "setbackE") {
            val candidates = prev.cuts.filter { it - x0 + 1 >= 12 && it <= prev.x1 - 3 }
            if (candidates.isEmpty()) op = "same"
            else newX1 = rng.pick(candidates)
        }

        val spec = Spec(
            level = n,
            x0 = x0,
            x1 = newX1,
            y0 = prev.y0 - (if (op == "jettyN") 1 else 0),
            y1 = prev.y1 + (if (op == "jettyS") 1 else 0),
            cuts = prev.cuts.filter { it < newX1 },
            dropN = mutableSetOf(),
            dropS = mutableSetOf(),
            material = if (rng.chance(0.85)) "timber" else "stone",
            balconies = mutableListOf(),
            notches = mutableListOf(),
        )

        if (op == "jettyS") {
            extS++
            spec.features.add("jetty (south overhang)")
        }
        if (op == "jettyN") {
            extN++
            spec.features.add("jetty (north overhang)")
        }
        if (op == "setbackE") {
            spec.terrace = Terrace(xNew = newX1, xOld = prev.x1)
            spec.features.add("east setback with roof terrace")
        }

        // Open up rooms by removing partitions (posts stay); some profiles open wide, some
        // open one partition now and then, some never.
        if (spec.cuts.isNotEmpty()) {
            if (prof.upperOpenChance >= 1.0) {
                spec.dropN.addAll(spec.cuts)
                spec.dropS.addAll(spec.cuts)
            } else {
                if (rng.chance(prof.upperOpenChance)) spec.dropN.add(rng.pick(spec.cuts))
                if (rng.chance(prof.upperOpenChance)) spec.dropS.add(rng.pick(spec.cuts))
            }
        }

        val nb = spec.cuts.size + 1
        val used = HashSet<String>()
        fun bandDepth(s: String): Int = if (s == "N") yHN - spec.y0 - 1 else spec.y1 - yHS - 1
        fun lowerHasNotch(e: Extra): Boolean = prev.notches.any { it.side == e.side && it.bay == e.bay }

        if (op == "same" && rng.chance(0.45)) {
            val e = Extra(side = if (rng.chance(0.5)) "S" else "N", bay = rng.int(0, nb - 1))
            if (!lowerHasNotch(e)) {
                spec.balconies.add(e)
                used.add(e.side + e.bay)
                spec.features.add("balcony (${if (e.side == "S") "south" else "north"})")
            }
        }
        if (rng.chance(0.4)) {
            val e = Extra(side = if (rng.chance(0.5)) "S" else "N", bay = rng.int(0, nb - 1))
            if (bandDepth(e.side) >= 4 && e.side + e.bay !in used) {
                spec.notches.add(e)
                spec.features.add("recessed balcony (${if (e.side == "S") "south" else "north"})")
            }
        }
        specs.add(spec)
    }

    // Staircase block at the hallway's west end, identical on every floor — but a
    // single-floor building needs no stairs at all.
    val stairs = Rect(x = lay.stairX, y = yh0, w = 3, h = prof.hallW)

    val floors = ArrayList<FloorPlan>()
    for (spec in specs) {
        val lower = floors.lastOrNull()?.tiles
        floors.add(renderFloor(spec, lay, gridW, gridH, lower, rng, prof, withStairs = nFloors > 1, windowEvery = prof.windowEvery))
    }

    return Building(seed = outSeed, attempts = attempts, width = gridW, height = gridH, floors = floors, stairs = stairs)
}

/**
 * Quality pass — the open-plan strategy: one primary space spans the full depth of the
 * building's front (the room the building is about), and the remaining rooms sit in a north
 * band that opens straight onto it through a single partition wall. No hallway: in a small
 * home the passage would eat the rooms it serves. Doorways are deliberate — one front door,
 * and one door per room into the primary space, never a lottery of connectors.
 */
private fun buildOpenPlan(
    rng: Rng,
    nFloors: Int,
    W: Int,
    D: Int,
    outSeed: Long,
    attempts: Int,
    prof: ArchProfile,
): Building {
    val x0 = MARGIN
    val y0 = MARGIN
    val x1 = x0 + W - 1

    // Two bands minus the one partition between them: the interior's whole depth is rooms.
    // The primary space keeps the depth its room kind requires — the bands split what is
    // left, never the primary's minimum.
    val openSum = D - 2
    val primaryMinD = maxOf(
        RoomRequirements.forKind(prof.frontGreat).minD,
        RoomRequirements.forKind(prof.upperGreat).minD,
    )
    val hi = minOf(prof.bandMax, openSum - primaryMinD).coerceAtLeast(1)
    val lo = maxOf(prof.bandMin, openSum - prof.bandMax).coerceAtMost(hi)
    val northDepth = rng.int(lo, hi)
    val southDepth = openSum - northDepth
    val yHN = y0 + northDepth + 1 // the partition wall between the north rooms and the primary space
    val y1 = yHN + southDepth + 1
    val lay = Layout(x0, yHN, yHN + 1, yHN, yHN + 1, x0 + 1, 2) // hall fields unused; the stairs live in the primary space

    // Bay lines for the north rooms and the facade rhythm — spaced so both rooms keep three
    // usable tiles of width once the partition wall between them is counted.
    val cuts = ArrayList<Int>()
    var pos = x0
    while (x1 - pos > 7) {
        pos += rng.int(4, min(7, x1 - pos - 4))
        cuts.add(pos)
    }

    val ground = Spec(
        level = 0, x0 = x0, x1 = x1, y0 = y0, y1 = y1, cuts = cuts,
        dropN = mutableSetOf(), dropS = mutableSetOf(),
        material = "stone", balconies = mutableListOf(), notches = mutableListOf(),
    )
    val lines0 = listOf(x0) + cuts + x1
    val nb0 = lines0.size - 1
    val mid = (x0 + x1) / 2.0
    var front = if (nb0 >= 2) 1 else 0
    for (i in 1 until nb0) {
        if (abs((lines0[i] + lines0[i + 1]) / 2.0 - mid) <
            abs((lines0[front] + lines0[front + 1]) / 2.0 - mid)
        ) {
            front = i
        }
    }
    ground.frontBay = front
    ground.features.add("front door (south)")
    if (prof.backDoor) {
        ground.backBay = rng.int(0, nb0 - 1)
        ground.features.add("back door (north)")
    }
    ground.features.add("open plan")

    val specs = ArrayList<Spec>()
    specs.add(ground)
    for (n in 1 until nFloors) {
        specs.add(
            Spec(
                level = n, x0 = x0, x1 = x1, y0 = y0, y1 = y1, cuts = cuts,
                dropN = mutableSetOf(), dropS = mutableSetOf(),
                material = if (rng.chance(0.85)) "timber" else "stone",
                balconies = mutableListOf(), notches = mutableListOf(),
            ),
        )
    }

    // The stair block stands at the primary space's west end, identical on every floor.
    val stairs = Rect(x = x0 + 1, y = yHN + 1, w = 3, h = 2)
    val floors = ArrayList<FloorPlan>()
    for (spec in specs) {
        floors.add(
            renderOpenFloor(
                spec, lay, W + 2 * MARGIN, D + 2 * MARGIN, rng, prof,
                withStairs = nFloors > 1, windowEvery = prof.windowEvery,
            ),
        )
    }
    return Building(
        seed = outSeed, attempts = attempts,
        width = W + 2 * MARGIN, height = D + 2 * MARGIN,
        floors = floors, stairs = stairs,
    )
}

/**
 * Renders one open-plan floor: shell, a full-width primary space across the front, and north
 * rooms that each open onto it through one door in the partition. Every room kind is held to
 * [RoomRequirements]; the generator's retry loop rejects any plan that falls short.
 */
private fun renderOpenFloor(
    spec: Spec,
    lay: Layout,
    gridW: Int,
    gridH: Int,
    rng: Rng,
    prof: ArchProfile,
    withStairs: Boolean,
    windowEvery: Int,
): FloorPlan {
    val g = Array(gridH) { IntArray(gridW) }
    fun set(x: Int, y: Int, t: Int) {
        if (x < 0 || y < 0 || x >= gridW || y >= gridH) {
            throw IndexOutOfBoundsException("tile ($x,$y) is outside the grid")
        }
        g[y][x] = t
    }

    val x0 = spec.x0
    val x1 = spec.x1
    val y0 = spec.y0
    val y1 = spec.y1
    val partY = lay.yHN
    val lines = listOf(x0) + spec.cuts + x1
    val nb = lines.size - 1
    fun center(i: Int): Int = (lines[i] + lines[i + 1]) / 2

    // Shell and the one partition wall between the north rooms and the primary space.
    for (y in y0..y1) {
        for (x in x0..x1) {
            set(x, y, if (x == x0 || x == x1 || y == y0 || y == y1) Tile.WALL else Tile.FLOOR)
        }
    }
    for (x in x0..x1) set(x, partY, Tile.WALL)

    // Timber frame: posts on every bay line across facade and partition rows.
    for (x in lines) {
        for (row in intArrayOf(y0, partY, y1)) set(x, row, Tile.BEAM)
    }

    // Partitions between the north rooms.
    for (c in spec.cuts) {
        for (y in y0 + 1 until partY) set(c, y, Tile.WALL)
    }

    // The stair block, at the primary space's west end, identical on every floor.
    if (withStairs) {
        for (y in partY + 1 until partY + 3) {
            for (x in lay.stairX until lay.stairX + 3) set(x, y, Tile.STAIRS)
        }
    }

    // Windows: one per bay on both facades (or every Nth bay, per profile), plus the west wall.
    for (i in 0 until nb) {
        if (i % windowEvery == 0) {
            set(center(i), y0, Tile.WINDOW)
            set(center(i), y1, Tile.WINDOW)
        }
    }
    set(x0, partY + 1, Tile.WINDOW)

    // Ground floor entrances: one deliberate front door, a back door only where the
    // profile's work justifies one.
    if (spec.level == 0) {
        set(center(spec.frontBay), y1, Tile.DOOR)
        if (prof.backDoor) set(center(spec.backBay), y0, Tile.DOOR)
    }

    // Rooms: the primary space spans the front; each north room opens straight onto it.
    val rooms = ArrayList<Room>()
    val primaryRect = Rect(x = x0 + 1, y = partY + 1, w = x1 - x0 - 1, h = y1 - partY - 1)
    rooms.add(
        Room(
            id = 0,
            floor = spec.level,
            kind = if (spec.level == 0) prof.frontGreat else prof.upperGreat,
            band = "south",
            rect = primaryRect,
            usableTiles = RoomRequirements.usableTiles(g, primaryRect),
        ),
    )

    fun groups(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until nb) out.add(i to i)
        return out
    }
    val full = if (spec.level == 0) prof.groundNorth else prof.upper
    val head = if (spec.level == 0 && prof.backRoom in full) listOf(prof.backRoom) else emptyList()
    val rest = full.filter { it !in head }
    // The pool is authored in priority order: the working room (kitchen) first, then the
    // sleeping room, then storage in a shuffled tail — a home always sleeps where it cooks.
    val firstRest = rest.firstOrNull()
    val pool = rng.shuffle(rest.drop(1))
    var k = 0
    for ((gs, ge) in groups()) {
        val rect = Rect(x = lines[gs] + 1, y = y0 + 1, w = lines[ge + 1] - lines[gs] - 1, h = partY - y0 - 1)
        val kind = when {
            gs < head.size -> head[gs]
            gs == head.size && firstRest != null -> firstRest
            else -> pool[k++ % pool.size]
        }
        val dx = center(gs).coerceIn(lines[gs] + 1, lines[ge + 1] - 1)
        set(dx, partY, Tile.DOOR)
        rooms.add(
            Room(
                id = rooms.size,
                floor = spec.level,
                kind = kind,
                band = "north",
                rect = rect,
                usableTiles = RoomRequirements.usableTiles(g, rect),
            ),
        )
    }

    return FloorPlan(
        level = spec.level,
        tiles = g,
        rect = Rect(x = x0, y = y0, w = x1 - x0 + 1, h = y1 - y0 + 1),
        material = spec.material,
        rooms = rooms,
        features = spec.features,
    )
}

private fun renderFloor(
    spec: Spec,
    lay: Layout,
    gridW: Int,
    gridH: Int,
    lower: Array<IntArray>?,
    rng: Rng,
    prof: ArchProfile,
    withStairs: Boolean,
    windowEvery: Int,
): FloorPlan {
    val g = Array(gridH) { IntArray(gridW) }
    fun set(x: Int, y: Int, t: Int) {
        if (x < 0 || y < 0 || x >= gridW || y >= gridH) {
            throw IndexOutOfBoundsException("tile ($x,$y) is outside the grid")
        }
        g[y][x] = t
    }

    val x0 = spec.x0
    val x1 = spec.x1
    val y0 = spec.y0
    val y1 = spec.y1
    val yHN = lay.yHN
    val yh0 = lay.yh0
    val yh1 = lay.yh1
    val yHS = lay.yHS
    val lines = listOf(x0) + spec.cuts + x1
    val nb = lines.size - 1
    fun center(i: Int): Int = (lines[i] + lines[i + 1]) / 2

    // Shell and hall walls
    for (y in y0..y1) {
        for (x in x0..x1) {
            set(x, y, if (x == x0 || x == x1 || y == y0 || y == y1) Tile.WALL else Tile.FLOOR)
        }
    }
    for (x in x0..x1) {
        set(x, yHN, Tile.WALL)
        set(x, yHS, Tile.WALL)
    }

    // Timber frame: posts on every bay line (corners, T-junctions, facade posts)
    for (x in lines) {
        for (row in intArrayOf(y0, yHN, yHS, y1)) set(x, row, Tile.BEAM)
    }

    // Partitions between rooms; a removed partition may keep a free-standing post
    fun midPost(c: Int, ya: Int, yb: Int) {
        val row = ya + (yb - ya + 1) / 2
        if (lower == null || lower[row][c] == Tile.BEAM || lower[row][c] == Tile.WALL) set(c, row, Tile.BEAM)
    }
    for (c in spec.cuts) {
        if (c !in spec.dropN) for (y in y0 + 1 until yHN) set(c, y, Tile.WALL) else midPost(c, y0 + 1, yHN - 1)
        if (c !in spec.dropS) for (y in yHS + 1 until y1) set(c, y, Tile.WALL) else midPost(c, yHS + 1, y1 - 1)
    }

    // Stairs
    if (withStairs) {
        for (y in yh0..yh1) {
            for (x in lay.stairX until lay.stairX + 3) set(x, y, Tile.STAIRS)
        }
    }

    // Windows: one per bay on both facades (or every Nth bay, per profile), plus hall ends
    for (i in 0 until nb) {
        if (i % windowEvery == 0) {
            set(center(i), y0, Tile.WINDOW)
            set(center(i), y1, Tile.WINDOW)
        }
    }
    set(x0, yh0, Tile.WINDOW)

    // Ground floor entrances: one front door; a back door only where the profile's work
    // justifies one — an ordinary home does not grow doorways by default.
    if (spec.level == 0) {
        set(center(spec.frontBay), y1, Tile.DOOR)
        if (prof.backDoor) set(center(spec.backBay), y0, Tile.DOOR)
    }

    // Rooms + their doors into the hall
    val rooms = ArrayList<Room>()
    fun groups(drop: Set<Int>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < nb) {
            var j = i
            while (j < nb - 1 && lines[j + 1] in drop) j++
            out.add(i to j)
            i = j + 1
        }
        return out
    }
    fun addBand(band: String) {
        val drop = if (band == "north") spec.dropN else spec.dropS
        val row = if (band == "north") yHN else yHS
        val ya = if (band == "north") y0 + 1 else yHS + 1
        val yb = if (band == "north") yHN - 1 else y1 - 1
        val pool = rng.shuffle(
            if (spec.level == 0) {
                if (band == "north") prof.groundNorth else prof.groundSouth
            } else {
                prof.upper
            },
        )
        var k = 0
        for ((gs, ge) in groups(drop)) {
            var dx = center(gs)
            if (gs == 0) dx = max(dx, x0 + 4) // clear the staircase
            dx = min(dx, lines[gs + 1] - 1)
            set(dx, row, Tile.DOOR)

            val bays = ge - gs + 1
            fun holds(bay: Int): Boolean = bay in gs..ge
            val kind = when {
                spec.level == 0 && band == "south" && holds(spec.frontBay) ->
                    if (bays > 1) prof.frontGreat else prof.frontSingle
                spec.level == 0 && band == "north" && holds(spec.backBay) -> prof.backRoom
                spec.level > 0 && bays > 1 -> prof.upperGreat
                else -> pool[k++ % pool.size]
            }

            val roomRect = Rect(
                x = lines[gs] + 1,
                y = ya,
                w = lines[ge + 1] - lines[gs] - 1,
                h = yb - ya + 1,
            )
            rooms.add(
                Room(
                    id = rooms.size,
                    floor = spec.level,
                    kind = kind,
                    band = band,
                    rect = roomRect,
                    usableTiles = RoomRequirements.usableTiles(g, roomRect),
                ),
            )
        }
    }
    addBand("north")
    addBand("south")

    // Connecting doors between neighbouring rooms — a profile trait, not a dice roll of the
    // style. Ordinary homes get none: doors grow only between meaningful spaces.
    for (c in spec.cuts) {
        if (c !in spec.dropN && rng.chance(prof.connectorChance)) set(c, yHN - 1, Tile.DOOR)
        if (c !in spec.dropS && rng.chance(prof.connectorChance)) set(c, yHS + 1, Tile.DOOR)
    }

    // Setback: the roof of the floor below becomes a railed terrace
    val terrace = spec.terrace
    if (terrace != null) {
        for (y in y0..y1) {
            for (x in terrace.xNew + 1..terrace.xOld) {
                set(x, y, if (y == y0 || y == y1 || x == terrace.xOld) Tile.RAILING else Tile.BALCONY)
            }
        }
        set(x1, yh0, Tile.DOOR)
        set(x1, yh1, Tile.WINDOW)
    } else {
        set(x1, yh0, Tile.WINDOW)
    }

    // Recessed balconies (loggias): carve the facade inward, keep the railing on the old wall line
    for (nt in spec.notches) {
        val l = lines[nt.bay]
        val r = lines[nt.bay + 1]
        val row = if (nt.side == "S") y1 else y0
        val d = if (nt.side == "S") -1 else 1 // inward direction
        for (x in l + 1 until r) {
            set(x, row, Tile.RAILING)
            set(x, row + d, Tile.BALCONY)
            set(x, row + 2 * d, Tile.WALL)
        }
        set(center(nt.bay), row + 2 * d, Tile.DOOR)
        for (sx in intArrayOf(l, r)) {
            for (k in intArrayOf(1, 2)) {
                if (g[row + k * d][sx] == Tile.FLOOR) set(sx, row + k * d, Tile.WALL)
            }
        }
    }

    // Projecting (cantilevered) balconies
    for (b in spec.balconies) {
        val l = lines[b.bay]
        val r = lines[b.bay + 1]
        val cx = center(b.bay)
        val row = if (b.side == "S") y1 else y0
        val d = if (b.side == "S") 1 else -1 // outward direction
        val px0 = max(l + 1, cx - 1)
        val px1 = min(r - 1, cx + 1)
        set(cx, row, Tile.DOOR)
        for (x in px0..px1) set(x, row + d, Tile.BALCONY)
        set(px0 - 1, row + d, Tile.RAILING)
        set(px1 + 1, row + d, Tile.RAILING)
        for (x in px0 - 1..px1 + 1) set(x, row + 2 * d, Tile.RAILING)
    }

    return FloorPlan(
        level = spec.level,
        tiles = g,
        rect = Rect(x = x0, y = y0, w = x1 - x0 + 1, h = y1 - y0 + 1),
        material = spec.material,
        rooms = rooms,
        features = spec.features,
    )
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

private fun isHard(t: Int): Boolean =
    t == Tile.WALL || t == Tile.BEAM || t == Tile.DOOR || t == Tile.WINDOW

private fun isWalkable(t: Int): Boolean =
    t == Tile.FLOOR || t == Tile.DOOR || t == Tile.STAIRS || t == Tile.BALCONY

/** Distance to the nearest load-bearing tile in a direction, or Int.MAX_VALUE (no support found). */
private fun scanSupport(lower: Array<IntArray>, x: Int, y: Int, dx: Int, dy: Int): Int {
    var s = 1
    while (true) {
        val t = lower.getOrNull(y + dy * s)?.getOrNull(x + dx * s)
        if (t == null || t == Tile.EMPTY) return Int.MAX_VALUE
        if (isHard(t)) return s
        s++
    }
}

/** Joists run one way: the span only has to be short enough along one axis. */
private fun spanOk(lower: Array<IntArray>, x: Int, y: Int): Boolean {
    val sx = scanSupport(lower, x, y, -1, 0).toLong() + scanSupport(lower, x, y, 1, 0).toLong() - 1
    val sy = scanSupport(lower, x, y, 0, -1).toLong() + scanSupport(lower, x, y, 0, 1).toLong() - 1
    return min(sx, sy) <= MAX_SPAN
}

/** A projection of one tile is fine if a load-bearing tile is right next to it below. */
private fun cantileverOk(lower: Array<IntArray>, x: Int, y: Int): Boolean {
    for (dy in -1..1) {
        for (dx in -1..1) {
            if ((dx != 0 || dy != 0) && lower.getOrNull(y + dy)?.getOrNull(x + dx)?.let(::isHard) == true) {
                return true
            }
        }
    }
    return false
}

/**
 * Returns a list of problems; an empty list means the building is sound. Validation is
 * profile-aware: the floor-count bounds, the required rooms and whether stairs must exist
 * at all all come from the profile, so a one-floor cottage is as sound as a four-storey manor.
 */
fun validateBuilding(b: Building, prof: ArchProfile = ArchitecturalProfiles.DEFAULT): List<String> {
    val issues = ArrayList<String>()
    if (b.floors.size < prof.floorsMin) issues.add("needs at least ${prof.floorsMin} floors, has ${b.floors.size}")
    if (b.floors.size > prof.floorsMax) issues.add("may not exceed ${prof.floorsMax} floors, has ${b.floors.size}")
    if (b.floors.isEmpty()) return issues

    fun stairKeys(f: FloorPlan): String = buildList {
        f.tiles.forEachIndexed { y, row ->
            row.forEachIndexed { x, t -> if (t == Tile.STAIRS) add("$x,$y") }
        }
    }.joinToString("|")

    val groundStairs = stairKeys(b.floors[0])
    if (b.floors.size > 1 && groundStairs.isEmpty()) issues.add("ground floor has no stairs")

    if (prof.required.isNotEmpty()) {
        val kinds = b.floors.flatMap { it.rooms }.mapTo(HashSet()) { it.kind }
        for (r in prof.required) {
            if (r !in kinds) issues.add("the ${prof.name} never grew its required '$r' room")
        }
    }

    b.floors.forEachIndexed { n, floor ->
        val g = floor.tiles
        if (n > 0 && b.floors.size > 1 && stairKeys(floor) != groundStairs) {
            issues.add("floor $n: stairs do not line up with the floor below")
        }

        for (y in g.indices) {
            for (x in g[y].indices) {
                val t = g[y][x]
                if (t == Tile.EMPTY) continue

                if (t == Tile.RAILING) {
                    var ok = false
                    outer@ for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (g.getOrNull(y + dy)?.getOrNull(x + dx) == Tile.BALCONY) {
                                ok = true
                                break@outer
                            }
                        }
                    }
                    if (!ok) issues.add("floor $n: railing at ($x,$y) guards nothing")
                    continue
                }
                if (n == 0) continue // resting on the ground

                val lowerFloor = b.floors[n - 1].tiles
                val below = lowerFloor[y][x]
                val ok = if (t == Tile.BEAM) {
                    below == Tile.BEAM || below == Tile.WALL || cantileverOk(lowerFloor, x, y)
                } else {
                    isHard(below) ||
                        cantileverOk(lowerFloor, x, y) ||
                        (below != Tile.EMPTY && spanOk(lowerFloor, x, y))
                }
                if (!ok) issues.add("floor $n: ${TILE_NAMES[t]} at ($x,$y) is unsupported")
            }
        }

        // Every doorway must open onto something: a door walled in on both sides along its
        // axis is a redundant or sealed opening, not a connection.
        for (y in g.indices) {
            for (x in g[y].indices) {
                if (g[y][x] != Tile.DOOR) continue
                val opens = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) ->
                    g.getOrNull(y + dy)?.getOrNull(x + dx)?.let(::isWalkable) == true
                }
                if (!opens) issues.add("floor $n: a doorway at ($x,$y) opens into solid wall")
            }
        }

        // Architectural quality: every room must be usable — wide enough, deep enough and
        // roomy enough for what it claims to be, per [RoomRequirements]. A plan that cannot
        // meet the minimums is rejected and regenerated, never quietly shrunk.
        for (room in floor.rooms) {
            val req = RoomRequirements.forKind(room.kind)
            if (room.rect.w < req.minW) {
                issues.add("floor $n: the ${room.kind} is ${room.rect.w} tiles wide, a usable one needs ${req.minW}")
            }
            if (room.rect.h < req.minD) {
                issues.add("floor $n: the ${room.kind} is only ${room.rect.h} tiles deep, a usable one needs ${req.minD}")
            }
            val usable = RoomRequirements.usableTiles(g, room.rect)
            if (usable < req.minArea) {
                issues.add("floor $n: the ${room.kind} holds only $usable usable tiles, it needs ${req.minArea}")
            }
        }

        // Every walkable tile on the floor must be reachable — from the stairs when there
        // are any, otherwise (single-floor buildings) from anywhere on the floor.
        var start: Pair<Int, Int>? = null
        var walkable = 0
        for (y in g.indices) {
            for (x in g[y].indices) {
                if (isWalkable(g[y][x])) {
                    walkable++
                    if (start == null) start = x to y
                }
                if (g[y][x] == Tile.STAIRS) start = x to y
            }
        }
        val from = start
        if (from != null) {
            val seen = HashSet<Int>()
            val queue = ArrayDeque<Pair<Int, Int>>()
            fun key(x: Int, y: Int) = y * g[0].size + x
            seen.add(key(from.first, from.second))
            queue.addLast(from)
            while (queue.isNotEmpty()) {
                val (cx, cy) = queue.removeLast()
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = cx + dx
                    val ny = cy + dy
                    val t = g.getOrNull(ny)?.getOrNull(nx)
                    if (t != null && isWalkable(t) && key(nx, ny) !in seen) {
                        seen.add(key(nx, ny))
                        queue.addLast(nx to ny)
                    }
                }
            }
            if (seen.size != walkable) {
                issues.add("floor $n: ${walkable - seen.size} walkable tiles are unreachable")
            }
            // And no room may stand cut off from the life of the floor: each one needs at
            // least one reachable tile of its own.
            for (room in floor.rooms) {
                var reached = false
                for (y in room.rect.y until room.rect.y + room.rect.h) {
                    for (x in room.rect.x until room.rect.x + room.rect.w) {
                        if (x >= 0 && y >= 0 && y < g.size && x < g[0].size &&
                            isWalkable(g[y][x]) && key(x, y) in seen
                        ) {
                            reached = true
                            break
                        }
                    }
                    if (reached) break
                }
                if (!reached) issues.add("floor $n: the ${room.kind} cannot be reached")
            }
        }
    }

    // The ground floor needs a way in from outside — and no more doors than the building's
    // work justifies: an ordinary home keeps one entrance.
    val gf = b.floors[0].tiles
    var exteriorDoors = 0
    for (y in gf.indices) {
        for (x in gf[y].indices) {
            if (gf[y][x] == Tile.DOOR &&
                listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) ->
                    gf.getOrNull(y + dy)?.getOrNull(x + dx) == Tile.EMPTY
                }
            ) {
                exteriorDoors++
            }
        }
    }
    if (exteriorDoors == 0) issues.add("ground floor has no exterior door")
    if (exteriorDoors > prof.maxExteriorDoors) {
        issues.add(
            "ground floor has $exteriorDoors exterior doors, " +
                "a ${prof.name} may have at most ${prof.maxExteriorDoors}",
        )
    }

    return issues
}

// ---------------------------------------------------------------------------
// Output helpers
// ---------------------------------------------------------------------------

/** Plain-text rendering, ground floor first, trimmed to the building's bounding box. */
fun renderAscii(b: Building, legend: Boolean = false): String {
    var minX = b.width
    var maxX = 0
    var minY = b.height
    var maxY = 0
    for (f in b.floors) {
        for (y in f.tiles.indices) {
            for (x in f.tiles[y].indices) {
                if (f.tiles[y][x] != Tile.EMPTY) {
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                }
            }
        }
    }
    val out = ArrayList<String>()
    for (f in b.floors) {
        val notes = if (f.features.isEmpty()) "" else " - ${f.features.joinToString(", ")}"
        out.add("Floor ${f.level} (${f.material})$notes")
        for (y in minY..maxY) {
            out.add((minX..maxX).map { x -> TILE_GLYPHS[f.tiles[y][x]] }.joinToString("").trimEnd())
        }
        out.add("")
    }
    if (legend) {
        out.add((0..8).joinToString(" ") { t -> "${TILE_GLYPHS[t]} ${TILE_NAMES[t]}" })
    }
    return out.joinToString("\n")
}
