package app.rork.p7buzvrzcw3frddi8y50ou.procgen

/**
 * Building Generator Quality Pass — centrally defined, data-driven room dimensions.
 *
 * Every room kind declares the smallest space that is still *usable*: a bedchamber a bed
 * fits into, a kitchen with room to work, a parlor one can live in. The generator consults
 * this table in two places: when shaping the plan (a profile's bands must run deep enough for
 * the rooms they will hold) and when validating it (a room under its minimum is a rejected
 * plan, never silently shrunk). Rooms whose dimensions cannot be met are removed, combined
 * or the whole layout strategy changes — the minimums themselves never bend.
 *
 * Dimensions are in tiles ([TILE_SIZE] world units each). Area counts *usable* floor tiles
 * only: walls, posts, doorways and openings are excluded.
 */
data class RoomRequirement(
    /** Least usable width (tiles across, west–east). */
    val minW: Int,
    /** Least usable depth (tiles, the band's run). */
    val minD: Int,
    /** Least usable floor area (walkable tiles, walls and doorways excluded). */
    val minArea: Int,
)

object RoomRequirements {

    private val TABLE = mapOf(
        // Great spaces: the room a building is about. (Areas equal minW x minD: a plan that
        // meets both dimensions always meets the area, so retries chase real shortfalls.)
        // Openness pass: the great rooms must run four tiles deep — a hall that shallow
        // cannot hold a long table and a hearth with room to walk between them. Width comes
        // from the profiles' merge chances, which grow whole open halls, not from a minimum
        // that would force one-bay plans into endless retries.
        "great hall" to RoomRequirement(4, 4, 16),
        "common room" to RoomRequirement(4, 4, 16),
        "meeting room" to RoomRequirement(3, 3, 9),
        "parlor" to RoomRequirement(3, 3, 9),
        // Working rooms.
        "workshop" to RoomRequirement(3, 3, 9),
        "kitchen" to RoomRequirement(3, 2, 6),
        "forge" to RoomRequirement(3, 2, 6),
        "shop" to RoomRequirement(3, 2, 6),
        "scriptorium" to RoomRequirement(3, 2, 6),
        // Private rooms: sleep, study, retreat. The home profiles' band ranges hold
        // bedchambers three to four tiles deep — a real bed, a nightstand and a chest fit
        // with room to walk — so the table only insists a chamber is never one tile deep.
        "bedchamber" to RoomRequirement(3, 2, 6),
        "guest chamber" to RoomRequirement(3, 2, 6),
        "nursery" to RoomRequirement(3, 2, 6),
        "solar" to RoomRequirement(3, 2, 6),
        "study" to RoomRequirement(3, 2, 6),
        "great chamber" to RoomRequirement(3, 2, 6),
        // Service and storage: small but never one tile deep.
        "office" to RoomRequirement(3, 2, 6),
        "guardroom" to RoomRequirement(3, 2, 6),
        "pantry" to RoomRequirement(2, 2, 4),
        "storeroom" to RoomRequirement(2, 2, 4),
        "stockroom" to RoomRequirement(2, 2, 4),
        "larder" to RoomRequirement(2, 2, 4),
        "scullery" to RoomRequirement(2, 2, 4),
        "cold cellar" to RoomRequirement(2, 2, 4),
        "buttery" to RoomRequirement(2, 2, 4),
        "linen room" to RoomRequirement(2, 2, 4),
        "armory" to RoomRequirement(2, 2, 4),
        "entry hall" to RoomRequirement(2, 2, 4),
    )

    /** Anything not tabled (a pack's new room kind) still gets an honest floor. */
    val GENERIC = RoomRequirement(2, 2, 6)

    fun forKind(kind: String): RoomRequirement = TABLE[kind.trim().lowercase()] ?: GENERIC

    /**
     * The usable floor tiles inside a room's rectangle: walked floor and stairs, with walls,
     * posts, doorways and openings excluded — what furniture and feet can actually claim.
     */
    fun usableTiles(tiles: Array<IntArray>, rect: Rect): Int {
        var n = 0
        for (y in rect.y until rect.y + rect.h) {
            for (x in rect.x until rect.x + rect.w) {
                val t = tiles.getOrNull(y)?.getOrNull(x) ?: continue
                if (t == Tile.FLOOR || t == Tile.STAIRS) n++
            }
        }
        return n
    }
}
