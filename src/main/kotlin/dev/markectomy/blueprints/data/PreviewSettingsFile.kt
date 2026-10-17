package dev.markectomy.blueprints.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.markectomy.blueprints.domain.PreviewStyle
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

class PreviewSettingsFile(private val path: Path) {
    fun load(): Boolean = loadStyle() != PreviewStyle.STATIC

    fun loadStyle(): PreviewStyle {
        if (Files.notExists(path)) {
            runCatching {
                path.parent?.let { Files.createDirectories(it) }
                Files.writeString(path, DEFAULT_CONTENT, CREATE_NEW, WRITE)
            }
            return PreviewStyle.HIGH_QUALITY
        }
        return runCatching {
            val root = JsonParser.parseString(readBounded().toString(Charsets.UTF_8)).asJsonObject
            val savedStyle = root.get(STYLE_PROPERTY)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isString }?.asString
            PreviewStyle.fromSerializedName(savedStyle) ?: run {
                val enabled = root.get(LEGACY_PROPERTY)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    ?.takeIf { it.isBoolean }?.asBoolean ?: true
                if (enabled) PreviewStyle.HIGH_QUALITY else PreviewStyle.STATIC
            }
        }.getOrDefault(PreviewStyle.HIGH_QUALITY)
    }

    @Throws(IOException::class)
    fun save(enabled: Boolean) = save(if (enabled) PreviewStyle.HIGH_QUALITY else PreviewStyle.STATIC)

    @Throws(IOException::class)
    fun save(style: PreviewStyle) {
        try {
            val root = if (Files.notExists(path)) JsonObject() else readObject()
            root.addProperty(STYLE_PROPERTY, style.serializedName)
            root.addProperty(LEGACY_PROPERTY, style != PreviewStyle.STATIC)
            val bytes = (JSON.toJson(root) + "\n").toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) throw IOException("Preview settings exceed the 64 KiB limit")

            val target = path.toAbsolutePath().normalize()
            val directory = target.parent ?: throw IOException("Preview settings have no parent directory")
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, ".${target.fileName}-", ".tmp")
            try {
                Files.write(temporary, bytes, TRUNCATE_EXISTING, WRITE)
                try {
                    Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (failure: RuntimeException) {
            throw IOException("Could not save preview settings", failure)
        }
    }

    private fun readBounded(): ByteArray {
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) throw IOException("Preview settings exceed the 64 KiB limit")
        return bytes
    }

    private fun readObject(): JsonObject {
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readBounded())).toString()
        return JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            val root = JsonParser.parseReader(reader)
            if (!root.isJsonObject || reader.peek() != JsonToken.END_DOCUMENT) {
                throw IOException("Preview settings must contain one JSON object")
            }
            root.asJsonObject
        }
    }

    companion object {
        private const val MAX_BYTES = 64 * 1024
        private const val STYLE_PROPERTY = "previewStyle"
        private const val LEGACY_PROPERTY = "animatedPreviews"
        private const val DEFAULT_CONTENT = "{\n  \"previewStyle\": \"high_quality\",\n  \"animatedPreviews\": true\n}\n"
        private val JSON = GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create()
    }
}
