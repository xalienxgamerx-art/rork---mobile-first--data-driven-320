package app.rork.p7buzvrzcw3frddi8y50ou.render

import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.hexToRgb
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.sim.ActiveInterior
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Terrain
import kotlin.math.atan2

/**
 * The bakery: converts authoritative structural building data into combined, renderable
 * building models — and back.
 *
 * Every building holds two representations. The **structure** — the grown tile plan with its
 * floors, rooms, stairs and features, or the pack's authored shell and room program — lives
 * in [BuildingCache] and the content registry, and is the only source of truth. The
 * **combined model** is a derived artifact: one local-space vertex stream holding all of a
 * building's static architecture (walls, floors, roof, doors, windows, posts, stairs,
 * balconies, railings), baked once, cached here, and always safe to discard and rebuild.
 * Nothing derived is serialized into a save, and no mesh is ever read back into structure —
 * a rebuilt building is re-grown from its plan, byte-for-byte deterministically.
 *
 * Interactive components are deliberately not merged into anonymity: a baked door leaf is
 * still the geometry under a logical [app.rork.p7buzvrzcw3frddi8y50ou.sim.Entrance] derived
 * from the same plan, and beds, containers and workstations render as separate nodes. The
 * exterior overworld model and the interior instance model are baked and invalidated
 * independently and never share a space.
 */
object BuildingModels {

    /** Bump when baking rules change; stale models are discarded, never served. */
    const val BAKE_VERSION = 4

    class BakedModel(
        val key: String,
        val buildingId: String,
        /** True when baked from a grown structural plan; false for an authored or fallback shell. */
        val fromPlan: Boolean,
        val planSeed: Long,
        /** The structural version this model was baked from; a mismatch forces a rebake. */
        val planVersion: Int,
        val bakeVersion: Int,
        val mesh: MeshBuilder,
        /** Static boxes the uncombined path would have managed as separate render objects. */
        val objectsCombined: Int,
        val bakeMillis: Float,
        /** The building's structural revision at bake time. */
        val revision: Int,
        /** The placement height the bake assumed (the terrain under the building). */
        val oy: Float,
    ) {
        val vertexCount get() = mesh.vertexCount
        val bytes get() = mesh.size * 4

        /** Appends the model into a world-space batch at the building's place and door-facing turn. */
        fun blit(dst: MeshBuilder, x: Float, z: Float, yaw: Float) = mesh.blitInto(dst, x, oy, z, yaw)
    }

    class Stats(
        val models: Int,
        val exteriorModels: Int,
        val interiorBakes: Int,
        val combinedObjects: Long,
        val vertices: Long,
        val rebakes: Int,
        val invalidations: Int,
        val failures: Int,
        val bakeVersion: Int,
    )

    private val exterior = HashMap<String, BakedModel>()
    private val revisions = HashMap<String, Int>()

    private var rebakes = 0
    private var invalidations = 0
    private var failures = 0
    private var interiorBakes = 0

    private fun modelKey(def: BuildingDef) =
        "ext|${BuildingCache.planKey(def)}|${def.type}|${def.wall}|${def.roof}|${def.w}|${def.d}|${def.h}"

    // ------------------------------------------------------------------ exterior

    /**
     * The combined model for [def]'s overworld instance, baked from its structural data.
     * A cached model is served only while its bake version, plan version and structural
     * revision all still match; anything else rebakes from the authoritative plan.
     *
     * Only a building that opted in with `procedural` is baked from a grown plan; a
     * hand-authored building keeps its authored shell (the pack's own silhouette is
     * canonical content, exactly as the rollout ruled). When a procedural plan *cannot*
     * be grown, the authored shell is baked as the fallback and the reason is recorded —
     * the building never vanishes and never fails silently.
     */
    @Synchronized
    fun exterior(def: BuildingDef): BakedModel? {
        val key = modelKey(def)
        exterior[key]?.let { cached ->
            if (cached.bakeVersion == BAKE_VERSION &&
                cached.planVersion == BuildingCache.version(def) &&
                cached.revision == revision(def.id)
            ) {
                return cached
            }
            exterior.remove(key)
        }
        return try {
            val plan = if (def.procedural) BuildingCache.get(def) else null
            val t0 = System.nanoTime()
            val fromPlan = plan != null
            val mesh = if (plan != null) {
                BuildingMesh.bakeLocal(plan, hexToRgb(def.wall), hexToRgb(def.roof))
            } else {
                BuildingMesh.bakeShellLocal(def)
            }
            if (plan == null && def.procedural) {
                val why = BuildingCache.summaries[def.id]?.problems?.take(160) ?: "no plan was grown"
                Diagnostics.record(
                    "procedural",
                    "The building '${def.name}' fell back to its hand-authored shell because no plan could be grown for it: $why",
                )
            }
            val model = BakedModel(
                key = key,
                buildingId = def.id,
                fromPlan = fromPlan,
                planSeed = plan?.seed ?: BuildingCache.seedFor(def),
                planVersion = BuildingCache.version(def),
                bakeVersion = BAKE_VERSION,
                mesh = mesh,
                objectsCombined = mesh.boxCount,
                bakeMillis = (System.nanoTime() - t0) / 1e6f,
                revision = revision(def.id),
                oy = Terrain.height(def.x, def.z) - if (fromPlan) 0.1f else 0f,
            )
            exterior[key] = model
            model
        } catch (err: Throwable) {
            failures++
            Diagnostics.record("baking", "The combined model of '${def.name}' could not be baked", err)
            null
        }
    }

    // ------------------------------------------------------------------ interior

    /**
     * One fresh combined model for the interior instance, baked from the instance's
     * structural plan in interior-local space. The single interior slot above this (in
     * [InteriorMesh]) decides when a rebake is due; baking itself never caches.
     */
    @Synchronized
    fun bakeInterior(inst: ActiveInterior): BakedModel? {
        interiorBakes++
        return try {
            val t0 = System.nanoTime()
            val mesh = InteriorMesh.bakeLocal(inst)
            BakedModel(
                key = "int|${inst.instanceId}",
                buildingId = inst.buildingId,
                fromPlan = true,
                planSeed = inst.plan.seed,
                planVersion = revision(inst.buildingId),
                bakeVersion = BAKE_VERSION,
                mesh = mesh,
                objectsCombined = mesh.boxCount,
                bakeMillis = (System.nanoTime() - t0) / 1e6f,
                revision = revision(inst.buildingId),
                oy = 0f,
            )
        } catch (err: Throwable) {
            failures++
            Diagnostics.record(
                "baking",
                "The combined interior model of '${inst.buildingId}' could not be baked",
                err,
            )
            null
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** The building's structural revision: bumped whenever its structure is invalidated. */
    @Synchronized
    fun revision(buildingId: String): Int = revisions[buildingId] ?: 0

    /**
     * Returns the building to its editable structural state: the derived models are dropped
     * (never the structure), the cached plans are marked stale so the next bake regenerates
     * them deterministically, and the world batch is told to recompose.
     */
    @Synchronized
    fun invalidate(buildingId: String) {
        invalidations++
        revisions[buildingId] = revision(buildingId) + 1
        exterior.keys.toList().forEach { if (it.startsWith("ext|$buildingId|")) exterior.remove(it) }
        BuildingCache.invalidate(buildingId)
        WorldMesh.invalidate()
    }

    /**
     * Rebuilds one building after a structural change: invalidates its derived geometry,
     * regenerates the plan deterministically and bakes a fresh combined model — only the
     * affected building. Transactional: a failed bake keeps the previous model (stale-marked
     * so it rebakes as soon as a sound structure exists) and records the reason, never
     * silently destroying the last valid building.
     */
    @Synchronized
    fun rebuild(def: BuildingDef): BakedModel? {
        rebakes++
        val previous = exterior.values.firstOrNull { it.buildingId == def.id }
        invalidate(def.id)
        val fresh = exterior(def)
        if (fresh == null) {
            Diagnostics.record(
                "baking",
                "The rebuild of '${def.name}' failed; its previous combined model stays until a sound one is baked.",
            )
            return previous
        }
        return fresh
    }

    /**
     * Releases all cached combined models — memory only; structure is never touched, and
     * the next [exterior] rebakes deterministically. Used when content changes wholesale
     * and by callers that want the geometry gone.
     */
    @Synchronized
    fun releaseAll() {
        exterior.clear()
        WorldMesh.invalidate()
    }

    @Synchronized
    fun stats(): Stats = Stats(
        models = exterior.size,
        exteriorModels = exterior.size,
        interiorBakes = interiorBakes,
        combinedObjects = exterior.values.sumOf { it.objectsCombined.toLong() },
        vertices = exterior.values.sumOf { it.vertexCount.toLong() },
        rebakes = rebakes,
        invalidations = invalidations,
        failures = failures,
        bakeVersion = BAKE_VERSION,
    )

    /** The yaw that turns a building's front doors toward the square — shared by every emitter. */
    fun yawOf(def: BuildingDef): Float = atan2(def.x, def.z)
}
