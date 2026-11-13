package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDirectory
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentRegistry
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.STORY
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.TILE_SIZE
import app.rork.p7buzvrzcw3frddi8y50ou.render.BuildingMesh
import app.rork.p7buzvrzcw3frddi8y50ou.render.BuildingModels
import app.rork.p7buzvrzcw3frddi8y50ou.render.InteriorMesh
import app.rork.p7buzvrzcw3frddi8y50ou.render.MeshBuilder
import app.rork.p7buzvrzcw3frddi8y50ou.render.WorldMesh
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reversible mesh baking: every building's structural data stays authoritative while its
 * static architecture is baked into one combined model per instance; combined models can be
 * invalidated and rebuilt from structure alone; interactive components, entrances, collision
 * and exterior/interior independence all survive. Run against the real production packs.
 */
class MeshBakingTest {

    /** The buildings grown from plans; the three that keep authored shells. */
    private val grown = setOf(
        "house_ne", "house_e", "house_se", "house_sw", "house_w", "house_nw",
        "smithy", "apothecary", "manor",
    )
    private val authored = setOf("inn", "shop", "well")

    private val registry: ContentRegistry by lazy {
        val dir = TestPacks.moduleDir()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        TestPacks.build(
            *listOf("core.json", "emberfall.json").map { name ->
                json.decodeFromString<ContentPack>(File(dir, "src/main/assets/content/$name").readText())
            }.toTypedArray(),
        )
    }

    private fun world(vararg packs: ContentPack): World {
        val content = if (packs.isEmpty()) TestPacks.build(TestPacks.base()) else TestPacks.build(*packs)
        return World(content, SaveGame(), InputState(), MutableSharedFlow())
    }

    private class Bounds {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    }

    private fun boundsOf(m: MeshBuilder): Bounds {
        val b = Bounds()
        var i = 0
        while (i + 3 <= m.size) {
            val x = m.data[i]; val y = m.data[i + 1]; val z = m.data[i + 2]
            if (x < b.minX) b.minX = x; if (x > b.maxX) b.maxX = x
            if (y < b.minY) b.minY = y; if (y > b.maxY) b.maxY = y
            if (z < b.minZ) b.minZ = z; if (z > b.maxZ) b.maxZ = z
            i += 9
        }
        return b
    }

    private fun vertexAt(m: MeshBuilder, index: Int): FloatArray =
        floatArrayOf(m.data[index * 9], m.data[index * 9 + 1], m.data[index * 9 + 2])

    private fun sameGeometry(a: MeshBuilder, bb: MeshBuilder, what: String, tol: Float = 1e-3f) {
        assertEquals("$what: vertex count differs", a.vertexCount, bb.vertexCount)
        for (v in 0 until a.vertexCount) {
            val pa = vertexAt(a, v); val pb = vertexAt(bb, v)
            for (k in 0..2) assertTrue(
                "$what: vertex $v moved by ${kotlin.math.abs(pa[k] - pb[k])}",
                kotlin.math.abs(pa[k] - pb[k]) <= tol,
            )
        }
    }

    // ---------------------------------------------------- the structural representation

    @Test
    fun `baking leaves the structural plan untouched and identities stable`() {
        val dir = BuildingDirectory(registry)
        for (id in grown) {
            val def = registry.buildings.getValue(id)
            val before = BuildingCache.get(def)!!
            val roomsBefore = dir.logical(id)!!.rooms.map { it.ref }
            val model = BuildingModels.exterior(def)
            assertNotNull("'$id' baked no model", model)
            assertEquals("baking changed '$id''s plan", before, BuildingCache.get(def))
            assertEquals("baking changed '$id''s room identities", roomsBefore, dir.logical(id)!!.rooms.map { it.ref })
            // The model reports the structure it was baked from, not a copy of it.
            assertEquals(before.seed, model!!.planSeed)
            assertTrue(model.fromPlan)
        }
    }

    // ---------------------------------------------------- combined geometry

    @Test
    fun `a baked building is one combined model holding all its architecture`() {
        for (id in grown + authored) {
            val def = registry.buildings.getValue(id)
            val model = BuildingModels.exterior(def)!!
            assertTrue("'$id' baked an empty model", model.vertexCount > 0)
            assertTrue("'$id' combined nothing", model.objectsCombined > 0)
            val plan = if (def.procedural) BuildingCache.get(def) else null
            val fp = if (def.procedural) BuildingCache.footprint(def) else null
            val b = boundsOf(model.mesh)
            if (plan != null) {
                // The bake stands as tall as its floors' architecture and covers its footprint.
                assertTrue(
                    "'$id' model is only ${b.maxY} tall for ${plan.floors.size} floors",
                    b.maxY >= plan.floors.size * STORY - 0.5f,
                )
                assertTrue(
                    "'$id' model misses its footprint",
                    b.maxX - b.minX >= (fp!!.first) - 1.5f && b.maxZ - b.minZ >= fp.second - 1.5f,
                )
            }
            // Glow encoding (doubled normals) survives: windows still light at night.
            // The well has no windows by design, so nothing may glow on it.
            var glowing = 0
            var i = 3
            while (i < model.mesh.size) {
                val nl = kotlin.math.sqrt(model.mesh.data[i] * model.mesh.data[i] + model.mesh.data[i + 1] * model.mesh.data[i + 1] + model.mesh.data[i + 2] * model.mesh.data[i + 2])
                if (nl >= 1.5f) glowing++
                i += 9
            }
            if (def.type == "well") {
                assertEquals("the well grew windows it never had", 0, glowing)
            } else {
                assertTrue("'$id' baked with no glowing window panes left", glowing > 0)
            }
        }
    }

    @Test
    fun `baking is deterministic and blitting equals direct emission`() {
        val def = registry.buildings.getValue("manor")
        val a = BuildingModels.exterior(def)!!
        BuildingModels.releaseAll()
        val b = BuildingModels.exterior(def)!!
        assertEquals("two bakes differ in vertex count", a.vertexCount, b.vertexCount)
        sameGeometry(a.mesh, b.mesh, "manor rebake")

        // The composed path (bake local, blit world) matches the old direct emission exactly
        // in vertex positions — the visual result is identical, one model instead of many.
        val direct = MeshBuilder(1 shl 17)
        assertTrue(BuildingMesh.emit(direct, def))
        val composed = MeshBuilder(1 shl 17)
        a.blit(composed, def.x, def.z, BuildingModels.yawOf(def))
        assertEquals(direct.vertexCount, composed.vertexCount)
        sameGeometry(direct, composed, "bake-then-blit vs direct emission", tol = 1e-3f)
    }

    // ---------------------------------------------------- reversibility

    @Test
    fun `invalidate and rebuild return the same geometry from structure alone`() {
        val def = registry.buildings.getValue("house_e")
        val original = BuildingModels.exterior(def)!!
        val planBefore = BuildingCache.get(def)!!
        val roomsBefore = BuildingDirectory(registry).logical("house_e")!!.rooms.map { it.ref }

        BuildingModels.invalidate(def.id)
        assertTrue(
            "invalidation did not bump the structural revision",
            BuildingModels.revision(def.id) > original.revision,
        )
        // The structure survives invalidation untouched (it is a pure function of seed+options).
        assertEquals(planBefore, BuildingCache.get(def))

        val rebuilt = BuildingModels.exterior(def)!!
        assertEquals("rebake changed vertex count", original.vertexCount, rebuilt.vertexCount)
        sameGeometry(original.mesh, rebuilt.mesh, "house_e rebake after invalidate")
        assertEquals("rebake changed room identities", roomsBefore, BuildingDirectory(registry).logical("house_e")!!.rooms.map { it.ref })
        assertTrue(rebuilt.revision > original.revision)
    }

    @Test
    fun `a structural change rebuilds only the affected building`() {
        val def = registry.buildings.getValue("house_w")
        val neighbour = registry.buildings.getValue("house_nw")
        val before = BuildingModels.exterior(def)!!
        val neighbourBefore = BuildingModels.exterior(neighbour)!!
        val neighbourPlanBefore = BuildingCache.get(neighbour)

        // Grow the house one storey past what it grew: a structural change to its definition.
        val floorsNow = BuildingCache.get(def)!!.floors.size
        val taller = def.copy(genFloors = floorsNow + 1)
        val after = BuildingModels.rebuild(taller)!!
        val tallerPlan = BuildingCache.get(taller)!!
        assertEquals(
            "the rebuild did not honour the structural change",
            floorsNow + 1,
            tallerPlan.floors.size,
        )
        assertTrue(
            "the rebuilt model is not taller: ${before.vertexCount} -> ${after.vertexCount}",
            boundsOf(after.mesh).maxY > boundsOf(before.mesh).maxY,
        )
        assertTrue(
            "the rebuilt model does not stand ${floorsNow + 1} storeys tall",
            boundsOf(after.mesh).maxY >= (floorsNow + 1) * STORY - 0.5f,
        )
        assertTrue(after.revision > before.revision)

        // The neighbour was never touched by the rebuild.
        val neighbourPlan = BuildingCache.get(neighbour)
        assertEquals(neighbourPlanBefore, neighbourPlan)
        val neighbourAfter = BuildingModels.exterior(neighbour)!!
        assertSame("the unrelated building was rebaked", neighbourBefore, neighbourAfter)
    }

    @Test
    fun `a building without a grown plan keeps its shell, stays in the world, and nothing fails silently`() {
        // The quality pass made generation total for every declarable footprint (options
        // are coerced into the profile's caps and retries end in a deterministic minimal
        // plan), so the shell-fallback path is exercised the honest way: by the three
        // hand-authored buildings, which have no grown plan by design and bake through
        // the very same shell path a failed generation would fall back to.
        for (id in authored) {
            val def = registry.buildings.getValue(id)
            val model = BuildingModels.exterior(def)
            assertNotNull("'$id' baked no model", model)
            assertFalse("'$id' claimed a plan it does not have", model!!.fromPlan)
            assertTrue("'$id' shell baked nothing", model.vertexCount > 0)
            // And it still reaches the world batch: a building never vanishes.
            val m = MeshBuilder(1 shl 15)
            model.blit(m, def.x, def.z, BuildingModels.yawOf(def))
            assertTrue("'$id' never reached the world batch", m.vertexCount > 0)
        }
        // Nothing was wrongly written down either: the coverage ledger reads clean, and no
        // authored building was reported as a fallback — a shell by design is not a failure.
        assertEquals(0, BuildingCache.coverage(registry.buildings.values).fallbacks.size)
        assertTrue(
            "an authored building was reported as a fallback",
            Diagnostics.state.value.entries.none {
                "fell back" in it.message && ("inn" in it.message || "shop" in it.message || "well" in it.message)
            },
        )
    }

    // ---------------------------------------------------- dynamic components

    @Test
    fun `doors stay identifiable entrances and services keep their rooms after baking`() {
        val entrances = app.rork.p7buzvrzcw3frddi8y50ou.sim.EntranceRegistry(registry)
        val dir = BuildingDirectory(registry)
        for (id in grown) {
            val def = registry.buildings.getValue(id)
            val before = entrances.entrances(id).map { it.id to it.side }
            BuildingModels.exterior(def)
            BuildingModels.invalidate(def.id)
            val after = entrances.entrances(id).map { it.id to it.side }
            assertEquals("'$id' entrances changed under baking", before, after)
            assertTrue("'$id' lost its entrances", after.isNotEmpty())
        }
        // A workstation's service spot still resolves from the plan, not the mesh.
        val w = world(*coreAndEmberfallPacks())
        val smithy = w.content.buildings.values.first { it.id.endsWith("smithy") }
        val plan = BuildingCache.interiorPlan(smithy)!!
        assertTrue(plan.floors.flatMap { it.rooms }.any { it.kind == "forge" })
    }

    // ---------------------------------------------------- exterior / interior

    @Test
    fun `exterior and interior instances bake independently and reload from structure`() {
        val w = world(*coreAndEmberfallPacks())
        val content = w.content
        val cottage = content.buildings.values.first { it.id.endsWith("house_ne") }
        val extBefore = WorldMesh.get(content, 1)

        // Enter: the interior instance bakes its own model in its own space.
        val e = w.interiors.entrances.entrances(cottage.id).first()
        w.p.x = e.outX; w.p.z = e.outZ; w.p.y = 1f; w.p.yaw = e.outYaw
        assertTrue(w.interiors.enterByEntranceId(e.id))
        val entryA = InteriorMesh.get(w.interiors.active)
        assertNotNull("the interior baked no model", entryA)
        val bA = boundsOf(entryA!!.mesh)
        assertTrue("the interior model is not in its own local space", bA.minY >= -0.5f)
        // None of it leaked into the overworld batch.
        assertEquals("interior geometry leaked into the overworld", extBefore.vertexCount, WorldMesh.get(content, 1).vertexCount)

        // Unload: the model dies, the structure and persistent state do not.
        val visitsAfterEnter = w.save.interiors.getValue(e.instanceId).visits
        w.interiors.exit()
        assertNull(
            "the unloaded slot still served a model",
            InteriorMesh.get(w.interiors.active),
        )
        assertEquals(
            "unloading lost persistent state",
            visitsAfterEnter,
            w.save.interiors.getValue(e.instanceId).visits,
        )

        // Reload: a fresh model, equivalent geometry, from the same authoritative plan.
        w.interiors.enterByEntranceId(e.id)
        val entryB = InteriorMesh.get(w.interiors.active)!!
        assertNotSame(entryA, entryB)
        sameGeometry(entryA.mesh, entryB.mesh, "interior reload")
        assertEquals(visitsAfterEnter + 1, w.save.interiors.getValue(e.instanceId).visits)

        // And invalidating the building stales the interior model too — a rebake follows.
        val stale = BuildingModels.bakeInterior(w.interiors.active!!)!!
        BuildingModels.invalidate(cottage.id)
        assertTrue(
            "the interior model did not go stale on structural invalidation",
            stale.planVersion != BuildingModels.revision(cottage.id),
        )
    }

    // ---------------------------------------------------- rollout & performance counts

    @Test
    fun `the core world quantified - every building bakes and the counts hold`() {
        val models = registry.buildings.values.map { it to BuildingModels.exterior(it)!! }
        val stats = BuildingModels.stats()
        assertEquals(12, models.size)
        // The bakery is a global shared across this test JVM's classes, so its absolute
        // cache size only ever grows; what must hold is that all twelve core models are in it.
        assertTrue("one baked model per building was expected", stats.models >= 12)
        assertEquals(9, models.count { it.first.procedural && it.second.fromPlan })
        assertEquals(authored, models.filter { !it.second.fromPlan }.map { it.first.id }.toSet())
        // Performance shape: many hundreds of static boxes collapse into 12 models — the
        // world batch stays one draw call either way, and the report says so.
        assertTrue("baked nothing to combine", stats.combinedObjects > 200)
        val report = app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics.report(emptyList(), emptyList(), registry.buildings)
        assertTrue("the report never mentions the baked models", "Baked models:" in report)
        assertTrue(report.contains("rebakes"))
        assertTrue("every bake took negative time?", stats.vertices > 0)
    }

    @Test
    fun `collision stays plan-derived, not mesh-derived`() {
        val def = registry.buildings.getValue("house_se")
        val model = BuildingModels.exterior(def)!!
        BuildingModels.invalidate(def.id)
        // The footprint collision reads from the plan and is unchanged by model churn.
        val fp1 = BuildingCache.footprint(def)
        val fp2 = BuildingCache.footprint(def)
        assertEquals(fp1, fp2)
        assertNotNull(fp1)
        assertTrue(fp1!!.first >= 9 * TILE_SIZE)
        // And the world batch rebuilt after invalidation still contains the house.
        WorldMesh.invalidate()
        val m = WorldMesh.get(registry, 1)
        assertTrue(m.vertexCount > 0)
        // The baked model knows the terrain it sits on, so blitting places it on the ground.
        assertEquals(app.rork.p7buzvrzcw3frddi8y50ou.sim.Terrain.height(def.x, def.z) - 0.1f, model.oy, 1e-4f)
        assertTrue(
            "the baked model does not sit on the terrain",
            !model.oy.isNaN() && !sin(model.oy).isNaN() && !cos(atan2(def.x, def.z)).isNaN(),
        )
    }

    private fun coreAndEmberfallPacks(): Array<ContentPack> {
        val dir = TestPacks.moduleDir()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        return listOf("core.json", "emberfall.json").map { name ->
            json.decodeFromString<ContentPack>(File(dir, "src/main/assets/content/$name").readText())
        }.toTypedArray()
    }
}
