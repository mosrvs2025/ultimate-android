package app.ultimate.room

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A furniture block in the room's own coordinates (metres, Y up). */
class Box(val x0: Float, val y0: Float, val z0: Float, val x1: Float, val y1: Float, val z1: Float)

/** A mirror set into the wall x = width: what it shows is the room again, seen "through" the wall at double the distance. */
class Mirror(val z0: Float, val z1: Float, val y0: Float, val y1: Float)

/** A room to scan: floor y=0, ceiling y=[h], walls at x=0, x=[w], z=0, z=[l]. [blank] is a wall area depth-from-motion can't read (plain paint). */
class SimWorld(val w: Float, val l: Float, val h: Float, val boxes: List<Box> = emptyList(), val mirror: Mirror? = null, val blankWallZ0: Float = Float.NaN, val blankWallZ1: Float = Float.NaN)

class SimPhone(
    val seed: Int = 1, val noiseA: Float = 0.01f, val noiseB: Float = 0.02f, val wildRate: Float = 0.02f, val dropout: Float = 0.1f,
    val hand: Boolean = true, val driftPos: Float = 0.002f, val driftYaw: Float = 0.01f,
)

/** One pose of the phone in the room: position, heading and tilt. */
class Cam(val x: Float, val y: Float, val z: Float, val yawDeg: Float, val pitchDeg: Float)

object Sim {
    fun gauss(r: Random): Float { val u = max(1e-9, r.nextDouble()); val v = r.nextDouble(); return (sqrt(-2.0 * kotlin.math.ln(u)) * cos(2 * PI * v)).toFloat() }
    const val W = 160; const val H = 90; const val F = 114f

    fun matrix(c: Cam): FloatArray {
        val ya = Math.toRadians(c.yawDeg.toDouble()); val pa = Math.toRadians(c.pitchDeg.toDouble())
        val cy = cos(ya).toFloat(); val sy = sin(ya).toFloat(); val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        // R = Ry(yaw) * Rx(pitch); columns are the camera's right, up and back axes in the room.
        val rx = floatArrayOf(cy, 0f, -sy); val up = floatArrayOf(sy * sp, cp, cy * sp); val back = floatArrayOf(sy * cp, -sp, cy * cp)
        return floatArrayOf(rx[0], rx[1], rx[2], 0f, up[0], up[1], up[2], 0f, back[0], back[1], back[2], 0f, c.x, c.y, c.z, 1f)
    }

    /** A session's own coordinates: the room turned by [yaw] and shifted by [t], with the origin height at the phone's starting height (as ARCore does). */
    fun toSession(m: FloatArray, yawDeg: Float, tx: Float, ty: Float, tz: Float): FloatArray {
        val a = Math.toRadians(yawDeg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        val out = FloatArray(16)
        for (col in 0 until 4) {
            val x = m[col * 4]; val y = m[col * 4 + 1]; val z = m[col * 4 + 2]
            out[col * 4] = x * c + z * s; out[col * 4 + 1] = y; out[col * 4 + 2] = -x * s + z * c; out[col * 4 + 3] = m[col * 4 + 3]
        }
        out[12] += tx; out[13] += ty; out[14] += tz
        return out
    }

    /** The alignment that brings a session made with [toSession] back into room coordinates (the truth a good aligner should find). */
    fun truth(yawDeg: Float, tx: Float, ty: Float, tz: Float): Alignment {
        val a = Math.toRadians(yawDeg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return Alignment(yawDeg, -(tx * c - tz * s), -ty, -(tx * s + tz * c))
    }

    private fun hit(w: SimWorld, o: FloatArray, d: FloatArray, mirrorDepth: Boolean): Float {
        var best = Float.MAX_VALUE
        fun plane(p: Float, oo: Float, dd: Float) { if (dd != 0f) { val t = (p - oo) / dd; if (t > 0.05f && t < best) best = t } }
        // Room walls: nearest of the six planes whose hit lies inside the room.
        for ((axis, p) in listOf(0 to 0f, 0 to w.w, 1 to 0f, 1 to w.h, 2 to 0f, 2 to w.l)) {
            val dd = d[axis]; if (dd == 0f) continue
            val t = (p - o[axis]) / dd; if (t <= 0.05f || t >= best) continue
            val x = o[0] + d[0] * t; val y = o[1] + d[1] * t; val z = o[2] + d[2] * t
            if (x in -0.01f..w.w + 0.01f && y in -0.01f..w.h + 0.01f && z in -0.01f..w.l + 0.01f) {
                if (mirrorDepth && axis == 0 && p == w.w && w.mirror != null && z in w.mirror.z0..w.mirror.z1 && y in w.mirror.y0..w.mirror.y1) {
                    // Reflect off the mirror and carry on: the phone measures the whole path.
                    val no = floatArrayOf(x - 1e-3f, y, z); val nd = floatArrayOf(-d[0], d[1], d[2])
                    val t2 = hit(w, no, nd, false); best = t + t2
                } else best = t
            }
        }
        for (b in w.boxes) {
            var t0 = 0.05f; var t1 = best
            val lo = floatArrayOf(b.x0, b.y0, b.z0); val hi = floatArrayOf(b.x1, b.y1, b.z1)
            var ok = true
            for (a in 0..2) {
                if (d[a] == 0f) { if (o[a] < lo[a] || o[a] > hi[a]) { ok = false; break } }
                else { var ta = (lo[a] - o[a]) / d[a]; var tb = (hi[a] - o[a]) / d[a]; if (ta > tb) { val s = ta; ta = tb; tb = s }; t0 = max(t0, ta); t1 = min(t1, tb); if (t0 > t1) { ok = false; break } }
            }
            if (ok && t0 < best) best = t0
        }
        return best
    }

    /** What the phone's depth camera would deliver from [pose] (session coordinates), with the world transformed by [session] to match. */
    fun frame(world: SimWorld, roomPose: FloatArray, sessionPose: FloatArray, phone: SimPhone, rnd: Random, tNs: Long, handNow: Boolean): DepthFrame {
        val mm = ShortArray(W * H); val conf = ByteArray(W * H)
        val o = floatArrayOf(roomPose[12], roomPose[13], roomPose[14])
        for (y in 0 until H) for (x in 0 until W) {
            val dc = floatArrayOf((x + 0.5f - W / 2f) / F, (H / 2f - y - 0.5f) / F, -1f)
            val d = floatArrayOf(
                roomPose[0] * dc[0] + roomPose[4] * dc[1] + roomPose[8] * dc[2],
                roomPose[1] * dc[0] + roomPose[5] * dc[1] + roomPose[9] * dc[2],
                roomPose[2] * dc[0] + roomPose[6] * dc[1] + roomPose[10] * dc[2])
            var t = hit(world, o, d, true)
            var c = 110 + rnd.nextInt(146)
            if (handNow && phone.hand && x in 100..150 && y in 52..88) { t = 0.5f + 0.15f * rnd.nextFloat(); c = 230 }
            else {
                if (rnd.nextFloat() < phone.dropout) c = rnd.nextInt(90)
                // Plain paint: no features, no depth.
                if (!world.blankWallZ0.isNaN()) { val hx = o[0] + d[0] * t; val hz = o[2] + d[2] * t; if (hx < 0.05f && hz in world.blankWallZ0..world.blankWallZ1 && rnd.nextFloat() < 0.8f) c = rnd.nextInt(80) }
                if (rnd.nextFloat() < phone.wildRate) { t = 0.3f + 7f * rnd.nextFloat(); c = 150 + rnd.nextInt(100) }
                else t += (gauss(rnd)) * (phone.noiseA + phone.noiseB * t * t)
            }
            mm[y * W + x] = if (t in 0.2f..65f) (t * 1000f).toInt().toShort() else 0
            conf[y * W + x] = c.toByte()
        }
        return DepthFrame(W, H, mm, conf, F, F, W / 2f, H / 2f, sessionPose, tNs)
    }

    /** A walk round the room looking about, the way a person scans: ~10 frames a second, a little slower than a stroll. */
    fun walk(world: SimWorld, loops: Int = 2, inset: Float = 0.8f, startYaw: Float = 0f): List<Cam> {
        val out = ArrayList<Cam>(); val perim = listOf(inset to inset, world.w - inset to inset, world.w - inset to world.l - inset, inset to world.l - inset)
        var k = 0
        for (loop in 0 until loops) for (e in perim.indices) {
            val (ax, az) = perim[e]; val (bx, bz) = perim[(e + 1) % 4]; val len = sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az)); val n = max(1, (len / 0.04f).toInt())
            for (i in 0 until n) {
                val f = i / n.toFloat(); val x = ax + (bx - ax) * f; val z = az + (bz - az) * f
                val towardCentre = Math.toDegrees(kotlin.math.atan2((world.w / 2 - x).toDouble(), -(world.l / 2 - z).toDouble())).toFloat()
                val sweep = 75f * sin(2 * PI * k / 55.0).toFloat() + (if (loop == 1) 180f else 0f)
                out += Cam(x, 1.35f + 0.1f * sin(k / 9.0).toFloat(), z, towardCentre + sweep + startYaw, -8f + 38f * sin(2 * PI * k / 37.0).toFloat())
                k++
            }
        }
        return out
    }

    /** Scans [world] with [phone] along [cams]; the session is turned/shifted from the room's frame by ([yaw], [t]). Returns the integrator used. */
    fun scan(world: SimWorld, cams: List<Cam>, phone: SimPhone, yaw: Float, tx: Float, tz: Float, map: RoomMap = RoomMap(), integrator: ScanIntegrator = ScanIntegrator(map)): ScanIntegrator {
        val rnd = Random(phone.seed); val startH = cams.first().y
        var dx = 0f; var dy = 0f; var dz = 0f; var dyaw = 0f; var t = 1_000_000_000L; var k = 0
        for (c in cams) {
            dx += gauss(rnd) * phone.driftPos; dy += gauss(rnd) * phone.driftPos * 0.3f; dz += gauss(rnd) * phone.driftPos; dyaw += gauss(rnd) * phone.driftYaw
            val rm = matrix(c)
            // The phone believes it is slightly elsewhere (drift): perturb only the pose it reports.
            val believed = matrix(Cam(c.x + dx, c.y + dy, c.z + dz, c.yawDeg + dyaw, c.pitchDeg))
            val sp = toSession(believed, yaw, tx, -startH, tz)
            integrator.noteCamera(sp)
            val frame = frame(world, rm, sp, phone, rnd, t, handNow = phone.hand && (k / 25) % 4 == 1)
            integrator.integrate(frame)
            t += 100_000_000L; k++
        }
        return integrator
    }
}
