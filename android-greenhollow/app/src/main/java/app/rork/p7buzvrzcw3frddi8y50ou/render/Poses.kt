package app.rork.p7buzvrzcw3frddi8y50ou.render

import app.rork.p7buzvrzcw3frddi8y50ou.sim.ActorAction
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The humanoid animation vocabulary, as pure math. A [Pose] is one set of joint angles —
 * hips, knees, shoulders, elbows, the height of the hip line and the torso's lean — and the
 * functions here fill one from a state: standing idle, walking and running, sitting down,
 * or one of the shared hand actions (reach, pick, hold, place, work).
 *
 * All angles are degrees. Limb angles are measured from hanging straight down, positive
 * swinging forward, matching the renderer's part convention (+rotation about x swings a
 * hanging limb toward the actor's front). Knees and elbows are relative bends: a knee bend
 * always folds the heel back, an elbow bend always folds the hand forward.
 *
 * Everything is a pure function of its arguments: no clocks, no world state, no allocation.
 * That makes the vocabulary testable on the JVM and identical for the player and every NPC.
 */
class Pose {
    var hipL = 0f
    var hipR = 0f
    var kneeL = 0f
    var kneeR = 0f
    var shoulderL = 0f
    var shoulderR = 0f
    var elbowL = 0f
    var elbowR = 0f

    /** Height of the hip line above the ground — 0.86 standing, the seat height sitting. */
    var hipY = STAND_HIP

    /** Torso lean, + = forward. */
    var lean = 0f

    fun reset() {
        hipL = 0f; hipR = 0f; kneeL = 0f; kneeR = 0f
        shoulderL = 0f; shoulderR = 0f; elbowL = 0f; elbowR = 0f
        hipY = STAND_HIP; lean = 0f
    }

    /** Copies the arm-and-trunk channels from [o] (legs stay whatever they were). */
    fun overlayArms(o: Pose) {
        shoulderL = o.shoulderL; shoulderR = o.shoulderR
        elbowL = o.elbowL; elbowR = o.elbowR
        hipY = o.hipY; lean = o.lean
    }

    companion object {
        const val STAND_HIP = 0.86f
    }
}

object Poses {

    private const val D = 0.017453292f

    /**
     * Walking and running, one blended gait. [phase] is the shared stride cycle, [intensity]
     * runs 0..1 from a slow walk to a full sprint: amplitude, knee lift, elbow bend and torso
     * lean all grow with it, so speeding up reads as running, not as faster stick-sliding.
     */
    fun locomotion(pose: Pose, phase: Float, intensity: Float) {
        val i = intensity.coerceIn(0f, 1f)
        val hipAmp = 18f + 30f * i
        val kneeAmp = 25f + 65f * i
        val armAmp = 15f + 22f * i
        val elbowBase = 15f + 15f * i
        val elbowFlex = 25f + 40f * i

        pose.hipL = sin(phase) * hipAmp
        pose.hipR = -sin(phase) * hipAmp
        // The knee folds while the leg swings forward, heel kicking back; stiff at stance.
        pose.kneeL = 6f + max(0f, cos(phase)) * kneeAmp
        pose.kneeR = 6f + max(0f, -cos(phase)) * kneeAmp
        // Arms swing against the legs; the elbow folds most early in the forward swing,
        // so the hand reaches out nearly straight and tucks on the way back.
        pose.shoulderL = -sin(phase) * armAmp
        pose.shoulderR = sin(phase) * armAmp
        pose.elbowL = elbowBase + elbowFlex * max(0f, -cos(phase))
        pose.elbowR = elbowBase + elbowFlex * max(0f, cos(phase))
        // Hips ride highest at mid-stance, lowest at the stride's extremes.
        pose.hipY = Pose.STAND_HIP - (0.015f + 0.045f * i) * (0.5f + 0.5f * cos(2f * phase))
        pose.lean = 3f + 9f * i
    }

    /** Standing at ease: breath, a slow shift of weight, arms hanging with a live elbow. */
    fun idle(pose: Pose, time: Float, seed: Float) {
        val t = time + seed
        pose.reset()
        pose.hipY = Pose.STAND_HIP - 0.006f * (1f + sin(t * 1.6f))
        pose.shoulderL = 2f + sin(t * 0.7f) * 2.5f
        pose.shoulderR = 2f + sin(t * 0.7f + 0.9f) * 2.5f
        pose.elbowL = 9f + sin(t * 0.9f) * 3f
        pose.elbowR = 9f + sin(t * 0.9f + 1.1f) * 3f
        pose.kneeL = 3f
        pose.kneeR = 3f
        pose.lean = 1.5f + sin(t * 1.6f) * 0.8f
    }

    /**
     * Sitting down onto (or rising from) a seat [seatH] tall. [stage] 0 = standing over the
     * chair, 1 = fully seated: the hips travel down and forward, the knees fold to right
     * angles, the shins stay near vertical so the feet rest on the floor.
     */
    fun sit(pose: Pose, stage: Float, seatH: Float) {
        val s = (stage * stage * (3f - 2f * stage)).coerceIn(0f, 1f) // smoothstep
        pose.hipL = 85f * s
        pose.hipR = 85f * s
        pose.kneeL = 5f + 83f * s
        pose.kneeR = 5f + 83f * s
        pose.hipY = Pose.STAND_HIP + (seatH + 0.04f - Pose.STAND_HIP) * s
        pose.lean = 2f - 4f * s // settles back into the chair
        pose.shoulderL = 8f * s
        pose.shoulderR = 8f * s
        pose.elbowL = 8f + 27f * s // hands rest in the lap
        pose.elbowR = 8f + 27f * s
    }

    /**
     * The shared hand actions. [stage] 0..1 drives one-shots (reach, pick, place) through
     * their arc and loops work cycles; all of them move the right arm, trunk and hips and
     * leave the legs alone, so they overlay any stance the body is holding.
     */
    fun hand(pose: Pose, action: Int, stage: Float) {
        val arch = sin(stage.coerceIn(0f, 1f) * 3.1415927f)
        when (action) {
            ActorAction.REACH -> {
                pose.shoulderR = 62f * arch
                pose.elbowR = 35f - 28f * arch // straightens as the hand arrives
                pose.lean = 7f * arch
                pose.hipY = Pose.STAND_HIP - 0.02f * arch
            }
            ActorAction.PICK -> {
                // Bend to the object: trunk down, arm out, hand closing at the bottom.
                pose.shoulderR = 48f * arch
                pose.elbowR = 12f + 28f * (1f - arch)
                pose.lean = 14f * arch
                pose.hipY = Pose.STAND_HIP - 0.24f * arch
                pose.kneeL = 14f * arch
                pose.kneeR = 14f * arch
            }
            ActorAction.HOLD -> {
                // Carrying in front of the chest, both hands cupped round the bundle.
                pose.shoulderL = 24f
                pose.shoulderR = 24f
                pose.elbowL = 55f
                pose.elbowR = 55f
                pose.lean = 3f
            }
            ActorAction.PLACE -> {
                pose.shoulderR = 42f * arch
                pose.elbowR = 20f + 12f * arch
                pose.lean = 10f * arch
                pose.hipY = Pose.STAND_HIP - 0.12f * arch
            }
            ActorAction.WORK -> {
                // A steady cycle of strokes: strike, lift, strike. Loops on stage.
                val t = stage * 6.2831855f
                val up = max(0f, sin(t))
                pose.shoulderR = 30f + 25f * sin(t)
                pose.elbowR = 40f + 35f * up
                pose.shoulderL = 18f
                pose.elbowL = 40f
                pose.lean = 6f + 3f * up
                pose.hipY = Pose.STAND_HIP - 0.012f * up
            }
        }
    }

    /** Degrees to radians, for callers that place geometry from a pose. */
    fun rad(deg: Float): Float = deg * D
}
