package dev.markectomy.blueprints.client.service

import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.integration.Axiom613Adapter
import dev.markectomy.blueprints.data.LibraryPaths
import dev.markectomy.blueprints.domain.BlueprintReference
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
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

/** One outstanding source load; only the final client-thread handoff can place a blueprint. */
class BlueprintPlacementService : AutoCloseable {
    private enum class PlacementMode { PREVIEW, IMMEDIATE }

    private sealed class UseState(val player: LocalPlayer, val level: ClientLevel) {
        class Loading(
            player: LocalPlayer,
            level: ClientLevel,
            val mode: PlacementMode,
            val target: BlockPos,
        ) : UseState(player, level)

        class AwaitingRelease(player: LocalPlayer, level: ClientLevel) : UseState(player, level)
    }

    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { task ->
        Thread(task, "blueprint-item-loader").apply { isDaemon = true }
    }
    private var useState: UseState? = null
    private var closed = false

    fun use(reference: BlueprintReference) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return
        if (useState != null || closed) return
        if (!player.isCreative) {
            message("Blueprint items require creative mode.")
            return
        }
        if (!Axiom613Adapter.isAvailable()) {
            message("Axiom is not ready or does not allow blueprint placement here.")
            return
        }
        if (Axiom613Adapter.isPlacing()) {
            message("Finish or cancel your current Axiom placement first.")
            return
        }
        val root = Axiom613Adapter.libraryRoot()
        val mode = if (client.options.keyShift.isDown) PlacementMode.IMMEDIATE else PlacementMode.PREVIEW
        val hit = player.pick(SURFACE_TARGET_DISTANCE, 1.0f, false)
        val target = if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            hit.blockPos.relative(hit.direction)
        } else {
            BlockPos.containing(player.eyePosition.add(player.lookAngle.scale(5.0)))
        }
        val request = UseState.Loading(player, level, mode, target)
        useState = request
        message("Loading ${reference.name}…")
        try {
            worker.execute {
                val result = runCatching {
                    val path = LibraryPaths(root).resolve(reference)
                    Axiom613Adapter.prepare(path)
                }
                client.execute completion@ {
                    if (useState !== request || closed) return@completion
                    finishLoad(request)
                    if (client.player !== player || client.level !== level || !player.isCreative ||
                        !Axiom613Adapter.isAvailable() || Axiom613Adapter.libraryRoot() != root ||
                        InteractionHand.entries.none { BlueprintItems.reference(player.getItemInHand(it)) == reference }
                    ) return@completion
                    result.fold(
                        onSuccess = { prepared ->
                            if (Axiom613Adapter.isPlacing()) {
                                message("Finish or cancel your current Axiom placement first.")
                            } else if (Axiom613Adapter.isAvailable()) {
                                val placed = if (request.mode == PlacementMode.IMMEDIATE) {
                                    Axiom613Adapter.placeImmediately(prepared, request.target)
                                } else {
                                    Axiom613Adapter.start(prepared, request.target)
                                }
                                if (placed) {
                                    message(if (request.mode == PlacementMode.IMMEDIATE) "Placed ${reference.name}." else reference.name)
                                } else {
                                    message("Axiom could not place this blueprint. Check builder permissions and that it contains blocks.")
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
            if (useState === request) finishLoad(request)
        }
    }

    fun tick() {
        Axiom613Adapter.validateImportedPreview()
        val state = useState ?: return
        val client = Minecraft.getInstance()
        if (client.player !== state.player || client.level !== state.level ||
            !state.player.isCreative || !Axiom613Adapter.isAvailable()
        ) {
            invalidate()
        } else if (state is UseState.AwaitingRelease && !client.options.keyUse.isDown) {
            useState = null
        }
    }

    private fun finishLoad(request: UseState.Loading) {
        useState = if (Minecraft.getInstance().options.keyUse.isDown) {
            UseState.AwaitingRelease(request.player, request.level)
        } else {
            null
        }
    }

    fun invalidate() {
        useState = null
        worker.queue.clear()
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
