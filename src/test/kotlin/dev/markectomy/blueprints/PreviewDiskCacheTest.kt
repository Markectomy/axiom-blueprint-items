package dev.markectomy.blueprints

import dev.markectomy.blueprints.data.preview.PreviewDiskCache
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewDiskCacheTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `atlas survives reopening the disk cache`() {
        val source = Files.writeString(temporaryDirectory.resolve("source.bp"), "blueprint")
        val directory = temporaryDirectory.resolve("cache")
        val cache = PreviewDiskCache(directory)
        val pixels = IntArray(65_536) { it * 0x10203 }
        val key = cache.keyFor(source)
        cache.write(key, pixels)
        assertContentEquals(pixels, PreviewDiskCache(directory).read(key))
        assertEquals(key, cache.keyFor(temporaryDirectory.resolve("unused/../source.bp")))
    }

    @Test
    fun `source size and modification time invalidate the key`() {
        val source = Files.writeString(temporaryDirectory.resolve("source.bp"), "first")
        val cache = PreviewDiskCache(temporaryDirectory.resolve("cache"))
        val initial = cache.keyFor(source)
        val modified = Files.getLastModifiedTime(source).toMillis()
        Files.setLastModifiedTime(source, FileTime.fromMillis(modified + 2_000))
        val changedTime = cache.keyFor(source)
        assertNotEquals(initial, changedTime)
        Files.writeString(source, "different length")
        assertNotEquals(changedTime, cache.keyFor(source))
    }

    @Test
    fun `corrupt oversized and overlong data are cache misses`() {
        val directory = Files.createDirectory(temporaryDirectory.resolve("cache"))
        val cache = PreviewDiskCache(directory)
        val key = "a".repeat(64)
        val file = directory.resolve("$key.preview.gz")
        Files.write(file, byteArrayOf(1, 2, 3))
        assertNull(cache.read(key))
        Files.write(file, ByteArray(300 * 1024 + 1))
        assertNull(cache.read(key))
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(file))).use { output ->
            output.writeInt(0x41425049)
            output.writeInt(1)
            output.writeInt(65_536)
            repeat(65_537) { output.writeInt(it) }
        }
        assertNull(cache.read(key))
        cache.write(key, IntArray(65_536))
        val compressed = Files.readAllBytes(file)
        compressed[compressed.lastIndex - 4] = (compressed[compressed.lastIndex - 4].toInt() xor 1).toByte()
        Files.write(file, compressed)
        assertNull(cache.read(key))
        assertNull(cache.read("../escape"))
    }

    @Test
    fun `trim limits owned files and leaves unrelated files intact`() {
        val directory = Files.createDirectory(temporaryDirectory.resolve("cache"))
        repeat(513) { index -> Files.write(directory.resolve("${index.toString(16).padStart(64, '0')}.preview.gz"), byteArrayOf(1)) }
        val unrelated = Files.writeString(directory.resolve("notes.txt"), "keep")
        val ownTemporary = Files.writeString(directory.resolve("${"b".repeat(64)}.preview-${UUID.randomUUID()}.tmp"), "incomplete")
        val otherTemporary = Files.writeString(directory.resolve("other.tmp"), "keep")
        PreviewDiskCache(directory).write("c".repeat(64), IntArray(65_536))
        val count = Files.newDirectoryStream(directory, "*.preview.gz").use { it.count() }
        assertTrue(count <= 512)
        assertTrue(Files.exists(unrelated))
        assertTrue(Files.exists(otherTemporary))
        assertTrue(Files.notExists(ownTemporary))
    }

    @Test
    fun `trim enforces total cache size independently of file count`() {
        val directory = Files.createDirectory(temporaryDirectory.resolve("cache"))
        val bytes = ByteArray(300 * 1024)
        repeat(112) { index -> Files.write(directory.resolve("${index.toString(16).padStart(64, '0')}.preview.gz"), bytes) }
        PreviewDiskCache(directory).write("d".repeat(64), IntArray(65_536))
        val size = Files.newDirectoryStream(directory, "*.preview.gz").use { entries -> entries.sumOf(Files::size) }
        assertTrue(size <= 32L * 1024 * 1024)
    }
}
