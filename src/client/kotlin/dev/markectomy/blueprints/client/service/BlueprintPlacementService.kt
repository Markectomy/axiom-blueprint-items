package dev.markectomy.blueprints.client.service

import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.integration.Axiom613Adapter
import dev.markectomy.blueprints.data.LibraryPaths
import dev.markectomy.blueprints.domain.BlueprintReference
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/** One outstanding source load; only the final client-thread handoff can start a preview. */
class BlueprintPlacementService : AutoCloseable {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { task ->
        Thread(task, "blueprint-item-loader").apply { isDaemon = true }
    }
    private var generation = 0L
    private var loading = false
    private var closed = false

    fun use(reference: BlueprintReference) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return
        if (!client.hasSingleplayerServer() || !player.isCreative) {
            message("Blueprint items currently work in singleplayer creative mode.")
            return
        }
        if (!Axiom613Adapter.isAvailable()) {
            message("Axiom is not ready or does not allow blueprint placement here.")
            return
        }
        if (Axiom613Adapter.isPlacing()) {
            message("Confirm or cancel your current Axiom placement first.")
            return
        }
        if (loading || closed) return
        val root = Axiom613Adapter.libraryRoot()
        val request = ++generation
        loading = true
        message("Loading ${reference.name}…")
        try {
            worker.execute {
                val result = runCatching {
                    val path = LibraryPaths(root).resolve(reference)
                    Axiom613Adapter.prepare(path)
                }
                client.execute completion@ {
                    if (request != generation || closed) return@completion
                    loading = false
                    if (client.player !== player || client.level !== level || !client.hasSingleplayerServer() ||
                        !player.isCreative || Axiom613Adapter.libraryRoot() != root ||
                        InteractionHand.entries.none { BlueprintItems.reference(player.getItemInHand(it)) == reference }
                    ) return@completion
                    result.fold(
                        onSuccess = { prepared ->
                            if (Axiom613Adapter.isPlacing()) {
                                message("Confirm or cancel your current Axiom placement first.")
                            } else if (Axiom613Adapter.isAvailable()) {
                                val hit = player.pick(SURFACE_TARGET_DISTANCE, 1.0f, false)
                                val target = if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
                                    hit.blockPos.relative(hit.direction)
                                } else {
                                    BlockPos.containing(player.eyePosition.add(player.lookAngle.scale(5.0)))
                                }
                                if (Axiom613Adapter.start(prepared, target)) {
                                    message("${reference.name}: scroll to move; right-click to confirm, left-click to cancel.")
                                } else {
                                    message("Axiom could not start its Clone preview. Check builder permissions and that the blueprint contains blocks.")
                                }
                            }
                        },
                        onFailure = { error ->
                            LOGGER.warn("Unable to load blueprint {}", reference.path, error)
                            message("Cannot load ${reference.name}. Its library file is missing or unreadable.")
                        },
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            loading = false
        }
    }

    fun invalidate() {
        generation++
        worker.queue.clear()
        loading = false
    }

    override fun close() {
        closed = true
        invalidate()
        worker.shutdownNow()
    }

    private fun message(text: String) {
        Minecraft.getInstance().player?.sendOverlayMessage(Component.literal(text))
    }

    companion object {
        private const val SURFACE_TARGET_DISTANCE = 1024.0
        private val LOGGER = LoggerFactory.getLogger("AxiomBlueprintItems")
    }
}
