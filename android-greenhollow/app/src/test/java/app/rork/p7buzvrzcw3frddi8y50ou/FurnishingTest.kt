package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentRegistry
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.ArchitecturalProfiles
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingOptions
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Furnishings
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Tile
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.generateBuilding
import app.rork.p7buzvrzcw3frddi8y50ou.render.InteriorMesh
import app.rork.p7buzvrzcw3frddi8y50ou.sim.ActiveInterior
import app.rork.p7buzvrzcw3frddi8y50ou.sim.furnitureBoxes
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * Interiors livability pass: every enterable building's interior is furnished from its own
 * rooms — deterministically, never in a doorway or on the stairs, always with room to walk —
 * the renderer bakes the same pieces the collision makes solid, and the great rooms the
 * openness pass grew are actually furnished like the spaces they are. Run against the real
 * production packs.
 */
class FurnishingTest {

    private val registry: ContentRegistry by lazy {
        val dir = TestPacks.moduleDir()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        TestPacks.build(
            *listOf("core.json", "emberfall.json").map { name ->
                json.decodeFromString<ContentPack>(File(dir, "src/main/assets/content/$name").readText())
            }.toTypedArray(),
        )
    }

    /** Every building the player can walk into, with its cached interior plan. */
    private fun enterable(): List<Pair<String, Building>> = registry.buildings.values.mapNotNull { def ->
        val plan = BuildingCache.interiorPlan(def) ?: return@mapNotNull null
        def.id to plan
    }

    private fun roomOf(plan: Building, floor: Int, tx: Int, ty: Int) =
        plan.floors.firstOrNull { it.level == floor }?.rooms?.firstOrNull { r ->
            tx >= r.rect.x && tx < r.rect.x + r.rect.w && ty >= r.rect.y && ty < r.rect.y + r.rect.h
        }

    private fun Furnishings.Piece.tile() = tx.roundToInt() to ty.roundToInt()

    @Test
    fun `every enterable core building grows furnishings`() {
        var withFurniture = 0
        for ((id, plan) in enterable()) {
            val pieces = Furnishings.plan(plan)
            assertTrue("$id has no furniture at all", pieces.isNotEmpty())
            if (pieces.isNotEmpty()) withFurniture++
        }
        assertTrue("expected most buildings furnished, got $withFurniture", withFurniture >= 10)
    }

    @Test
    fun `rooms hold the furniture that makes them what they are`() {
        var bedchambers = 0
        var kitchens = 0
        var forges = 0
        var shops = 0
        var commonRooms = 0
        for ((id, plan) in enterable()) {
            val pieces = Furnishings.plan(plan)
            for (f in plan.floors) for (room in f.rooms) {
                val inRoom = pieces.filter {
                    it.floor == f.level && it.tile().let { (tx, ty) ->
                        tx >= room.rect.x && tx < room.rect.x + room.rect.w &&
                            ty >= room.rect.y && ty < room.rect.y + room.rect.h
                    }
                }
                val kinds = inRoom.map { it.kind }.toSet()
                fun assertHas(kind: Furnishings.Kind) {
                    assertTrue(
                        "$id floor ${f.level} ${room.kind} has no $kind (holds ${inRoom.map { p -> p.kind }})",
                        kind in kinds,
                    )
                }
                when (room.kind) {
                    "bedchamber", "guest chamber" -> { assertHas(Furnishings.Kind.BED); bedchambers++ }
                    "kitchen" -> { assertHas(Furnishings.Kind.HEARTH); kitchens++ }
                    "forge" -> { assertHas(Furnishings.Kind.FORGE); assertHas(Furnishings.Kind.ANVIL); forges++ }
                    "shop" -> { assertHas(Furnishings.Kind.COUNTER); shops++ }
                    "common room" -> { assertHas(Furnishings.Kind.HEARTH); assertHas(Furnishings.Kind.TABLE); commonRooms++ }
                    "parlor" -> assertHas(Furnishings.Kind.HEARTH)
                }
            }
        }
        assertTrue("no bedchambers exercised", bedchambers >= 5)
        assertTrue("no kitchens exercised", kitchens >= 3)
        assertTrue("no forge exercised", forges >= 1)
        assertTrue("no shop exercised", shops >= 1)
        assertTrue("no common room exercised", commonRooms >= 1)
    }

    @Test
    fun `furniture never blocks a door, the stairs, or a wall`() {
        val dirs = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)
        for ((id, plan) in enterable()) {
            for (p in Furnishings.plan(plan)) {
                val f = plan.floors.first { it.level == p.floor }
                val (tx, ty) = p.tile()
                assertEquals(
                    "$id piece $p sits off the room grid",
                    Tile.FLOOR,
                    f.tiles.getOrNull(ty)?.getOrNull(tx) ?: -1,
                )
                for ((dx, dy) in dirs) {
                    val t = f.tiles.getOrNull(ty + dy)?.getOrNull(tx + dx)
                    assertTrue(
                        "$id piece $p stands against a door or the stairs",
                        t != Tile.DOOR && t != Tile.STAIRS,
                    )
                }
                assertNotNull("$id piece $p belongs to no room", roomOf(plan, p.floor, tx, ty))
            }
        }
    }

    @Test
    fun `every room keeps a quarter of its floor to walk on`() {
        val dirs = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)
        for ((id, plan) in enterable()) {
            val pieces = Furnishings.plan(plan)
            for (f in plan.floors) for (room in f.rooms) {
                // Blocked exactly where collision blocks: the union of every solid
                // piece's box, in tiles — a box covers a tile when it covers its centre.
                val solid = pieces.filter { it.floor == f.level && it.kind in Furnishings.SOLID }
                fun blocked(x: Int, y: Int): Boolean = solid.any { p ->
                    kotlin.math.abs(x - p.tx) <= p.hw + 1e-4f && kotlin.math.abs(y - p.ty) <= p.hd + 1e-4f
                }
                // The room's furnishable floor: tiles furniture could ever have claimed.
                var candidates = 0
                var free = 0
                for (y in room.rect.y until room.rect.y + room.rect.h) {
                    for (x in room.rect.x until room.rect.x + room.rect.w) {
                        if (f.tiles.getOrNull(y)?.getOrNull(x) != Tile.FLOOR) continue
                        val nearTraffic = dirs.any { (dx, dy) ->
                            f.tiles.getOrNull(y + dy)?.getOrNull(x + dx).let { it == Tile.DOOR || it == Tile.STAIRS }
                        }
                        if (nearTraffic) continue
                        candidates++
                        if (!blocked(x, y)) free++
                    }
                }
                // A quarter of that same floor stays clear — two is the closet floor.
                val keep = maxOf(2, (candidates + 3) / 4)
                assertTrue(
                    "$id floor ${f.level} ${room.kind} leaves only $free open tiles (wants $keep)",
                    free >= keep,
                )
            }
        }
    }

    @Test
    fun `every walkable tile reaches a door without squeezing past furniture`() {
        // Livability, not just clearance: from every doorway, the unclaimed floor must
        // flood through the whole floor — no furniture row a body has to squeeze past,
        // no corner cut off by a bed. Tile resolution matches the collision's box centres.
        val dirs = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)
        for ((id, plan) in enterable()) {
            val pieces = Furnishings.plan(plan)
            for (f in plan.floors) {
                // Blocked exactly where collision blocks: the union of every solid
                // piece's box, in tiles — a box covers a tile when it covers its centre.
                val solid = pieces.filter { it.floor == f.level && it.kind in Furnishings.SOLID }
                fun blocked(x: Int, y: Int): Boolean = solid.any { p ->
                    kotlin.math.abs(x - p.tx) <= p.hw + 1e-4f && kotlin.math.abs(y - p.ty) <= p.hd + 1e-4f
                }
                fun walkable(x: Int, y: Int): Boolean {
                    val t = f.tiles.getOrNull(y)?.getOrNull(x) ?: return false
                    return (t == Tile.FLOOR || t == Tile.DOOR || t == Tile.STAIRS) && !blocked(x, y)
                }
                val reached = HashSet<Pair<Int, Int>>()
                val queue = ArrayDeque<Pair<Int, Int>>()
                for (y in f.tiles.indices) for (x in f.tiles[y].indices) {
                    if (f.tiles[y][x] == Tile.DOOR) {
                        queue.add(x to y); reached.add(x to y)
                    }
                }
                while (queue.isNotEmpty()) {
                    val (x, y) = queue.removeFirst()
                    for ((dx, dy) in dirs) {
                        val n = x + dx to y + dy
                        if (n !in reached && walkable(n.first, n.second)) {
                            reached.add(n); queue.add(n)
                        }
                    }
                }
                for (y in f.tiles.indices) for (x in f.tiles[y].indices) {
                    if (walkable(x, y)) {
                        val room = f.rooms.firstOrNull { r ->
                            x >= r.rect.x && x < r.rect.x + r.rect.w && y >= r.rect.y && y < r.rect.y + r.rect.h
                        }
                        assertTrue(
                            "$id floor ${f.level} tile ($x,$y) in ${room?.kind ?: "?"} is walled off by furniture",
                            x to y in reached,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the same plan always furnishes the same`() {
        for ((_, plan) in enterable()) {
            assertEquals(Furnishings.plan(plan), Furnishings.plan(plan))
        }
        // And a freshly grown building from the same seed furnishes identically.
        val b = generateBuilding(BuildingOptions(seed = 7L, profile = ArchitecturalProfiles.MANOR))
        val again = generateBuilding(BuildingOptions(seed = 7L, profile = ArchitecturalProfiles.MANOR))
        assertEquals(Furnishings.plan(b), Furnishings.plan(again))
    }

    @Test
    fun `interior collision is solid where the furniture stands`() {
        for ((id, plan) in enterable()) {
            val inst = ActiveInterior("int:$id", id, plan)
            val boxes = furnitureBoxes(plan, inst.furnishings)
            val solid = inst.furnishings.filter { it.kind in Furnishings.SOLID }
            assertEquals(solid.size, boxes.size)
            for (p in solid) {
                val (across, along) = Furnishings.solidHalfExtents(p.kind)
                val alongX = p.wallSide == 1 || p.wallSide == 3
                val hw = if (alongX) along else across
                val hd = if (alongX) across else along
                val cx = Furnishings.localX(plan, p.tx)
                val cz = Furnishings.localZ(plan, p.ty)
                val hit = boxes.any { (level, b) ->
                    level == p.floor &&
                        kotlin.math.abs(b.cx - cx) < 0.05f && kotlin.math.abs(b.cz - cz) < 0.05f &&
                        kotlin.math.abs(b.hw - hw) < 0.05f && kotlin.math.abs(b.hd - hd) < 0.05f
                }
                assertTrue("$id solid piece $p has no collision box", hit)
            }
            // Stools, shelves, rugs and plants stay walkable: no collision box for them.
            val walkable = inst.furnishings.filter {
                it.kind == Furnishings.Kind.STOOL || it.kind == Furnishings.Kind.SHELF ||
                    it.kind == Furnishings.Kind.RUG || it.kind == Furnishings.Kind.PLANT
            }
            for (p in walkable) {
                val cx = Furnishings.localX(plan, p.tx)
                val cz = Furnishings.localZ(plan, p.ty)
                val blocked = boxes.any { (level, b) ->
                    level == p.floor && kotlin.math.abs(b.cx - cx) < 0.05f && kotlin.math.abs(b.cz - cz) < 0.05f
                }
                assertTrue("$id walkable piece $p was boxed solid", !blocked)
            }
        }
    }

    @Test
    fun `the interior renderer bakes the furnishing deterministically`() {
        for ((id, plan) in enterable()) {
            val a = InteriorMesh.bakeLocal(ActiveInterior("int:$id", id, plan))
            val b = InteriorMesh.bakeLocal(ActiveInterior("int:$id", id, plan))
            assertEquals("$id interior bake differs between instances", a.vertexCount, b.vertexCount)
            assertTrue(a.data.contentEquals(b.data))
        }
        // The smithy's forge glows: furnishing carries the glow encoding into interiors.
        val smithy = enterable().first { it.first == "smithy" }
        val mesh = InteriorMesh.bakeLocal(ActiveInterior("int:smithy", "smithy", smithy.second))
        var glows = 0
        var i = 3
        while (i + 6 <= mesh.size) {
            val nx = mesh.data[i]; val ny = mesh.data[i + 1]; val nz = mesh.data[i + 2]
            if (nx * nx + ny * ny + nz * nz >= 1.5f * 1.5f) glows++
            i += 9
        }
        assertTrue("smithy interior has no glowing forge or embers", glows > 0)
    }

    @Test
    fun `homes are spacious enough to live in`() {
        for (seed in 0L until 30L) {
            val b = generateBuilding(BuildingOptions(seed = seed, profile = ArchitecturalProfiles.COTTAGE))
            assertTrue(
                "cottage seed $seed grew only ${b.width}x${b.height} tiles",
                b.width >= 13 && b.height >= 10,
            )
            val ground = b.floors.first()
            val primary = ground.rooms.filter { it.band == "south" }
            assertTrue("cottage seed $seed grew ${ground.rooms}", primary.size == 1)
            assertTrue(
                "cottage seed $seed primary space has only ${primary[0].usableTiles} usable tiles",
                primary[0].usableTiles >= 35,
            )
            val bedchamber = ground.rooms.firstOrNull { it.kind == "bedchamber" }
            assertNotNull("cottage seed $seed grew no bedchamber", bedchamber)
            val chamber = requireNotNull(bedchamber)
            assertTrue(
                "cottage seed $seed bedchamber is only ${chamber.rect.h} deep",
                chamber.rect.h >= 3,
            )
        }
    }

    @Test
    fun `parlors gather a sofa and a rug, bedchambers a nightstand`() {
        var parlors = 0
        var bedchambers = 0
        for ((id, plan) in enterable()) {
            val pieces = Furnishings.plan(plan)
            for (f in plan.floors) for (room in f.rooms) {
                val inRoom = pieces.filter {
                    it.floor == f.level && it.tile().let { (tx, ty) ->
                        tx >= room.rect.x && tx < room.rect.x + room.rect.w &&
                            ty >= room.rect.y && ty < room.rect.y + room.rect.h
                    }
                }
                val kinds = inRoom.map { it.kind }.toSet()
                when (room.kind) {
                    "parlor" -> {
                        assertTrue(
                            "$id parlor holds no sofa (holds ${inRoom.map { p -> p.kind }})",
                            Furnishings.Kind.SOFA in kinds,
                        )
                        assertTrue(
                            "$id parlor holds no rug (holds ${inRoom.map { p -> p.kind }})",
                            Furnishings.Kind.RUG in kinds,
                        )
                        parlors++
                    }
                    "bedchamber", "guest chamber" -> {
                        assertTrue(
                            "$id ${room.kind} holds no nightstand (holds ${inRoom.map { p -> p.kind }})",
                            Furnishings.Kind.NIGHTSTAND in kinds,
                        )
                        bedchambers++
                    }
                }
            }
        }
        assertTrue("no parlors exercised", parlors >= 5)
        assertTrue("no bedchambers exercised", bedchambers >= 5)
    }

    @Test
    fun `open ground floors and deep great rooms are the norm now`() {
        // Manor and tavern grounds merge into open halls most of the time; the great rooms
        // that survive validation always run four tiles deep.
        for ((prof, minOpen, name) in listOf(
            Triple(ArchitecturalProfiles.MANOR, 0.6, "manor"),
            Triple(ArchitecturalProfiles.TAVERN, 0.7, "tavern"),
        )) {
            var open = 0
            for (seed in 0L until 60L) {
                val b = generateBuilding(BuildingOptions(seed = seed, profile = prof))
                val ground = b.floors.first()
                if (ground.features.any { it.startsWith("great hall") || it.startsWith("open") }) open++
                for (room in ground.rooms) {
                    if (room.kind == prof.frontGreat) {
                        assertTrue(
                            "$name seed $seed great room only ${room.rect.h} deep",
                            room.rect.h >= 4,
                        )
                    }
                }
            }
            assertTrue(
                "$name open-hall rate only $open/60",
                open >= 60 * minOpen,
            )
        }
    }
}
