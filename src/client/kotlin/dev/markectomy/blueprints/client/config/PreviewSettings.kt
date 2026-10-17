package dev.markectomy.blueprints.client.config

import dev.markectomy.blueprints.data.PreviewSettingsFile
import java.io.IOException
import java.nio.file.Path

data class PreviewSettings(val enabled: Boolean = true) {
    @Throws(IOException::class)
    fun save(path: Path) = PreviewSettingsFile(path).save(enabled)

    companion object {
        const val FILE_NAME = "axiom-blueprint-items.json"

        fun load(path: Path): PreviewSettings = PreviewSettings(PreviewSettingsFile(path).load())
    }
}
