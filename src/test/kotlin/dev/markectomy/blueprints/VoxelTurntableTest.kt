package dev.markectomy.blueprints

import dev.markectomy.blueprints.domain.preview.TurntableAtlas
import dev.markectomy.blueprints.domain.preview.VoxelPreview
import dev.markectomy.blueprints.domain.preview.VoxelTurntable
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoxelTurntableTest {
    @Test
    fun `interrupted preview work cancels without clearing interrupt state`() {
        val thread = Thread.currentThread()
        val wasInterrupted = thread.isInterrupted
        try {
            thread.interrupt()
            assertFailsWith<CancellationException> { VoxelTurntable.render(VoxelPreview(1, 1, 1, intArrayOf(-1))) }
            assertTrue(thread.isInterrupted)
        } finally {
            Thread.interrupted()
            if (wasInterrupted) thread.interrupt()
        }
    }

    @Test
    fun `asymmetric build has distinct solid views throughout the turntable`() {
        val colors = IntArray(5 * 4 * 3)
        fun block(x: Int, y: Int, z: Int, color: Int) {
            colors[(y * 3 + z) * 5 + x] = color
        }
        for (z in 0 until 3) for (x in 0 until 5) block(x, 0, z, 0xffbb4433.toInt())
        for (y in 1 until 4) block(0, y, 0, 0xff33bb44.toInt())
        for (y in 1 until 3) block(4, y, 2, 0xff3344bb.toInt())
        block(2, 1, 0, 0xffddbb33.toInt())
        val atlas = VoxelTurntable.render(VoxelPreview(5, 4, 3, colors))

        assertEquals(256, atlas.width)
        assertEquals(256, atlas.height)
        assertEquals(16, atlas.frameCount)
        val frames = (0 until atlas.frameCount).map { frame(atlas, it) }
        assertTrue(frames.map { it.toList() }.toSet().size >= 12)
        for (pixels in frames) {
            assertTrue(pixels.count { it != 0 } > 100)
            assertTrue(pixels.all { it == 0 || it ushr 24 == 255 })
        }
        assertFalse(frames.first().contentEquals(frames.last()))
    }

    @Test
    fun `full prisms stay centered and retain transparent margins for every yaw`() {
        val atlas = VoxelTurntable.render(VoxelPreview(2, 3, 4, IntArray(24) { -1 }, 20, 30, 40))
        for (frame in 0 until atlas.frameCount) {
            val bounds = bounds(frame(atlas, frame))
            assertTrue(bounds.minX >= 2 && bounds.minY >= 2)
            assertTrue(bounds.maxX <= 61 && bounds.maxY <= 61)
            assertTrue(abs((bounds.minX + bounds.maxX) / 2.0 - 31.5) <= 1.0)
            assertTrue(abs((bounds.minY + bounds.maxY) / 2.0 - 31.5) <= 1.0)
        }
    }

    @Test
    fun `original extents preserve tall and wide builds despite equal sampling grids`() {
        val colors = IntArray(8) { -1 }
        val tall = VoxelTurntable.render(VoxelPreview(2, 2, 2, colors, 2, 20, 2))
        val wide = VoxelTurntable.render(VoxelPreview(2, 2, 2, colors, 20, 2, 2))
        val tallBounds = bounds(frame(tall, 14))
        val wideBounds = bounds(frame(wide, 14))
        assertTrue(tallBounds.height > tallBounds.width * 4)
        assertTrue(wideBounds.width > wideBounds.height * 3)
    }

    @Test
    fun `front walls occlude rear walls at opposite camera angles`() {
        val red = 0xffff0000.toInt()
        val blue = 0xff0000ff.toInt()
        val colors = IntArray(8) { if (it % 2 == 0) blue else red }
        val atlas = VoxelTurntable.render(VoxelPreview(1, 4, 2, colors))
        val front = frame(atlas, 14)[40 * 64 + 32]
        val back = frame(atlas, 6)[40 * 64 + 32]
        assertTrue(front ushr 16 and 255 > 100)
        assertEquals(0, front and 255)
        assertTrue(back and 255 > 100)
        assertEquals(0, back ushr 16 and 255)
    }

    @Test
    fun `empty preview is transparent and pixel ownership is isolated`() {
        val empty = VoxelTurntable.render(VoxelPreview(1, 1, 1, intArrayOf(0)))
        assertTrue(empty.copyPixels().all { it == 0 })
        val source = intArrayOf(-1)
        val preview = VoxelPreview(1, 1, 1, source)
        source[0] = 0
        val atlas = VoxelTurntable.render(preview)
        assertTrue(atlas.copyPixels().any { it != 0 })
        val copy = atlas.copyPixels()
        copy.fill(0)
        assertTrue(atlas.copyPixels().any { it != 0 })
        assertFailsWith<IllegalArgumentException> { atlas.colorAt(-1, 0) }
        assertFailsWith<IllegalArgumentException> { atlas.colorAt(0, 256) }
    }

    @Test
    fun `invalid or oversized sampling grids are rejected before allocation`() {
        assertFailsWith<IllegalArgumentException> { VoxelPreview(0, 1, 1, intArrayOf()) }
        assertFailsWith<IllegalArgumentException> { VoxelPreview(33, 1, 1, IntArray(33)) }
        assertFailsWith<IllegalArgumentException> { VoxelPreview(Int.MAX_VALUE, 1, 1, intArrayOf()) }
        assertFailsWith<IllegalArgumentException> { VoxelPreview(2, 2, 2, IntArray(7)) }
        assertFailsWith<IllegalArgumentException> { VoxelPreview(1, 1, 1, intArrayOf(-1), extentY = 0) }
        assertFailsWith<IllegalArgumentException> { VoxelPreview(1, 1, 1, intArrayOf(-1), extentZ = 1_000_001) }
    }

    private fun frame(atlas: TurntableAtlas, frame: Int): IntArray = IntArray(64 * 64) { index ->
        atlas.colorAt(frame % 4 * 64 + index % 64, frame / 4 * 64 + index / 64)
    }

    private fun bounds(pixels: IntArray): Bounds {
        val indices = pixels.indices.filter { pixels[it] != 0 }
        assertTrue(indices.isNotEmpty())
        return Bounds(indices.minOf { it % 64 }, indices.minOf { it / 64 }, indices.maxOf { it % 64 }, indices.maxOf { it / 64 })
    }

    private data class Bounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
    }
}
