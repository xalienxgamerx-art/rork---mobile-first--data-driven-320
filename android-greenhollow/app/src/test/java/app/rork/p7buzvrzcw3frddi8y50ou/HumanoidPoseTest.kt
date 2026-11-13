package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.render.Pose
import app.rork.p7buzvrzcw3frddi8y50ou.render.Poses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The humanoid animation vocabulary: one blended walk-to-run gait with real knees and elbows,
 * an idle that breathes, a sit that folds the legs onto a chair, and the shared hand actions
 * that overlay on any stance. All pure math — the same functions the renderer poses from.
 */
class HumanoidPoseTest {

    private fun walked(phase: Float, intensity: Float = 0.58f): Pose {
        val p = Pose()
        Poses.locomotion(p, phase, intensity)
        return p
    }

    @Test
    fun `the stride is a stride - legs oppose each other`() {
        val a = walked(0.7f)
        val b = walked(0.7f + Math.PI.toFloat()) // half a cycle later
        assertEquals(a.hipL, -a.hipR, 1e-4f)
        assertEquals(a.hipL, b.hipR, 1e-4f) // the other leg is where this one was
        assertEquals(a.shoulderL, -a.shoulderR, 1e-4f) // arms swing against the legs
    }

    @Test
    fun `knees only ever bend backwards - the heel kicks, never the shin bows forward`() {
        var phase = 0f
        while (phase < 6.3f) {
            val p = walked(phase, 1f)
            assertTrue("knee L at $phase was ${p.kneeL}", p.kneeL in 0f..130f)
            assertTrue("knee R at $phase was ${p.kneeR}", p.kneeR in 0f..130f)
            phase += 0.1f
        }
    }

    @Test
    fun `knees and elbows articulate independently of the hips`() {
        val p = walked(0.8f, 1f)
        // A stiff-limbed swing would tie the elbow to the shoulder angle; the gait must not.
        assertTrue(abs(p.elbowR - p.shoulderR) > 5f)
        assertTrue(abs(p.kneeL - p.hipL) > 5f)
        // And over a cycle the elbow varies while the shoulder varies, out of step.
        var elbowMin = 999f; var elbowMax = -999f; var shoulderMax = -999f
        var t = 0f
        while (t < 6.3f) {
            val q = walked(t, 1f)
            elbowMin = minOf(elbowMin, q.elbowR)
            elbowMax = maxOf(elbowMax, q.elbowR)
            shoulderMax = maxOf(shoulderMax, abs(q.shoulderR))
            t += 0.05f
        }
        assertTrue(elbowMax - elbowMin > 30f)
        assertTrue(shoulderMax > 10f)
    }

    @Test
    fun `running is a bigger movement than walking`() {
        val walk = walked(0.7f, 0.55f)
        val run = walked(0.7f, 1f)
        assertTrue(abs(run.hipL) > abs(walk.hipL))
        assertTrue(run.kneeL > walk.kneeL)
        assertTrue(run.lean > walk.lean)
        assertTrue(run.hipY < walk.hipY) // the sprinting body rides lower into its stride
    }

    @Test
    fun `the hips bob twice per stride`() {
        val low = walked(0f).hipY
        val mid = walked(0.25f * Math.PI.toFloat()).hipY
        val again = walked(Math.PI.toFloat()).hipY
        assertTrue(mid > low) // highest at mid-stance
        assertEquals(low, again, 1e-3f) // two bobs per cycle, symmetric
        assertTrue(Pose.STAND_HIP - low < 0.08f)
    }

    @Test
    fun `sitting folds the body onto the chair and rises back to stand`() {
        val seated = Pose()
        Poses.sit(seated, 1f, 0.46f)
        assertTrue(seated.hipL in 65f..105f) // thighs roughly horizontal
        assertTrue(seated.kneeL in 65f..115f) // shins roughly vertical
        assertTrue(seated.hipY < Pose.STAND_HIP - 0.2f) // the body actually went down
        // At the bottom the shins (hip - knee) hang near vertical, so feet rest on the floor.
        assertTrue(abs(seated.hipL - seated.kneeL) < 25f)
        val mid = Pose()
        Poses.sit(mid, 0.5f, 0.46f)
        assertTrue(mid.hipY in seated.hipY..Pose.STAND_HIP) // the travel is continuous
        val standing = Pose()
        Poses.sit(standing, 0f, 0.46f)
        assertEquals(Pose.STAND_HIP, standing.hipY, 1e-4f)
        assertEquals(0f, standing.hipL, 1e-4f)
    }

    @Test
    fun `picking bends the body down and reaching straightens the arm`() {
        val pick = Pose()
        Poses.hand(pick, app.rork.p7buzvrzcw3frddi8y50ou.sim.ActorAction.PICK, 0.5f)
        assertTrue(pick.hipY < Pose.STAND_HIP - 0.1f) // the trunk goes down to the object

        val reach = Pose()
        Poses.hand(reach, app.rork.p7buzvrzcw3frddi8y50ou.sim.ActorAction.REACH, 0.5f)
        val rest = walked(0f)
        assertTrue(reach.shoulderR > rest.shoulderR + 20f) // the arm comes up and out

        val work = Pose()
        Poses.hand(work, app.rork.p7buzvrzcw3frddi8y50ou.sim.ActorAction.WORK, 0.25f)
        val work2 = Pose()
        Poses.hand(work2, app.rork.p7buzvrzcw3frddi8y50ou.sim.ActorAction.WORK, 0.75f)
        assertTrue(abs(work.shoulderR - work2.shoulderR) > 15f) // the stroke actually strokes
    }

    @Test
    fun `hand actions leave the legs alone when overlaid on a stride`() {
        val base = walked(0.9f)
        val overlay = Pose()
        Poses.hand(overlay, app.rork.p7buzvrzcw3frddi8y50ou.sim.ActorAction.REACH, 0.5f)
        base.overlayArms(overlay)
        val fresh = walked(0.9f)
        assertEquals(fresh.hipL, base.hipL, 1e-5f)
        assertEquals(fresh.kneeR, base.kneeR, 1e-5f)
        assertEquals(overlay.shoulderR, base.shoulderR, 1e-5f)
    }

    @Test
    fun `idle is alive but nearly still`() {
        var phase = 0f
        while (phase < 20f) {
            val p = Pose()
            Poses.idle(p, phase, 1.7f)
            assertTrue(abs(p.hipL) < 5f)
            assertTrue(abs(p.shoulderL) < 6f)
            assertTrue(abs(p.hipY - Pose.STAND_HIP) < 0.03f)
            phase += 0.7f
        }
        val a = Pose()
        Poses.idle(a, 3f, 0f)
        val b = Pose()
        Poses.idle(b, 3f, 0f)
        assertEquals(a.shoulderL, b.shoulderL, 1e-6f) // deterministic
    }

    @Test
    fun `every pose is a pure function of its inputs`() {
        for (t in intArrayOf(0, 7, 31)) {
            val f = t / 7f
            val a = walked(f, 0.8f)
            val b = walked(f, 0.8f)
            assertEquals(a.hipL, b.hipL, 0f)
            assertEquals(a.hipY, b.hipY, 0f)
            assertTrue(sin(0f) == sin(0f)) // and the math underneath is honest
        }
    }
}
