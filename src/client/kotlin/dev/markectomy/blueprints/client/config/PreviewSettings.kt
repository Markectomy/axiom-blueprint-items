package dev.markectomy.blueprints.client.config

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

data class PreviewSettings(val enabled: Boolean = true) {
    companion object {
        const val FILE_NAME = "axiom-blueprint-items.json"
        private const val MAX_BYTES = 64 * 1024

        fun load(path: Path): PreviewSettings {
            if (Files.notExists(path)) {
                runCatching {
                    path.parent?.let { Files.createDirectories(it) }
                    Files.writeString(path, "{\n  \"animatedPreviews\": true\n}\n", CREATE_NEW, WRITE)
                }
                return PreviewSettings()
            }
            return runCatching {
                val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) return@runCatching PreviewSettings()
                val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8))
                val value = root.takeIf { it.isJsonObject }?.asJsonObject?.get("animatedPreviews")
                val enabled = value?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    ?.takeIf { it.isBoolean }?.asBoolean ?: true
                PreviewSettings(enabled)
            }.getOrDefault(PreviewSettings())
        }
    }
}
