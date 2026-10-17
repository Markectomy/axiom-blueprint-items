package dev.markectomy.blueprints.client.config

import dev.markectomy.blueprints.client.render.BlueprintThumbnails
import dev.markectomy.blueprints.domain.PreviewStyle
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Client-thread state with serialized, coalesced background persistence. */
object BlueprintPreviewSettings : AutoCloseable {
    private data class Save(val version: Long, val settings: PreviewSettings)
    private val path = FabricLoader.getInstance().configDir.resolve(PreviewSettings.FILE_NAME)
    private val logger = LoggerFactory.getLogger("AxiomBlueprintItems/Settings")
    private val lock = Any()
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { task ->
        Thread(task, "blueprint-settings-writer")
    }
    private var pending: Save? = null
    private var running = false
    private var version = 0L
    private var closed = false
    private var initialized = false
    var current = PreviewSettings()
        private set
    var saving = false
        private set
    var saveFailed = false
        private set

    fun initialize() {
        if (initialized) return
        current = PreviewSettings.load(path)
        initialized = true
    }

    fun setEnabled(enabled: Boolean) = setStyle(if (enabled) PreviewStyle.HIGH_QUALITY else PreviewStyle.STATIC)

    fun setStyle(style: PreviewStyle) {
        if (closed || (current.style == style && !saveFailed)) return
        current = PreviewSettings(style)
        BlueprintThumbnails.setStyle(style)
        persist()
    }

    fun retrySave() {
        if (!closed && saveFailed) persist()
    }

    private fun persist() {
        val save = Save(++version, current)
        saving = true
        saveFailed = false
        synchronized(lock) {
            pending = save
            if (!running) {
                running = true
                writer.execute(::drain)
            }
        }
    }

    private fun drain() {
        while (true) {
            val save = synchronized(lock) {
                val next = pending
                if (next == null) {
                    running = false
                    return
                }
                pending = null
                next
            }
            val result = runCatching { save.settings.save(path) }
            result.exceptionOrNull()?.let { logger.warn("Unable to save blueprint preview settings", it) }
            Minecraft.getInstance().execute {
                if (!closed && version == save.version) {
                    saving = false
                    saveFailed = result.isFailure
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // The non-daemon writer finishes the latest queued choice before the JVM exits.
        writer.shutdown()
    }
}
