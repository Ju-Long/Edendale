package com.babasama.edendale.android.xr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The theater's own small vector algebra, so its placement rules stay pure
 * Kotlin (ENHANCEMENT.md code conventions) and the hermetic JVM suite covers
 * them without the Jetpack XR libraries, which only the :xr module carries.
 *
 * Frame: metres, right-handed, +Y up, the viewer's "ahead" is −Z and +X is
 * to the viewer's right, which is both glTF's and Jetpack XR's convention. A
 * yaw is a rotation about +Y; a positive yaw is a turn to the left.
 */

/** A point or direction in metres. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(x * x + y * y + z * z)
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    /** The unit vector along this one; the zero vector stays zero. */
    fun normalized(): Vec3 {
        val l = length
        return if (l > 0f) Vec3(x / l, y / l, z / l) else this
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        /** The viewer's "ahead" at yaw 0. */
        val FORWARD = Vec3(0f, 0f, -1f)

        fun distance(a: Vec3, b: Vec3): Float = (a - b).length
    }
}

/** A unit quaternion `(x, y, z, w)`; the identity is `(0, 0, 0, 1)`. */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {

    /** Rotates [v] by this quaternion. */
    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q × v) + 2(q × (q × v)), q = (x, y, z)
        val q = Vec3(x, y, z)
        val t = (q cross v) * 2f
        return v + t * w + (q cross t)
    }

    /** This rotation followed by... no: applies [o] first, then this one, as `this * o`. */
    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    val inverse: Quat get() = Quat(-x, -y, -z, w)

    /** The angle of this rotation in radians, in `[0, π]`. */
    val angle: Float
        get() = 2f * kotlin.math.acos(abs(w).coerceIn(0f, 1f))

    /** Absolute dot product with [o]: 1 when both describe the same rotation. */
    fun sameRotationAs(o: Quat, tolerance: Float = 1e-4f): Boolean =
        abs(x * o.x + y * o.y + z * o.z + w * o.w) > 1f - tolerance

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l > 0f) Quat(x / l, y / l, z / l, w / l) else IDENTITY
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        /** A right-handed rotation of [radians] about the unit [axis]. */
        fun axisAngle(axis: Vec3, radians: Float): Quat {
            val a = axis.normalized()
            val s = sin(radians / 2f)
            return Quat(a.x * s, a.y * s, a.z * s, cos(radians / 2f))
        }

        /** A yaw: a rotation of [radians] about +Y (positive turns to the left). */
        fun yaw(radians: Float): Quat = axisAngle(Vec3.UP, radians)

        /**
         * The rotation whose columns are the orthonormal [right], [up], and
         * [back] vectors (the frame's +X, +Y, +Z), as Apple's `simd_quatf(matrix)`.
         */
        fun fromColumns(right: Vec3, up: Vec3, back: Vec3): Quat {
            val m00 = right.x; val m01 = up.x; val m02 = back.x
            val m10 = right.y; val m11 = up.y; val m12 = back.y
            val m20 = right.z; val m21 = up.z; val m22 = back.z
            val trace = m00 + m11 + m22
            return if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                Quat(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                Quat((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                Quat((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }.normalized()
        }
    }
}

/** A rigid transform, applied as a turn and then a move: `p' = R·p + t`. */
data class RoomPose(val rotation: Quat, val translation: Vec3) {
    fun apply(p: Vec3): Vec3 = rotation.rotate(p) + translation

    /** Rotates a direction without moving it. */
    fun applyDirection(d: Vec3): Vec3 = rotation.rotate(d)

    val inverse: RoomPose
        get() {
            val r = rotation.inverse
            return RoomPose(r, -(r.rotate(translation)))
        }

    /** `this` after [first]: `p' = this(first(p))`. */
    fun compose(first: RoomPose): RoomPose =
        RoomPose(rotation * first.rotation, apply(first.translation))

    companion object {
        val IDENTITY = RoomPose(Quat.IDENTITY, Vec3.ZERO)
    }
}

/**
 * Where the head is and how it is turned: the position of the eyes and the
 * head's own right, up, and back directions (the columns of its 4×4).
 */
data class HeadFrame(val position: Vec3, val right: Vec3, val up: Vec3, val back: Vec3) {
    /** The gaze, straight out of the face. */
    val forward: Vec3 get() = -back

    companion object {
        /** A frame from a pose's position and rotation. */
        fun of(position: Vec3, rotation: Quat) = HeadFrame(
            position = position,
            right = rotation.rotate(Vec3(1f, 0f, 0f)),
            up = rotation.rotate(Vec3.UP),
            back = rotation.rotate(Vec3(0f, 0f, 1f)),
        )
    }
}

/** `a − b` reduced to `(−π, π]`, as IEEE `remainder(x, 2π)`. */
fun angleGap(a: Float, b: Float): Float = Math.IEEEremainder((a - b).toDouble(), 2.0 * Math.PI).toFloat()
