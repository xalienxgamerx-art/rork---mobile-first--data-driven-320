package app.rork.p7buzvrzcw3frddi8y50ou.procgen

/**
 * Interiors livability pass — deterministic furnishing.
 *
 * A grown tile plan says which room is which; this module says what lives inside each one.
 * Every enterable building's interior is dressed from its own rooms — beds in bedchambers, a
 * hearth in every parlor and kitchen, long tables in the common room, a counter in the shop,
 * an anvil and glowing forge in the forge — placed along the rooms' own walls, never in a
 * doorway, never on the stairs, always leaving room to walk. Nothing is stored: the pieces
 * are a pure function of the plan (same plan, same furniture), so saves stay geometry-free
 * and a regenerated plan re-furnishes itself.
 *
 * The planner is consumed twice, from the same pieces: the renderer draws them into the
 * interior's baked model, and the interior's collision grows solid boxes for the pieces a
 * body should bump into (stools and wall-flush shelves are left walkable).
 *
 * Tile coordinates match the plan grid ([Building] floors, tiles[y][x]); the two local*()
 * helpers convert to interior-local world space exactly as the mesh and collision emitters do.
 */
object Furnishings {

    /** A piece of furniture. Closed vocabulary; the renderer draws every kind. */
    enum class Kind { BED, TABLE, STOOL, HEARTH, SHELF, CHEST, COUNTER, ANVIL, FORGE, BARREL, RUG, SOFA, BENCH, NIGHTSTAND, PLANT, CHAIR }

    /** Kinds the interior's collision treats as solid; stools, rugs and plants yield. */
    val SOLID = setOf(
        Kind.BED, Kind.TABLE, Kind.HEARTH, Kind.CHEST, Kind.COUNTER, Kind.ANVIL, Kind.FORGE,
        Kind.BARREL, Kind.SOFA, Kind.BENCH, Kind.NIGHTSTAND, Kind.CHAIR,
    )

    /**
     * One piece: centre in tile coordinates (fractional when a piece spans two tiles), half
     * extents in tiles, and the wall it backs onto — 0 north (−y), 1 east (+x), 2 south (+y),
     * 3 west (−x) in tile-grid terms.
     */
    data class Piece(
        val floor: Int,
        val kind: Kind,
        val tx: Float,
        val ty: Float,
        val hw: Float,
        val hd: Float,
        val wallSide: Int,
    )

    /** Tile x → interior-local world x, exactly as the mesh emitter converts. */
    fun localX(b: Building, tx: Float): Float = (tx - (b.width - 1) / 2f) * TILE_SIZE

    /** Tile y → interior-local world z, exactly as the mesh emitter converts. */
    fun localZ(b: Building, ty: Float): Float = ((b.height - 1) / 2f - ty) * TILE_SIZE

    /** Half extents (world units) for the solid kinds, as (across, along) the wall it backs. */
    fun solidHalfExtents(kind: Kind): Pair<Float, Float> = when (kind) {
        Kind.BED -> 0.45f to 0.95f
        Kind.SOFA -> 0.45f to 0.95f
        Kind.BENCH -> 0.38f to 0.9f
        Kind.TABLE -> 0.42f to 0.42f
        Kind.CHAIR -> 0.28f to 0.28f
        Kind.HEARTH -> 0.45f to 0.25f
        Kind.CHEST -> 0.38f to 0.32f
        Kind.NIGHTSTAND -> 0.26f to 0.26f
        Kind.COUNTER -> 0.42f to 0.32f
        Kind.ANVIL -> 0.32f to 0.32f
        Kind.FORGE -> 0.45f to 0.30f
        Kind.BARREL -> 0.30f to 0.30f
        else -> 0f to 0f // rugs lie flat, plants yield to the shin
    }

    private val DIRS = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0) // N, E, S, W (tile grid)

    // Per-kind furnishing programs: how many of what a room of a given kind holds. The first
    // piece of each entry is the room's reason for being (bed, hearth, anvil); the rest is
    // comfort, scaled to the space actually available.
    private data class Want(val kind: Kind, val perTiles: Int, val cap: Int)

    private val PROGRAM: Map<String, List<Want>> = mapOf(
        // Bedchambers dress like the reference homes: bed, nightstand at its side, chest,
        // rug underfoot, a shelf and a plant in the corner.
        "bedchamber" to listOf(
            Want(Kind.BED, 6, 2), Want(Kind.NIGHTSTAND, 8, 2), Want(Kind.CHEST, 8, 1),
            Want(Kind.SHELF, 10, 1), Want(Kind.PLANT, 14, 1),
        ),
        "guest chamber" to listOf(
            Want(Kind.BED, 6, 1), Want(Kind.NIGHTSTAND, 8, 1), Want(Kind.CHEST, 8, 1),
            Want(Kind.SHELF, 10, 1), Want(Kind.PLANT, 14, 1),
        ),
        "nursery" to listOf(
            Want(Kind.BED, 6, 1), Want(Kind.CHEST, 8, 1), Want(Kind.SHELF, 10, 1),
            Want(Kind.PLANT, 14, 1),
        ),
        "parlor" to listOf(
            Want(Kind.HEARTH, 0, 1), Want(Kind.SOFA, 8, 1), Want(Kind.TABLE, 9, 1),
            Want(Kind.CHAIR, 10, 2), Want(Kind.STOOL, 5, 2), Want(Kind.SHELF, 12, 1), Want(Kind.PLANT, 14, 1),
        ),
        "kitchen" to listOf(
            Want(Kind.HEARTH, 0, 1), Want(Kind.COUNTER, 7, 2), Want(Kind.TABLE, 10, 1),
            Want(Kind.SHELF, 8, 1), Want(Kind.BARREL, 12, 1), Want(Kind.PLANT, 16, 1),
        ),
        "great hall" to listOf(
            Want(Kind.HEARTH, 0, 1), Want(Kind.TABLE, 10, 3), Want(Kind.BENCH, 8, 3),
            Want(Kind.CHAIR, 12, 2), Want(Kind.STOOL, 4, 4),
        ),
        "common room" to listOf(
            Want(Kind.HEARTH, 0, 1), Want(Kind.TABLE, 10, 3), Want(Kind.BENCH, 8, 3),
            Want(Kind.CHAIR, 12, 2), Want(Kind.STOOL, 4, 4), Want(Kind.BARREL, 14, 2),
        ),
        "meeting room" to listOf(
            Want(Kind.TABLE, 0, 1), Want(Kind.BENCH, 7, 2), Want(Kind.STOOL, 4, 4),
        ),
        "solar" to listOf(
            Want(Kind.TABLE, 0, 1), Want(Kind.CHAIR, 9, 1), Want(Kind.STOOL, 6, 1), Want(Kind.SHELF, 8, 1),
            Want(Kind.PLANT, 12, 1),
        ),
        "study" to listOf(
            Want(Kind.TABLE, 0, 1), Want(Kind.CHAIR, 9, 1), Want(Kind.STOOL, 6, 1), Want(Kind.SHELF, 7, 2),
            Want(Kind.PLANT, 12, 1),
        ),
        "scriptorium" to listOf(
            Want(Kind.TABLE, 8, 2), Want(Kind.STOOL, 6, 2), Want(Kind.SHELF, 7, 2),
            Want(Kind.PLANT, 14, 1),
        ),
        "office" to listOf(
            Want(Kind.TABLE, 0, 1), Want(Kind.CHAIR, 9, 1), Want(Kind.STOOL, 6, 1), Want(Kind.CHEST, 9, 1),
            Want(Kind.PLANT, 12, 1),
        ),
        "workshop" to listOf(Want(Kind.TABLE, 8, 2), Want(Kind.SHELF, 8, 2), Want(Kind.CHEST, 12, 1)),
        "forge" to listOf(Want(Kind.FORGE, 0, 1), Want(Kind.ANVIL, 0, 1), Want(Kind.CHEST, 12, 1), Want(Kind.BARREL, 12, 1)),
        "shop" to listOf(Want(Kind.COUNTER, 6, 2), Want(Kind.SHELF, 7, 2), Want(Kind.CHEST, 12, 1)),
        "guardroom" to listOf(Want(Kind.TABLE, 0, 1), Want(Kind.BENCH, 7, 1), Want(Kind.STOOL, 5, 3), Want(Kind.CHEST, 10, 1)),
        "storeroom" to listOf(Want(Kind.SHELF, 5, 3), Want(Kind.BARREL, 7, 2)),
        "stockroom" to listOf(Want(Kind.COUNTER, 8, 1), Want(Kind.SHELF, 5, 3), Want(Kind.BARREL, 7, 2)),
        "pantry" to listOf(Want(Kind.SHELF, 4, 2), Want(Kind.BARREL, 6, 2)),
        "larder" to listOf(Want(Kind.SHELF, 4, 2), Want(Kind.BARREL, 6, 1)),
        "buttery" to listOf(Want(Kind.SHELF, 4, 2), Want(Kind.BARREL, 5, 2)),
        "scullery" to listOf(Want(Kind.TABLE, 0, 1), Want(Kind.BARREL, 6, 1)),
        "cold cellar" to listOf(Want(Kind.SHELF, 4, 2), Want(Kind.BARREL, 5, 2)),
        "linen room" to listOf(Want(Kind.SHELF, 4, 2), Want(Kind.CHEST, 6, 1)),
        "armory" to listOf(Want(Kind.COUNTER, 0, 1), Want(Kind.SHELF, 5, 2), Want(Kind.CHEST, 8, 1)),
        "entry hall" to listOf(Want(Kind.SHELF, 8, 1), Want(Kind.CHEST, 9, 1), Want(Kind.PLANT, 12, 1)),
    )

    /** A room kind nobody tabled still gets a shelf and a barrel — no bare room anywhere. */
    private val GENERIC_PROGRAM = listOf(Want(Kind.SHELF, 6, 2), Want(Kind.BARREL, 8, 1))

    /** Room kinds that take a rug on their open floor: the gathered look of the reference homes. */
    private val RUG_ROOMS = setOf(
        "parlor", "bedchamber", "guest chamber", "nursery", "great hall", "common room",
        "meeting room", "solar", "study", "office", "scriptorium", "entry hall",
    )

    /**
     * Furnishes every floor of a building. Deterministic: the same plan always yields the
     * same pieces, in the same order, whatever the caller does in between.
     */
    fun plan(b: Building): List<Piece> {
        val rng = Rng(b.seed * 7919L + 104729L)
        val out = ArrayList<Piece>()
        for (f in b.floors) out += furnishFloor(b, f, rng)
        return out
    }

    private fun furnishFloor(b: Building, f: FloorPlan, rng: Rng): List<Piece> {
        val out = ArrayList<Piece>()
        val rugs = ArrayList<Piece>() // rugs draw first, underfoot; furniture may sit on them
        val occupied = HashSet<Long>() // packed tile key per floor
        // Everywhere a body enters or climbs between floors: the sources every free
        // tile of every room must stay reachable from.
        val entries = ArrayList<Pair<Int, Int>>()
        for (y in f.tiles.indices) for (x in f.tiles[y].indices) {
            if (f.tiles[y][x] == Tile.DOOR || f.tiles[y][x] == Tile.STAIRS) entries.add(x to y)
        }
        fun key(tx: Int, ty: Int) = ty.toLong() * 10000L + tx
        fun free(tx: Int, ty: Int) = key(tx, ty) !in occupied

        for (room in f.rooms) {
            val program = PROGRAM[room.kind] ?: GENERIC_PROGRAM
            // Usable tiles that are neither doorway- nor stair-adjacent: the only tiles
            // furniture may claim, and the walk-around space the room must keep.
            val buildable = ArrayList<Pair<Int, Int>>()
            for (y in room.rect.y until room.rect.y + room.rect.h) {
                for (x in room.rect.x until room.rect.x + room.rect.w) {
                    if (f.tiles.getOrNull(y)?.getOrNull(x) == Tile.FLOOR && placeable(f, x, y)) {
                        buildable.add(x to y)
                    }
                }
            }
            var freeCount = buildable.size
            // Comfort rule: at least a quarter of the tiles furniture could ever claim
            // stay clear — two is the floor for a closet, not for a lived-in room. The
            // measure is the furnishable set, not the raw usable count: balconies and
            // door-adjacent tiles were never furniture's to spend.
            val keepFree = maxOf(2, (buildable.size + 3) / 4)
            // The room's own wall side first: north bands back north, south bands back south.
            val homeSide = if (room.band == "north") 0 else 2
            // A shallow room is furnished on its own wall only: pieces on facing walls pinch
            // the lane between them, and a body needs a clear line from door to door.
            val sides = if (room.rect.h <= 3) listOf(homeSide) else
                listOf(homeSide, (homeSide + 2) % 4, (homeSide + 1) % 4, (homeSide + 3) % 4)

            // The rug pass: one rug on the room's open floor, two in a truly great room.
            // Rugs claim nothing — furniture stands on them, as in the reference homes —
            // so they go wherever a clear interior spot exists, centred best.
            if (room.kind in RUG_ROOMS) {
                val clearSpots = ArrayList<Pair<Int, Int>>()
                for (y in room.rect.y until room.rect.y + room.rect.h) {
                    for (x in room.rect.x until room.rect.x + room.rect.w) {
                        if (!placeable(f, x, y)) continue
                        var clear = true
                        for (ny in y - 1..y + 1) {
                            for (nx in x - 1..x + 1) {
                                if (f.tiles.getOrNull(ny)?.getOrNull(nx) != Tile.FLOOR) clear = false
                            }
                        }
                        if (clear) clearSpots.add(x to y)
                    }
                }
                val rugCount = if (room.usableTiles >= 30) 2 else 1
                if (clearSpots.isNotEmpty()) {
                    val cx = room.rect.x + (room.rect.w - 1) / 2f
                    val cy = room.rect.y + (room.rect.h - 1) / 2f
                    val chosen = clearSpots.sortedBy { (x, y) ->
                        val dx = x - cx; val dy = y - cy
                        (dx * dx + dy * dy) * 64f + x * 4f + y // centre-most first, scan order breaks ties
                    }.take(rugCount)
                    val alongX = room.rect.w >= room.rect.h
                    for ((x, y) in chosen) {
                        rugs += Piece(
                            floor = f.level,
                            kind = Kind.RUG,
                            tx = x.toFloat(), ty = y.toFloat(),
                            hw = if (alongX) 0.85f else 0.55f,
                            hd = if (alongX) 0.55f else 0.85f,
                            wallSide = if (alongX) 0 else 1,
                        )
                    }
                }
            }

            for (want in program) {
                var budget = if (want.perTiles <= 0) want.cap else {
                    minOf(want.cap, (room.usableTiles + want.perTiles - 1) / want.perTiles)
                }
                // A snug chamber sleeps its own under one roof: two beds would leave no
                // wall for a nightstand and no floor for a chest.
                if (want.kind == Kind.BED && room.usableTiles < 16) budget = minOf(budget, 1)
                budget = minOf(budget, (freeCount - keepFree).coerceAtLeast(0)) // keep the walking room
                var placed = 0
                for (side in sides) {
                    if (placed >= budget) break
                    val (dx, dy) = DIRS[side]
                    // Wall-adjacent candidates on this side, deterministically shuffled.
                    val spots = rng.shuffle(
                        buildable.filter { (x, y) ->
                            free(x, y) &&
                                f.tiles.getOrNull(y + dy)?.getOrNull(x + dx)?.let {
                                    it == Tile.WALL || it == Tile.WINDOW
                                } == true
                        },
                    )
                    for ((x, y) in spots) {
                        if (placed >= budget) break
                        val twoTile = want.kind == Kind.BED || want.kind == Kind.SOFA || want.kind == Kind.BENCH
                        val tiles = if (twoTile) 2 else 1
                        if (freeCount - tiles < keepFree) break
                        // A two-tile piece's foot must land on a buildable tile too — the
                        // foot steps inward, away from the wall the head backs onto.
                        val fx = x - dx
                        val fy = y - dy
                        if (twoTile) {
                            val footOk = f.tiles.getOrNull(fy)?.getOrNull(fx) == Tile.FLOOR &&
                                placeable(f, fx, fy) && free(fx, fy)
                            if (!footOk) continue
                        }
                        // A piece may not seal any free floor of the room away from its
                        // doors: every tile a body could stand on stays reachable.
                        occupied.add(key(x, y))
                        if (twoTile) occupied.add(key(fx, fy))
                        if (sealsLane(f, room, occupied, entries)) {
                            occupied.remove(key(x, y))
                            if (twoTile) occupied.remove(key(fx, fy))
                            continue
                        }
                        val (hw, hd) = halfExtents(want.kind, side)
                        val along = if (twoTile) 0.5f else 0f
                        out += Piece(
                            floor = f.level,
                            kind = want.kind,
                            tx = x - dx * along,
                            ty = y - dy * along,
                            hw = hw, hd = hd, wallSide = side,
                        )
                        occupied.add(key(x, y))
                        if (twoTile) occupied.add(key(fx, fy))
                        freeCount -= tiles
                        placed++
                    }
                }
            }
        }
        return rugs + out
    }

    /** A tile furniture may claim: walkable floor, and never right against a door or the stairs. */
    private fun placeable(f: FloorPlan, x: Int, y: Int): Boolean {
        if (f.tiles.getOrNull(y)?.getOrNull(x) != Tile.FLOOR) return false
        for ((dx, dy) in DIRS) {
            val t = f.tiles.getOrNull(y + dy)?.getOrNull(x + dx)
            if (t == Tile.DOOR || t == Tile.STAIRS) return false
        }
        return true
    }

    /**
     * False when the currently claimed tiles seal some free floor of the room away from
     * every doorway and stair: flood from the entries over unclaimed walkable tiles and
     * require every free floor tile of the room to be reached.
     */
    private fun sealsLane(
        f: FloorPlan,
        room: Room,
        occupied: Set<Long>,
        entries: List<Pair<Int, Int>>,
    ): Boolean {
        fun key(tx: Int, ty: Int) = ty.toLong() * 10000L + tx
        fun open(x: Int, y: Int): Boolean {
            val t = f.tiles.getOrNull(y)?.getOrNull(x) ?: return false
            return (t == Tile.FLOOR || t == Tile.DOOR || t == Tile.STAIRS) && key(x, y) !in occupied
        }
        val reached = HashSet<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        for ((x, y) in entries) if (open(x, y) && reached.add(x to y)) queue.add(x to y)
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            for ((dx, dy) in DIRS) {
                val n = x + dx to y + dy
                if (n !in reached && open(n.first, n.second) && reached.add(n)) queue.add(n)
            }
        }
        for (y in room.rect.y until room.rect.y + room.rect.h) {
            for (x in room.rect.x until room.rect.x + room.rect.w) {
                if (f.tiles.getOrNull(y)?.getOrNull(x) == Tile.FLOOR &&
                    key(x, y) !in occupied && (x to y) !in reached
                ) return true
            }
        }
        return false
    }

    /** Half extents in tiles, turned to face the wall the piece backs onto. */
    private fun halfExtents(kind: Kind, side: Int): Pair<Float, Float> = when (kind) {
        Kind.BED, Kind.SOFA -> if (side == 1 || side == 3) 0.95f to 0.45f else 0.45f to 0.95f
        Kind.BENCH -> if (side == 1 || side == 3) 0.9f to 0.38f else 0.38f to 0.9f
        Kind.HEARTH, Kind.FORGE -> if (side == 1 || side == 3) 0.25f to 0.45f else 0.45f to 0.25f
        Kind.COUNTER, Kind.CHEST -> if (side == 1 || side == 3) 0.32f to 0.42f else 0.42f to 0.32f
        Kind.NIGHTSTAND, Kind.PLANT -> 0.26f to 0.26f
        else -> 0.42f to 0.42f
    }
}
