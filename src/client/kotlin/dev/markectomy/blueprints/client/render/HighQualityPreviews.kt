package dev.markectomy.blueprints.client.render

import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.integration.Axiom613Adapter
import dev.markectomy.blueprints.client.integration.AxiomBlueprintIcon
import dev.markectomy.blueprints.data.LibraryPaths
import dev.markectomy.blueprints.data.preview.PreviewDiskCache
import dev.markectomy.blueprints.domain.BlueprintReference
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.PI

/** Actual Axiom meshes and framebuffers; all graphics ownership stays on the client thread. */
object HighQualityPreviews : AutoCloseable {
    private val logger = LoggerFactory.getLogger("AxiomBlueprintItems/HighQualityPreviews")
    private const val MAX_ICONS = 64
    private const val MAX_ENTRIES = 256
    private const val MAX_GPU_BYTES = 384L * 1024 * 1024
    private const val MAX_BLOCKS = 16_777_215L
    private const val CHUNKS_PER_FRAME = 4
    private const val COMPILE_NANOS = 3_000_000L
    private data class Key(val root: Path, val path: String)
    private class Entry(
        val icon: AxiomBlueprintIcon?, val slot: Int?, val stamp: String?, var generation: Long,
        val revision: Long, var seen: Long, var rendered: Long = -1,
    )
    private val cache = LinkedHashMap<Key, Entry>(MAX_ENTRIES, 0.75f, true)
    private val slots = ArrayDeque<Int>((0 until MAX_ICONS).toList())
    private val pending = mutableSetOf<Key>()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { task ->
        Thread(task, "blueprint-model-loader").apply { isDaemon = true }
    }
    private val fingerprints = PreviewDiskCache(FabricLoader.getInstance().gameDir.resolve(".cache/axiom-blueprint-items/previews"))
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    private var revision = 0L
    private var frame = 0L
    private var remainingChunks = CHUNKS_PER_FRAME
    private var compilationNanos = 0L
    private var yaw = 0f
    private val epoch = System.nanoTime()

    fun beginFrame() {
        frame++
        remainingChunks = CHUNKS_PER_FRAME
        compilationNanos = 0L
        yaw = (((System.nanoTime() - epoch) / 1_000_000_000.0 % 8.0) * (2.0 * PI / 8.0)).toFloat()
    }

    fun view(reference: BlueprintReference, fallback: ThumbnailView): ThumbnailView {
        if (closed || Minecraft.getInstance().level == null) return fallback
        val key = Key(Axiom613Adapter.libraryRoot().toAbsolutePath().normalize(), reference.path)
        val entry = cache[key]
        if ((entry == null || entry.generation != generation) && key !in pending && pending.size < 2) request(key, reference, entry)
        if (entry == null) return fallback
        entry.seen = frame
        val icon = entry.icon ?: return fallback
        try {
            while (!icon.ready && remainingChunks > 0 && compilationNanos < COMPILE_NANOS) {
                val started = System.nanoTime()
                val used = icon.prepare(1)
                compilationNanos += System.nanoTime() - started
                remainingChunks -= maxOf(used, 1)
            }
            if (!makeRoom(key, 0, 0)) {
                fail(key)
                return fallback
            }
            if (!icon.ready) return fallback
            val texture = BlueprintItems.id("high_quality_preview/${entry.slot}")
            if (entry.rendered != frame) {
                icon.render(texture, yaw)
                entry.rendered = frame
                if (!makeRoom(key, 0, 0)) {
                    fail(key)
                    return fallback
                }
            }
            return ThumbnailView(texture, revision = entry.revision, highQuality = true)
        } catch (error: Exception) {
            logger.warn("Unable to render high-quality blueprint preview {}", reference.path, error)
            fail(key)
            return fallback
        }
    }

    fun unavailable(reference: BlueprintReference): Boolean {
        val key = Key(Axiom613Adapter.libraryRoot().toAbsolutePath().normalize(), reference.path)
        val entry = cache[key] ?: return false
        return entry.icon == null && entry.generation == generation && key !in pending
    }

    fun refresh() {
        generation++
        pending.clear()
        worker.queue.clear()
    }

    fun invalidate() {
        refresh()
        cache.values.forEach(::release)
        cache.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidate()
        worker.shutdownNow()
    }

    private fun request(key: Key, reference: BlueprintReference, previous: Entry?) {
        val request = generation
        pending.add(key)
        try {
            worker.execute {
                var prepared: AxiomBlueprintIcon? = null
                var stamp: String? = null
                try {
                    val path = LibraryPaths(key.root).resolve(reference)
                    stamp = fingerprints.keyFor(path)
                    if (previous?.stamp != stamp) {
                        prepared = AxiomBlueprintIcon.load(path)
                        if (fingerprints.keyFor(path) != stamp) throw IllegalStateException("Blueprint changed during preparation")
                    }
                } catch (error: Exception) {
                    logger.debug("Unable to prepare high-quality blueprint preview {}", reference.path, error)
                    stamp = null
                }
                val icon = prepared
                val checkedStamp = stamp
                Minecraft.getInstance().execute completion@ {
                    if (closed || request != generation) {
                        icon?.close()
                        return@completion
                    }
                    pending.remove(key)
                    if (checkedStamp != null && previous?.stamp == checkedStamp && icon == null) {
                        if (cache[key] === previous) previous.generation = request
                        return@completion
                    }
                    remove(key)
                    if (cache.size >= MAX_ENTRIES) remove(cache.entries.first().key)
                    if (checkedStamp == null || icon == null || !makeRoom(key, icon.blockCount.toLong(), 1)) {
                        icon?.close()
                        cache[key] = Entry(null, null, null, request, ++revision, frame)
                    } else {
                        cache[key] = Entry(icon, slots.removeFirst(), checkedStamp, request, ++revision, frame)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
        }
    }

    private fun makeRoom(protected: Key, incomingBlocks: Long, incomingIcons: Int): Boolean {
        while (true) {
            var blocks = incomingBlocks
            var bytes = 0L
            var icons = incomingIcons
            cache.values.forEach { entry -> entry.icon?.let {
                blocks += it.blockCount
                bytes += it.gpuBytes
                icons++
            } }
            if (blocks <= MAX_BLOCKS && bytes <= MAX_GPU_BYTES && icons <= MAX_ICONS) return true
            val victim = cache.entries.firstOrNull { it.key != protected && it.value.icon != null && it.value.seen < frame - 2 }?.key
                ?: return false
            remove(victim)
        }
    }

    private fun fail(key: Key) {
        val entry = cache[key] ?: return
        release(entry)
        cache[key] = Entry(null, null, null, generation, ++revision, frame)
    }

    private fun release(entry: Entry) {
        entry.icon?.close()
        entry.slot?.let(slots::addLast)
    }

    private fun remove(key: Key) {
        cache.remove(key)?.let(::release)
    }
}
