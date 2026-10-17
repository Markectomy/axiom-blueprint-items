package dev.markectomy.blueprints

import com.google.gson.JsonParser
import dev.markectomy.blueprints.data.PreviewSettingsFile
import dev.markectomy.blueprints.domain.PreviewStyle
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
    fun `first load creates high quality defaults and existing false survives loading`() {
        val path = temporaryDirectory.resolve("config/axiom-blueprint-items.json")
        assertEquals(PreviewStyle.HIGH_QUALITY, PreviewSettingsFile(path).loadStyle())
        assertTrue(Files.isRegularFile(path))
        assertEquals("high_quality", JsonParser.parseString(Files.readString(path)).asJsonObject["previewStyle"].asString)
        Files.writeString(path, """{"animatedPreviews":false}""")
        val original = Files.readAllBytes(path)
        assertEquals(PreviewStyle.STATIC, PreviewSettingsFile(path).loadStyle())
        assertContentEquals(original, Files.readAllBytes(path))
    }

    @Test
    fun `legacy boolean choices still save and reopen`() {
        val path = temporaryDirectory.resolve("config/axiom-blueprint-items.json")
        PreviewSettingsFile(path).save(false)
        assertFalse(PreviewSettingsFile(path).load())
        PreviewSettingsFile(path).save(true)
        assertTrue(PreviewSettingsFile(path).load())
        val files = Files.list(path.parent).use { entries -> entries.toList() }
        assertEquals(listOf(path), files)
    }

    @Test
    fun `all presets persist independently and synchronize the legacy preference`() {
        val path = temporaryDirectory.resolve("config/settings.json")
        val settings = PreviewSettingsFile(path)
        val choices = listOf(
            PreviewStyle.LIGHTWEIGHT to "lightweight",
            PreviewStyle.STATIC to "static",
            PreviewStyle.HIGH_QUALITY to "high_quality"
        )
        for ((style, serializedName) in choices) {
            settings.save(style)
            assertEquals(style, PreviewSettingsFile(path).loadStyle())
            val saved = JsonParser.parseString(Files.readString(path)).asJsonObject
            assertEquals(serializedName, saved["previewStyle"].asString)
            assertEquals(style != PreviewStyle.STATIC, saved["animatedPreviews"].asBoolean)
        }
        assertEquals(listOf(path), Files.list(path.parent).use { it.toList() })
    }

    @Test
    fun `legacy and invalid preset values migrate without rewriting user data`() {
        val path = temporaryDirectory.resolve("settings.json")
        val inputs = listOf(
            """{"animatedPreviews":false}""" to PreviewStyle.STATIC,
            "// Existing preference\n{\"animatedPreviews\":false}" to PreviewStyle.STATIC,
            """{"animatedPreviews":true}""" to PreviewStyle.HIGH_QUALITY,
            """{}""" to PreviewStyle.HIGH_QUALITY,
            """{"animatedPreviews":"false"}""" to PreviewStyle.HIGH_QUALITY,
            """{"previewStyle":"future_style","animatedPreviews":false}""" to PreviewStyle.STATIC,
            """{"previewStyle":"future_style","animatedPreviews":true}""" to PreviewStyle.HIGH_QUALITY,
            """{"previewStyle":false}""" to PreviewStyle.HIGH_QUALITY,
            """{"previewStyle":null,"animatedPreviews":false}""" to PreviewStyle.STATIC,
            """{"previewStyle":[],"animatedPreviews":false}""" to PreviewStyle.STATIC
        )
        for ((content, expected) in inputs) {
            Files.writeString(path, content)
            val original = Files.readAllBytes(path)
            assertEquals(expected, PreviewSettingsFile(path).loadStyle())
            assertEquals(expected, PreviewSettingsFile(path).loadStyle())
            assertContentEquals(original, Files.readAllBytes(path))
        }
    }

    @Test
    fun `explicit preset takes priority over conflicting legacy boolean`() {
        val path = temporaryDirectory.resolve("settings.json")
        val inputs = listOf(
            """{"previewStyle":"static","animatedPreviews":true}""" to PreviewStyle.STATIC,
            """{"previewStyle":"lightweight","animatedPreviews":false}""" to PreviewStyle.LIGHTWEIGHT,
            """{"previewStyle":"high_quality","animatedPreviews":false}""" to PreviewStyle.HIGH_QUALITY
        )
        for ((content, expected) in inputs) {
            Files.writeString(path, content)
            assertEquals(expected, PreviewSettingsFile(path).loadStyle())
        }
    }

    @Test
    fun `saving preserves unknown nested values including null`() {
        val path = temporaryDirectory.resolve("settings.json")
        Files.writeString(path, """{"animatedPreviews":false,"future":{"label":"é<&>","values":[null,4,true]},"unused":null}""")
        val original = JsonParser.parseString(Files.readString(path)).asJsonObject
        PreviewSettingsFile(path).save(PreviewStyle.LIGHTWEIGHT)
        val saved = JsonParser.parseString(Files.readString(path)).asJsonObject
        assertTrue(saved["animatedPreviews"].asBoolean)
        assertEquals("lightweight", saved["previewStyle"].asString)
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
