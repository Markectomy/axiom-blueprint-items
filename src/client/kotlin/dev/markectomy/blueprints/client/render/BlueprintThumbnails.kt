package dev.markectomy.blueprints.client.render

import com.mojang.blaze3d.platform.NativeImage
import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.integration.Axiom613Adapter
import dev.markectomy.blueprints.data.LibraryPaths
import dev.markectomy.blueprints.domain.BlueprintReference
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.special.SpecialModelRenderers
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Render-thread cache; its single worker owns decoding and never accesses live game state. */
object BlueprintThumbnails : AutoCloseable {
    private const val LIMIT = 256
    private const val SIZE = 64
    private data class Key(val root: Path, val path: String)
    private data class Entry(val texture: Identifier?, val slot: Int?)
    private val cache = LinkedHashMap<Key, Entry>(LIMIT, 0.75f, true)
    private val pending = mutableSetOf<Key>()
    private val slots = ArrayDeque<Int>((0 until LIMIT).toList())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32)) { task ->
        Thread(task, "blueprint-thumbnail-loader").apply { isDaemon = true }
    }
    private var registered = false
    private var generation = 0L
    private var closed = false
    internal var revision: Long = 0
        private set

    fun initialize() {
        if (registered) return
        SpecialModelRenderers.ID_MAPPER.put(BlueprintItems.id("thumbnail"), BlueprintThumbnailRenderer.Unbaked.CODEC)
        registered = true
    }

    /** Nonblocking lookup. Missing, loading and corrupt previews return the generic item icon. */
    fun texture(reference: BlueprintReference): Identifier? {
        if (closed) return null
        val key = Key(Axiom613Adapter.libraryRoot().toAbsolutePath().normalize(), reference.path)
        cache[key]?.let { return it.texture }
        if (key in pending || pending.size >= 33) return null
        val request = generation
        pending.add(key)
        try {
            worker.execute {
                val pixels = runCatching {
                    val path = LibraryPaths(key.root).resolve(reference)
                    val png = Axiom613Adapter.readThumbnailPng(path) ?: return@runCatching null
                    downsample(png)
                }.getOrNull()
                Minecraft.getInstance().execute completion@ {
                    if (closed || request != generation) return@completion
                    pending.remove(key)
                    upload(key, pixels)
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
        }
        return null
    }

    fun invalidate() {
        generation++
        revision++
        pending.clear()
        worker.queue.clear()
        val manager = Minecraft.getInstance().textureManager
        cache.values.forEach { entry -> entry.texture?.let(manager::release) }
        cache.clear()
        slots.clear()
        slots.addAll(0 until LIMIT)
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidate()
        worker.shutdownNow()
    }

    private fun upload(key: Key, pixels: IntArray?) {
        val manager = Minecraft.getInstance().textureManager
        if (cache.size >= LIMIT) {
            val iterator = cache.entries.iterator()
            val old = iterator.next().value
            iterator.remove()
            old.texture?.let(manager::release)
            old.slot?.let(slots::addLast)
        }
        val entry = if (pixels == null) {
            Entry(null, null)
        } else {
            val slot = slots.removeFirst()
            val id = BlueprintItems.id("thumbnail_cache/$slot")
            val image = NativeImage(SIZE, SIZE, true)
            try {
                for (y in 0 until SIZE) for (x in 0 until SIZE) image.setPixel(x, y, pixels[y * SIZE + x])
                val texture = DynamicTexture({ "Axiom blueprint thumbnail" }, image)
                try {
                    manager.register(id, texture)
                } catch (error: Exception) {
                    texture.close()
                    throw error
                }
                Entry(id, slot)
            } catch (_: Exception) {
                if (!image.isClosed) image.close()
                slots.addLast(slot)
                Entry(null, null)
            }
        }
        cache[key] = entry
        // Invalidate vanilla's cached fallback image when the thumbnail becomes ready.
        revision++
    }

    private fun downsample(png: ByteArray): IntArray = NativeImage.read(png).use { source ->
        require(source.width in 1..4096 && source.height in 1..4096)
        require(source.width.toLong() * source.height <= 4_194_304)
        val scale = SIZE.toDouble() / maxOf(source.width, source.height)
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val left = (SIZE - width) / 2
        val top = (SIZE - height) / 2
        IntArray(SIZE * SIZE).also { pixels ->
            for (y in 0 until height) for (x in 0 until width) {
                val sourceX = minOf(source.width - 1, x * source.width / width)
                // Axiom's saved framebuffer thumbnails use inverted Y coordinates.
                val sourceY = source.height - 1 - minOf(source.height - 1, y * source.height / height)
                pixels[(top + y) * SIZE + left + x] = source.getPixel(sourceX, sourceY)
            }
        }
    }
}
