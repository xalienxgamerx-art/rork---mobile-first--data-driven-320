package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.PropertyDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PropRels
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveBinding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveStore
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.GoalSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.HouseholdSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.KinshipSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.NpcSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.ProgressionSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.PropertySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.SocialSystem
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paper side of buildings: a property is an asset bound to a building's logical id, and
 * ownership, tenancy, lease, residence, business and employment are independent relationship
 * records with stable ids — never one owner field. Everything runs on the JVM through the
 * production pathways: the content pipeline, save binding and the property system itself.
 */
class PropertyTest {

    /** Three buildings and four people, one of them keeping a shop he does not own. The pack
     *  runs as "core" so its ids stay in plain form, as the real core pack's do. */
    private fun propPack(): ContentPack = TestPacks.base(id = "core").copy(
        professions = listOf(ProfessionDef(id = "fisher", name = "Fisher")),
        buildings = TestPacks.base(id = "core").buildings + listOf(
            BuildingDef(id = "forge", name = "Forge", type = "workshop", classification = "industrial", x = 5f, z = 10f),
            BuildingDef(id = "barn", name = "Barn", type = "storage", classification = "storage", x = -10f, z = 25f),
        ),
        npcs = listOf(
            NpcDef(id = "hawk", name = "Hawk", profession = "fisher", age = 46, home = "cottage"),
            NpcDef(id = "wren", name = "Wren", profession = "fisher", age = 40, home = "cottage"),
            NpcDef(id = "finn", name = "Finn", profession = "fisher", age = 20, home = "barn"),
            NpcDef(id = "oskar", name = "Oskar", profession = "fisher", age = 33, home = "barn"),
        ),
        properties = listOf(
            PropertyDef(id = "prop_forge", building = "forge", name = "The forge", owner = "hawk", business = "wren", employees = listOf("finn")),
        ),
    )

    /** A running world, bound in the engine's order, with the property system on top. */
    private fun propWorld(save: SaveGame = SaveGame(), pack: ContentPack = propPack()): World {
        val content = TestPacks.build(pack)
        val w = World(content, save, InputState(), MutableSharedFlow())
        SaveBinding.bind(save, content)
        w.progression = ProgressionSystem(w)
        w.social = SocialSystem(w)
        w.npcSim = NpcSystem(w)
        w.progression.recalc()
        w.npcSim.build()
        w.kin = KinshipSystem(w)
        w.households = HouseholdSystem(w)
        w.goals = GoalSystem(w)
        w.properties = PropertySystem(w)
        w.kin.bind()
        w.households.bind()
        w.goals.bind()
        w.properties.bind()
        return w
    }

    private fun diagnosticsText() = Diagnostics.state.value.entries.joinToString(" ") { it.message }

    // ------------------------------------------------------------ identity

    @Test
    fun `a property is born bound to its building and keeps one record`() {
        val w = propWorld()
        val p = w.properties.ensureProperty("forge")

        assertEquals("prop:forge", p!!.id)
        assertEquals("forge", p.buildingId)
        // Asking twice hands back the same record, never a second deed.
        assertTrue(w.properties.ensureProperty("forge") === w.properties.propertyOf("forge"))
        assertEquals(1, w.properties.allProperties().count { it.buildingId == "forge" })
    }

    @Test
    fun `no property exists until the world writes paper about a building`() {
        val w = propWorld()

        assertNull(w.properties.propertyOf("barn"))
        assertNull(w.properties.ownerOf("barn"))
        assertEquals(PropertySystem.VACANT, w.properties.statusOf("barn"))
    }

    // ------------------------------------------------------------ ownership

    @Test
    fun `ownership passes on and the deed keeps its history`() {
        val w = propWorld()
        val first = w.properties.transferOwnership("barn", "hawk", 100.0)!!

        assertTrue(first.isActive())
        assertEquals("hawk", w.properties.ownerOf("barn"))

        val second = w.properties.transferOwnership("barn", "oskar", 200.0) ?: error("refused: " + diagnosticsText())

        assertEquals("oskar", w.properties.ownerOf("barn"))
        assertFalse("the old ownership is ended, not deleted", first.isActive())
        assertEquals("it ended when the new one began", 200.0, first.endMinute, 0.001)
        assertTrue("the new ownership is a different record", second.id != first.id)
        assertEquals("one live ownership at a time", 1, w.save.propertyRels.count { it.propertyId == "prop:barn" && it.kind == PropRels.OWNERSHIP && it.isActive() })
        assertEquals("both deeds remain readable", 2, w.save.propertyRels.count { it.kind == PropRels.OWNERSHIP && it.propertyId == "prop:barn" })
    }

    @Test
    fun `one person may own several buildings`() {
        val w = propWorld()
        w.properties.transferOwnership("barn", "hawk", 10.0)
        w.properties.transferOwnership("forge", "hawk", 20.0)

        val owned = w.properties.ownershipsOf("hawk")
        assertEquals(setOf("prop:barn", "prop:forge"), owned.map { it.id }.toSet())
    }

    @Test
    fun `ownership can be given up without a successor`() {
        val w = propWorld()
        w.properties.transferOwnership("barn", "hawk", 10.0)

        assertTrue(w.properties.endOwnership("barn", 50.0))
        assertNull(w.properties.ownerOf("barn"))
        assertEquals("with no holder, the paper is vacant again", PropertySystem.VACANT, w.properties.statusOf("barn"))
    }

    // ------------------------------------------------------------ tenancy & lease

    @Test
    fun `a tenancy begins, holds and ends without erasing the record`() {
        val w = propWorld()
        w.properties.transferOwnership("cottage", "hawk", 10.0)

        val tenancy = w.properties.startTenancy("cottage", "finn", 20.0, "weekly")!!
        assertEquals(listOf("finn"), w.properties.tenantsOf("cottage"))
        assertTrue(tenancy.isActive())
        assertEquals(20.0, tenancy.startMinute, 0.001)

        assertTrue(w.properties.endTenancy("cottage", "finn", 40.0))

        assertTrue(w.properties.tenantsOf("cottage").isEmpty())
        assertFalse(tenancy.isActive())
        assertEquals("weekly", tenancy.terms)
        assertEquals("the ended tenancy stays in the ledger", "finn", w.save.propertyRels.first { it.id == tenancy.id }.partyId)
        assertTrue("ending again has nothing to end", !w.properties.endTenancy("cottage", "finn", 60.0))    }

    @Test
    fun `a tenant may hold a building its owner does not live in`() {
        val w = propWorld()
        w.properties.transferOwnership("cottage", "hawk", 10.0)
        w.properties.startTenancy("cottage", "wren", 12.0)

        assertEquals("hawk", w.properties.ownerOf("cottage"))
        assertEquals(listOf("wren"), w.properties.tenantsOf("cottage"))
        assertEquals(PropertySystem.TENANTED, w.properties.statusOf("cottage"))
    }

    @Test
    fun `a commercial lease is its own word, separate from a home's tenancy`() {
        val w = propWorld()
        w.properties.startTenancy("barn", "finn", 10.0)
        w.properties.startLease("barn", "wren", 12.0)

        assertEquals(listOf("finn"), w.properties.tenantsOf("barn"))
        assertEquals(listOf("wren"), w.properties.leaseholdersOf("barn"))
        assertTrue(w.properties.homesOf("finn").isNotEmpty())
        assertTrue(w.properties.leasesOf("wren").isNotEmpty())
        assertTrue(w.properties.homesOf("wren").isEmpty())
        assertTrue(w.properties.endLease("barn", "wren", 30.0))
        assertTrue(w.properties.leaseholdersOf("barn").isEmpty())
        assertEquals("the tenancy is untouched by the lease ending", listOf("finn"), w.properties.tenantsOf("barn"))
    }

    // ------------------------------------------------------------ residence

    @Test
    fun `residence is recorded in its own name, apart from owning and renting`() {
        val w = propWorld()
        w.properties.transferOwnership("barn", "hawk", 10.0)
        w.properties.addResident("barn", "finn", 12.0)

        assertEquals(listOf("finn"), w.properties.residentsOf("barn", includeHousehold = false))
        assertTrue("living somewhere is not owning it", w.properties.ownerOf("barn") == "hawk")
        assertTrue(w.properties.removeResident("barn", "finn", 30.0))
        assertTrue(w.properties.residentsOf("barn", includeHousehold = false).isEmpty())
    }

    @Test
    fun `a household's members are surfaced as residents without paper made for them`() {
        val w = propWorld()

        val before = w.save.propertyRels.size
        val residents = w.properties.residentsOf("cottage")

        assertEquals("hawk and wren share the cottage", setOf("hawk", "wren"), residents.toSet())
        assertEquals("the bridge writes no relationship records", before, w.save.propertyRels.size)
        assertNull("a household's home is never read as ownership", w.properties.ownerOf("cottage"))
        assertTrue(w.properties.residentsOf("cottage", includeHousehold = false).isEmpty())
    }

    // ------------------------------------------------------------ business & employment

    @Test
    fun `running a business says nothing about owning the building`() {
        val w = propWorld()
        w.properties.transferOwnership("forge", "hawk", 10.0)
        w.properties.associateBusiness("forge", "wren", 12.0)

        assertEquals("hawk", w.properties.ownerOf("forge"))
        assertEquals("wren", w.properties.businessOf("forge"))
        assertEquals(listOf("prop:forge"), w.properties.businessesOf("wren").map { it.id })
        assertTrue(w.properties.endBusiness("forge", "wren", 30.0))
        assertNull(w.properties.businessOf("forge"))
        assertEquals("ownership is untouched by the business closing", "hawk", w.properties.ownerOf("forge"))
    }

    @Test
    fun `employment names the employer and never confers ownership`() {
        val w = propWorld()
        w.properties.transferOwnership("forge", "hawk", 10.0)

        val job = w.properties.hire("finn", "forge", "wren", 20.0, "apprentice")!!

        assertEquals("wren", job.principalId)
        assertEquals(listOf("finn"), w.properties.employeesOf("forge"))
        assertEquals(listOf("prop:forge"), w.properties.workplacesOf("finn").map { it.id })
        assertEquals("working there makes no one but the deed's holder an owner", "hawk", w.properties.ownerOf("forge"))
        assertTrue("Finn works but does not own", "finn" !in w.save.propertyRels.filter { it.kind == PropRels.OWNERSHIP && it.isActive() }.map { it.partyId })
        assertTrue(w.properties.endEmployment("finn", "forge", 40.0))
        assertTrue(w.properties.employeesOf("forge").isEmpty())
    }

    @Test
    fun `rehiring after a job ended keeps both spells on the ledger`() {
        val w = propWorld()
        val first = w.properties.hire("finn", "barn", "hawk", 10.0)!!
        w.properties.endEmployment("finn", "barn", 20.0)
        val second = w.properties.hire("finn", "barn", "hawk", 30.0)!!

        assertTrue(first.id != second.id)
        assertFalse(first.isActive())
        assertTrue(second.isActive())
        assertEquals(2, w.save.propertyRels.count { it.kind == PropRels.EMPLOYMENT && it.propertyId == "prop:barn" })
    }

    // ------------------------------------------------------------ derived status

    @Test
    fun `a building's status is derived from its relationships and nothing else`() {
        val w = propWorld()

        assertEquals("no paper, no story", PropertySystem.VACANT, w.properties.statusOf("barn"))
        w.properties.transferOwnership("barn", "hawk", 10.0)
        assertEquals(PropertySystem.OWNED, w.properties.statusOf("barn"))
        w.properties.addResident("barn", "finn", 12.0)
        assertEquals("lived in without a letting", PropertySystem.OCCUPIED, w.properties.statusOf("barn"))
        w.properties.removeResident("barn", "finn", 14.0)
        w.properties.startTenancy("barn", "wren", 16.0)
        assertEquals(PropertySystem.TENANTED, w.properties.statusOf("barn"))
        w.properties.endTenancy("barn", "wren", 18.0)
        w.properties.addResident("barn", "hawk", 20.0)
        assertEquals("the owner living in their own building", PropertySystem.OWNER_OCCUPIED, w.properties.statusOf("barn"))
    }

    // ------------------------------------------------------------ coexistence

    @Test
    fun `every relationship can hold one building at once, independently`() {
        val w = propWorld()
        w.properties.transferOwnership("forge", "hawk", 10.0)
        w.properties.startLease("forge", "oskar", 12.0)
        w.properties.associateBusiness("forge", "oskar", 14.0)
        w.properties.hire("finn", "forge", "oskar", 16.0)
        w.properties.addResident("forge", "finn", 18.0)

        assertEquals("hawk", w.properties.ownerOf("forge"))
        assertEquals(listOf("oskar"), w.properties.leaseholdersOf("forge"))
        assertEquals("oskar", w.properties.businessOf("forge"))
        assertEquals(listOf("finn"), w.properties.employeesOf("forge"))
        assertEquals(listOf("finn"), w.properties.residentsOf("forge", includeHousehold = false))
        assertEquals("let to a business under lease", PropertySystem.TENANTED, w.properties.statusOf("forge"))
        assertEquals("five live relationships, ended ones kept aside", 5, w.save.propertyRels.count { it.propertyId == "prop:forge" && it.isActive() })
    }

    // ------------------------------------------------------------ invalid references

    @Test
    fun `a relationship to no one is refused with a report, never guessed`() {
        val w = propWorld()

        val before = w.save.propertyRels.size
        assertNull(w.properties.transferOwnership("barn", "nobody", 10.0))
        assertNull(w.properties.startTenancy("barn", "nobody", 10.0))
        assertNull(w.properties.hire("nobody", "barn", "hawk", 10.0))
        assertNull(w.properties.hire("finn", "barn", "nobody", 10.0))
        assertNull(w.properties.associateBusiness("nowhere", "hawk", 10.0))
        assertEquals("nothing was recorded about the barn", before, w.save.propertyRels.size)
        assertTrue("every refusal is reported", diagnosticsText().contains("nobody") || diagnosticsText().contains("nowhere"))
    }

    @Test
    fun `the content pipeline drops a property about a building that does not exist`() {
        val pack = propPack().copy(properties = listOf(
            PropertyDef(id = "prop_ghost", building = "nowhere", owner = "hawk"),
            PropertyDef(id = "prop_stray", building = "barn", owner = "ghost", residents = listOf("finn", "phantom")),
        ))
        val content = TestPacks.build(pack)

        assertTrue("the ghost building's property is removed", content.properties["prop_ghost"] == null)
        val stray = content.properties["prop_stray"]!!
        assertEquals("barn", stray.building)
        assertEquals("an owner nothing defines is dropped with a report", "", stray.owner)
        assertEquals("finn", stray.residents.single())
        assertTrue(content.issues.any { it.severity == "error" && it.entry == "prop_ghost" })
        assertTrue(content.issues.any { it.severity == "warning" && it.message.contains("prop_stray") })
    }

    // ------------------------------------------------------------ save binding & migration

    @Test
    fun `a loaded save's property paper is canonicalised and self-heals`() {
        val save = SaveGame()
        save.properties += app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave(id = "prop:cottage", buildingId = "core:cottage", name = "Old deed")
        save.properties += app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave(id = "prop:forge", buildingId = "forge")
        save.properties += app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave(id = "prop:forge2", buildingId = "forge", name = "Duplicate")
        save.propertyRels += app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertyRelationship(id = "r1", kind = PropRels.OWNERSHIP, propertyId = "prop:forge", partyId = "hawk", startMinute = 100.0)
        save.propertyRels += app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertyRelationship(id = "r2", kind = PropRels.OWNERSHIP, propertyId = "prop:forge", partyId = "wren", startMinute = 50.0)
        save.propertyRels += app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertyRelationship(id = "r3", kind = PropRels.RESIDENCE, propertyId = "prop:vanished", partyId = "finn")
        save.propertyRels += app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertyRelationship(id = "r4", kind = PropRels.RESIDENCE, propertyId = "prop:cottage", partyId = "stranger")

        val content = TestPacks.build(propPack())
        SaveBinding.bind(save, content)

        assertEquals("the canonical building id is rewritten to full form", "cottage", save.properties.first { it.id == "prop:cottage" }.buildingId)
        assertEquals("one property to a building - the first recorded stays", 2, save.properties.size)
        assertEquals("two live ownerships resolve to the earlier begun", "wren", save.propertyRels.first { it.id == "r2" }.partyId)
        assertTrue(save.propertyRels.first { it.id == "r2" }.isActive())
        assertTrue("the second claim was let go", save.propertyRels.none { it.id == "r1" })
        assertTrue("a relationship to a property that is gone is let go", save.propertyRels.none { it.id == "r3" })
        assertTrue("a person nothing defines is let go with a report", save.propertyRels.none { it.id == "r4" })
        assertTrue(diagnosticsText().contains("stranger") || diagnosticsText().contains("property"))
    }

    @Test
    fun `a save round-trips with its property paper intact`() {
        val save = SaveGame(minutes = 5000.0)
        save.properties += app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertySave(id = "prop:barn", buildingId = "barn", name = "The barn")
        save.propertyRels += app.rork.p7buzvrzcw3frddi8y50ou.sim.PropertyRelationship(
            id = "ownership:prop:barn:hawk", kind = PropRels.OWNERSHIP, propertyId = "prop:barn",
            partyId = "hawk", startMinute = 100.0, terms = "clear",
        )

        val text = SaveStore.encode(save)
        assertEquals(6, save.version)
        val again = SaveStore.decode(text)

        assertEquals(1, again.properties.size)
        val rel = again.propertyRels.single()
        assertEquals(PropRels.OWNERSHIP, rel.kind)
        assertEquals("hawk", rel.partyId)
        assertEquals(100.0, rel.startMinute, 0.001)
        assertTrue(rel.isActive())
    }

    // ------------------------------------------------------------ content seeding

    @Test
    fun `a pack's properties seed once and a world that changed hands keeps its own story`() {
        val w = propWorld()

        assertEquals("hawk", w.properties.ownerOf("forge"))
        assertEquals("wren", w.properties.businessOf("forge"))
        assertEquals(listOf("finn"), w.properties.employeesOf("forge"))

        // The world moves on: the forge changes hands.
        w.properties.transferOwnership("forge", "oskar", 9000.0)

        // A reload re-binds the packs; the player's world is not overwritten by them.
        w.properties.bind()
        assertEquals("the pack's seed does not undo a sale", "oskar", w.properties.ownerOf("forge"))
        assertEquals("and nothing is seeded twice", 1, w.save.propertyRels.count { it.kind == PropRels.OWNERSHIP && it.propertyId == "prop:forge" && it.isActive() })
    }

    @Test
    fun `a pack names its employer through the business it declares`() {
        val w = propWorld()

        val job = w.save.propertyRels.first { it.kind == PropRels.EMPLOYMENT && it.propertyId == "prop:forge" }
        assertEquals("finn", job.partyId)
        assertEquals("wren runs the forge, so Finn works for Wren", "wren", job.principalId)
    }

    // ------------------------------------------------------------ regression

    @Test
    fun `property records leave the rest of the save untouched`() {
        val w = propWorld()
        val householdsBefore = w.save.households.size
        w.properties.transferOwnership("cottage", "oskar", 100.0)
        w.properties.addResident("cottage", "finn", 100.0)

        assertEquals("households are unchanged by property ops", householdsBefore, w.save.households.size)
        assertEquals("the household still holds the cottage as its home", "cottage", w.save.households.first().home)
        assertEquals("and its members still live there through the bridge", setOf("hawk", "wren", "finn"), w.properties.residentsOf("cottage").toSet())
        assertEquals(6, w.save.version)
    }
}
