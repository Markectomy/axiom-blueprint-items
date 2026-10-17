package dev.markectomy.blueprints.client.render

import com.mojang.blaze3d.platform.NativeImage
import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.integration.Axiom613Adapter
import dev.markectomy.blueprints.data.LibraryPaths
import dev.markectomy.blueprints.data.preview.PreviewDiskCache
import dev.markectomy.blueprints.domain.PreviewStyle
import dev.markectomy.blueprints.domain.BlueprintReference
import dev.markectomy.blueprints.domain.preview.VoxelTurntable
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.special.SpecialModelRenderers
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class ThumbnailView(val texture: Identifier?, val frame: Int = 0, val columns: Int = 1, val revision: Long = 0, val highQuality: Boolean = false)

/** Client-thread cache; one bounded worker owns source IO, sampling and rasterization. */
object BlueprintThumbnails : AutoCloseable {
    private const val LIMIT = 256
    private const val ANIMATED_LIMIT = 64
    private const val SIZE = 64
    private const val FRAME_NANOS = 250_000_000L
    private data class Key(val root: Path, val path: String)
    private data class Image(val texture: Identifier, val slot: Int, val animated: Boolean, val revision: Long)
    private data class Entry(val image: Image?, val stamp: String?, val generation: Long)
    private val cache = LinkedHashMap<Key, Entry>(LIMIT, 0.75f, true)
    private val pending = mutableSetOf<Key>()
    private val staticSlots = ArrayDeque<Int>((0 until LIMIT).toList())
    private val animatedSlots = ArrayDeque<Int>((0 until ANIMATED_LIMIT).toList())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32)) { task ->
        Thread(task, "blueprint-preview-loader").apply { isDaemon = true }
    }
    private val disk = PreviewDiskCache(FabricLoader.getInstance().gameDir.resolve(".cache/axiom-blueprint-items/previews"))
    private var registered = false
    @Volatile private var style = PreviewStyle.HIGH_QUALITY
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    private var revision = 0L

    fun initialize(style: PreviewStyle) {
        if (registered) return
        this.style = style
        SpecialModelRenderers.ID_MAPPER.put(BlueprintItems.id("thumbnail"), BlueprintThumbnailRenderer.Unbaked.CODEC)
        registered = true
    }

    fun setAnimated(animated: Boolean) = setStyle(if (animated) PreviewStyle.HIGH_QUALITY else PreviewStyle.STATIC)

    fun setStyle(style: PreviewStyle) {
        if (closed || this.style == style) return
        this.style = style
        invalidate()
    }

    /** Nonblocking lookup; only the bounded angle joins vanilla's GUI model cache key. */
    fun view(reference: BlueprintReference): ThumbnailView {
        val fallback = savedView(reference)
        return if (style == PreviewStyle.HIGH_QUALITY) HighQualityPreviews.view(reference, fallback) else fallback
    }

    private fun savedView(reference: BlueprintReference): ThumbnailView {
        if (closed) return ThumbnailView(null)
        val key = Key(Axiom613Adapter.libraryRoot().toAbsolutePath().normalize(), reference.path)
        val entry = cache[key]
        if ((entry == null || entry.generation != generation) && key !in pending && pending.size < 33) request(key, reference, entry)
        val image = entry?.image ?: return ThumbnailView(null)
        val frame = if (image.animated) Math.floorMod(System.nanoTime() / FRAME_NANOS, VoxelTurntable.FRAME_COUNT.toLong()).toInt() else 0
        return ThumbnailView(image.texture, frame, if (image.animated) VoxelTurntable.COLUMNS else 1, image.revision)
    }

    fun texture(reference: BlueprintReference): Identifier? = view(reference).texture

    /** Keep ready textures while checking whether their sources have changed. */
    fun refresh() {
        HighQualityPreviews.refresh()
        generation++
        pending.clear()
        worker.queue.clear()
    }

    fun invalidate() {
        HighQualityPreviews.invalidate()
        refresh()
        cache.values.forEach { release(it.image) }
        cache.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        HighQualityPreviews.close()
        invalidate()
        worker.shutdownNow()
    }

    private fun request(key: Key, reference: BlueprintReference, previous: Entry?) {
        val request = generation
        val animated = style == PreviewStyle.LIGHTWEIGHT
        pending.add(key)
        try {
            worker.execute {
                try {
                    val path = LibraryPaths(key.root).resolve(reference)
                    val stamp = disk.keyFor(path)
                    if (previous?.stamp == stamp) {
                        complete(request, key) {
                            // A refresh must not upload or invalidate unchanged item images.
                            cache[key]?.let { cache[key] = it.copy(generation = request) }
                        }
                        return@execute
                    }
                    if (animated) {
                        val saved = disk.read(stamp)
                        if (saved != null && disk.keyFor(path) == stamp) {
                            complete(request, key) { upload(key, saved, true, stamp, request) }
                            return@execute
                        }
                    }
                    if (!active(request)) return@execute
                    val fallback = runCatching { Axiom613Adapter.readThumbnailPng(path)?.let(::downsample) }.getOrNull()
                    Minecraft.getInstance().execute {
                        if (active(request)) upload(key, fallback, false, stamp, request)
                    }
                    val atlas = if (animated && active(request)) runCatching {
                        VoxelTurntable.render(Axiom613Adapter.preparePreview(path)).copyPixels()
                    }.getOrNull() else null
                    if (!active(request)) return@execute
                    if (disk.keyFor(path) != stamp) {
                        complete(request, key) { remove(key) }
                        return@execute
                    }
                    if (atlas != null) disk.write(stamp, atlas)
                    complete(request, key) {
                        if (atlas != null) upload(key, atlas, true, stamp, request)
                    }
                } catch (_: Exception) {
                    complete(request, key) { upload(key, null, false, null, request) }
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
        }
    }

    private fun active(request: Long): Boolean = !closed && generation == request && !Thread.currentThread().isInterrupted

    private fun complete(request: Long, key: Key, action: () -> Unit) {
        Minecraft.getInstance().execute {
            if (!active(request)) return@execute
            pending.remove(key)
            action()
        }
    }

    private fun release(image: Image?) {
        if (image == null) return
        Minecraft.getInstance().textureManager.release(image.texture)
        (if (image.animated) animatedSlots else staticSlots).addLast(image.slot)
    }

    private fun remove(key: Key) {
        release(cache.remove(key)?.image)
    }

    private fun upload(key: Key, pixels: IntArray?, animated: Boolean, stamp: String?, request: Long) {
        if (pixels == null) {
            remove(key)
            if (cache.size >= LIMIT) remove(cache.entries.first().key)
            cache[key] = Entry(null, stamp, request)
            return
        }
        val image = createImage(key, pixels, animated)
        if (image == null) {
            // Keep a usable saved thumbnail and retry a failed upload after an explicit refresh.
            val previous = cache[key]
            if (previous == null && cache.size >= LIMIT) remove(cache.entries.first().key)
            cache[key] = Entry(previous?.image, null, request)
            return
        }
        remove(key)
        if (cache.size >= LIMIT) remove(cache.entries.first().key)
        cache[key] = Entry(image, stamp, request)
    }

    private fun createImage(key: Key, pixels: IntArray, animated: Boolean): Image? {
        val slots = if (animated) animatedSlots else staticSlots
        val size = if (animated) VoxelTurntable.ATLAS_SIZE else SIZE
        var native: NativeImage? = null
        var texture: DynamicTexture? = null
        var slot: Int? = null
        return try {
            native = NativeImage(size, size, true)
            for (y in 0 until size) for (x in 0 until size) native.setPixel(x, y, pixels[y * size + x])
            texture = DynamicTexture({ "Axiom blueprint preview" }, native)
            if (slots.isEmpty()) {
                val victim = cache.entries.first { it.key != key && it.value.image?.animated == animated }.key
                remove(victim)
            }
            slot = slots.removeFirst()
            val id = BlueprintItems.id("thumbnail_cache/${if (animated) "animated" else "static"}/$slot")
            Minecraft.getInstance().textureManager.register(id, texture)
            Image(id, slot, animated, ++revision)
        } catch (_: Exception) {
            texture?.close()
            if (native?.isClosed == false) native.close()
            slot?.let(slots::addLast)
            null
        }
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
