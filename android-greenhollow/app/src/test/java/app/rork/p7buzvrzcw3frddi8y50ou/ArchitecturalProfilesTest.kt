package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentBuilder
import app.rork.p7buzvrzcw3frddi8y50ou.data.ModManifest
import app.rork.p7buzvrzcw3frddi8y50ou.data.PackSource
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingTaxonomy
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.ArchitecturalProfiles
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingOptions
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Tile
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.TILE_SIZE
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.generateBuilding
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.validateBuilding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building Roadmap Phase 3: architectural profiles. Profiles drive the shared generator as
 * configuration — footprints, floors, halls, rooms, openness — and every claim here is
 * demonstrated on generated plans, mostly across many seeds, never asserted from the
 * profile's own numbers alone.
 */
class ArchitecturalProfilesTest {

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

    private fun generate(prof: app.rork.p7buzvrzcw3frddi8y50ou.procgen.ArchProfile, seed: Long) =
        generateBuilding(BuildingOptions(seed = seed, profile = prof))

    /** Enclosed ground-floor footprint in tiles (walls included). */
    private fun footprint(b: Building): Pair<Int, Int> {
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

    private fun roomKinds(b: Building): Set<String> = b.floors.flatMap { it.rooms }.mapTo(HashSet()) { it.kind }

    /** Fraction of the ground floor's enclosed tiles held by its largest rooms, biggest first. */
    private fun largestRoomShares(b: Building, n: Int): Float {
        val ground = b.floors[0]
        var enclosed = 0
        for (y in ground.tiles.indices) for (x in ground.tiles[y].indices) {
            val t = ground.tiles[y][x]
            if (t != Tile.EMPTY) enclosed++
        }
        val top = ground.rooms.map { r -> r.rect.w * r.rect.h }.sortedDescending().take(n).sum()
        return top.toFloat() / enclosed.toFloat()
    }

    // ------------------------------------------------------- profiles resolve and stand

    @Test
    fun `every profile is declared, resolvable and grows sound buildings across many seeds`() {
        for (prof in profiles) {
            for (seed in 0L..39L) {
                val b = generate(prof, seed)
                val issues = validateBuilding(b, prof)
                assertTrue("${prof.name} seed $seed was unsound: $issues", issues.isEmpty())
            }
        }
    }

    @Test
    fun `an unknown profile name falls back to the default, never guessed`() {
        assertSame(ArchitecturalProfiles.DEFAULT, ArchitecturalProfiles.resolve("palace"))
        assertSame(ArchitecturalProfiles.DEFAULT, ArchitecturalProfiles.resolve(""))
        assertSame(ArchitecturalProfiles.COTTAGE, ArchitecturalProfiles.resolve(" cottage "))
    }

    @Test
    fun `an invalid profile reference is reported and the building still grows`() {
        val pack = ContentPack(
            ModManifest(id = "town", name = "Town"),
            buildings = listOf(
                BuildingDef(id = "cottage", name = "Cottage", x = 0f, z = 0f, profile = "palace"),
                BuildingDef(id = "inn", name = "Inn", x = 5f, z = 0f, profile = "tavern", intProfile = "nope"),
            ),
        )
        val reg = ContentBuilder.build(listOf(PackSource(pack, false)), emptySet())
        val messages = reg.issues.joinToString("\n") { it.message }
        assertTrue("the bad profile is named", "palace" in messages)
        assertTrue("the bad interior profile is named", "nope" in messages)
        assertEquals("", reg.buildings["cottage"]!!.profile)
        assertEquals("", reg.buildings["inn"]!!.intProfile)
        assertEquals("tavern", reg.buildings["inn"]!!.profile) // the valid word survives
    }

    // ------------------------------------------------------- dimensions and floors

    @Test
    fun `a cottage is a real home, spacious inside and out`() {
        for (seed in 0L..29L) {
            val b = generate(ArchitecturalProfiles.COTTAGE, seed)
            val (w, d) = footprint(b)
            // A home is spacious enough to live in: thirteen declared tiles across at the
            // least, and still one household's scale — never the manor's width, never a
            // third floor.
            assertTrue("cottage seed $seed grew ${w}x$d — a shack, not a home", w >= 13 && d >= 11)
            assertTrue("cottage seed $seed grew ${w}x$d — a hall, not a home", w <= 18 && d <= 14)
            assertTrue("cottage seed $seed stood ${b.floors.size} floors", b.floors.size <= 2)
        }
    }

    @Test
    fun `a townhouse is narrow and stands tall`() {
        for (seed in 0L..29L) {
            val b = generate(ArchitecturalProfiles.TOWNHOUSE, seed)
            val (w, _) = footprint(b)
            assertTrue("townhouse seed $seed grew ${w} wide", w <= 14)
            assertTrue("townhouse seed $seed stood ${b.floors.size} floors", b.floors.size >= 2)
        }
    }

    @Test
    fun `a warehouse is broad and a manor keeps its three storeys`() {
        for (seed in 0L..19L) {
            val wh = generate(ArchitecturalProfiles.WAREHOUSE, seed)
            val (ww, wd) = footprint(wh)
            assertTrue("warehouse seed $seed grew ${ww}x$wd", ww >= 24 && wd >= 14)
            val manor = generate(ArchitecturalProfiles.MANOR, seed)
            assertTrue("manor seed $seed stood ${manor.floors.size} floors", manor.floors.size >= 3)
        }
    }

    @Test
    fun `a single-floor building stands, walks and needs no stairs`() {
        val b = generateBuilding(BuildingOptions(seed = 7L, profile = ArchitecturalProfiles.COTTAGE, floors = 1))
        assertEquals(1, b.floors.size)
        assertTrue(validateBuilding(b, ArchitecturalProfiles.COTTAGE).isEmpty())
        for (f in b.floors) for (row in f.tiles) for (t in row) {
            assertTrue("a lone cottage grew a stair to nowhere", t != Tile.STAIRS)
        }
        assertTrue("the lone floor is walkable", roomKinds(b).isNotEmpty())
    }

    @Test
    fun `a two-floor building keeps its stairs aligned and everything reachable`() {
        val b = generateBuilding(BuildingOptions(seed = 11L, profile = ArchitecturalProfiles.COTTAGE, floors = 2))
        assertEquals(2, b.floors.size)
        val issues = validateBuilding(b, ArchitecturalProfiles.COTTAGE)
        assertTrue("two-floor cottage unsound: $issues", issues.isEmpty())
        val keys = b.floors.map { f ->
            f.tiles.flatMapIndexed { y, row ->
                row.mapIndexed { x, t -> if (t == Tile.STAIRS) "$x,$y" else null }.filterNotNull()
            }.sorted()
        }
        assertTrue(keys[0].isNotEmpty())
        assertEquals(keys[0], keys[1])
    }

    // ------------------------------------------------------- functional layouts

    @Test
    fun `a tavern grows its common room, kitchen and mixed functional zones`() {
        for (seed in 0L..19L) {
            val b = generate(ArchitecturalProfiles.TAVERN, seed)
            val kinds = roomKinds(b)
            assertTrue("tavern seed $seed grew $kinds", "common room" in kinds && "kitchen" in kinds)
            val zones = kinds.mapNotNull { k -> BuildingTaxonomy.zoneForPurpose(k).ifEmpty { null } }.toSet()
            assertTrue(
                "tavern seed $seed mixed only $zones",
                setOf("public", "workshop", "storage", "residential").all { it in zones },
            )
        }
    }

    @Test
    fun `a workshop grows working areas and a smith's forge`() {
        for (seed in 0L..19L) {
            val b = generate(ArchitecturalProfiles.WORKSHOP, seed)
            val kinds = roomKinds(b)
            assertTrue("workshop seed $seed grew $kinds", "workshop" in kinds && "forge" in kinds)
        }
    }

    @Test
    fun `a warehouse grows great open storage floors`() {
        for (seed in 0L..19L) {
            val b = generate(ArchitecturalProfiles.WAREHOUSE, seed)
            // Its great rooms (the bands either side of a narrow aisle) hold most of the floor.
            val share = largestRoomShares(b, 2)
            assertTrue(
                "warehouse seed $seed's two largest rooms hold only $share of the ground floor",
                share >= 0.55f,
            )
            for (f in b.floors) assertTrue(
                "warehouse seed $seed floor ${f.level} grew ${f.rooms.size} rooms",
                f.rooms.size <= 3,
            )
        }
    }

    @Test
    fun `a cottage and a civic hall grow their own kinds of rooms`() {
        for (seed in 0L..19L) {
            val home = roomKinds(generate(ArchitecturalProfiles.COTTAGE, seed))
            assertTrue("cottage seed $seed grew $home", "parlor" in home && "kitchen" in home)
            assertTrue("cottage seed $seed grew a common room", "common room" !in home)

            val hall = roomKinds(generate(ArchitecturalProfiles.CIVIC, seed))
            assertTrue("civic seed $seed grew $hall", "great hall" in hall && "office" in hall)
        }
    }

    @Test
    fun `a merchant townhouse mixes trade below and living above`() {
        for (seed in 0L..19L) {
            val b = generate(ArchitecturalProfiles.TOWNHOUSE, seed)
            val kinds = roomKinds(b)
            assertTrue("townhouse seed $seed grew $kinds", "shop" in kinds)
            val zones = kinds.mapNotNull { k -> BuildingTaxonomy.zoneForPurpose(k).ifEmpty { null } }.toSet()
            assertTrue(
                "townhouse seed $seed mixed only $zones",
                "commercial" in zones && "residential" in zones,
            )
        }
    }

    // ------------------------------------------------------- determinism and caching

    @Test
    fun `the same seed and profile grow the same building, a different profile does not`() {
        val a1 = generate(ArchitecturalProfiles.TAVERN, 42L)
        val a2 = generate(ArchitecturalProfiles.TAVERN, 42L)
        assertEquals(a1, a2)

        val other = generate(ArchitecturalProfiles.WORKSHOP, 42L)
        val wh = generate(ArchitecturalProfiles.WAREHOUSE, 42L)
        assertNotEquals(a1, other)
        assertNotEquals(a1, wh)

        // A different profile with a different footprint range cannot masquerade either.
        val cottage = generate(ArchitecturalProfiles.COTTAGE, 42L)
        val civic = generate(ArchitecturalProfiles.CIVIC, 42L)
        assertNotEquals(footprint(cottage), footprint(civic))
    }

    @Test
    fun `the cache keys profiles apart, so two profiles sharing a seed never collide`() {
        BuildingCache.summaries.clear()
        val plain = BuildingDef(id = "hall", name = "Hall", x = 0f, z = 0f, procedural = true, genSeed = 99L)
        val tavern = plain.copy(profile = "tavern")
        val warehouse = plain.copy(profile = "warehouse")
        val p1 = BuildingCache.get(plain)!!
        val p2 = BuildingCache.get(tavern)!!
        val p3 = BuildingCache.get(warehouse)!!
        assertNotEquals(p1, p2)
        assertNotEquals(p2, p3)
        assertSame(p1, BuildingCache.get(plain)) // repeated access reuses the cached plan
        assertSame(p2, BuildingCache.get(tavern))
        assertSame(p3, BuildingCache.get(warehouse))
        assertEquals("warehouse", BuildingCache.summaries.getValue("hall").profile)
    }

    @Test
    fun `legacy buildings without a profile grow exactly what the default always grew`() {
        // The default profile's options draw the same sequence the pre-profile generator did.
        val old = generateBuilding(BuildingOptions(seed = 5L, floors = 3, width = 19, depth = 13))
        val new = generateBuilding(
            BuildingOptions(seed = 5L, floors = 3, width = 19, depth = 13, profile = ArchitecturalProfiles.DEFAULT),
        )
        assertEquals(old, new)
    }

    // ------------------------------------------------------- independent interiors

    @Test
    fun `a building may grow a more spacious interior than its own walls`() {
        val def = BuildingDef(
            id = "town:tavern", name = "Tavern", x = 0f, z = 0f,
            profile = "tavern", genWidth = 15, genDepth = 11,
            intProfile = "tavern", intWidth = 24, intDepth = 14, intFloors = 2,
        )
        val ext = BuildingCache.get(def)!!
        val int = BuildingCache.interiorPlan(def)!!
        assertNotEquals("the interior grows its own plan", ext, int)
        val (ew, _) = footprint(ext)
        val (iw, _) = footprint(int)
        assertTrue("interior $iw was no wider than the shell $ew", iw > ew)
        // Deterministic across reloads, and never overwriting the canonical exterior plan.
        assertSame(int, BuildingCache.interiorPlan(def))
        assertSame(ext, BuildingCache.get(def))
    }

    @Test
    fun `without interior configuration the interior shares the exterior's plan`() {
        val def = BuildingDef(id = "town:cottage", name = "Cottage", x = 0f, z = 0f, profile = "cottage")
        assertSame(BuildingCache.get(def), BuildingCache.interiorPlan(def))
    }

    @Test
    fun `entrances still join a differing exterior to a valid interior spawn`() {
        val pack = ContentPack(
            ModManifest(id = "town", name = "Town"),
            buildings = listOf(
                BuildingDef(
                    id = "inn", name = "Inn", x = 0f, z = 40f,
                    profile = "tavern", genWidth = 15, genDepth = 11,
                    intProfile = "tavern", intWidth = 24, intDepth = 14, intFloors = 2,
                ),
            ),
        )
        val content = ContentBuilder.build(listOf(PackSource(pack, false)), emptySet())
        val w = World(content, SaveGame(), InputState(), MutableSharedFlow())
        val e = w.interiors.entrances.entrances("town:inn").first()
        val int = BuildingCache.interiorPlan(content.buildings.getValue("town:inn"))!!

        assertTrue("the door opened", w.interiors.enterByEntranceId(e.id))
        assertEquals("int:town:inn", w.p.location)

        // The spawn stands on the interior plan's own doorway tile, in the interior's space.
        val cx = (int.width - 1) / 2f
        val cz = (int.height - 1) / 2f
        val tx = Math.round(w.p.x / TILE_SIZE + cx)
        val ty = Math.round(cz - w.p.z / TILE_SIZE)
        assertEquals(Tile.DOOR, int.floors.first().tiles[ty][tx])
        assertEquals(w.interiors.groundY(w.p.x, w.p.z, 0f), w.p.y, 0.01f)

        // Walking out through the door (with the saved return point let go) restores the
        // exterior doorstep, computed from the exterior's plan.
        w.p.returnX = 0f; w.p.returnZ = 0f
        w.interiors.exit()
        assertEquals("", w.p.location)
        assertEquals(e.outX, w.p.x, 0.01f)
        assertEquals(e.outZ, w.p.z, 0.01f)
    }

    // ------------------------------------------------------- structural validation

    @Test
    fun `validation rejects plans that break their profile's rules`() {
        val prof = ArchitecturalProfiles.TAVERN
        val b = generate(prof, 3L)
        // Too few floors for a tavern.
        val stub = Building(
            seed = b.seed, attempts = b.attempts, width = b.width, height = b.height,
            floors = b.floors.take(1), stairs = b.stairs,
        )
        assertTrue("a one-floor tavern passed as sound", validateBuilding(stub, prof).isNotEmpty())
        // The required-room rule fires on a plan that lost its common room.
        val stripped = Building(
            seed = b.seed, attempts = b.attempts, width = b.width, height = b.height,
            floors = b.floors.map { f ->
                app.rork.p7buzvrzcw3frddi8y50ou.procgen.FloorPlan(
                    f.level, f.tiles, f.rect, f.material,
                    f.rooms.filter { it.kind != "common room" }, f.features,
                )
            },
            stairs = b.stairs,
        )
        assertTrue("a tavern without its common room passed", "common room" in validateBuilding(stripped, prof).joinToString())
        // And the real plan is clean.
        assertTrue(validateBuilding(b, prof).isEmpty())
    }

    @Test
    fun `a pack of profiled buildings all grow sound plans`() {
        val pack = ContentPack(
            ModManifest(id = "town", name = "Town"),
            buildings = listOf(
                BuildingDef(id = "cot", name = "Cottage", x = 0f, z = 0f, profile = "cottage", genFloors = 2),
                BuildingDef(id = "row", name = "Townhouse", x = 5f, z = 0f, profile = "townhouse"),
                BuildingDef(id = "inn", name = "Inn", x = 10f, z = 0f, profile = "tavern"),
                BuildingDef(id = "forge", name = "Smithy", x = 15f, z = 0f, profile = "workshop"),
                BuildingDef(id = "manor", name = "Manor", x = 20f, z = 0f, profile = "manor"),
                BuildingDef(id = "store", name = "Warehouse", x = 30f, z = 0f, profile = "warehouse"),
                BuildingDef(id = "hall", name = "Town Hall", x = 40f, z = 0f, profile = "civic"),
            ),
        )
        val reg = ContentBuilder.build(listOf(PackSource(pack, false)), emptySet())
        for ((id, def) in reg.buildings) {
            assertTrue("the $id lost its profile", def.profile.isNotEmpty())
            val int = BuildingCache.interiorPlan(def)
            assertTrue("the $id interior never grew", int != null)
            val prof = ArchitecturalProfiles.resolve(def.profile)
            assertTrue("the $id interior was unsound: ${validateBuilding(int!!, prof)}", validateBuilding(int, prof).isEmpty())
        }
    }

    @Test
    fun `a broad deterministic batch of every profile stays sound`() {
        for (prof in profiles) {
            for (seed in 100L..199L) {
                val b = generate(prof, seed)
                assertTrue("${prof.name} seed $seed: ${validateBuilding(b, prof)}", validateBuilding(b, prof).isEmpty())
            }
        }
    }
}
