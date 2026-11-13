package app.rork.p7buzvrzcw3frddi8y50ou.sim.systems

import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PLAYER_ID
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PropRels
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertyRelationship
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World

/**
 * The paper side of the vale's buildings: who owns them, who rents them, who lives in them,
 * who runs a business in them and who works in them. The building itself is the physical thing;
 * a property is the asset bound to its logical id, and each relationship is an independent
 * record with a stable id — owning says nothing about living there, working says nothing about
 * renting, and no single field decides a building's whole story.
 *
 * The records live in the save; this system owns the lawful ways they change, the indexes that
 * make asking cheap, and the statuses derived from them. It never reinterprets a household's
 * `home` as ownership — household members are surfaced as residents through a query-only bridge.
 * There is no second clock: relationships start and end at the world's own minute.
 */
class PropertySystem(private val w: World) {

    /** How many times the paper has changed since the world began, for the report. */
    var changes = 0
        private set

    // ------------------------------------------------------------ indexes
    // Rebuilt only when the records change; the save stays the single source of truth.

    private var indexDirty = true
    private val propertyById = HashMap<String, PropertySave>()
    private val propertyByBuilding = HashMap<String, PropertySave>()
    private val relsByProperty = HashMap<String, MutableList<PropertyRelationship>>()
    private val relsByParty = HashMap<String, MutableList<PropertyRelationship>>()

    private fun invalidate() { indexDirty = true }

    private fun reindex() {
        if (!indexDirty) return
        propertyById.clear(); propertyByBuilding.clear(); relsByProperty.clear(); relsByParty.clear()
        for (p in w.save.properties) {
            propertyById[p.id] = p
            propertyByBuilding[p.buildingId] = p
        }
        for (r in w.save.propertyRels) {
            relsByProperty.getOrPut(r.propertyId) { mutableListOf() }.add(r)
            relsByParty.getOrPut(r.partyId) { mutableListOf() }.add(r)
        }
        indexDirty = false
    }

    // ------------------------------------------------------------ lookups

    /** The property bound to a building, or null when the building holds no paper yet. */
    fun propertyOf(buildingId: String): PropertySave? {
        reindex(); return propertyByBuilding[buildingId]
    }

    /** Every property in the vale. */
    fun allProperties(): List<PropertySave> { reindex(); return w.save.properties.toList() }

    /**
     * Seeds the paper from the content packs, once every life exists. A pack's declaration is
     * applied only when the building holds no property yet: a world whose buildings have since
     * changed hands keeps its own story, and a reloaded world is not re-seeded. The save was
     * already canonicalised on load, so what remains here is known to resolve.
     */
    fun bind() {
        val now = w.save.minutes
        for (d in w.content.properties.values) {
            if (d.building.isEmpty()) continue
            if (propertyOf(d.building) != null) continue
            ensureProperty(d.building, d.name) ?: continue
            if (d.owner.isNotEmpty()) transferOwnership(d.building, d.owner, now)
            for (t in d.tenants) startTenancy(d.building, t, now)
            for (r in d.residents) addResident(d.building, r, now)
            if (d.business.isNotEmpty()) associateBusiness(d.building, d.business, now)
            val employer = d.business.ifEmpty { d.owner }
            for (e in d.employees) hire(e, d.building, employer, now)
        }
    }

    private fun relsFor(propertyId: String, kind: String): List<PropertyRelationship> {
        reindex()
        return relsByProperty[propertyId].orEmpty().filter { it.kind == kind }
    }

    private fun activeRelsFor(propertyId: String, kind: String): List<PropertyRelationship> =
        relsFor(propertyId, kind).filter { it.isActive() }

    private fun relsOf(partyId: String, kind: String): List<PropertyRelationship> {
        reindex()
        return relsByParty[partyId].orEmpty().filter { it.kind == kind && it.isActive() }
    }

    private fun propertiesOfRels(rels: List<PropertyRelationship>): List<PropertySave> {
        reindex()
        return rels.mapNotNull { propertyById[it.propertyId] }.distinctBy { it.id }
    }

    // ------------------------------------------------------------ validation

    /** Every stored id is written in full form, like the rest of the save's references. */
    private fun canonBuilding(id: String): String = w.content.buildings.canonical(id)

    /** The person's full id, or null when no one by that name exists. */
    private fun canonNpc(id: String): String? = when {
        id == PLAYER_ID -> id
        else -> w.content.npcs.canonical(id).takeIf { w.content.npcs[it] != null || w.npcById.containsKey(it) }
    }

    private fun buildingKnown(id: String): Boolean = w.content.buildings[canonBuilding(id)] != null

    private fun npcKnown(id: String): Boolean = canonNpc(id) != null

    private fun refuse(what: String, why: String) {
        Diagnostics.record("property", "$what was not recorded: $why")
    }

    // ------------------------------------------------------------ properties

    /**
     * The property for a building, created the first time the world writes paper about it.
     * One property to a building for now; the id keeps room for a later subdivision.
     */
    fun ensureProperty(buildingId: String, name: String = ""): PropertySave? {
        val bid = canonBuilding(buildingId)
        if (!buildingKnown(bid)) {
            refuse("A property for '$bid'", "no such building exists")
            return null
        }
        propertyOf(bid)?.let {
            if (name.isNotEmpty() && it.name.isEmpty()) it.name = name
            return it
        }
        val p = PropertySave(id = "prop:$bid", buildingId = bid, name = name)
        w.save.properties += p
        invalidate()
        return p
    }

    // ------------------------------------------------------------ ownership

    /** The current owner of a building, or null when no one holds its paper. */
    fun ownerOf(buildingId: String): String? =
        propertyOf(buildingId)?.let { activeRelsFor(it.id, PropRels.OWNERSHIP).firstOrNull()?.partyId }

    /** Every property a person owns. A person may own several. */
    fun ownershipsOf(npcId: String): List<PropertySave> =
        propertiesOfRels(relsOf(npcId, PropRels.OWNERSHIP))

    /**
     * Hands a building's paper to someone: the previous ownership ends at [minute] and a new
     * one begins. The property itself is created if it never existed.
     */
    fun transferOwnership(buildingId: String, npcId: String, minute: Double, terms: String = ""): PropertyRelationship? {
        val party = canonNpc(npcId)
        if (party == null) {
            refuse("The ownership of '$buildingId' by '$npcId'", "no such person exists")
            return null
        }
        val p = ensureProperty(buildingId) ?: return null
        endRelationships(activeRelsFor(p.id, PropRels.OWNERSHIP), minute)
        val rel = newRel(PropRels.OWNERSHIP, p.id, party, minute, terms)
        changes++
        return rel
    }

    /** Ends a building's ownership: the paper returns to no one. */
    fun endOwnership(buildingId: String, minute: Double): Boolean {
        val p = propertyOf(buildingId) ?: return false
        val open = activeRelsFor(p.id, PropRels.OWNERSHIP)
        endRelationships(open, minute)
        return open.isNotEmpty()
    }

    // ------------------------------------------------------------ tenancy & lease

    /** Everyone renting a building as a home. */
    fun tenantsOf(buildingId: String): List<String> =
        propertyOf(buildingId)?.let { activeRelsFor(it.id, PropRels.TENANCY).map { r -> r.partyId } } ?: emptyList()

    /** Everyone holding a building under a commercial lease. */
    fun leaseholdersOf(buildingId: String): List<String> =
        propertyOf(buildingId)?.let { activeRelsFor(it.id, PropRels.LEASE).map { r -> r.partyId } } ?: emptyList()

    /** Every building a person rents as a home. */
    fun homesOf(npcId: String): List<PropertySave> = propertiesOfRels(relsOf(npcId, PropRels.TENANCY))

    /** Every building a person holds under lease. */
    fun leasesOf(npcId: String): List<PropertySave> = propertiesOfRels(relsOf(npcId, PropRels.LEASE))

    private fun start(buildingId: String, kind: String, npcId: String, minute: Double, terms: String, what: String): PropertyRelationship? {
        val party = canonNpc(npcId)
        if (party == null) {
            refuse("The $what of '$buildingId' by '$npcId'", "no such person exists")
            return null
        }
        val p = ensureProperty(buildingId) ?: return null
        if (activeRelsFor(p.id, kind).any { it.partyId == party }) return relsFor(p.id, kind).first { it.isActive() && it.partyId == party }
        changes++
        return newRel(kind, p.id, party, minute, terms)
    }

    /** Someone takes up residence as a tenant, paying rent to whoever owns the building. */
    fun startTenancy(buildingId: String, npcId: String, minute: Double, terms: String = ""): PropertyRelationship? =
        start(buildingId, PropRels.TENANCY, npcId, minute, terms, "tenancy")

    /** A business takes the building under a commercial lease. */
    fun startLease(buildingId: String, npcId: String, minute: Double, terms: String = ""): PropertyRelationship? =
        start(buildingId, PropRels.LEASE, npcId, minute, terms, "lease")

    private fun end(buildingId: String, kind: String, npcId: String, minute: Double, what: String): Boolean {
        val p = propertyOf(buildingId) ?: return false
        val rel = activeRelsFor(p.id, kind).firstOrNull { it.partyId == npcId } ?: return false
        rel.endMinute = minute
        changes++
        return true
    }

    /** A tenant gives up a building. */
    fun endTenancy(buildingId: String, npcId: String, minute: Double): Boolean =
        end(buildingId, PropRels.TENANCY, npcId, minute, "tenancy")

    /** A lease is given up. */
    fun endLease(buildingId: String, npcId: String, minute: Double): Boolean =
        end(buildingId, PropRels.LEASE, npcId, minute, "lease")

    // ------------------------------------------------------------ residence

    /**
     * The people recorded as living in a building. With [includeHousehold] the households that
     * call it home lend their members too — a query-only bridge: no residence records are made
     * for them, and a household's `home` never reads as ownership.
     */
    fun residentsOf(buildingId: String, includeHousehold: Boolean = true): List<String> {
        val recorded = propertyOf(buildingId)
            ?.let { activeRelsFor(it.id, PropRels.RESIDENCE).map { r -> r.partyId } }
            .orEmpty()
        if (!includeHousehold) return recorded
        val bridged = w.save.households.filter { it.home == buildingId }.flatMap { it.members }
        return (recorded + bridged).distinct()
    }

    /** Someone comes to live in a building, recorded in their own name. */
    fun addResident(buildingId: String, npcId: String, minute: Double, terms: String = ""): PropertyRelationship? =
        start(buildingId, PropRels.RESIDENCE, npcId, minute, terms, "residence")

    /** Someone moves out of a building they were recorded in. */
    fun removeResident(buildingId: String, npcId: String, minute: Double): Boolean =
        end(buildingId, PropRels.RESIDENCE, npcId, minute, "residence")

    // ------------------------------------------------------------ business & employment

    /** The person running a business at a building, or null. Says nothing about ownership. */
    fun businessOf(buildingId: String): String? =
        propertyOf(buildingId)?.let { activeRelsFor(it.id, PropRels.BUSINESS).firstOrNull()?.partyId }

    /** Every building where a person runs a business. */
    fun businessesOf(npcId: String): List<PropertySave> = propertiesOfRels(relsOf(npcId, PropRels.BUSINESS))

    /** A person starts running a business at a building; any previous trade there closes. */
    fun associateBusiness(buildingId: String, npcId: String, minute: Double, terms: String = ""): PropertyRelationship? {
        val party = canonNpc(npcId) ?: run {
            refuse("The business of '$buildingId' by '$npcId'", "no such person exists")
            return null
        }
        val p = ensureProperty(buildingId) ?: return null
        endRelationships(activeRelsFor(p.id, PropRels.BUSINESS), minute)
        return start(p.buildingId, PropRels.BUSINESS, party, minute, terms, "business")
    }

    /** The business at a building closes. */
    fun endBusiness(buildingId: String, npcId: String, minute: Double): Boolean =
        end(buildingId, PropRels.BUSINESS, npcId, minute, "business")

    /** Everyone working at a building. */
    fun employeesOf(buildingId: String): List<String> =
        propertyOf(buildingId)?.let { activeRelsFor(it.id, PropRels.EMPLOYMENT).map { r -> r.partyId } } ?: emptyList()

    /** Every building where a person works for someone. */
    fun workplacesOf(npcId: String): List<PropertySave> = propertiesOfRels(relsOf(npcId, PropRels.EMPLOYMENT))

    /**
     * Someone takes up work at a building, for an employer named separately — working for
     * someone never implies they own the place.
     */
    fun hire(employeeId: String, buildingId: String, employerId: String, minute: Double, terms: String = ""): PropertyRelationship? {
        val party = canonNpc(employeeId)
        if (party == null) {
            refuse("The employment of '$employeeId' at '$buildingId'", "no such person exists")
            return null
        }
        val principal = if (employerId.isEmpty()) "" else canonNpc(employerId) ?: run {
            refuse("The employment of '$employeeId' under '$employerId'", "no such employer exists")
            return null
        }
        val p = ensureProperty(buildingId) ?: return null
        if (activeRelsFor(p.id, PropRels.EMPLOYMENT).any { it.partyId == party }) {
            return relsFor(p.id, PropRels.EMPLOYMENT).first { it.isActive() && it.partyId == party }
        }
        val rel = newRel(PropRels.EMPLOYMENT, p.id, party, minute, terms)
        rel.principalId = principal
        changes++
        return rel
    }

    /** Someone's work at a building ends. */
    fun endEmployment(employeeId: String, buildingId: String, minute: Double): Boolean =
        end(buildingId, PropRels.EMPLOYMENT, employeeId, minute, "employment")

    // ------------------------------------------------------------ derived status

    /**
     * What a building's paper says it is, derived from the relationships and nothing else:
     * an owner living or trading in their own building is owner_occupied; one let to a tenant
     * or under lease is tenanted; one lived or worked in without a letting is occupied; one
     * merely owned is owned; and one with no paper or no holding relationship is vacant.
     */
    fun statusOf(buildingId: String): String {
        val p = propertyOf(buildingId) ?: return VACANT
        val owner = activeRelsFor(p.id, PropRels.OWNERSHIP).firstOrNull()?.partyId
        val holds = PropRels.ALL - PropRels.OWNERSHIP
        val holding = holds.flatMap { kind -> activeRelsFor(p.id, kind).map { it.kind to it.partyId } }
        if (owner != null && holding.any { it.second == owner }) return OWNER_OCCUPIED
        if (holding.any { it.first == PropRels.TENANCY || it.first == PropRels.LEASE }) return TENANTED
        if (holding.isNotEmpty()) return OCCUPIED
        return if (owner != null) OWNED else VACANT
    }

    // ------------------------------------------------------------ internals

    private fun newRel(kind: String, propertyId: String, partyId: String, minute: Double, terms: String): PropertyRelationship {
        var id = "$kind:$propertyId:$partyId"
        val taken = HashSet<String>()
        reindex()
        for (r in w.save.propertyRels) taken.add(r.id)
        var n = 2
        while (id in taken) id = "$kind:$propertyId:$partyId#${n++}"
        val rel = PropertyRelationship(
            id = id, kind = kind, propertyId = propertyId, partyId = partyId,
            startMinute = minute, endMinute = PropertyRelationship.END_OPEN, terms = terms,
        )
        w.save.propertyRels += rel
        invalidate()
        return rel
    }

    private fun endRelationships(rels: List<PropertyRelationship>, minute: Double) {
        for (r in rels) {
            if (r.isActive()) {
                r.endMinute = minute
                changes++
            }
        }
    }

    companion object {
        const val OWNER_OCCUPIED = "owner_occupied"
        const val TENANTED = "tenanted"
        const val OCCUPIED = "occupied"
        const val OWNED = "owned"
        const val VACANT = "vacant"
    }
}
