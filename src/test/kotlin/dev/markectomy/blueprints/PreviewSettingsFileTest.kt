package dev.markectomy.blueprints

import com.google.gson.JsonParser
import dev.markectomy.blueprints.data.PreviewSettingsFile
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewSettingsFileTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `first load creates defaults and existing false survives loading`() {
        val path = temporaryDirectory.resolve("config/axiom-blueprint-items.json")
        assertTrue(PreviewSettingsFile(path).load())
        assertTrue(Files.isRegularFile(path))
        Files.writeString(path, """{"animatedPreviews":false}""")
        val original = Files.readAllBytes(path)
        assertFalse(PreviewSettingsFile(path).load())
        assertContentEquals(original, Files.readAllBytes(path))
    }

    @Test
    fun `both preview modes survive reopening with no temporary files left`() {
        val path = temporaryDirectory.resolve("config/axiom-blueprint-items.json")
        PreviewSettingsFile(path).save(false)
        assertFalse(PreviewSettingsFile(path).load())
        PreviewSettingsFile(path).save(true)
        assertTrue(PreviewSettingsFile(path).load())
        val files = Files.list(path.parent).use { entries -> entries.toList() }
        assertEquals(listOf(path), files)
    }

    @Test
    fun `saving preserves unknown nested values including null`() {
        val path = temporaryDirectory.resolve("settings.json")
        Files.writeString(path, """{"animatedPreviews":false,"future":{"label":"é<&>","values":[null,4,true]},"unused":null}""")
        val original = JsonParser.parseString(Files.readString(path)).asJsonObject
        PreviewSettingsFile(path).save(true)
        val saved = JsonParser.parseString(Files.readString(path)).asJsonObject
        assertTrue(saved["animatedPreviews"].asBoolean)
        assertEquals(original["future"], saved["future"])
        assertTrue(saved.has("unused"))
        assertEquals(original["unused"], saved["unused"])
    }

    @Test
    fun `malformed nonobject and oversized files fall back without being overwritten`() {
        val path = temporaryDirectory.resolve("settings.json")
        val invalid = listOf("{broken", "[]", "null", "{\"animatedPreviews\":true} false", " ".repeat(65_537))
        for (content in invalid) {
            Files.writeString(path, content)
            val original = Files.readAllBytes(path)
            assertTrue(PreviewSettingsFile(path).load())
            assertFailsWith<IOException> { PreviewSettingsFile(path).save(false) }
            assertContentEquals(original, Files.readAllBytes(path))
        }
    }

    @Test
    fun `invalid UTF8 is not rewritten`() {
        val path = temporaryDirectory.resolve("settings.json")
        val original = "{\"future\":\"".toByteArray() + byteArrayOf(0xC3.toByte()) + "\"}".toByteArray()
        Files.write(path, original)
        assertFailsWith<IOException> { PreviewSettingsFile(path).save(false) }
        assertContentEquals(original, Files.readAllBytes(path))
    }

    @Test
    fun `output limit prevents truncating a large otherwise valid settings file`() {
        val path = temporaryDirectory.resolve("settings.json")
        Files.writeString(path, "{\"future\":\"${"x".repeat(65_500)}\"}")
        val original = Files.readAllBytes(path)
        assertTrue(original.size <= 65_536)
        assertFailsWith<IOException> { PreviewSettingsFile(path).save(false) }
        assertContentEquals(original, Files.readAllBytes(path))
        assertEquals(1L, Files.list(temporaryDirectory).use { it.count() })
    }

    @Test
    fun `unwritable destination reports failure and leaves existing data intact`() {
        val blocker = Files.writeString(temporaryDirectory.resolve("config"), "existing file")
        val path = blocker.resolve("settings.json")
        assertFailsWith<IOException> { PreviewSettingsFile(path).save(false) }
        assertEquals("existing file", Files.readString(blocker))
        assertEquals(1L, Files.list(temporaryDirectory).use { it.count() })
    }

    @Test
    fun `failed write leaves the previous saved preference intact`() {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"))
        val directory = Files.createDirectory(temporaryDirectory.resolve("read-only"))
        val path = Files.writeString(directory.resolve("settings.json"), """{"animatedPreviews":false}""")
        val original = Files.readAllBytes(path)
        val permissions = Files.getPosixFilePermissions(directory)
        try {
            Files.setPosixFilePermissions(directory, setOf(OWNER_READ, OWNER_EXECUTE))
            assumeFalse(Files.isWritable(directory))
            assertFailsWith<IOException> { PreviewSettingsFile(path).save(true) }
            assertContentEquals(original, Files.readAllBytes(path))
            assertFalse(PreviewSettingsFile(path).load())
            assertEquals(1L, Files.list(directory).use { it.count() })
        } finally {
            Files.setPosixFilePermissions(directory, permissions)
        }
    }
}
