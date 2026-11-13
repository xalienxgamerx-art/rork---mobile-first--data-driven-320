package app.rork.p7buzvrzcw3frddi8y50ou.procgen

import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef

/**
 * Grows each procedural building once and keeps it. Generation runs only when a building
 * definition first appears (world start, pack change); afterwards everyone — renderer,
 * collision, diagnostics — reads the same cached plan, so a seed never grows two buildings.
 *
 * Phase 3 adds architectural profiles: the profile a building declares (or the default it
 * falls back to) is part of the cache identity, so two buildings sharing an id or a seed but
 * differing in profile never collide. A building may also declare interior-only generation
 * (the int* fields): the interior instance then grows its own plan under its own key, so a
 * compact tavern shell can hold a spacious common room. Without interior configuration the
 * interior shares the exterior's plan, exactly as in Phase 2.
 */
object BuildingCache {

    class Summary(
        val id: String,
        val name: String,
        val seed: Long,
        val floors: Int,
        val attempts: Int,
        val sound: Boolean,
        val problems: String,
        val building: Building?,
        /** The architectural profile the plan was grown under ("default" when none declared). */
        val profile: String = "default",
    )

    private val cache = HashMap<String, Building>()
    private val interiorCache = HashMap<String, Building>()

    /** Monotonic version per cache key, bumped on every generation and invalidation, so
     *  derived geometry (baked models) can tell stale from current structure. */
    private val versions = HashMap<String, Int>()

    /** One entry per generated plan, for the diagnostics report. */
    val summaries = LinkedHashMap<String, Summary>()

    /** How a loaded world's buildings are rendered, for the diagnostics coverage summary:
     *  every building is either grown from a plan (its pack opted in with "procedural")
     *  or keeps a hand-authored shell the pack chose to author. A procedural building
     *  whose plan could not be grown is a fallback: the shell is rendered instead and
     *  the reason is kept so nothing fails silently. */
    class Coverage(
        val total: Int,
        val procedural: Int,
        val authored: List<String>,
        val fallbacks: List<Pair<String, String>>,
    )

    fun coverage(buildings: Collection<BuildingDef>): Coverage {
        val procedural = buildings.filter { it.procedural }
        val authored = buildings.filterNot { it.procedural }
        val fallbacks = procedural.mapNotNull { d ->
            val s = summaries[d.id]
            if (s?.building == null) d.id to (s?.problems ?: "no plan was recorded") else null
        }
        return Coverage(buildings.size, procedural.size, authored.map { it.id }, fallbacks)
    }

    /**
     * A building without an explicit seed still gets a stable one, derived from its id —
     * the same pack always grows the same hall, from session to session and device to device.
     */
    fun seedFor(def: BuildingDef): Long =
        if (def.genSeed != 0L) def.genSeed
        else (def.id.fold(2166136261L) { acc, ch -> (acc xor ch.code.toLong()) * 16777619L } and 0x7FFFFFFFL)
            .coerceAtLeast(1L)

    fun options(def: BuildingDef) = BuildingOptions(
        seed = seedFor(def),
        floors = def.genFloors.takeIf { it > 0 },
        width = def.genWidth.takeIf { it > 0 },
        depth = def.genDepth.takeIf { it > 0 },
        profile = ArchitecturalProfiles.resolve(def.profile),
    )

    private fun key(def: BuildingDef) =
        "${def.id}|${def.genSeed}|${def.genFloors}|${def.genWidth}|${def.genDepth}|${def.profile.trim().lowercase()}"

    /** The plan cache key for [def], exposed for derived-geometry caches that must share it. */
    fun planKey(def: BuildingDef): String = key(def)

    /** How many times the plan under [def]'s key has been generated or invalidated. */
    fun version(def: BuildingDef): Int = versions[key(def)] ?: 0

    /** The plan for this definition, generated and validated on first ask. Null when it never managed one. */
    fun get(def: BuildingDef): Building? {
        val k = key(def)
        cache[k]?.let {
            // A cached plan always reports itself, so coverage and the diagnostics
            // report see a sound plan even when generation ran before their time.
            if (summaries[def.id]?.building == null) {
                summaries[def.id] = Summary(def.id, def.name, it.seed, it.floors.size, it.attempts, true, "", it, planProfile(def))
            }
            return it
        }
        return try {
            val b = generateBuilding(options(def))
            cache[k] = b
            versions[k] = (versions[k] ?: 0) + 1
            summaries[def.id] = Summary(def.id, def.name, b.seed, b.floors.size, b.attempts, true, "", b, planProfile(def))
            b
        } catch (err: Throwable) {
            Diagnostics.record("procedural", "The building '${def.name}' could not be generated", err)
            summaries[def.id] = Summary(def.id, def.name, seedFor(def), 0, 0, false, err.message ?: "unknown", null, planProfile(def))
            null
        }
    }

    private fun planProfile(def: BuildingDef): String = ArchitecturalProfiles.resolve(def.profile).name

    // ------------------------------------------------------------- interior plans

    /** Whether the building declares interior-only generation, apart from its exterior plan. */
    fun hasInteriorConfig(def: BuildingDef): Boolean =
        def.intProfile.isNotEmpty() || def.intFloors > 0 || def.intWidth > 0 || def.intDepth > 0

    /**
     * The interior's own seed: explicit when the pack gives one, otherwise derived from the
     * id with a salt, so a spacious interior never coincides with the exterior's plan.
     */
    fun interiorSeedFor(def: BuildingDef): Long =
        if (def.intSeed != 0L) def.intSeed
        else ((def.id + "|int").fold(2166136261L) { acc, ch -> (acc xor ch.code.toLong()) * 16777619L } and 0x7FFFFFFFL)
            .coerceAtLeast(1L)

    fun interiorOptions(def: BuildingDef) = BuildingOptions(
        seed = interiorSeedFor(def),
        floors = def.intFloors.takeIf { it > 0 },
        width = def.intWidth.takeIf { it > 0 },
        depth = def.intDepth.takeIf { it > 0 },
        profile = ArchitecturalProfiles.resolve(def.intProfile, ArchitecturalProfiles.resolve(def.profile)),
    )

    /**
     * The plan the interior instance is built from. With no interior-only configuration this
     * is the exterior's plan — the Phase 2 arrangement, one plan shared by both. With it, the
     * interior grows independently under its own cache key and its own diagnostics summary.
     */
    fun interiorPlan(def: BuildingDef): Building? {
        if (!hasInteriorConfig(def)) return get(def)
        val k = "int|${def.id}|${def.intSeed}|${def.intFloors}|${def.intWidth}|${def.intDepth}|${def.intProfile.trim().lowercase()}|${def.profile.trim().lowercase()}"
        interiorCache[k]?.let { return it }
        return try {
            val b = generateBuilding(interiorOptions(def))
            interiorCache[k] = b
            versions[k] = (versions[k] ?: 0) + 1
            summaries["${def.id} (interior)"] = Summary("${def.id} (interior)", def.name, b.seed, b.floors.size, b.attempts, true, "", b, interiorOptions(def).profile?.name ?: "default")
            b
        } catch (err: Throwable) {
            Diagnostics.record("procedural", "The interior of '${def.name}' could not be generated", err)
            summaries["${def.id} (interior)"] = Summary("${def.id} (interior)", def.name, interiorSeedFor(def), 0, 0, false, err.message ?: "unknown", null, interiorOptions(def).profile?.name ?: "default")
            null
        }
    }

    /**
     * Marks a building's structure stale: its cached plans (exterior and interior) are
     * dropped and their versions bumped, so the next [get] regenerates deterministically
     * from the same options and every derived baked model knows to rebake. Never destroys
     * the authoritative data itself — the plan is a pure function of seed and options.
     */
    @Synchronized
    fun invalidate(buildingId: String) {
        cache.keys.toList().forEach { k ->
            if (k.startsWith("$buildingId|")) {
                cache.remove(k)
                versions[k] = (versions[k] ?: 0) + 1
            }
        }
        interiorCache.keys.toList().forEach { k ->
            if (k.startsWith("int|$buildingId|")) {
                interiorCache.remove(k)
                versions[k] = (versions[k] ?: 0) + 1
            }
        }
    }

    /** Ground-floor enclosed footprint in world units, for collision boxes. Null without a plan. */
    fun footprint(def: BuildingDef): Pair<Float, Float>? {
        val b = get(def) ?: return null
        val g = b.floors[0].tiles
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in g.indices) {
            for (x in g[y].indices) {
                if (g[y][x] != Tile.EMPTY) {
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                }
            }
        }
        if (minX > maxX) return null
        return (maxX - minX + 1) * TILE_SIZE to (maxY - minY + 1) * TILE_SIZE
    }
}
