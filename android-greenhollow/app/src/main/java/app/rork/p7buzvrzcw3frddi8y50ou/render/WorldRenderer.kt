package app.rork.p7buzvrzcw3frddi8y50ou.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import app.rork.p7buzvrzcw3frddi8y50ou.sim.ActorAction
import app.rork.p7buzvrzcw3frddi8y50ou.sim.GameEngine
import app.rork.p7buzvrzcw3frddi8y50ou.sim.RenderFrame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Terrain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the vale. The renderer only ever reads a [RenderFrame] snapshot: the simulation is
 * stepped once per frame from here, then the lock is released before any GL work.
 */
class WorldRenderer(
    @Volatile var engine: GameEngine,
    @Volatile var level: Int,
) : GLSurfaceView.Renderer {

    /** Called (on the GL thread) roughly once a second with the measured frame rate. */
    var onFps: ((Int) -> Unit)? = null

    private val frame = RenderFrame()
    private var program = 0
    private var skyProgram = 0
    private var worldVbo = 0
    private var worldCount = 0
    private var interiorVbo = 0
    private var interiorCount = 0
    private var interiorFor: InteriorMesh.Entry? = null
    /** One VBO for everything that moves: cube, tapered limb segment and joint sphere. */
    private var cubeVbo = 0
    private var geoFrustumOffset = 0
    private var geoFrustumCount = 0
    private var geoSphereOffset = 0
    private var geoSphereCount = 0
    private var skyVbo = 0
    private var waterVbo = 0
    private var builtFor: Any? = null
    private var builtLevel = -1
    private var builtEpoch = -1L

    private var aPos = 0
    private var aNormal = 0
    private var aColor = 0
    private var uMvp = 0
    private var uModel = 0
    private var uTint = 0
    private var uSunDir = 0
    private var uSunCol = 0
    private var uAmbient = 0
    private var uCam = 0
    private var uFogCol = 0
    private var uFogDensity = 0
    private var uGlow = 0
    private var uAlpha = 0

    private var sPos = 0
    private var sForward = 0
    private var sRight = 0
    private var sUp = 0
    private var sZenith = 0
    private var sHorizon = 0
    private var sSunDir = 0
    private var sSunCol = 0
    private var sGround = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val base = FloatArray(16)
    private val torsoM = FloatArray(16)
    private val chain = FloatArray(16)
    private val seg = FloatArray(16)
    private val segB = FloatArray(16)
    private val pose = Pose()
    private val handPose = Pose()
    private var clock = 0f
    private val mvp = FloatArray(16)
    private val camWorld = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var aspect = 0.5f
    private var lastNanos = 0L
    private var fpsFrames = 0
    private var fpsTime = 0f
    private val fov = 72f

    private val zenith = FloatArray(3)
    private val horizon = FloatArray(3)
    private val sunCol = FloatArray(3)
    private val ambient = FloatArray(3)
    private val sunDir = FloatArray(3)
    private val fogCol = FloatArray(3)
    private var night = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERT, FRAG)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uSunDir = GLES20.glGetUniformLocation(program, "uSunDir")
        uSunCol = GLES20.glGetUniformLocation(program, "uSunCol")
        uAmbient = GLES20.glGetUniformLocation(program, "uAmbient")
        uCam = GLES20.glGetUniformLocation(program, "uCam")
        uFogCol = GLES20.glGetUniformLocation(program, "uFogCol")
        uFogDensity = GLES20.glGetUniformLocation(program, "uFogDensity")
        uGlow = GLES20.glGetUniformLocation(program, "uGlow")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")

        skyProgram = link(SKY_VERT, SKY_FRAG)
        sPos = GLES20.glGetAttribLocation(skyProgram, "aPos")
        sForward = GLES20.glGetUniformLocation(skyProgram, "uForward")
        sRight = GLES20.glGetUniformLocation(skyProgram, "uRight")
        sUp = GLES20.glGetUniformLocation(skyProgram, "uUp")
        sZenith = GLES20.glGetUniformLocation(skyProgram, "uZenith")
        sHorizon = GLES20.glGetUniformLocation(skyProgram, "uHorizon")
        sSunDir = GLES20.glGetUniformLocation(skyProgram, "uSunDir")
        sSunCol = GLES20.glGetUniformLocation(skyProgram, "uSunCol")
        sGround = GLES20.glGetUniformLocation(skyProgram, "uGround")

        val ids = IntArray(5)
        GLES20.glGenBuffers(5, ids, 0)
        worldVbo = ids[0]; cubeVbo = ids[1]; skyVbo = ids[2]; waterVbo = ids[3]; interiorVbo = ids[4]
        // Everything that moves draws from ONE buffer: the unit cube, the tapered limb
        // segment (thick end at the joint) and a coarse sphere for joints and heads —
        // uploaded once, drawn in ranges, so a humanoid costs no extra binds at all.
        val cube = WorldMesh.cube()
        val frustum = WorldMesh.frustum()
        val sphere = WorldMesh.sphere()
        geoFrustumOffset = cube.vertexCount
        geoFrustumCount = frustum.vertexCount
        geoSphereOffset = geoFrustumOffset + geoFrustumCount
        geoSphereCount = sphere.vertexCount
        val geo = FloatArray(cube.size + frustum.size + sphere.size)
        System.arraycopy(cube.data, 0, geo, 0, cube.size)
        System.arraycopy(frustum.data, 0, geo, cube.size, frustum.size)
        System.arraycopy(sphere.data, 0, geo, cube.size + frustum.size, sphere.size)
        upload(cubeVbo, geo, geo.size)
        upload(skyVbo, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f), 12)
        val w = MeshBuilder(256)
        w.place(0f, 0f, 0f, 0f)
        w.upward()
        val e = Terrain.HALF
        w.quad(-e, Terrain.WATER, -e, e, Terrain.WATER, -e, e, Terrain.WATER, e, -e, Terrain.WATER, e, 0.22f, 0.40f, 0.48f)
        upload(waterVbo, w.data, w.size)
        builtFor = null
        builtLevel = -1
        builtEpoch = -1L
        lastNanos = 0L
        clock = 0f
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / max(1, height)
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.12f, 700f)
    }

    private fun upload(vbo: Int, data: FloatArray, size: Int) {
        val buf = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data, 0, size).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, size * 4, buf, GLES20.GL_STATIC_DRAW)
    }

    private fun ensureWorld(e: GameEngine) {
        val lvl = level
        // The epoch moves whenever the world batch was rebuilt or invalidated — a rebaked
        // building, for instance — so the new geometry is uploaded exactly once.
        if (builtFor === e.content && builtLevel == lvl && builtEpoch == WorldMesh.epoch) return
        val mesh = WorldMesh.get(e.content, lvl)
        upload(worldVbo, mesh.data, mesh.size)
        worldCount = mesh.vertexCount
        builtFor = e.content
        builtLevel = lvl
        builtEpoch = WorldMesh.epoch
    }

    override fun onDrawFrame(gl: GL10?) {
        val e = engine
        ensureWorld(e)
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastNanos = now
        fpsFrames++
        fpsTime += dt
        clock += dt
        if (fpsTime >= 1f) {
            val fps = (fpsFrames / fpsTime).toInt()
            e.fps = fps
            onFps?.invoke(fps)
            fpsFrames = 0
            fpsTime = 0f
        }
        e.update(dt)
        e.fillFrame(frame)
        val f = frame

        atmosphere(f.hour, f.mist)
        val cp = cos(f.pitch)
        val fx = sin(f.yaw) * cp
        val fy = sin(f.pitch)
        val fz = -cos(f.yaw) * cp
        Matrix.setLookAtM(view, 0, f.camX, f.camY, f.camZ, f.camX + fx, f.camY + fy, f.camZ + fz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glClearColor(fogCol[0], fogCol[1], fogCol[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Sky.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(skyProgram)
        val th = tan(Math.toRadians(fov / 2.0)).toFloat()
        val rx = cos(f.yaw); val rz = sin(f.yaw)
        // up = right x forward
        val ux = -rz * fy
        val uy = rz * fx - rx * fz
        val uz = rx * fy
        GLES20.glUniform3f(sForward, fx, fy, fz)
        GLES20.glUniform3f(sRight, rx * th * aspect, 0f, rz * th * aspect)
        GLES20.glUniform3f(sUp, ux * th, uy * th, uz * th)
        GLES20.glUniform3fv(sZenith, 1, zenith, 0)
        GLES20.glUniform3fv(sHorizon, 1, horizon, 0)
        GLES20.glUniform3fv(sSunDir, 1, sunDir, 0)
        GLES20.glUniform3f(sSunCol, sunCol[0], sunCol[1], sunCol[2])
        GLES20.glUniform3fv(sGround, 1, fogCol, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyVbo)
        GLES20.glEnableVertexAttribArray(sPos)
        GLES20.glVertexAttribPointer(sPos, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(sPos)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Land.
        GLES20.glUseProgram(program)
        GLES20.glUniform3fv(uSunDir, 1, sunDir, 0)
        GLES20.glUniform3fv(uSunCol, 1, sunCol, 0)
        GLES20.glUniform3fv(uAmbient, 1, ambient, 0)
        GLES20.glUniform3f(uCam, f.camX, f.camY, f.camZ)
        GLES20.glUniform3fv(uFogCol, 1, fogCol, 0)
        val fogDistance = GraphicsPreset.of(level).fog / (1f + f.mist * 1.3f)
        GLES20.glUniform1f(uFogDensity, 1.5f / fogDistance)
        GLES20.glUniform1f(uGlow, 0.55f + night * 0.9f)
        GLES20.glUniform1f(uAlpha, 1f)
        GLES20.glUniform3f(uTint, 1f, 1f, 1f)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, identity, 0)
        // Land — or, with the player indoors, the interior instance's own geometry in its own
        // space, uploaded only when a different interior became active.
        if (f.inside) {
            val entry = InteriorMesh.get(e.activeInterior())
            if (entry != null) {
                if (entry !== interiorFor) {
                    upload(interiorVbo, entry.mesh.data, entry.mesh.size)
                    interiorFor = entry
                    interiorCount = entry.vertexCount
                }
                bind(interiorVbo)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, interiorCount)
            }
        } else {
            bind(worldVbo)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, worldCount)
        }

        // Everything that moves is assembled from one cube.
        bind(cubeVbo)
        for (i in 0 until f.nodeCount) node(f, i)
        for (i in 0 until f.actorCount) {
            val dx = f.ax[i] - f.camX
            val dz = f.az[i] - f.camZ
            val along = dx * fx + dz * fz
            val d2 = dx * dx + dz * dz
            if (along < -3f && d2 > 16f) continue
            if (f.akind[i] == 0) humanoid(f, i, d2 > 80f * 80f, d2 > 15f * 15f) else quadruped(f, i)
        }

        // The Silverrun — there is no river inside a house.
        if (!f.inside) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            GLES20.glUniform1f(uAlpha, 0.72f)
            GLES20.glUniform3f(uTint, 0.7f + horizon[0] * 0.5f, 0.8f + horizon[1] * 0.3f, 0.9f + horizon[2] * 0.2f)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, identity, 0)
            bind(waterVbo)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUniform1f(uAlpha, 1f)
        }

        if (f.firstPersonWeapon) weapon(f)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aColor)
    }

    private fun bind(vbo: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 36, 0)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 36, 12)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, 36, 24)
    }

    /** Draws one cube: pivot at (px,py,pz), rotated about x, then offset along y and scaled. */
    private fun part(
        px: Float, py: Float, pz: Float, angle: Float, oy: Float,
        sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float,
    ) {
        System.arraycopy(base, 0, model, 0, 16)
        Matrix.translateM(model, 0, px, py, pz)
        if (angle != 0f) Matrix.rotateM(model, 0, angle, 1f, 0f, 0f)
        if (oy != 0f) Matrix.translateM(model, 0, 0f, oy, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
        draw(model, sx, sy, sz, r, g, b, 0, 36)
    }

    /** Draws one body piece of the given geometry, already placed by the model matrix. */
    private fun piece(
        m: FloatArray, sx: Float, sy: Float, sz: Float,
        r: Float, g: Float, b: Float, geo: Int,
    ) {
        System.arraycopy(m, 0, model, 0, 16)
        Matrix.scaleM(model, 0, sx, sy, sz)
        when (geo) {
            GEO_FRUSTUM -> draw(model, sx, sy, sz, r, g, b, geoFrustumOffset, geoFrustumCount)
            GEO_SPHERE -> draw(model, sx, sy, sz, r, g, b, geoSphereOffset, geoSphereCount)
            else -> draw(model, sx, sy, sz, r, g, b, 0, 36)
        }
    }

    private fun draw(
        m: FloatArray, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float,
        offset: Int, count: Int,
    ) {
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0)
        GLES20.glUniform3f(uTint, r, g, b)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, offset, count)
    }

    private fun place(f: RenderFrame, i: Int) {
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, f.ax[i], f.ay[i], f.az[i])
        Matrix.rotateM(base, 0, -Math.toDegrees(f.ayaw[i].toDouble()).toFloat(), 0f, 1f, 0f)
        if (f.aroll[i] != 0f) Matrix.rotateM(base, 0, f.aroll[i], 0f, 0f, 1f)
        val s = f.ascale[i]
        Matrix.scaleM(base, 0, s, s, s)
    }

    private fun humanoid(f: RenderFrame, i: Int, far: Boolean, mid: Boolean) {
        place(f, i)
        val o = i * 9
        val c = f.acol
        val sr = c[o]; val sg = c[o + 1]; val sb = c[o + 2]
        val cr = c[o + 3]; val cg = c[o + 4]; val cb = c[o + 5]
        val hr = c[o + 6]; val hg = c[o + 7]; val hb = c[o + 8]
        val flags = f.aflags[i]

        // ---- the pose: what the whole body is doing this frame -------------------------
        pose.reset()
        val action = f.aaction[i]
        when (action) {
            ActorAction.SIT -> Poses.sit(pose, f.astage[i], SEAT_H)
            else -> {
                if (f.aintensity[i] > 0.04f) {
                    Poses.locomotion(pose, f.aphase[i], f.aintensity[i])
                } else {
                    Poses.idle(pose, clock, i * 1.7f)
                }
                if (action != ActorAction.LOCO) {
                    // A hand action plays the arms, trunk and hips over whatever the legs
                    // are doing: you can reach, gather or work mid-stride or standing.
                    handPose.reset()
                    Poses.hand(handPose, action, if (action == ActorAction.HOLD) 1f else f.astage[i])
                    pose.overlayArms(handPose)
                }
            }
        }

        if (far) {
            // Three blocks, still honouring the hip line: distant figures shrink into their
            // chairs and bob with their stride, they just carry no joints.
            part(0f, pose.hipY - 0.46f, 0f, 0f, 0f, 0.34f, 0.8f, 0.2f, cr * 0.6f, cg * 0.6f, cb * 0.6f)
            part(0f, pose.hipY + 0.24f, 0f, 0f, 0f, 0.44f, 0.62f, 0.26f, cr, cg, cb)
            part(0f, pose.hipY + 0.70f, 0f, 0f, 0f, 0.26f, 0.28f, 0.26f, sr, sg, sb)
            return
        }

        // ---- the rig: hip line, leaning trunk, and four two-segment limbs -------------
        val hipY = pose.hipY
        val clothDarkR = cr * 0.58f; val clothDarkG = cg * 0.58f; val clothDarkB = cb * 0.58f
        val sleeveR = cr * 0.88f; val sleeveG = cg * 0.88f; val sleeveB = cb * 0.88f
        val bootR = 0.30f; val bootG = 0.22f; val bootB = 0.15f
        val woodR = 0.25f; val woodG = 0.17f; val woodB = 0.11f

        // Torso space: hips up, leaning forward as the pose asks. Everything above the
        // belt — torso, head, arms — is drawn in this space and leans together.
        System.arraycopy(base, 0, torsoM, 0, 16)
        Matrix.translateM(torsoM, 0, 0f, hipY, 0f)
        if (pose.lean != 0f) Matrix.rotateM(torsoM, 0, pose.lean, 1f, 0f, 0f)

        // Legs hang from the hip line in body space, so a seated figure's folded thighs
        // and near-vertical shins reach the floor on their own.
        leg(-1, pose.hipL, pose.kneeL, hipY, mid, clothDarkR, clothDarkG, clothDarkB, bootR, bootG, bootB, sr, sg, sb)
        leg(1, pose.hipR, pose.kneeR, hipY, mid, clothDarkR, clothDarkG, clothDarkB, bootR, bootG, bootB, sr, sg, sb)

        // Trunk: pelvis, tapered chest (wide at the shoulders), belt, neck and head.
        piece(torsoM, 0.30f, 0.16f, 0.17f, clothDarkR, clothDarkG, clothDarkB, 0)
        System.arraycopy(torsoM, 0, seg, 0, 16)
        Matrix.translateM(seg, 0, 0f, 0.30f, 0f)
        piece(seg, 0.36f, 0.55f, 0.20f, cr, cg, cb, GEO_FRUSTUM)
        if (!mid) {
            System.arraycopy(torsoM, 0, seg, 0, 16)
            Matrix.translateM(seg, 0, 0f, 0.02f, 0f)
            piece(seg, 0.34f, 0.06f, 0.19f, woodR, woodG, woodB, 0) // belt
            System.arraycopy(torsoM, 0, seg, 0, 16)
            Matrix.translateM(seg, 0, 0f, 0.62f, 0f)
            piece(seg, 0.09f, 0.05f, 0.09f, sr, sg, sb, 0) // neck
        }
        // Head and everything worn on it (local to the leaning trunk).
        System.arraycopy(torsoM, 0, seg, 0, 16)
        Matrix.translateM(seg, 0, 0f, 0.76f, -0.01f)
        piece(seg, 0.24f, 0.26f, 0.24f, sr, sg, sb, GEO_SPHERE)
        if (flags and 16 == 0) {
            System.arraycopy(torsoM, 0, seg, 0, 16)
            Matrix.translateM(seg, 0, 0f, 0.85f, 0.02f)
            piece(seg, 0.29f, 0.09f, 0.30f, hr, hg, hb, 0) // hair
        }
        if (flags and 4 != 0 && flags and 16 == 0) {
            System.arraycopy(torsoM, 0, seg, 0, 16)
            Matrix.translateM(seg, 0, 0f, 0.64f, 0.15f)
            piece(seg, 0.28f, 0.36f, 0.07f, hr, hg, hb, 0) // long hair
        }
        if (flags and 2 != 0) {
            System.arraycopy(torsoM, 0, seg, 0, 16)
            Matrix.translateM(seg, 0, 0f, 0.59f, -0.14f)
            piece(seg, 0.22f, 0.13f, 0.06f, hr, hg, hb, 0) // beard
        }
        if (flags and 1 != 0) {
            System.arraycopy(torsoM, 0, seg, 0, 16)
            Matrix.translateM(seg, 0, 0f, 0.90f, 0f)
            piece(seg, 0.50f, 0.04f, 0.50f, clothDarkR, clothDarkG, clothDarkB, 0) // hat brim
            System.arraycopy(torsoM, 0, seg, 0, 16)
            Matrix.translateM(seg, 0, 0f, 0.97f, 0f)
            piece(seg, 0.27f, 0.12f, 0.27f, clothDarkR, clothDarkG, clothDarkB, 0) // crown
        }

        // Arms, in torso space so they lean and swing with the body. The sword rides the
        // right hand's frame; a held bundle sits in both, cupped at the chest.
        arm(-1, pose.shoulderL, pose.elbowL, mid, sleeveR, sleeveG, sleeveB, sr, sg, sb, cr, cg, cb, holding = false, armed = false)
        arm(1, pose.shoulderR, pose.elbowR, mid, sleeveR, sleeveG, sleeveB, sr, sg, sb, cr, cg, cb,
            holding = action == ActorAction.HOLD,
            armed = flags and 8 != 0,
        )
    }

    /** One leg: hip joint, tapered thigh, knee, tapered shin, and a foot kept level. */
    private fun leg(
        side: Int, hip: Float, knee: Float, hipY: Float, mid: Boolean,
        tr: Float, tg: Float, tb: Float, br: Float, bg: Float, bb: Float,
        sr: Float, sg: Float, sb: Float,
    ) {
        System.arraycopy(base, 0, chain, 0, 16)
        Matrix.translateM(chain, 0, side * 0.10f, hipY, 0f)
        if (!mid) {
            piece(chain, 0.18f, 0.18f, 0.18f, sr * 0.9f, sg * 0.9f, sb * 0.9f, GEO_SPHERE) // hip joint
        }
        if (hip != 0f) Matrix.rotateM(chain, 0, hip, 1f, 0f, 0f)
        System.arraycopy(chain, 0, seg, 0, 16)
        Matrix.translateM(seg, 0, 0f, -0.22f, 0f)
        piece(seg, 0.10f, 0.44f, 0.11f, tr, tg, tb, GEO_FRUSTUM) // thigh
        Matrix.translateM(chain, 0, 0f, -0.44f, 0f)
        if (knee != 0f) Matrix.rotateM(chain, 0, -knee, 1f, 0f, 0f) // the shin folds back
        System.arraycopy(chain, 0, seg, 0, 16)
        Matrix.translateM(seg, 0, 0f, -0.20f, 0f)
        piece(seg, 0.085f, 0.40f, 0.095f, tr, tg, tb, GEO_FRUSTUM) // shin
        // The foot stays level whatever the leg does: cancel the accumulated swing.
        if (hip - knee != 0f) Matrix.rotateM(chain, 0, -(hip - knee), 1f, 0f, 0f)
        Matrix.translateM(chain, 0, 0f, -0.41f, 0f)
        Matrix.rotateM(chain, 0, -8f, 1f, 0f, 0f) // heel down, toes out
        piece(chain, 0.11f, 0.07f, 0.22f, br, bg, bb, 0)
    }

    /** One arm: shoulder joint, tapered upper arm (sleeve), elbow, forearm, hand. */
    private fun arm(
        side: Int, shoulder: Float, elbow: Float, mid: Boolean,
        slR: Float, slG: Float, slB: Float, sr: Float, sg: Float, sb: Float,
        skinR: Float, skinG: Float, skinB: Float,
        holding: Boolean, armed: Boolean,
    ) {
        System.arraycopy(torsoM, 0, chain, 0, 16)
        Matrix.translateM(chain, 0, side * 0.24f, 0.54f, 0f)
        if (!mid) {
            piece(chain, 0.15f, 0.15f, 0.15f, slR * 0.9f, slG * 0.9f, slB * 0.9f, GEO_SPHERE) // shoulder
        }
        if (shoulder != 0f) Matrix.rotateM(chain, 0, shoulder, 1f, 0f, 0f)
        System.arraycopy(chain, 0, seg, 0, 16)
        Matrix.translateM(seg, 0, 0f, -0.15f, 0f)
        piece(seg, 0.085f, 0.30f, 0.09f, slR, slG, slB, GEO_FRUSTUM) // upper arm, sleeve
        Matrix.translateM(chain, 0, 0f, -0.30f, 0f)
        if (elbow != 0f) Matrix.rotateM(chain, 0, elbow, 1f, 0f, 0f) // the hand folds forward
        System.arraycopy(chain, 0, seg, 0, 16)
        Matrix.translateM(seg, 0, 0f, -0.14f, 0f)
        piece(seg, 0.068f, 0.28f, 0.075f, skinR, skinG, skinB, GEO_FRUSTUM) // forearm
        Matrix.translateM(chain, 0, 0f, -0.28f, 0f)
        if (!mid) {
            System.arraycopy(chain, 0, segB, 0, 16)
            Matrix.translateM(segB, 0, 0f, -0.05f, 0f)
            piece(segB, 0.09f, 0.11f, 0.06f, skinR, skinG, skinB, 0) // hand
        }
        if (armed && side > 0) {
            // The blade hangs from the hand's own frame: down at rest, sweeping when the
            // arm swings, guarding forward when it is raised.
            System.arraycopy(chain, 0, segB, 0, 16)
            Matrix.translateM(segB, 0, 0f, -0.40f, 0f)
            piece(segB, 0.04f, 0.70f, 0.02f, 0.75f, 0.76f, 0.80f, 0) // blade
            System.arraycopy(chain, 0, segB, 0, 16)
            Matrix.translateM(segB, 0, 0f, -0.06f, 0f)
            piece(segB, 0.14f, 0.03f, 0.04f, 0.5f, 0.42f, 0.25f, 0) // guard
        }
        if (holding && side > 0) {
            System.arraycopy(chain, 0, segB, 0, 16)
            Matrix.translateM(segB, 0, 0f, -0.12f, -0.02f)
            piece(segB, 0.16f, 0.14f, 0.12f, 0.55f, 0.42f, 0.26f, 0) // the gathered bundle
        }
    }

    private fun quadruped(f: RenderFrame, i: Int) {
        place(f, i)
        val o = i * 9
        val r = f.acol[o]; val g = f.acol[o + 1]; val b = f.acol[o + 2]
        val swing = sin(f.aphase[i]) * 30f
        part(0f, 0.62f, 0f, 0f, 0f, 0.4f, 0.4f, 0.95f, r, g, b)
        part(0f, 0.78f, -0.6f, 0f, 0f, 0.3f, 0.3f, 0.34f, r * 1.08f, g * 1.08f, b * 1.08f)
        part(0f, 0.72f, -0.82f, 0f, 0f, 0.16f, 0.14f, 0.16f, r * 0.7f, g * 0.7f, b * 0.7f)
        part(-0.1f, 0.98f, -0.55f, 0f, 0f, 0.07f, 0.14f, 0.07f, r * 0.8f, g * 0.8f, b * 0.8f)
        part(0.1f, 0.98f, -0.55f, 0f, 0f, 0.07f, 0.14f, 0.07f, r * 0.8f, g * 0.8f, b * 0.8f)
        part(-0.14f, 0.45f, -0.35f, swing, -0.22f, 0.1f, 0.45f, 0.1f, r * 0.8f, g * 0.8f, b * 0.8f)
        part(0.14f, 0.45f, -0.35f, -swing, -0.22f, 0.1f, 0.45f, 0.1f, r * 0.8f, g * 0.8f, b * 0.8f)
        part(-0.14f, 0.45f, 0.35f, -swing, -0.22f, 0.1f, 0.45f, 0.1f, r * 0.8f, g * 0.8f, b * 0.8f)
        part(0.14f, 0.45f, 0.35f, swing, -0.22f, 0.1f, 0.45f, 0.1f, r * 0.8f, g * 0.8f, b * 0.8f)
        part(0f, 0.72f, 0.5f, -40f, 0.18f, 0.08f, 0.36f, 0.08f, r * 0.9f, g * 0.9f, b * 0.9f)
    }

    private fun node(f: RenderFrame, i: Int) {
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, f.nx[i], f.ny[i], f.nz[i])
        Matrix.rotateM(base, 0, i * 47f, 0f, 1f, 0f)
        when (f.nkind[i]) {
            1 -> {
                part(0f, 0.35f, 0f, 0f, 0f, 1.1f, 0.8f, 0.9f, 0.42f, 0.40f, 0.40f)
                part(0.45f, 0.3f, 0.3f, 0f, 0f, 0.6f, 0.6f, 0.6f, 0.36f, 0.34f, 0.35f)
                part(-0.2f, 0.62f, 0.2f, 0f, 0f, 0.3f, 0.3f, 0.3f, 0.72f, 0.48f, 0.32f)
                part(0.3f, 0.5f, -0.3f, 0f, 0f, 0.22f, 0.22f, 0.22f, 0.76f, 0.52f, 0.34f)
            }
            // chest: box, lid, iron bands and a latch
            2 -> {
                part(0f, 0.22f, 0f, 0f, 0f, 0.8f, 0.44f, 0.5f, 0.42f, 0.27f, 0.14f)
                part(0f, 0.48f, 0f, 0f, 0f, 0.84f, 0.1f, 0.54f, 0.33f, 0.21f, 0.11f)
                part(-0.26f, 0.27f, 0f, 0f, 0f, 0.07f, 0.56f, 0.56f, 0.28f, 0.28f, 0.3f)
                part(0.26f, 0.27f, 0f, 0f, 0f, 0.07f, 0.56f, 0.56f, 0.28f, 0.28f, 0.3f)
                part(0f, 0.36f, 0.27f, 0f, 0f, 0.1f, 0.12f, 0.04f, 0.74f, 0.6f, 0.28f)
            }
            // signpost: post and a notice board
            3 -> {
                part(0f, 0.75f, 0f, 0f, 0f, 0.1f, 1.5f, 0.1f, 0.36f, 0.24f, 0.14f)
                part(0f, 1.28f, 0.07f, 0f, 0f, 0.8f, 0.46f, 0.05f, 0.62f, 0.48f, 0.3f)
                part(0f, 1.28f, 0.1f, 0f, 0f, 0.58f, 0.3f, 0.02f, 0.9f, 0.84f, 0.7f)
            }
            // bed: frame, mattress, pillow and blanket
            4 -> {
                part(0f, 0.16f, 0f, 0f, 0f, 0.9f, 0.32f, 1.9f, 0.4f, 0.27f, 0.15f)
                part(0f, 0.36f, 0f, 0f, 0f, 0.8f, 0.12f, 1.8f, 0.86f, 0.8f, 0.66f)
                part(0f, 0.46f, -0.68f, 0f, 0f, 0.56f, 0.1f, 0.3f, 0.93f, 0.9f, 0.82f)
                part(0f, 0.44f, 0.3f, 0f, 0f, 0.84f, 0.06f, 1.1f, 0.5f, 0.3f, 0.24f)
                part(0f, 0.45f, -0.95f, 0f, 0f, 0.9f, 0.9f, 0.08f, 0.36f, 0.24f, 0.13f)
            }
            // standing stone with a lighter cap
            5 -> {
                part(0f, 0.12f, 0f, 0f, 0f, 0.7f, 0.24f, 0.6f, 0.44f, 0.43f, 0.41f)
                part(0f, 0.7f, 0f, 0f, 0f, 0.42f, 1.0f, 0.3f, 0.56f, 0.55f, 0.52f)
                part(0f, 1.24f, 0f, 0f, 0f, 0.32f, 0.12f, 0.24f, 0.68f, 0.66f, 0.6f)
            }
            // lectern with an open book
            6 -> {
                part(0f, 0.5f, 0f, 0f, 0f, 0.14f, 1.0f, 0.14f, 0.36f, 0.24f, 0.14f)
                part(0f, 0.05f, 0f, 0f, 0f, 0.5f, 0.1f, 0.5f, 0.3f, 0.2f, 0.12f)
                part(0f, 1.02f, 0f, 24f, 0f, 0.62f, 0.06f, 0.46f, 0.42f, 0.28f, 0.16f)
                part(0f, 1.07f, 0f, 24f, 0f, 0.5f, 0.05f, 0.36f, 0.93f, 0.89f, 0.76f)
            }
            else -> {
                part(0f, 0.22f, 0f, 0f, 0f, 0.5f, 0.44f, 0.5f, 0.30f, 0.46f, 0.22f)
                part(0.1f, 0.52f, 0.05f, 0f, 0f, 0.16f, 0.18f, 0.16f, 0.82f, 0.74f, 0.9f)
                part(-0.14f, 0.48f, -0.1f, 0f, 0f, 0.14f, 0.16f, 0.14f, 0.9f, 0.84f, 0.5f)
            }
        }
    }

    /** First-person blade, drawn over the world in camera space. */
    private fun weapon(f: RenderFrame) {
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.invertM(camWorld, 0, view, 0)
        System.arraycopy(camWorld, 0, base, 0, 16)
        val t = 1f - f.swing
        val arc = if (f.swing > 0f) sin(t * 3.1416f) else 0f
        if (f.blocking) {
            Matrix.translateM(base, 0, 0.02f, -0.3f, -0.55f)
            Matrix.rotateM(base, 0, 72f, 0f, 0f, 1f)
            Matrix.rotateM(base, 0, -12f, 1f, 0f, 0f)
        } else {
            Matrix.translateM(base, 0, 0.3f - arc * 0.34f, -0.36f + arc * 0.08f, -0.6f - arc * 0.12f)
            Matrix.rotateM(base, 0, 9f + arc * 62f, 0f, 0f, 1f)
            Matrix.rotateM(base, 0, -18f - arc * 58f, 1f, 0f, 0f)
        }
        val len = f.bladeLength.coerceIn(0.25f, 1.25f) * 0.72f
        val c = f.bladeColor
        part(0f, 0.1f + len / 2f, 0f, 0f, 0f, 0.04f, len, 0.012f, c[0], c[1], c[2])
        part(0f, 0.09f, 0f, 0f, 0f, 0.16f, 0.025f, 0.035f, 0.5f, 0.42f, 0.25f)
        part(0f, 0f, 0f, 0f, 0f, 0.032f, 0.17f, 0.032f, 0.3f, 0.2f, 0.13f)
        part(0f, -0.1f, 0f, 0f, 0f, 0.05f, 0.04f, 0.05f, 0.5f, 0.42f, 0.25f)
    }

    private fun atmosphere(hour: Float, mist: Float) {
        var i = 0
        while (i < KEYS.size - 1 && hour >= KEYS[i + 1][0]) i++
        val a = KEYS[i]
        val b = KEYS[min(i + 1, KEYS.size - 1)]
        val t = if (b[0] > a[0]) ((hour - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f) else 0f
        for (k in 0 until 3) {
            zenith[k] = a[1 + k] + (b[1 + k] - a[1 + k]) * t
            horizon[k] = a[4 + k] + (b[4 + k] - a[4 + k]) * t
            sunCol[k] = a[7 + k] + (b[7 + k] - a[7 + k]) * t
            ambient[k] = a[10 + k] + (b[10 + k] - a[10 + k]) * t
        }
        val angle = (hour - 6f) / 12f * 3.1415927f
        var sx = cos(angle) * 0.85f
        var sy = sin(angle)
        val sz = 0.4f
        night = (1f - Terrain.smooth(-0.1f, 0.35f, sy))
        if (sy < 0f) { sx = -sx; sy = -sy }
        sy = max(sy, 0.12f)
        val len = sqrt(sx * sx + sy * sy + sz * sz)
        sunDir[0] = sx / len; sunDir[1] = sy / len; sunDir[2] = sz / len
        val grey = (horizon[0] + horizon[1] + horizon[2]) / 3f
        val m = mist * 0.75f
        for (k in 0 until 3) {
            horizon[k] += (grey * 0.95f - horizon[k]) * m
            zenith[k] += (grey * 0.8f - zenith[k]) * m
            sunCol[k] *= 1f - mist * 0.45f
            fogCol[k] = horizon[k] * 0.86f + zenith[k] * 0.14f
        }
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private companion object {
        // Geometry ids into the combined moving-parts VBO (cube is id 0, always first).
        const val GEO_FRUSTUM = 1
        const val GEO_SPHERE = 2
        /** The chair seat height the sitting pose is tuned to; InteriorMesh draws to match. */
        const val SEAT_H = 0.46f

        // hour, zenith rgb, horizon rgb, light rgb, ambient rgb
        val KEYS = arrayOf(
            floatArrayOf(0f, 0.03f, 0.04f, 0.10f, 0.08f, 0.10f, 0.17f, 0.16f, 0.19f, 0.30f, 0.13f, 0.15f, 0.22f),
            floatArrayOf(5f, 0.03f, 0.04f, 0.10f, 0.08f, 0.10f, 0.17f, 0.16f, 0.19f, 0.30f, 0.13f, 0.15f, 0.22f),
            floatArrayOf(6.6f, 0.30f, 0.36f, 0.56f, 0.97f, 0.66f, 0.46f, 0.95f, 0.60f, 0.38f, 0.36f, 0.34f, 0.40f),
            floatArrayOf(9f, 0.27f, 0.50f, 0.84f, 0.74f, 0.84f, 0.93f, 1.00f, 0.95f, 0.86f, 0.46f, 0.49f, 0.54f),
            floatArrayOf(14.5f, 0.27f, 0.50f, 0.84f, 0.74f, 0.84f, 0.93f, 1.00f, 0.95f, 0.86f, 0.46f, 0.49f, 0.54f),
            floatArrayOf(16.5f, 0.38f, 0.44f, 0.70f, 0.98f, 0.74f, 0.50f, 1.00f, 0.72f, 0.44f, 0.46f, 0.40f, 0.40f),
            floatArrayOf(18.6f, 0.30f, 0.28f, 0.50f, 0.96f, 0.56f, 0.36f, 0.90f, 0.50f, 0.30f, 0.38f, 0.32f, 0.36f),
            floatArrayOf(20f, 0.10f, 0.11f, 0.24f, 0.36f, 0.24f, 0.28f, 0.30f, 0.24f, 0.28f, 0.20f, 0.19f, 0.26f),
            floatArrayOf(21.2f, 0.03f, 0.04f, 0.10f, 0.08f, 0.10f, 0.17f, 0.16f, 0.19f, 0.30f, 0.13f, 0.15f, 0.22f),
            floatArrayOf(24f, 0.03f, 0.04f, 0.10f, 0.08f, 0.10f, 0.17f, 0.16f, 0.19f, 0.30f, 0.13f, 0.15f, 0.22f),
        )

        const val VERT = """
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec3 aColor;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform vec3 uTint;
uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform vec3 uAmbient;
uniform vec3 uCam;
uniform float uFogDensity;
uniform float uGlow;
varying vec3 vColor;
varying float vFog;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    float nl = length(aNormal);
    vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);
    float d = max(dot(n, uSunDir), 0.0);
    float sky = 0.5 + 0.5 * n.y;
    vec3 lit = uAmbient * (0.7 + 0.5 * sky) + uSunCol * d * 0.85;
    vec3 c = aColor * uTint;
    float e = step(1.5, nl);
    vColor = mix(c * lit, c * uGlow, e);
    float dist = length(wp.xyz - uCam) * uFogDensity;
    vFog = clamp(1.0 - exp(-dist * dist), 0.0, 1.0) * (1.0 - e * 0.45);
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""
        const val FRAG = """
precision mediump float;
uniform vec3 uFogCol;
uniform float uAlpha;
varying vec3 vColor;
varying float vFog;
void main() {
    gl_FragColor = vec4(mix(vColor, uFogCol, vFog), uAlpha);
}
"""
        const val SKY_VERT = """
attribute vec2 aPos;
uniform vec3 uForward;
uniform vec3 uRight;
uniform vec3 uUp;
varying vec3 vDir;
void main() {
    vDir = uForward + aPos.x * uRight + aPos.y * uUp;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
        const val SKY_FRAG = """
precision mediump float;
uniform vec3 uZenith;
uniform vec3 uHorizon;
uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform vec3 uGround;
varying vec3 vDir;
void main() {
    vec3 d = normalize(vDir);
    float t = clamp(d.y, 0.0, 1.0);
    vec3 col = mix(uHorizon, uZenith, pow(t, 0.55));
    col = mix(uGround, col, smoothstep(-0.02, 0.06, d.y));
    float s = max(dot(d, uSunDir), 0.0);
    col += uSunCol * (pow(s, 180.0) * 0.9 + pow(s, 8.0) * 0.22);
    gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
