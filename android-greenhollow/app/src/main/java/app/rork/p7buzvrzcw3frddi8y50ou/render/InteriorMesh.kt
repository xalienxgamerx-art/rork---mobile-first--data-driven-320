package app.rork.p7buzvrzcw3frddi8y50ou.render

import app.rork.p7buzvrzcw3frddi8y50ou.procgen.DOOR_H
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.FloorPlan
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Furnishings
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.STORY
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.TILE_SIZE
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Tile
import app.rork.p7buzvrzcw3frddi8y50ou.sim.ActiveInterior

/**
 * Turns the active interior instance's tile plan into geometry in the interior's OWN local
 * space — the overworld never sees a vertex of it, and the plan's cached exterior mesh stays
 * exactly as it is. Read from the inside, the same generator that built the shell now dresses
 * the rooms: plank floors underfoot, plaster walls, doorways left open (jambs and a header,
 * no leaf — you see the way out), glowing windows, posts, stairs, and per-floor ceilings that
 * leave the stairwell open. The single-slot cache keys on the instance itself, so entering a
 * different building replaces the mesh instead of accumulating them.
 */
object InteriorMesh {
    private const val WINDOW_SILL = 0.9f
    private const val WINDOW_TOP = 1.9f
    private val TIMBER = floatArrayOf(0.30f, 0.21f, 0.14f)
    private val PLASTER = floatArrayOf(0.80f, 0.74f, 0.62f)
    private val PLANK = floatArrayOf(0.47f, 0.36f, 0.23f)
    private val CEILING = floatArrayOf(0.58f, 0.53f, 0.43f)
    private val SOLID = intArrayOf(Tile.WALL, Tile.FLOOR, Tile.BEAM, Tile.STAIRS, Tile.DOOR, Tile.WINDOW)

    /** The baked combined model behind one interior instance; [key] is compared by identity. */
    class Entry(val key: ActiveInterior, val model: BuildingModels.BakedModel) {
        val mesh: MeshBuilder get() = model.mesh
        val vertexCount get() = model.vertexCount
    }

    private var cache: Entry? = null

    /**
     * The active interior's combined model, rebuilt when a different instance becomes
     * active or the building's structure was invalidated since the bake (stale models are
     * never served). Unloading the slot never touches the structure or persistent state.
     */
    @Synchronized
    fun get(inst: ActiveInterior?): Entry? {
        if (inst == null) {
            cache = null
            return null
        }
        cache?.let {
            if (it.key === inst &&
                it.model.bakeVersion == BuildingModels.BAKE_VERSION &&
                it.model.planVersion == BuildingModels.revision(inst.buildingId)
            ) {
                return it
            }
        }
        val model = BuildingModels.bakeInterior(inst) ?: return null
        return Entry(inst, model).also { cache = it }
    }

    /** Bakes the interior instance's static architecture into one local-space model. */
    fun bakeLocal(inst: ActiveInterior): MeshBuilder {
        val m = MeshBuilder(1 shl 18)
        emit(m, inst)
        return m
    }

    private fun emit(m: MeshBuilder, inst: ActiveInterior) {
        val gen = inst.plan
        // The builder's transform stays at the origin: every coordinate below is already
        // interior-local, which is the whole point of the instance's own space.
        m.place(0f, 0f, 0f, 0f)
        val ex = gen.width * TILE_SIZE / 2f + 0.4f
        val ez = gen.height * TILE_SIZE / 2f + 0.4f
        // Dark ground under the house, so no gap ever shows the sky through the floor.
        m.box(0f, -0.18f, 0f, ex, 0.18f, ez, 0.28f, 0.26f, 0.23f)
        val top = gen.floors.maxOf { it.level }
        for (f in gen.floors) {
            emitFloor(m, gen, f)
            if (f.level < top) emitCeiling(m, gen, f)
        }
        emitRoof(m, gen)
        emitFurnishings(m, inst)
    }

    /**
     * Dresses the rooms from the plan's own furnishing: the same deterministic pieces the
     * interior's collision grows solid boxes for. Every kind is drawn in its own orientation —
     * beds back onto their wall, hearths glow into the room.
     */
    private fun emitFurnishings(m: MeshBuilder, inst: ActiveInterior) {
        val gen = inst.plan
        for (p in inst.furnishings) {
            val x = Furnishings.localX(gen, p.tx)
            val z = Furnishings.localZ(gen, p.ty)
            val base = p.floor * STORY
            // Local-space direction the piece's depth runs, from its wall into the room.
            val (ddx, ddz) = when (p.wallSide) {
                0 -> 0f to -1f  // backs north (row 0) — local +z; depth runs to −z
                2 -> 0f to 1f
                1 -> -1f to 0f  // backs east — local −x; depth runs to −x
                else -> 1f to 0f
            }
            val acrossX = ddx == 0f
            val wood = floatArrayOf(0.42f, 0.29f, 0.16f)
            val darkWood = floatArrayOf(0.30f, 0.21f, 0.13f)

            when (p.kind) {
                Furnishings.Kind.BED -> {
                    // Frame, mattress, pillow at the head (wall end), blanket at the foot.
                    val ex = if (acrossX) 0.45f else 0.95f
                    val ez = if (acrossX) 0.95f else 0.45f
                    m.box(x, base + 0.16f, z, ex, 0.16f, ez, wood[0], wood[1], wood[2])
                    m.box(x, base + 0.36f, z, ex * 0.9f, 0.09f, ez * 0.92f, 0.86f, 0.8f, 0.66f)
                    m.box(x + ddx * 0.62f, base + 0.46f, z + ddz * 0.62f, ex * 0.5f, 0.06f, ez * 0.5f, 0.93f, 0.9f, 0.82f)
                    m.box(x - ddx * 0.35f, base + 0.44f, z - ddz * 0.35f, ex * 0.88f, 0.05f, ez * 0.88f, 0.5f, 0.3f, 0.24f)
                    // Headboard against the wall.
                    m.box(
                        x + ddx * 0.88f, base + 0.55f, z + ddz * 0.88f,
                        if (acrossX) 0.06f else ex, 0.4f, if (acrossX) ez else 0.06f,
                        darkWood[0], darkWood[1], darkWood[2],
                    )
                }

                Furnishings.Kind.TABLE -> {
                    m.box(x, base + 0.72f, z, 0.55f, 0.05f, 0.55f, wood[0] * 1.1f, wood[1] * 1.1f, wood[2] * 1.1f)
                    for ((sx, sz) in listOf(-0.42f to -0.42f, 0.42f to -0.42f, -0.42f to 0.42f, 0.42f to 0.42f)) {
                        m.box(x + sx, base + 0.35f, z + sz, 0.05f, 0.35f, 0.05f, darkWood[0], darkWood[1], darkWood[2])
                    }
                }

                Furnishings.Kind.STOOL -> {
                    m.box(x, base + 0.11f, z, 0.14f, 0.11f, 0.14f, darkWood[0], darkWood[1], darkWood[2])
                    m.box(x, base + 0.26f, z, 0.2f, 0.04f, 0.2f, wood[0], wood[1], wood[2])
                }

                Furnishings.Kind.HEARTH -> {
                    // Stone surround against the wall, glowing firebox, timber mantel.
                    m.box(x, base + 0.6f, z, if (acrossX) 0.25f else 0.45f, 0.6f, if (acrossX) 0.45f else 0.25f, 0.52f, 0.5f, 0.47f)
                    m.glow = true
                    m.box(x - ddx * 0.18f, base + 0.26f, z - ddz * 0.18f, if (acrossX) 0.1f else 0.3f, 0.16f, if (acrossX) 0.3f else 0.1f, 1.0f, 0.55f, 0.22f)
                    m.glow = false
                    m.box(x, base + 1.24f, z, if (acrossX) 0.3f else 0.5f, 0.06f, if (acrossX) 0.5f else 0.3f, darkWood[0], darkWood[1], darkWood[2])
                }

                Furnishings.Kind.SHELF -> {
                    // Back panel flush to the wall, three boards, a few things on them.
                    m.box(x + ddx * 0.36f, base + 0.8f, z + ddz * 0.36f, if (acrossX) 0.03f else 0.42f, 0.8f, if (acrossX) 0.42f else 0.03f, darkWood[0], darkWood[1], darkWood[2])
                    for (h in listOf(0.35f, 0.75f, 1.15f)) {
                        m.box(x, base + h, z, if (acrossX) 0.18f else 0.44f, 0.03f, if (acrossX) 0.44f else 0.18f, wood[0], wood[1], wood[2])
                        m.box(x - ddx * 0.08f, base + h + 0.1f, z - ddz * 0.08f, if (acrossX) 0.1f else 0.3f, 0.07f, if (acrossX) 0.3f else 0.1f, 0.56f, 0.42f, 0.28f)
                    }
                }

                Furnishings.Kind.CHEST -> {
                    m.box(x, base + 0.22f, z, 0.4f, 0.22f, 0.32f, 0.42f, 0.27f, 0.14f)
                    m.box(x, base + 0.48f, z, 0.42f, 0.05f, 0.34f, 0.33f, 0.21f, 0.11f)
                    m.box(x, base + 0.3f, z, 0.08f, 0.1f, 0.04f, 0.74f, 0.6f, 0.28f)
                }

                Furnishings.Kind.COUNTER -> {
                    m.box(x, base + 0.5f, z, if (acrossX) 0.3f else 0.44f, 0.5f, if (acrossX) 0.44f else 0.3f, darkWood[0], darkWood[1], darkWood[2])
                    m.box(x, base + 1.04f, z, if (acrossX) 0.34f else 0.48f, 0.05f, if (acrossX) 0.48f else 0.34f, wood[0] * 1.15f, wood[1] * 1.15f, wood[2] * 1.15f)
                }

                Furnishings.Kind.ANVIL -> {
                    m.box(x, base + 0.2f, z, 0.24f, 0.2f, 0.24f, 0.32f, 0.31f, 0.3f)
                    m.box(x, base + 0.5f, z, 0.34f, 0.09f, 0.15f, 0.24f, 0.24f, 0.26f)
                }

                Furnishings.Kind.FORGE -> {
                    m.box(x, base + 0.35f, z, if (acrossX) 0.22f else 0.45f, 0.35f, if (acrossX) 0.45f else 0.22f, 0.45f, 0.42f, 0.4f)
                    m.glow = true
                    m.box(x - ddx * 0.12f, base + 0.74f, z - ddz * 0.12f, if (acrossX) 0.12f else 0.32f, 0.06f, if (acrossX) 0.32f else 0.12f, 1.0f, 0.45f, 0.15f)
                    m.glow = false
                }

                Furnishings.Kind.BARREL -> {
                    m.box(x, base + 0.42f, z, 0.3f, 0.42f, 0.3f, wood[0] * 1.05f, wood[1] * 1.05f, wood[2] * 1.05f)
                    m.box(x, base + 0.16f, z, 0.32f, 0.03f, 0.32f, 0.28f, 0.28f, 0.3f)
                    m.box(x, base + 0.66f, z, 0.32f, 0.03f, 0.32f, 0.28f, 0.28f, 0.3f)
                }

                Furnishings.Kind.RUG -> {
                    // Flat underfoot, a hand-span above the plank top: border, field, pattern.
                    val rex = if (acrossX) 0.85f else 0.55f
                    val rez = if (acrossX) 0.55f else 0.85f
                    m.box(x, base + 0.135f, z, rex, 0.015f, rez, 0.44f, 0.19f, 0.15f)
                    m.box(x, base + 0.156f, z, rex * 0.8f, 0.012f, rez * 0.76f, 0.56f, 0.31f, 0.21f)
                    m.box(x, base + 0.172f, z, rex * 0.3f, 0.008f, rez * 0.22f, 0.62f, 0.42f, 0.26f)
                }

                Furnishings.Kind.SOFA -> {
                    // Upholstered settle: seat, bolster back against the wall, rolled arms.
                    val sex = if (acrossX) 0.42f else 0.2f
                    val sez = if (acrossX) 0.2f else 0.42f
                    m.box(x, base + 0.24f, z, if (acrossX) 0.44f else 0.2f, 0.12f, if (acrossX) 0.2f else 0.44f, 0.5f, 0.24f, 0.19f)
                    m.box(x + ddx * 0.36f, base + 0.5f, z + ddz * 0.36f, sex, 0.26f, sez, 0.45f, 0.21f, 0.17f)
                    m.box(x, base + 0.38f, z, if (acrossX) 0.38f else 0.16f, 0.06f, if (acrossX) 0.16f else 0.38f, 0.58f, 0.3f, 0.23f)
                    val armX = if (acrossX) 0.44f else 0f
                    val armZ = if (acrossX) 0f else 0.44f
                    m.box(x - armX, base + 0.38f, z - armZ, if (acrossX) 0.06f else 0.18f, 0.14f, if (acrossX) 0.18f else 0.06f, 0.45f, 0.21f, 0.17f)
                    m.box(x + armX, base + 0.38f, z + armZ, if (acrossX) 0.06f else 0.18f, 0.14f, if (acrossX) 0.18f else 0.06f, 0.45f, 0.21f, 0.17f)
                }

                Furnishings.Kind.BENCH -> {
                    m.box(x, base + 0.26f, z, if (acrossX) 0.9f else 0.18f, 0.05f, if (acrossX) 0.18f else 0.9f, wood[0] * 1.1f, wood[1] * 1.1f, wood[2] * 1.1f)
                    val lx = if (acrossX) 0.74f else 0.1f
                    val lz = if (acrossX) 0.1f else 0.74f
                    for ((sx, sz) in listOf(-lx to -lz, lx to -lz, -lx to lz, lx to lz)) {
                        m.box(x + sx, base + 0.12f, z + sz, 0.05f, 0.12f, 0.05f, darkWood[0], darkWood[1], darkWood[2])
                    }
                }

                Furnishings.Kind.NIGHTSTAND -> {
                    m.box(x, base + 0.26f, z, 0.24f, 0.26f, 0.24f, darkWood[0], darkWood[1], darkWood[2])
                    m.box(x, base + 0.53f, z, 0.27f, 0.03f, 0.27f, wood[0], wood[1], wood[2])
                    // A candle left burning: cream stub over a warm glow.
                    m.box(x, base + 0.6f, z, 0.045f, 0.06f, 0.045f, 0.9f, 0.86f, 0.72f)
                    m.glow = true
                    m.box(x, base + 0.69f, z, 0.025f, 0.03f, 0.025f, 1.0f, 0.72f, 0.3f)
                    m.glow = false
                }

                Furnishings.Kind.CHAIR -> {
                    // Four legs, a seat at sitting height, a back slat against the wall:
                    // the seat height is what the sitting pose is tuned to.
                    val legO = 0.12f
                    for ((sx, sz) in listOf(-legO to -legO, legO to -legO, -legO to legO, legO to legO)) {
                        m.box(x + sx, base + 0.21f, z + sz, 0.03f, 0.21f, 0.03f, darkWood[0], darkWood[1], darkWood[2])
                    }
                    m.box(x, base + 0.45f, z, if (acrossX) 0.17f else 0.16f, 0.025f, if (acrossX) 0.16f else 0.17f, wood[0], wood[1], wood[2])
                    // Backrest: two posts and a slat, pushed toward the wall (+dd).
                    val bx = x + ddx * 0.14f
                    val bz = z + ddz * 0.14f
                    val px = if (acrossX) 0.13f else 0f
                    val pz = if (acrossX) 0f else 0.13f
                    m.box(bx - px, base + 0.62f, bz - pz, 0.03f, 0.18f, 0.03f, darkWood[0], darkWood[1], darkWood[2])
                    m.box(bx + px, base + 0.62f, bz + pz, 0.03f, 0.18f, 0.03f, darkWood[0], darkWood[1], darkWood[2])
                    m.box(bx, base + 0.8f, bz, if (acrossX) 0.16f else 0.03f, 0.05f, if (acrossX) 0.03f else 0.16f, wood[0], wood[1], wood[2])
                }

                Furnishings.Kind.PLANT -> {
                    m.box(x, base + 0.14f, z, 0.16f, 0.14f, 0.16f, 0.55f, 0.32f, 0.2f)
                    m.box(x, base + 0.29f, z, 0.18f, 0.03f, 0.18f, 0.48f, 0.28f, 0.17f)
                    m.box(x, base + 0.44f, z, 0.14f, 0.13f, 0.14f, 0.24f, 0.42f, 0.18f)
                    m.box(x - 0.07f, base + 0.6f, z + 0.05f, 0.1f, 0.08f, 0.1f, 0.28f, 0.47f, 0.2f)
                    m.box(x + 0.08f, base + 0.58f, z - 0.06f, 0.09f, 0.07f, 0.09f, 0.2f, 0.38f, 0.16f)
                }
            }
        }
    }

    private fun emitFloor(m: MeshBuilder, gen: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building, f: FloorPlan) {
        val base = f.level * STORY
        val cx = (gen.width - 1) / 2f
        val cz = (gen.height - 1) / 2f
        val tiles = f.tiles
        for (ty in tiles.indices) {
            for (tx in tiles[ty].indices) {
                val x = (tx - cx) * TILE_SIZE
                val z = (cz - ty) * TILE_SIZE
                when (tiles[ty][tx]) {
                    Tile.FLOOR -> m.box(x, base + 0.06f, z, TILE_SIZE / 2f, 0.06f, TILE_SIZE / 2f, PLANK[0], PLANK[1], PLANK[2])

                    Tile.WALL -> m.box(x, base + STORY / 2f, z, TILE_SIZE / 2f, STORY / 2f, TILE_SIZE / 2f, PLASTER[0], PLASTER[1], PLASTER[2])

                    Tile.DOOR -> {
                        // An open doorway: header and jambs, no leaf — the way out stays visible.
                        m.box(x, base + (STORY + DOOR_H) / 2f, z, TILE_SIZE / 2f, (STORY - DOOR_H) / 2f, 0.12f, PLASTER[0] * 0.94f, PLASTER[1] * 0.94f, PLASTER[2] * 0.94f)
                        m.box(x - TILE_SIZE / 2f + 0.07f, base + DOOR_H / 2f, z, 0.07f, DOOR_H / 2f, 0.12f, TIMBER[0], TIMBER[1], TIMBER[2])
                        m.box(x + TILE_SIZE / 2f - 0.07f, base + DOOR_H / 2f, z, 0.07f, DOOR_H / 2f, 0.12f, TIMBER[0], TIMBER[1], TIMBER[2])
                        m.box(x, base + 0.03f, z, TILE_SIZE / 2f, 0.03f, TILE_SIZE / 2f, 0.38f, 0.28f, 0.18f)
                    }

                    Tile.WINDOW -> {
                        m.box(x, base + WINDOW_SILL / 2f, z, TILE_SIZE / 2f, WINDOW_SILL / 2f, TILE_SIZE / 2f, PLASTER[0], PLASTER[1], PLASTER[2])
                        m.box(x, base + (STORY + WINDOW_TOP) / 2f, z, TILE_SIZE / 2f, (STORY - WINDOW_TOP) / 2f, TILE_SIZE / 2f, PLASTER[0], PLASTER[1], PLASTER[2])
                        m.glow = true
                        m.box(x, base + (WINDOW_SILL + WINDOW_TOP) / 2f, z, TILE_SIZE / 2f + 0.03f, (WINDOW_TOP - WINDOW_SILL) / 2f, TILE_SIZE / 2f + 0.03f, 1.0f, 0.78f, 0.38f)
                        m.glow = false
                    }

                    Tile.BEAM -> m.box(x, base + STORY / 2f, z, 0.09f, STORY / 2f, 0.09f, TIMBER[0], TIMBER[1], TIMBER[2])

                    Tile.STAIRS -> {
                        val col = tx - gen.stairs.x
                        val h = STORY * (col + 1) / 3f
                        m.box(x, base + h / 2f, z, TILE_SIZE / 2f, h / 2f, TILE_SIZE / 2f, PLANK[0] * 0.88f, PLANK[1] * 0.88f, PLANK[2] * 0.88f)
                    }

                    Tile.BALCONY -> m.box(x, base + 0.06f, z, TILE_SIZE / 2f, 0.06f, TILE_SIZE / 2f, PLANK[0] * 0.8f, PLANK[1] * 0.8f, PLANK[2] * 0.8f)

                    Tile.RAILING -> {
                        val alongX = (tx > 0 && tiles[ty][tx - 1] == Tile.RAILING) ||
                            (tx < tiles[ty].size - 1 && tiles[ty][tx + 1] == Tile.RAILING)
                        val ex2 = if (alongX) TILE_SIZE / 2f else 0.07f
                        val ez2 = if (alongX) 0.07f else TILE_SIZE / 2f
                        m.box(x, base + 0.5f, z, ex2, 0.5f, ez2, TIMBER[0], TIMBER[1], TIMBER[2])
                        m.box(x, base + 1.04f, z, ex2, 0.05f, ez2, 0.42f, 0.31f, 0.19f)
                    }
                }
            }
        }
    }

    /** A plaster ceiling under the next floor, everywhere but the stairwell and open air. */
    private fun emitCeiling(m: MeshBuilder, gen: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building, f: FloorPlan) {
        val above = gen.floors.firstOrNull { it.level == f.level + 1 } ?: return
        val base = f.level * STORY + STORY
        val cx = (gen.width - 1) / 2f
        val cz = (gen.height - 1) / 2f
        for (ty in f.tiles.indices) {
            for (tx in f.tiles[ty].indices) {
                val t = f.tiles[ty][tx]
                if (t == Tile.EMPTY || t == Tile.STAIRS) continue
                val up = above.tiles.getOrNull(ty)?.getOrNull(tx) ?: Tile.EMPTY
                // Above an empty tile is open air (a setback or a projection); above stairs is
                // the stairwell. Both stay open to the floor above.
                if (up == Tile.EMPTY || up == Tile.STAIRS) continue
                val x = (tx - cx) * TILE_SIZE
                val z = (cz - ty) * TILE_SIZE
                m.box(x, base - 0.05f, z, TILE_SIZE / 2f, 0.05f, TILE_SIZE / 2f, CEILING[0], CEILING[1], CEILING[2])
            }
        }
    }

    /** The underside of the roof over the topmost enclosed part; terraces stay open. */
    private fun emitRoof(m: MeshBuilder, gen: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building) {
        val top = gen.floors.last()
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in top.tiles.indices) {
            for (x in top.tiles[y].indices) {
                if (top.tiles[y][x] in SOLID) {
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                }
            }
        }
        if (minX > maxX) return

        val cx = (gen.width - 1) / 2f
        val cz = (gen.height - 1) / 2f
        val centreX = ((minX + maxX) / 2f - cx) * TILE_SIZE
        val centreZ = (cz - (minY + maxY) / 2f) * TILE_SIZE
        val ex = (maxX - minX + 1) / 2f * TILE_SIZE + 0.5f
        val ez = (maxY - minY + 1) / 2f * TILE_SIZE + 0.6f
        val y0 = top.level * STORY + STORY
        val rise = (maxY - minY + 1) * TILE_SIZE * 0.42f
        val r = 0.34f; val g = 0.24f; val b = 0.16f

        m.around(centreX, y0 + rise * 0.2f, centreZ)
        m.quad(centreX - ex, y0, centreZ - ez, centreX + ex, y0, centreZ - ez, centreX + ex, y0 + rise, centreZ, centreX - ex, y0 + rise, centreZ, r, g, b)
        m.quad(centreX - ex, y0, centreZ + ez, centreX + ex, y0, centreZ + ez, centreX + ex, y0 + rise, centreZ, centreX - ex, y0 + rise, centreZ, r * 0.9f, g * 0.9f, b * 0.9f)
        m.tri(centreX - ex, y0, centreZ - ez, centreX - ex, y0, centreZ + ez, centreX - ex, y0 + rise, centreZ, r * 0.8f, g * 0.8f, b * 0.8f)
        m.tri(centreX + ex, y0, centreZ - ez, centreX + ex, y0, centreZ + ez, centreX + ex, y0 + rise, centreZ, r * 0.8f, g * 0.8f, b * 0.8f)
    }
}
