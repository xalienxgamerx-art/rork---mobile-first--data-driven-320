package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDirectory
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentBuilder
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.ModManifest
import app.rork.p7buzvrzcw3frddi8y50ou.data.PackSource
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.ArchitecturalProfiles
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.ArchProfile
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingOptions
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.FloorPlan
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.LayoutStyle
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.RoomRequirements
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Tile
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.generateBuilding
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.validateBuilding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building Generator Quality Pass: adaptive floor plans, room proportions and doorway logic.
 * Every claim is demonstrated on generated plans across many seeds — cottages grow one warm
 * primary space with a single door and no one-tile strips; every profile's rooms meet the
 * central minimums; doorways are deliberate; and a full multi-seed report surfaces the stats.
 */
class BuildingQualityTest {

    private val profiles = listOf(
        ArchitecturalProfiles.DEFAULT,
        ArchitecturalProfiles.COTTAGE,
        ArchitecturalProfiles.TOWNHOUSE,
        ArchitecturalProfiles.TAVERN,
        ArchitecturalProfiles.WORKSHOP,
        ArchitecturalProfiles.MANOR,
        ArchitecturalProfiles.WAREHOUSE,
        ArchitecturalProfiles.CIVIC,
    )

    private fun generate(prof: ArchProfile, seed: Long) =
        generateBuilding(BuildingOptions(seed = seed, profile = prof))

    private fun footprint(b: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building): Pair<Int, Int> {
        val g = b.floors[0].tiles
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (y in g.indices) for (x in g[y].indices) {
            if (g[y][x] != Tile.EMPTY) {
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
        return (maxX - minX + 1) to (maxY - minY + 1)
    }

    /** Doors on the ground floor that open to open air, with the side they open on. */
    private fun exteriorDoors(b: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building): Int {
        val g = b.floors[0].tiles
        var n = 0
        for (y in g.indices) for (x in g[y].indices) {
            if (g[y][x] == Tile.DOOR &&
                listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) ->
                    g.getOrNull(y + dy)?.getOrNull(x + dx) == Tile.EMPTY
                }
            ) n++
        }
        return n
    }

    private fun doorCount(b: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building): Int =
        b.floors.sumOf { f -> f.tiles.sumOf { row -> row.count { it == Tile.DOOR } } }

    // ------------------------------------------------------- cottage: the redesign

    @Test
    fun `a cottage grows one warm primary space with exactly one exterior door`() {
        for (seed in 0L..49L) {
            val b = generate(ArchitecturalProfiles.COTTAGE, seed)
            val ground = b.floors[0]
            val doors = exteriorDoors(b)
            assertEquals(
                "cottage seed $seed grew $doors exterior doors; a home keeps one",
                1, doors,
            )
            val primary = ground.rooms.filter { it.band == "south" }
            assertTrue("cottage seed $seed grew ${ground.rooms}", primary.size == 1)
            assertEquals("the primary space is the parlor", "parlor", primary[0].kind)
            assertTrue(
                "cottage seed $seed's primary space is only ${primary[0].rect.h} deep",
                primary[0].rect.h >= 3,
            )
        }
    }

    @Test
    fun `a cottage keeps its kitchen and a real bedroom, never a one-tile strip`() {
        for (seed in 0L..49L) {
            val b = generate(ArchitecturalProfiles.COTTAGE, seed)
            val kinds = b.floors.flatMap { it.rooms }.map { it.kind }
            assertTrue("cottage seed $seed grew $kinds", "parlor" in kinds && "kitchen" in kinds)
            assertTrue("cottage seed $seed grew $kinds", "bedchamber" in kinds)
            for (floor in b.floors) {
                for (room in floor.rooms) {
                    assertTrue(
                        "cottage seed $seed grew a ${room.kind} ${room.rect.w}x${room.rect.h}",
                        room.rect.h >= 2 && room.rect.w >= 2,
                    )
                }
            }
        }
    }

    @Test
    fun `a two-floor cottage reaches its bedrooms by an honest stairwell`() {
        for (seed in 0L..29L) {
            val b = generateBuilding(
                BuildingOptions(seed = seed, floors = 2, profile = ArchitecturalProfiles.COTTAGE),
            )
            assertEquals(2, b.floors.size)
            assertTrue(
                "cottage seed $seed unsound: ${validateBuilding(b, ArchitecturalProfiles.COTTAGE)}",
                validateBuilding(b, ArchitecturalProfiles.COTTAGE).isEmpty(),
            )
            // The stair block sits in the primary space, and both floors agree on it.
            val keys = b.floors.map { f ->
                f.tiles.flatMapIndexed { y, row ->
                    row.mapIndexed { x, t -> if (t == Tile.STAIRS) "$x,$y" else null }.filterNotNull()
                }.sorted()
            }
            assertTrue(keys[0].isNotEmpty())
            assertEquals(keys[0], keys[1])
        }
    }

    // ------------------------------------------------------- room dimensions everywhere

    @Test
    fun `no profiled building grows a room under its minimum dimensions`() {
        for (prof in profiles) {
            for (seed in 0L..49L) {
                val b = generate(prof, seed)
                for (floor in b.floors) {
                    for (room in floor.rooms) {
                        val req = RoomRequirements.forKind(room.kind)
                        val usable = RoomRequirements.usableTiles(floor.tiles, room.rect)
                        assertTrue(
                            "${prof.name} seed $seed grew a ${room.kind} " +
                                "${room.rect.w}x${room.rect.h} ($usable usable) under " +
                                "${req.minW}x${req.minD}/${req.minArea}",
                            room.rect.w >= req.minW && room.rect.h >= req.minD && usable >= req.minArea,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `room metadata reports usable tiles and interior-local bounds`() {
        val pack = ContentPack(
            ModManifest(id = "town", name = "Town"),
            buildings = listOf(
                BuildingDef(id = "cot", name = "Cottage", x = 0f, z = 0f, procedural = true, profile = "cottage", genFloors = 2),
                BuildingDef(id = "manor", name = "Manor", x = 20f, z = 0f, procedural = true, profile = "manor"),
            ),
        )
        val reg = ContentBuilder.build(listOf(PackSource(pack, false)), emptySet())
        val dir = BuildingDirectory(reg)
        for (id in listOf("cot", "manor")) {
            val def = reg.buildings.getValue(id)
            val plan = BuildingCache.get(def)!!
            val logical = dir.logical(id)!!
            assertTrue(logical.rooms.isNotEmpty())
            for (room in logical.rooms) {
                assertTrue("the ${room.purpose} reported no usable tiles", room.usableTiles > 0)
                val b = room.bounds!!
                assertTrue(
                    "the ${room.purpose} bounds (${b.x}) sit outside the interior",
                    kotlin.math.abs(b.x) <= plan.width / 2f + 1f &&
                        kotlin.math.abs(b.z) <= plan.height / 2f + 1f,
                )
                // The declared usable tiles are plausible for the room's declared size.
                val floor = plan.floors.first { it.level == room.floor }
                assertTrue(
                    "the ${room.purpose} claims $room.usableTiles usable tiles",
                    room.usableTiles <= (b.w * b.d).toInt() + 4,
                )
                assertTrue(room.usableTiles > 0)
                assertTrue(floor.tiles.isNotEmpty())
            }
        }
    }

    // ------------------------------------------------------- doorway logic

    @Test
    fun `ordinary homes keep one entrance, service buildings at most their budget`() {
        for (seed in 0L..49L) {
            for (prof in profiles) {
                val b = generate(prof, seed)
                val doors = exteriorDoors(b)
                assertTrue(
                    "${prof.name} seed $seed grew $doors exterior doors, over its budget ${prof.maxExteriorDoors}",
                    doors in 1..prof.maxExteriorDoors,
                )
            }
        }
    }

    @Test
    fun `a cottage carries no random connecting doors`() {
        for (seed in 0L..49L) {
            val b = generate(ArchitecturalProfiles.COTTAGE, seed)
            val ground = b.floors[0]
            // Every ground door is either the entrance, or a deliberate door from a room
            // into the primary space (on the partition row).
            val partitionRow = ground.rooms.first { it.band == "south" }.rect.y - 1
            for (y in ground.tiles.indices) for (x in ground.tiles[y].indices) {
                if (ground.tiles[y][x] != Tile.DOOR) continue
                val exterior = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) ->
                    ground.tiles.getOrNull(y + dy)?.getOrNull(x + dx) == Tile.EMPTY
                }
                assertTrue(
                    "cottage seed $seed has a stray doorway at ($x,$y)",
                    exterior || y == partitionRow,
                )
            }
            // A home's doorway budget grows with its rooms: one door each, plus the
            // entrance and at most one spare — a bigger cottage earns more doors.
            assertTrue(
                "cottage seed $seed grew ${doorCount(b)} doors total — too many for a home",
                doorCount(b) <= b.floors.sumOf { it.rooms.size } + 2,
            )
        }
    }

    // ------------------------------------------------------- validation bites

    @Test
    fun `validation rejects one-tile-deep rooms, missing reachability and door gluts`() {
        val prof = ArchitecturalProfiles.COTTAGE
        val b = generate(prof, 3L)
        val ground = b.floors[0]

        // A room shaved to one tile deep fails.
        val shaved = FloorPlan(
            ground.level, ground.tiles, ground.rect, ground.material,
            ground.rooms.map { it.copy(rect = it.rect.copy(h = 1)) }, ground.features,
        )
        val stub = app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building(
            seed = b.seed, attempts = b.attempts, width = b.width, height = b.height,
            floors = listOf(shaved), stairs = b.stairs,
        )
        val issues = validateBuilding(stub, prof)
        assertTrue("a one-tile-deep room passed as usable", "tiles deep" in issues.joinToString())

        // A second exterior door breaks a home's doorway budget: cut one into the south
        // facade where the room behind it stands.
        val g = ground.tiles.map { it.copyOf() }.toTypedArray()
        val y1 = g.indexOfLast { row -> row.any { it != Tile.EMPTY } }
        val fx = (0 until g[y1].size).firstOrNull { g[y1][it] == Tile.WALL && g[y1 - 1][it] == Tile.FLOOR }!!
        g[y1][fx] = Tile.DOOR
        val twoDoors = FloorPlan(ground.level, g, ground.rect, ground.material, ground.rooms, ground.features)
        val glut = app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building(
            seed = b.seed, attempts = b.attempts, width = b.width, height = b.height,
            floors = listOf(twoDoors), stairs = b.stairs,
        )
        assertTrue(
            "a cottage with two entrances passed",
            validateBuilding(glut, prof).any { "exterior doors" in it },
        )

        // A room cut off from the floor cannot pass either: its record claims a patch of
        // open air no door serves, and every required kind is still present.
        val sealedRooms = ground.rooms.mapIndexed { i, room ->
            if (i == 1) room.copy(rect = app.rork.p7buzvrzcw3frddi8y50ou.procgen.Rect(0, 0, 2, 2)) else room
        }
        val sealedPlan = FloorPlan(ground.level, ground.tiles, ground.rect, ground.material, sealedRooms, ground.features)
        val sealed = app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building(
            seed = b.seed, attempts = b.attempts, width = b.width, height = b.height,
            floors = listOf(sealedPlan), stairs = b.stairs,
        )
        assertTrue(
            "a room cut off from the floor passed as sound",
            validateBuilding(sealed, prof).any { "cannot be reached" in it },
        )
    }

    // ------------------------------------------------------- strategies differ

    @Test
    fun `profiles declare meaningfully different strategies and doorway budgets`() {
        assertEquals(LayoutStyle.OPEN_PLAN, ArchitecturalProfiles.COTTAGE.style)
        assertEquals(LayoutStyle.CENTRAL_HALL, ArchitecturalProfiles.MANOR.style)
        assertEquals(1, ArchitecturalProfiles.COTTAGE.maxExteriorDoors)
        assertTrue(ArchitecturalProfiles.COTTAGE.connectorChance == 0.0)
        assertTrue(ArchitecturalProfiles.MANOR.connectorChance > 0.0)
        assertTrue(ArchitecturalProfiles.COTTAGE.backDoor.not())
        assertTrue(ArchitecturalProfiles.WORKSHOP.backDoor)

        // And a compact declared footprint on a fallback-allowed profile changes strategy
        // instead of growing unusable rooms.
        for (seed in 0L..29L) {
            val b = generateBuilding(
                BuildingOptions(seed = seed, floors = 2, width = 9, depth = 8, profile = ArchitecturalProfiles.TOWNHOUSE),
            )
            for (floor in b.floors) for (room in floor.rooms) {
                val req = RoomRequirements.forKind(room.kind)
                assertTrue(
                    "compact townhouse seed $seed grew a ${room.kind} ${room.rect.w}x${room.rect.h}",
                    room.rect.w >= req.minW && room.rect.h >= req.minD,
                )
            }
        }
    }

    // ------------------------------------------------------- the multi-seed report

    /**
     * A full batch over every profile and a hundred seeds each: failures, retries, fallbacks,
     * room counts, doorway counts and the minimum and average room dimensions by room kind —
     * printed for tuning, asserted for honesty.
     */
    @Test
    fun `multi-seed quality report over every profile`() {
        val failures = StringBuilder()
        var total = 0
        var retried = 0
        var fallback = 0
        val dims = HashMap<String, MutableList<Pair<Int, Int>>>() // kind -> (w, h) of ground rooms
        val roomCounts = HashMap<String, MutableList<Int>>()
        val doorCounts = HashMap<String, MutableList<Int>>()
        val extDoorCounts = HashMap<String, MutableList<Int>>()

        for (prof in profiles) {
            for (seed in 0L until 100L) {
                total++
                val b = try {
                    generate(prof, seed)
                } catch (err: Throwable) {
                    failures.append("${prof.name} seed $seed: ${err.message}\n")
                    continue
                }
                if (b.attempts > 1) retried++
                if (b.attempts > 300) fallback++
                roomCounts.getOrPut(prof.name) { mutableListOf() }.add(b.floors.sumOf { it.rooms.size })
                doorCounts.getOrPut(prof.name) { mutableListOf() }.add(doorCount(b))
                extDoorCounts.getOrPut(prof.name) { mutableListOf() }.add(exteriorDoors(b))
                for (room in b.floors[0].rooms) {
                    dims.getOrPut(room.kind) { mutableListOf() }.add(room.rect.w to room.rect.h)
                }
                val issues = validateBuilding(b, prof)
                if (issues.isNotEmpty()) failures.append("${prof.name} seed $seed: $issues\n")
            }
        }

        val report = StringBuilder("=== Building quality report (8 profiles x 100 seeds) ===\n")
        for (prof in profiles) {
            val rc = roomCounts[prof.name] ?: emptyList()
            val dc = doorCounts[prof.name] ?: emptyList()
            val ec = extDoorCounts[prof.name] ?: emptyList()
            report.append(
                "${prof.name.padEnd(10)} rooms avg %.1f | doors avg %.1f | ext doors avg %.1f\n".format(
                    rc.average(), dc.average(), ec.average(),
                ),
            )
        }
        report.append("Room dimensions (ground floor), min and avg:\n")
        for ((kind, list) in dims.toSortedMap()) {
            val minW = list.minOf { it.first }
            val minH = list.minOf { it.second }
            val avgW = list.sumOf { it.first }.toDouble() / list.size
            val avgH = list.sumOf { it.second }.toDouble() / list.size
            report.append("  ${kind.padEnd(14)} min ${minW}x$minH | avg %.1fx%.1f | n=${list.size}\n".format(avgW, avgH))
        }
        report.append(
            "retried plans: $retried/$total (%.1f%%), fallbacks: $fallback\n".format(retried * 100.0 / total),
        )
        println(report)

        assertEquals("unsound or unusable plans:\n$failures", "", failures.toString())
        assertEquals("no plan needed the fallback", 0, fallback)
        // Some retries are structural and predate the quality pass (a manor's front bay is
        // occasionally a single bay, so the great hall grows as an entry hall); the cap
        // catches runaway retry storms, not honest second drafts.
        assertTrue("too many retries: $retried/$total", retried < total / 5)
    }
}
