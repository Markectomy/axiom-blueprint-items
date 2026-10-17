package dev.markectomy.blueprints.domain.preview

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

class VoxelPreview(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    colors: IntArray,
    val extentX: Int = sizeX,
    val extentY: Int = sizeY,
    val extentZ: Int = sizeZ,
) {
    private val colors: IntArray

    init {
        require(sizeX in 1..MAX_AXIS && sizeY in 1..MAX_AXIS && sizeZ in 1..MAX_AXIS)
        require(colors.size == sizeX * sizeY * sizeZ)
        require(extentX in 1..MAX_EXTENT && extentY in 1..MAX_EXTENT && extentZ in 1..MAX_EXTENT)
        this.colors = colors.copyOf()
    }

    // Dense ARGB samples use (y * sizeZ + z) * sizeX + x. Alpha zero denotes empty space.
    internal fun colorAt(x: Int, y: Int, z: Int): Int =
        if (x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ) {
            colors[(y * sizeZ + z) * sizeX + x]
        } else {
            0
        }

    companion object {
        const val MAX_AXIS = 32
        const val MAX_EXTENT = 1_000_000
    }
}

class TurntableAtlas internal constructor(private val pixels: IntArray) {
    val width: Int get() = VoxelTurntable.ATLAS_SIZE
    val height: Int get() = VoxelTurntable.ATLAS_SIZE
    val frameSize: Int get() = VoxelTurntable.FRAME_SIZE
    val frameCount: Int get() = VoxelTurntable.FRAME_COUNT

    fun colorAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return pixels[y * width + x]
    }

    // Pixels are straight ARGB, top row first; frames occupy a row-major 4 by 4 grid.
    fun copyPixels(): IntArray = pixels.copyOf()
}

object VoxelTurntable {
    const val FRAME_SIZE = 64
    const val FRAME_COUNT = 16
    const val COLUMNS = 4
    const val ATLAS_SIZE = FRAME_SIZE * COLUMNS
    private const val PADDING = 3.0
    private val pitch = 25.0 * PI / 180.0
    private val pitchCos = cos(pitch)
    private val pitchSin = sin(pitch)
    private val normalX = intArrayOf(-1, 1, 0, 0, 0, 0)
    private val normalY = intArrayOf(0, 0, -1, 1, 0, 0)
    private val normalZ = intArrayOf(0, 0, 0, 0, -1, 1)

    fun render(preview: VoxelPreview): TurntableAtlas {
        checkCancelled()
        val faces = exposedFaces(preview)
        val pixels = IntArray(ATLAS_SIZE * ATLAS_SIZE)
        if (faces.isEmpty()) return TurntableAtlas(pixels)

        val rowStride = preview.sizeX + 1
        val layerStride = rowStride * (preview.sizeZ + 1)
        val vertices = DoubleArray(layerStride * (preview.sizeY + 1) * 3)
        val depth = DoubleArray(FRAME_SIZE * FRAME_SIZE)
        val diagonal = hypot(preview.extentX.toDouble(), preview.extentZ.toDouble())
        val projectedHeight = preview.extentY * pitchCos + diagonal * pitchSin
        val scale = (FRAME_SIZE - PADDING * 2.0) / maxOf(diagonal, projectedHeight)
        val visible = BooleanArray(6)
        val shades = DoubleArray(6)
        val corners = IntArray(4)

        for (frame in 0 until FRAME_COUNT) {
            checkCancelled()
            val yaw = PI / 4.0 + frame * PI * 2.0 / FRAME_COUNT
            val yawCos = cos(yaw)
            val yawSin = sin(yaw)
            projectVertices(preview, vertices, yawCos, yawSin, scale)
            depth.fill(Double.NEGATIVE_INFINITY)
            for (side in 0 until 6) {
                val rotatedX = normalX[side] * yawCos + normalZ[side] * yawSin
                val rotatedZ = -normalX[side] * yawSin + normalZ[side] * yawCos
                visible[side] = normalY[side] * pitchSin + rotatedZ * pitchCos > 0.000001
                shades[side] = if (normalY[side] > 0) 1.0 else 0.62 + 0.24 * maxOf(0.0, -rotatedX * 0.6 + rotatedZ * 0.8)
            }
            for (packed in faces) {
                val side = packed % 6
                if (!visible[side]) continue
                val cell = packed / 6
                val x = cell % preview.sizeX
                val z = cell / preview.sizeX % preview.sizeZ
                val y = cell / (preview.sizeX * preview.sizeZ)
                val base = y * layerStride + z * rowStride + x
                faceCorners(base, rowStride, layerStride, side, corners)
                val color = shade(preview.colorAt(x, y, z), shades[side])
                triangle(vertices, corners[0], corners[1], corners[2], color, frame, pixels, depth)
                triangle(vertices, corners[0], corners[2], corners[3], color, frame, pixels, depth)
            }
        }
        return TurntableAtlas(pixels)
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Blueprint preview rendering cancelled")
    }

    private fun exposedFaces(preview: VoxelPreview): IntArray {
        val faces = IntArray(preview.sizeX * preview.sizeY * preview.sizeZ * 6)
        var count = 0
        for (y in 0 until preview.sizeY) {
            for (z in 0 until preview.sizeZ) {
                for (x in 0 until preview.sizeX) {
                    if (preview.colorAt(x, y, z) ushr 24 == 0) continue
                    val cell = (y * preview.sizeZ + z) * preview.sizeX + x
                    for (side in 0 until 6) {
                        if (preview.colorAt(x + normalX[side], y + normalY[side], z + normalZ[side]) ushr 24 == 0) {
                            faces[count++] = cell * 6 + side
                        }
                    }
                }
            }
        }
        return faces.copyOf(count)
    }

    private fun projectVertices(preview: VoxelPreview, vertices: DoubleArray, yawCos: Double, yawSin: Double, scale: Double) {
        var index = 0
        for (y in 0..preview.sizeY) {
            val localY = (y.toDouble() / preview.sizeY - 0.5) * preview.extentY
            for (z in 0..preview.sizeZ) {
                val localZ = (z.toDouble() / preview.sizeZ - 0.5) * preview.extentZ
                for (x in 0..preview.sizeX) {
                    val localX = (x.toDouble() / preview.sizeX - 0.5) * preview.extentX
                    val rotatedX = localX * yawCos + localZ * yawSin
                    val rotatedZ = -localX * yawSin + localZ * yawCos
                    vertices[index++] = FRAME_SIZE / 2.0 + rotatedX * scale
                    vertices[index++] = FRAME_SIZE / 2.0 + (-localY * pitchCos + rotatedZ * pitchSin) * scale
                    vertices[index++] = localY * pitchSin + rotatedZ * pitchCos
                }
            }
        }
    }

    private fun faceCorners(base: Int, row: Int, layer: Int, side: Int, corners: IntArray) {
        when (side) {
            0 -> { corners[0] = base; corners[1] = base + row; corners[2] = base + row + layer; corners[3] = base + layer }
            1 -> { corners[0] = base + 1; corners[1] = base + 1 + row; corners[2] = base + 1 + row + layer; corners[3] = base + 1 + layer }
            2 -> { corners[0] = base; corners[1] = base + 1; corners[2] = base + 1 + row; corners[3] = base + row }
            3 -> { corners[0] = base + layer; corners[1] = base + 1 + layer; corners[2] = base + 1 + row + layer; corners[3] = base + row + layer }
            4 -> { corners[0] = base; corners[1] = base + 1; corners[2] = base + 1 + layer; corners[3] = base + layer }
            else -> { corners[0] = base + row; corners[1] = base + 1 + row; corners[2] = base + 1 + row + layer; corners[3] = base + row + layer }
        }
    }

    private fun shade(argb: Int, amount: Double): Int =
        (0xff shl 24) or
            (((argb ushr 16 and 0xff) * amount).toInt() shl 16) or
            (((argb ushr 8 and 0xff) * amount).toInt() shl 8) or
            ((argb and 0xff) * amount).toInt()

    private fun triangle(vertices: DoubleArray, a: Int, b: Int, c: Int, color: Int, frame: Int, pixels: IntArray, depth: DoubleArray) {
        val ax = vertices[a * 3]
        val ay = vertices[a * 3 + 1]
        val bx = vertices[b * 3]
        val by = vertices[b * 3 + 1]
        val cx = vertices[c * 3]
        val cy = vertices[c * 3 + 1]
        val area = edge(ax, ay, bx, by, cx, cy)
        if (abs(area) < 0.000001) return
        val minX = floor(minOf(ax, bx, cx)).toInt().coerceAtLeast(0)
        val maxX = ceil(maxOf(ax, bx, cx)).toInt().coerceAtMost(FRAME_SIZE - 1)
        val minY = floor(minOf(ay, by, cy)).toInt().coerceAtLeast(0)
        val maxY = ceil(maxOf(ay, by, cy)).toInt().coerceAtMost(FRAME_SIZE - 1)
        val atlasX = frame % COLUMNS * FRAME_SIZE
        val atlasY = frame / COLUMNS * FRAME_SIZE
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val px = x + 0.5
                val py = y + 0.5
                val wa = edge(bx, by, cx, cy, px, py) / area
                val wb = edge(cx, cy, ax, ay, px, py) / area
                val wc = 1.0 - wa - wb
                if (wa < -0.000001 || wb < -0.000001 || wc < -0.000001) continue
                val value = wa * vertices[a * 3 + 2] + wb * vertices[b * 3 + 2] + wc * vertices[c * 3 + 2]
                val index = y * FRAME_SIZE + x
                if (value <= depth[index]) continue
                depth[index] = value
                pixels[(atlasY + y) * ATLAS_SIZE + atlasX + x] = color
            }
        }
    }

    private fun edge(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)
}
