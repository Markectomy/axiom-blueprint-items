package dev.markectomy.blueprints

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import dev.markectomy.blueprints.component.BlueprintReferenceCodecs
import dev.markectomy.blueprints.data.LibraryPaths
import dev.markectomy.blueprints.domain.BlueprintEntry
import dev.markectomy.blueprints.domain.BlueprintFilters
import dev.markectomy.blueprints.domain.BlueprintReference
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlueprintDomainTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `reference round trips through persistent and network codecs`() {
        val reference = BlueprintReference(path = "houses/Stone House.bp", name = "Stone House")
        val encoded = BlueprintReferenceCodecs.CODEC.encodeStart(JsonOps.INSTANCE, reference).orThrow
        assertEquals(reference, BlueprintReferenceCodecs.CODEC.parse(JsonOps.INSTANCE, encoded).orThrow)
        val buffer = Unpooled.buffer()
        try {
            BlueprintReferenceCodecs.STREAM_CODEC.encode(buffer, reference)
            assertEquals(reference, BlueprintReferenceCodecs.STREAM_CODEC.decode(buffer))
        } finally {
            buffer.release()
        }
    }

    @Test
    fun `malformed persistent data is rejected without constructor exceptions`() {
        for (path in listOf("../escape.bp", "/absolute.bp", "C:/escape.bp", "nested\\escape.bp", "a//b.bp", "a/./b.bp")) {
            val json = JsonParser.parseString("""{"version":1,"path":${com.google.gson.Gson().toJson(path)},"name":"House"}""")
            assertTrue(BlueprintReferenceCodecs.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent)
        }
        assertFailsWith<IllegalArgumentException> { BlueprintReference(version = 2, path = "a.bp", name = "A") }
        assertFailsWith<IllegalArgumentException> { BlueprintReference(path = "a.bp", name = " ") }
    }

    @Test
    fun `library resolves nested files and rejects missing directories and symlink escapes`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("library"))
        val nested = Files.createDirectories(root.resolve("houses"))
        val file = Files.writeString(nested.resolve("a.bp"), "blueprint")
        val paths = LibraryPaths(root)
        val reference = paths.reference(file, "House")
        assertEquals("houses/a.bp", reference.path)
        assertEquals(file.toRealPath(), paths.resolve(reference))
        assertFailsWith<java.nio.file.NoSuchFileException> { paths.resolve(BlueprintReference(path = "missing.bp", name = "Missing")) }
        assertFailsWith<IllegalArgumentException> { paths.resolve(BlueprintReference(path = "houses", name = "Folder")) }
        val outside = Files.writeString(temporaryDirectory.resolve("outside.bp"), "outside")
        Files.createSymbolicLink(root.resolve("escape.bp"), outside)
        assertFailsWith<IllegalArgumentException> { paths.resolve(BlueprintReference(path = "escape.bp", name = "Escape")) }
        Files.createSymbolicLink(root.resolve("escape-folder"), temporaryDirectory)
        assertFailsWith<IllegalArgumentException> {
            paths.resolve(BlueprintReference(path = "escape-folder/outside.bp", name = "Escape"))
        }
    }

    @Test
    fun `name search and tags combine with all selected tags required`() {
        val entry = BlueprintEntry(BlueprintReference(path = "houses/a.bp", name = "Stone House"), "Stone House", setOf("house", "stone"))
        assertTrue(BlueprintFilters.matches(entry, "  HOUSE ", emptySet()))
        assertTrue(BlueprintFilters.matches(entry, "", setOf("house", "stone")))
        assertFalse(BlueprintFilters.matches(entry, "", setOf("house", "wood")))
        assertFalse(BlueprintFilters.matches(entry, "tower", setOf("house")))
    }

    @Test
    fun `duplicate names have stable path ordering`() {
        val a = BlueprintEntry(BlueprintReference(path = "a.bp", name = "House"), "House", emptySet())
        val b = BlueprintEntry(BlueprintReference(path = "b.bp", name = "House"), "House", emptySet())
        assertEquals(listOf(a, b), listOf(b, a).sortedWith(BlueprintFilters.ORDER))
    }
}
