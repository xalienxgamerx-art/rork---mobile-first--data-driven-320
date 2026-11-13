package app.rork.p7buzvrzcw3frddi8y50ou.render

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentRegistry
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Terrain
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Vertex layout shared by every mesh: position(3) normal(3) colour(3). */
const val STRIDE_FLOATS = 9

/**
 * Accumulates flat-shaded triangles. A yaw-only transform is applied to everything emitted,
 * and face normals are oriented away from [hint] so winding never matters.
 */
class MeshBuilder(initial: Int = 1 shl 18) {
    var data = FloatArray(initial)
        private set
    var size = 0
        private set

    /** Boxes emitted so far — the object count a naive per-primitive renderer would manage. */
    var boxCount = 0
        private set

    private var ox = 0f
    private var oy = 0f
    private var oz = 0f
    private var c = 1f
    private var s = 0f
    private var hx = 0f
    private var hy = 0f
    private var hz = 0f
    private var useHint = false
    var glow = false

    val vertexCount get() = size / STRIDE_FLOATS

    fun place(x: Float, y: Float, z: Float, yaw: Float) {
        ox = x; oy = y; oz = z
        c = cos(yaw); s = sin(yaw)
    }

    /** Local-space point that the following faces should point away from. */
    fun around(x: Float, y: Float, z: Float) {
        hx = ox + x * c + z * s
        hy = oy + y
        hz = oz - x * s + z * c
        useHint = true
    }

    fun upward() {
        useHint = false
    }

    /** Raw append with no transform — used when copying already-baked geometry. */
    fun append(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float) = put(x, y, z, nx, ny, nz, r, g, b)

    private fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float) {
        if (size + STRIDE_FLOATS > data.size) data = data.copyOf(data.size * 2)
        val d = data
        var i = size
        d[i++] = x; d[i++] = y; d[i++] = z
        d[i++] = nx; d[i++] = ny; d[i++] = nz
        d[i++] = r; d[i++] = g; d[i++] = b
        size = i
    }

    fun tri(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        r: Float, g: Float, b: Float,
    ) {
        val wax = ox + ax * c + az * s; val waz = oz - ax * s + az * c; val way = oy + ay
        val wbx = ox + bx * c + bz * s; val wbz = oz - bx * s + bz * c; val wby = oy + by
        val wcx = ox + cx * c + cz * s; val wcz = oz - cx * s + cz * c; val wcy = oy + cy
        val ux = wbx - wax; val uy = wby - way; val uz = wbz - waz
        val vx = wcx - wax; val vy = wcy - way; val vz = wcz - waz
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9f) return
        nx /= len; ny /= len; nz /= len
        val flip = if (useHint) {
            val mx = (wax + wbx + wcx) / 3f - hx
            val my = (way + wby + wcy) / 3f - hy
            val mz = (waz + wbz + wcz) / 3f - hz
            nx * mx + ny * my + nz * mz < 0f
        } else ny < 0f
        if (flip) { nx = -nx; ny = -ny; nz = -nz }
        if (glow) { nx *= 2f; ny *= 2f; nz *= 2f }
        put(wax, way, waz, nx, ny, nz, r, g, b)
        put(wbx, wby, wbz, nx, ny, nz, r, g, b)
        put(wcx, wcy, wcz, nx, ny, nz, r, g, b)
    }

    fun quad(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        r: Float, g: Float, b: Float,
    ) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, r, g, b)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, r, g, b)
    }

    /** Axis-aligned (in local space) box given its centre and half sizes. */
    fun box(cx: Float, cy: Float, cz: Float, ex: Float, ey: Float, ez: Float, r: Float, g: Float, b: Float) {
        boxCount++
        around(cx, cy, cz)
        val x0 = cx - ex; val x1 = cx + ex
        val y0 = cy - ey; val y1 = cy + ey
        val z0 = cz - ez; val z1 = cz + ez
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b)
        quad(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b)
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r * 0.92f, g * 0.92f, b * 0.92f)
        quad(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r * 0.92f, g * 0.92f, b * 0.92f)
        quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b)
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r * 0.8f, g * 0.8f, b * 0.8f)
    }

    /** Pyramid / cone with [sides] faces, base centred at (cx, y0, cz). */
    fun cone(cx: Float, y0: Float, cz: Float, radius: Float, height: Float, sides: Int, r: Float, g: Float, b: Float) {
        around(cx, y0 + height * 0.2f, cz)
        for (i in 0 until sides) {
            val a0 = i * 6.2831855f / sides
            val a1 = (i + 1) * 6.2831855f / sides
            val shade = 0.86f + 0.14f * ((i * 7) % 3) / 2f
            tri(
                cx + cos(a0) * radius, y0, cz + sin(a0) * radius,
                cx + cos(a1) * radius, y0, cz + sin(a1) * radius,
                cx, y0 + height, cz,
                r * shade, g * shade, b * shade,
            )
        }
    }

    /**
     * Appends this mesh's vertices into [dst], transformed by the same yaw-only transform
     * [place] applies: a baked local-space model blits exactly where it was baked, glow
     * encoding (doubled normals) preserved. Positions and normals transform; colours copy.
     */
    fun blitInto(dst: MeshBuilder, ox: Float, oy: Float, oz: Float, yaw: Float) {
        val c = cos(yaw); val s = sin(yaw)
        var i = 0
        while (i + STRIDE_FLOATS <= size) {
            val x = data[i]; val y = data[i + 1]; val z = data[i + 2]
            val nx = data[i + 3]; val ny = data[i + 4]; val nz = data[i + 5]
            dst.append(
                ox + x * c + z * s, oy + y, oz - x * s + z * c,
                nx * c + nz * s, ny, -nx * s + nz * c,
                data[i + 6], data[i + 7], data[i + 8],
            )
            i += STRIDE_FLOATS
        }
    }

    /** Gable roof with its ridge along local x. */
    fun gable(ex: Float, ez: Float, y0: Float, rise: Float, r: Float, g: Float, b: Float) {
        around(0f, y0, 0f)
        quad(-ex, y0, -ez, ex, y0, -ez, ex, y0 + rise, 0f, -ex, y0 + rise, 0f, r, g, b)
        quad(-ex, y0, ez, ex, y0, ez, ex, y0 + rise, 0f, -ex, y0 + rise, 0f, r * 0.9f, g * 0.9f, b * 0.9f)
        tri(-ex, y0, -ez, -ex, y0, ez, -ex, y0 + rise, 0f, r * 0.8f, g * 0.8f, b * 0.8f)
        tri(ex, y0, -ez, ex, y0, ez, ex, y0 + rise, 0f, r * 0.8f, g * 0.8f, b * 0.8f)
    }
}

class GraphicsPreset(val cell: Float, val trees: Int, val fog: Float, val scale: Float) {
    companion object {
        fun of(level: Int) = when (level) {
            0 -> GraphicsPreset(5f, 260, 150f, 0.6f)
            2 -> GraphicsPreset(3.125f, 900, 260f, 1f)
            else -> GraphicsPreset(4f, 520, 200f, 0.8f)
        }
    }
}

/**
 * Builds the single static batch that holds the land, the village and the woods. Every
 * building enters it as one baked combined model from [BuildingModels] — the world batch is
 * the composition of those models and the land, never the keeper of their structure.
 */
object WorldMesh {
    private var cacheKey: Any? = null
    private var cacheLevel = -1
    private var cache: MeshBuilder? = null

    /** Bumped on every rebuild or invalidation; the renderer compares it to re-upload. */
    var epoch = 0L
        private set

    @Synchronized
    fun get(content: ContentRegistry, level: Int): MeshBuilder {
        val hit = cache
        if (hit != null && cacheKey === content && cacheLevel == level) return hit
        val built = build(content, GraphicsPreset.of(level))
        cache = built
        cacheKey = content
        cacheLevel = level
        epoch++
        return built
    }

    /**
     * Drops the composed batch so the next frame rebuilds it from the (possibly rebaked)
     * building models. Safe to call any time; the structure in [BuildingCache] is untouched.
     */
    @Synchronized
    fun invalidate() {
        cache = null
        cacheKey = null
        epoch++
    }

    private fun groundColor(x: Float, z: Float, h: Float, slope: Float, out: FloatArray) {
        val n = Terrain.noise(x * 0.09f, z * 0.09f)
        val road = Terrain.onRoad(x, z)
        // Autumn meadow.
        var r = 0.40f + n * 0.10f
        var g = 0.44f + n * 0.07f
        var b = 0.20f + n * 0.04f
        if (h < Terrain.WATER + 0.7f) {
            val t = Terrain.smooth(Terrain.WATER + 0.7f, Terrain.WATER - 0.3f, h)
            r = Terrain.lerp(r, 0.62f, t); g = Terrain.lerp(g, 0.56f, t); b = Terrain.lerp(b, 0.42f, t)
        }
        val rock = maxOf(Terrain.smooth(0.55f, 1.1f, slope), Terrain.smooth(22f, 36f, h))
        r = Terrain.lerp(r, 0.47f + n * 0.06f, rock)
        g = Terrain.lerp(g, 0.44f + n * 0.05f, rock)
        b = Terrain.lerp(b, 0.42f + n * 0.05f, rock)
        val snow = Terrain.smooth(46f, 62f, h + n * 6f)
        r = Terrain.lerp(r, 0.93f, snow); g = Terrain.lerp(g, 0.93f, snow); b = Terrain.lerp(b, 0.96f, snow)
        r = Terrain.lerp(r, 0.52f + n * 0.05f, road * 0.85f)
        g = Terrain.lerp(g, 0.40f + n * 0.04f, road * 0.85f)
        b = Terrain.lerp(b, 0.27f + n * 0.03f, road * 0.85f)
        out[0] = r; out[1] = g; out[2] = b
    }

    private fun build(content: ContentRegistry, preset: GraphicsPreset): MeshBuilder {
        val m = MeshBuilder(1 shl 20)
        val cells = (Terrain.HALF * 2f / preset.cell).toInt()
        val n = cells + 1
        val heights = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            heights[j * n + i] = Terrain.height(-Terrain.HALF + i * preset.cell, -Terrain.HALF + j * preset.cell)
        }
        val col = FloatArray(3)
        m.place(0f, 0f, 0f, 0f)
        m.upward()
        for (j in 0 until cells) for (i in 0 until cells) {
            val x0 = -Terrain.HALF + i * preset.cell
            val z0 = -Terrain.HALF + j * preset.cell
            val x1 = x0 + preset.cell
            val z1 = z0 + preset.cell
            val h00 = heights[j * n + i]
            val h10 = heights[j * n + i + 1]
            val h01 = heights[(j + 1) * n + i]
            val h11 = heights[(j + 1) * n + i + 1]
            val slope = (abs(h00 - h11) + abs(h10 - h01)) / (preset.cell * 1.41f)
            groundColor(x0 + preset.cell * 0.3f, z0 + preset.cell * 0.3f, (h00 + h10 + h01) / 3f, slope, col)
            m.tri(x0, h00, z0, x1, h10, z0, x0, h01, z1, col[0], col[1], col[2])
            groundColor(x0 + preset.cell * 0.7f, z0 + preset.cell * 0.7f, (h11 + h10 + h01) / 3f, slope, col)
            m.tri(x1, h10, z0, x1, h11, z1, x0, h01, z1, col[0], col[1], col[2])
        }

        // Packed dirt of the south road, laid on the same samples as the terrain so it never sinks.
        var z = 24f
        while (z < Terrain.LIMIT + 10f) {
            val za = z
            val zb = z + preset.cell
            val xa = -sin(za * 0.05f) * 0.6f
            val xb = -sin(zb * 0.05f) * 0.6f
            val ya = maxOf(Terrain.height(xa - 2f, za), Terrain.height(xa + 2f, za), Terrain.height(xa, za)) + 0.07f
            val yb = maxOf(Terrain.height(xb - 2f, zb), Terrain.height(xb + 2f, zb), Terrain.height(xb, zb)) + 0.07f
            val k = Terrain.noise(z * 0.3f, 3f) * 0.05f
            m.quad(xa - 2.3f, ya, za, xa + 2.3f, ya, za, xb + 2.3f, yb, zb, xb - 2.3f, yb, zb, 0.55f + k, 0.42f + k, 0.28f + k)
            // wheel ruts
            m.quad(xa - 1.0f, ya + 0.02f, za, xa - 0.7f, ya + 0.02f, za, xb - 0.7f, yb + 0.02f, zb, xb - 1.0f, yb + 0.02f, zb, 0.44f, 0.33f, 0.22f)
            m.quad(xa + 0.7f, ya + 0.02f, za, xa + 1.0f, ya + 0.02f, za, xb + 1.0f, yb + 0.02f, zb, xb + 0.7f, yb + 0.02f, zb, 0.44f, 0.33f, 0.22f)
            z += preset.cell
        }
        // The village square.
        val sides = 28
        for (i in 0 until sides) {
            val a0 = i * 6.2831855f / sides
            val a1 = (i + 1) * 6.2831855f / sides
            val k = (i % 2) * 0.03f
            m.tri(0f, 1.46f, 0f, cos(a0) * 29f, 1.44f, sin(a0) * 29f, cos(a1) * 29f, 1.44f, sin(a1) * 29f, 0.56f + k, 0.44f + k, 0.30f + k)
        }

        // Each building enters the batch as one baked combined model: grown from its
        // structural plan, or — when no plan could be grown and the failure was recorded —
        // from its hand-authored shell, so no building ever vanishes.
        for (b in content.buildings.values) {
            val model = BuildingModels.exterior(b)
            if (model != null) {
                model.blit(m, b.x, b.z, atan2(b.x, b.z))
                continue
            }
            val shell = BuildingMesh.bakeShellLocal(b)
            shell.blitInto(m, b.x, Terrain.height(b.x, b.z), b.z, atan2(b.x, b.z))
        }
        roadside(m)

        val clearings = ArrayList<FloatArray>()
        content.spots.values.forEach { clearings += floatArrayOf(it.x, it.z, 6f) }
        content.resources.values.forEach { clearings += floatArrayOf(it.x, it.z, 2.6f) }
        // And each building keeps its own yard: no tree grows into a house or hard against
        // its walls, wherever in the forest the village's clearing was cut.
        for (b in content.buildings.values) {
            val fp = if (b.procedural) BuildingCache.footprint(b) else null
            val half = maxOf(fp?.first ?: b.w, fp?.second ?: b.d) / 2f
            clearings += floatArrayOf(b.x, b.z, half + 4f)
        }
        clearings += floatArrayOf(-6.5f, 116f, 5f)
        for (t in Terrain.trees(preset.trees, clearings)) tree(m, t.x, t.z, t.scale, t.kind, t.tint)
        return m
    }

    private fun tree(m: MeshBuilder, x: Float, z: Float, s: Float, kind: Int, tint: Float) {
        val y = Terrain.height(x, z) - 0.15f
        m.place(x, y, z, tint * 6.28f)
        if (kind == 1) {
            m.box(0f, 0.9f * s, 0f, 0.16f * s, 0.9f * s, 0.16f * s, 0.30f, 0.21f, 0.14f)
            val g = 0.26f + tint * 0.08f
            m.cone(0f, 1.2f * s, 0f, 1.9f * s, 2.6f * s, 5, 0.13f + tint * 0.04f, g, 0.17f)
            m.cone(0f, 2.7f * s, 0f, 1.45f * s, 2.3f * s, 5, 0.15f + tint * 0.04f, g + 0.03f, 0.18f)
            m.cone(0f, 4.1f * s, 0f, 0.95f * s, 2.1f * s, 5, 0.17f + tint * 0.04f, g + 0.06f, 0.19f)
        } else {
            m.box(0f, 1.3f * s, 0f, 0.2f * s, 1.3f * s, 0.2f * s, 0.36f, 0.27f, 0.19f)
            // Frostfall canopy: gold, rust and the last of the green.
            val r: Float; val g: Float; val b: Float
            when {
                tint < 0.4f -> { r = 0.85f; g = 0.58f + tint * 0.2f; b = 0.16f }
                tint < 0.7f -> { r = 0.74f; g = 0.36f; b = 0.14f }
                else -> { r = 0.42f; g = 0.50f; b = 0.20f }
            }
            m.box(0f, 3.3f * s, 0f, 1.5f * s, 1.0f * s, 1.4f * s, r, g, b)
            m.box(0.3f * s, 4.4f * s, -0.2f * s, 1.0f * s, 0.7f * s, 1.0f * s, r * 1.08f, g * 1.08f, b * 1.05f)
            m.box(-0.9f * s, 2.9f * s, 0.7f * s, 0.8f * s, 0.6f * s, 0.8f * s, r * 0.9f, g * 0.9f, b * 0.9f)
        }
    }

    /** The signpost, banner and white ash where every new life begins, and the fences up the road. */
    private fun roadside(m: MeshBuilder) {
        var z = 58f
        while (z < 132f) {
            for (side in intArrayOf(-1, 1)) {
                val x = side * 5.2f - sin(z * 0.05f) * 0.6f
                if (side == -1 && z > 112f && z < 122f) continue
                val y = Terrain.height(x, z)
                val zn = z + 3f
                val xn = side * 5.2f - sin(zn * 0.05f) * 0.6f
                val yn = Terrain.height(xn, zn)
                m.place(x, y, z, 0f)
                m.box(0f, 0.55f, 0f, 0.09f, 0.6f, 0.09f, 0.38f, 0.29f, 0.20f)
                m.place(0f, 0f, 0f, 0f)
                m.around((x + xn) / 2f, y - 3f, (z + zn) / 2f)
                for (rail in floatArrayOf(0.5f, 0.9f)) {
                    m.quad(x, y + rail, z, xn, yn + rail, zn, xn, yn + rail + 0.1f, zn, x, y + rail + 0.1f, z, 0.42f, 0.32f, 0.22f)
                    m.quad(x + 0.06f, y + rail, z, xn + 0.06f, yn + rail, zn, xn + 0.06f, yn + rail + 0.1f, zn, x + 0.06f, y + rail + 0.1f, z, 0.36f, 0.27f, 0.18f)
                }
            }
            z += 3f
        }
        // Signpost with the white-tree banner.
        val sx = -3.6f
        val sz = 117.5f
        m.place(sx, Terrain.height(sx, sz), sz, 0.25f)
        m.box(0f, 0.25f, 0f, 0.45f, 0.3f, 0.45f, 0.50f, 0.49f, 0.46f)
        m.box(0f, 1.8f, 0f, 0.11f, 1.8f, 0.11f, 0.33f, 0.24f, 0.16f)
        m.box(0.55f, 3.2f, 0f, 0.95f, 0.09f, 0.09f, 0.33f, 0.24f, 0.16f)
        m.box(0.75f, 2.35f, 0f, 0.42f, 0.7f, 0.025f, 0.33f, 0.40f, 0.22f)
        m.box(0.75f, 2.5f, -0.035f, 0.05f, 0.3f, 0.012f, 0.90f, 0.86f, 0.70f)
        m.box(0.75f, 2.72f, -0.035f, 0.22f, 0.16f, 0.012f, 0.90f, 0.86f, 0.70f)
        // The white ash.
        val ax = -6.5f
        val az = 116f
        m.place(ax, Terrain.height(ax, az) - 0.1f, az, 0.6f)
        m.box(0f, 2.4f, 0f, 0.28f, 2.4f, 0.28f, 0.86f, 0.84f, 0.78f)
        m.box(0.7f, 4.2f, 0f, 0.7f, 0.12f, 0.12f, 0.84f, 0.82f, 0.76f)
        m.box(-0.5f, 4.6f, 0.4f, 0.6f, 0.11f, 0.11f, 0.84f, 0.82f, 0.76f)
        m.box(0f, 6.2f, 0f, 2.4f, 1.2f, 2.2f, 0.93f, 0.66f, 0.17f)
        m.box(1.5f, 5.3f, -0.8f, 1.5f, 0.8f, 1.4f, 0.88f, 0.57f, 0.14f)
        m.box(-1.3f, 5.2f, 1.1f, 1.4f, 0.8f, 1.3f, 0.96f, 0.72f, 0.2f)
        m.box(0.3f, 7.5f, 0.2f, 1.5f, 0.7f, 1.4f, 0.97f, 0.76f, 0.24f)
        // Lavender by the stones.
        for (i in 0 until 6) {
            val lx = -4.6f + (i % 3) * 0.5f
            val lz = 118.6f + (i / 3) * 0.6f
            m.place(lx, Terrain.height(lx, lz), lz, i * 1.1f)
            m.box(0f, 0.25f, 0f, 0.05f, 0.25f, 0.05f, 0.30f, 0.42f, 0.22f)
            m.box(0f, 0.6f, 0f, 0.09f, 0.16f, 0.09f, 0.55f, 0.42f, 0.72f)
        }
    }

    /** Unit cube centred on the origin, used for every moving thing. */
    fun cube(): MeshBuilder {
        val m = MeshBuilder(512)
        m.place(0f, 0f, 0f, 0f)
        m.around(0f, 0f, 0f)
        val a = 0.5f
        m.quad(-a, -a, a, a, -a, a, a, a, a, -a, a, a, 1f, 1f, 1f)
        m.quad(-a, -a, -a, a, -a, -a, a, a, -a, -a, a, -a, 1f, 1f, 1f)
        m.quad(-a, -a, -a, -a, -a, a, -a, a, a, -a, a, -a, 1f, 1f, 1f)
        m.quad(a, -a, -a, a, -a, a, a, a, a, a, a, -a, 1f, 1f, 1f)
        m.quad(-a, a, -a, a, a, -a, a, a, a, -a, a, a, 1f, 1f, 1f)
        m.quad(-a, -a, -a, a, -a, -a, a, -a, a, -a, -a, a, 1f, 1f, 1f)
        return m
    }

    /**
     * Tapered limb segment: unit height (y from −0.5 to +0.5), square cross-sections scaling
     * from a half-extent of 0.5 at the top (the joint end) to 0.3 at the bottom, centred on
     * the origin. Limbs hang down from their joint pivot, so the thick end sits at the
     * shoulder or hip and the wrist or ankle is the thin end.
     */
    fun frustum(): MeshBuilder {
        val m = MeshBuilder(512)
        m.place(0f, 0f, 0f, 0f)
        m.around(0f, 0f, 0f)
        val b = 0.30f
        val t = 0.5f
        val y0 = -0.5f
        val y1 = 0.5f
        // Four side faces; each is a trapezoid from a bottom edge to the wider top edge.
        m.quad(-b, y0, b, b, y0, b, t, y1, t, -t, y1, t, 1f, 1f, 1f)
        m.quad(b, y0, -b, -b, y0, -b, -t, y1, -t, t, y1, -t, 1f, 1f, 1f)
        m.quad(-b, y0, -b, -b, y0, b, -t, y1, t, -t, y1, -t, 1f, 1f, 1f)
        m.quad(b, y0, b, b, y0, -b, t, y1, -t, t, y1, t, 1f, 1f, 1f)
        m.quad(-t, y1, -t, t, y1, -t, t, y1, t, -t, y1, t, 1f, 1f, 1f)
        m.quad(-b, y0, -b, b, y0, -b, b, y0, b, -b, y0, b, 1f, 1f, 1f)
        return m
    }

    /**
     * A low-poly sphere, radius 0.5, centred on the origin — joint caps and the head.
     * Deliberately coarse: it reads as a rounded joint at humanoid scale and costs a
     * fraction of a proper lat-long ball.
     */
    fun sphere(): MeshBuilder {
        val m = MeshBuilder(2048)
        m.place(0f, 0f, 0f, 0f)
        m.around(0f, 0f, 0f)
        val slices = 8
        val stacks = 5
        val r = 0.5f
        fun ring(stack: Int): FloatArray {
            val phi = 3.1415927f * stack / stacks
            val y = r * kotlin.math.cos(phi)
            val rr = r * kotlin.math.sin(phi)
            val out = FloatArray(slices * 2)
            for (s in 0 until slices) {
                val th = 6.2831855f * s / slices
                out[s * 2] = rr * kotlin.math.cos(th)
                out[s * 2 + 1] = rr * kotlin.math.sin(th)
            }
            return out
        }
        for (st in 0 until stacks) {
            val lower = ring(st)
            val upper = ring(st + 1)
            val y0 = r * kotlin.math.cos(3.1415927f * st / stacks)
            val y1 = r * kotlin.math.cos(3.1415927f * (st + 1) / stacks)
            for (s in 0 until slices) {
                val s2 = (s + 1) % slices
                val x0 = lower[s * 2]; val z0 = lower[s * 2 + 1]
                val x1 = lower[s2 * 2]; val z1 = lower[s2 * 2 + 1]
                val x2 = upper[s2 * 2]; val z2 = upper[s2 * 2 + 1]
                val x3 = upper[s * 2]; val z3 = upper[s * 2 + 1]
                if (st == 0) {
                    m.tri(x0, y0, z0, x1, y0, z1, x2, y1, z2, 1f, 1f, 1f)
                } else if (st == stacks - 1) {
                    m.tri(x0, y0, z0, x1, y0, z1, x3, y1, z3, 1f, 1f, 1f)
                } else {
                    m.quad(x0, y0, z0, x1, y0, z1, x2, y1, z2, x3, y1, z3, 1f, 1f, 1f)
                }
            }
        }
        return m
    }
}
