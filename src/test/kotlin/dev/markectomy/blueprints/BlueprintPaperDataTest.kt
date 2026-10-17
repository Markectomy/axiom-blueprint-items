package dev.markectomy.blueprints

import dev.markectomy.blueprints.component.BlueprintPaperData
import dev.markectomy.blueprints.domain.BlueprintReference
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.item.component.CustomData
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlueprintPaperDataTest {
    @Test
    fun `paper reference survives vanilla NBT serialization`() {
        val reference = BlueprintReference(path = "houses/Stone House.bp", name = "Stone House")
        val data = BlueprintPaperData.create(reference)
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { NbtIo.write(data.copyTag(), it) }
        }.toByteArray()
        val restored = DataInputStream(ByteArrayInputStream(bytes)).use {
            CustomData.of(NbtIo.read(it, NbtAccounter.create(16_384)))
        }
        assertEquals(reference, BlueprintPaperData.reference(restored))
        assertEquals(setOf("axiom_blueprint_items"), restored.copyTag().keySet())
    }

    @Test
    fun `malformed or unsupported paper references are ignored`() {
        assertNull(BlueprintPaperData.reference(CustomData.EMPTY))
        for (path in listOf("../escape.bp", "/absolute.bp", "a//b.bp", "a\\b.bp", "C:/escape.bp", "a".repeat(2049))) {
            assertNull(BlueprintPaperData.reference(data(path, "House", 1)))
        }
        assertNull(BlueprintPaperData.reference(data("a.bp", "House", 2)))
        assertNull(BlueprintPaperData.reference(data("a.bp", " ", 1)))
        assertNull(BlueprintPaperData.reference(data("a.bp", "a".repeat(513), 1)))
        assertNull(BlueprintPaperData.reference(CustomData.of(CompoundTag().apply {
            putString("axiom_blueprint_items", "invalid")
        })))
    }

    @Test
    fun `replacing immutable custom data never keeps a stale cached reference`() {
        val original = BlueprintReference(path = "a.bp", name = "A")
        val data = BlueprintPaperData.create(original)
        assertEquals(original, BlueprintPaperData.reference(data))
        val edited = data.update { it.getCompoundOrEmpty("axiom_blueprint_items").putString("path", "b.bp") }
        assertEquals(original.copy(path = "b.bp"), BlueprintPaperData.reference(edited))
        assertEquals(original, BlueprintPaperData.reference(data))
    }

    @Test
    fun `reference lookup remains consistent across client and render threads`() {
        val entries = (0 until 32).map { index ->
            BlueprintReference(path = "$index.bp", name = "Blueprint $index").let { it to BlueprintPaperData.create(it) }
        }
        Executors.newFixedThreadPool(4).use { executor ->
            val results = (0 until 4).map {
                executor.submit<Boolean> {
                    (0 until 100).all { entries.all { (reference, data) -> BlueprintPaperData.reference(data) == reference } }
                }
            }
            results.forEach { assertEquals(true, it.get()) }
        }
    }

    private fun data(path: String, name: String, version: Int): CustomData = CustomData.of(CompoundTag().apply {
        put("axiom_blueprint_items", CompoundTag().apply {
            putInt("version", version)
            putString("path", path)
            putString("name", name)
        })
    })
}
