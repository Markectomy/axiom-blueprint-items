package dev.markectomy.blueprints.client.service

import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.integration.Axiom613Adapter
import dev.markectomy.blueprints.data.LibraryPaths
import dev.markectomy.blueprints.domain.BlueprintReference
import dev.markectomy.blueprints.domain.BlueprintLoadFailure
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
            val reference: BlueprintReference,
            val mode: PlacementMode,
            val target: BlockPos,
            val repeated: Boolean,
        ) : UseState(player, level)

        class RepeatReady(player: LocalPlayer, level: ClientLevel, val reference: BlueprintReference) : UseState(player, level)
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
        val repeat = useState as? UseState.RepeatReady
        if ((useState != null && repeat == null) || closed) return
        if (repeat != null && (repeat.player !== player || repeat.level !== level ||
            !repeatHeld(client) || repeat.reference != reference)
        ) {
            awaitRelease(player, level)
            return
        }
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
        if (repeat != null && !Axiom613Adapter.canRepeatPlacement()) return
        val root = Axiom613Adapter.libraryRoot()
        val mode = if (client.options.keyShift.isDown) PlacementMode.IMMEDIATE else PlacementMode.PREVIEW
        val hit = player.pick(SURFACE_TARGET_DISTANCE, 1.0f, false)
        val target = if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            hit.blockPos.relative(hit.direction)
        } else {
            BlockPos.containing(player.eyePosition.add(player.lookAngle.scale(5.0)))
        }
        val request = UseState.Loading(player, level, reference, mode, target, repeat != null)
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
                    var repeatAllowed = false
                    try {
                        if (client.player !== player || client.level !== level || !player.isCreative ||
                            !Axiom613Adapter.isAvailable() || Axiom613Adapter.libraryRoot() != root ||
                            !holdsReference(player, reference) || (request.repeated && !repeatHeld(client))
                        ) return@completion
                        if (request.repeated && !Axiom613Adapter.canRepeatPlacement()) {
                            repeatAllowed = true
                            return@completion
                        }
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
                                    repeatAllowed = placed && request.mode == PlacementMode.IMMEDIATE
                                    if (placed) {
                                        message(if (request.mode == PlacementMode.IMMEDIATE) "Placed ${reference.name}." else reference.name)
                                    } else {
                                        message("Axiom could not place this blueprint. Check builder permissions and that it contains blocks.")
                                    }
                                }
                            },
                            onFailure = { error ->
                                LOGGER.warn("Unable to load blueprint {}", reference.path, error)
                                message("Cannot load ${reference.name}. ${BlueprintLoadFailure.classify(error).description}")
                            },
                        )
                    } finally {
                        if (useState === request) finishLoad(request, repeatAllowed)
                    }
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
        val repeatReference = when (state) {
            is UseState.RepeatReady -> state.reference
            is UseState.Loading -> state.reference.takeIf { state.repeated }
            is UseState.AwaitingRelease -> null
        }
        if (client.player !== state.player || client.level !== state.level ||
            !state.player.isCreative || !Axiom613Adapter.isAvailable()
        ) {
            invalidate()
        } else if (repeatReference != null && (!repeatHeld(client) || !holdsReference(state.player, repeatReference))) {
            awaitRelease(state.player, state.level)
            worker.queue.clear()
        } else if (state is UseState.AwaitingRelease && !client.options.keyUse.isDown) {
            useState = null
        }
    }

    private fun finishLoad(request: UseState.Loading, repeatAllowed: Boolean = false) {
        if (repeatAllowed && repeatHeld(Minecraft.getInstance())) {
            useState = UseState.RepeatReady(request.player, request.level, request.reference)
        } else {
            awaitRelease(request.player, request.level)
        }
    }

    private fun awaitRelease(player: LocalPlayer, level: ClientLevel) {
        useState = if (Minecraft.getInstance().options.keyUse.isDown) {
            UseState.AwaitingRelease(player, level)
        } else {
            null
        }
    }

    private fun repeatHeld(client: Minecraft): Boolean = client.options.keyUse.isDown &&
        client.options.keyShift.isDown && client.gui.screen() == null

    private fun holdsReference(player: LocalPlayer, reference: BlueprintReference): Boolean =
        InteractionHand.entries.any { BlueprintItems.reference(player.getItemInHand(it)) == reference }

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
