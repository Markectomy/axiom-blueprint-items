package dev.markectomy.blueprints.client.config

import dev.markectomy.blueprints.data.PreviewSettingsFile
import dev.markectomy.blueprints.domain.InstantPlacementSpeed
import dev.markectomy.blueprints.domain.PreviewStyle
import java.io.IOException
import java.nio.file.Path

data class PreviewSettings @JvmOverloads constructor(
    val style: PreviewStyle = PreviewStyle.HIGH_QUALITY,
    val instantPlacementSpeed: InstantPlacementSpeed = InstantPlacementSpeed.NORMAL,
    val airOnly: Boolean = false
) {
    constructor(enabled: Boolean) : this(if (enabled) PreviewStyle.HIGH_QUALITY else PreviewStyle.STATIC)

    val enabled: Boolean get() = style != PreviewStyle.STATIC

    @Throws(IOException::class)
    fun save(path: Path) = PreviewSettingsFile(path).save(style, instantPlacementSpeed, airOnly)

    companion object {
        const val FILE_NAME = "axiom-blueprint-items.json"

        fun load(path: Path): PreviewSettings {
            val values = PreviewSettingsFile(path).loadSettings()
            return PreviewSettings(values.style, values.instantPlacementSpeed, values.airOnly)
        }
    }
}
