package dev.markectomy.blueprints.client

import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings
import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.service.BlueprintPlacementService
import dev.markectomy.blueprints.client.library.BlueprintLibrary
import dev.markectomy.blueprints.client.render.BlueprintThumbnails
import dev.markectomy.blueprints.client.ui.BlueprintHoverPreview
import dev.markectomy.blueprints.client.ui.BlueprintCreativeUi
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import dev.markectomy.blueprints.client.integration.Axiom613Adapter
import dev.markectomy.blueprints.client.integration.BlueprintItemInteractions
import dev.markectomy.blueprints.client.input.BlueprintPlacementControls
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.PreparableReloadListener

object BlueprintItemsClient : ClientModInitializer {
    val placement = BlueprintPlacementService()

    override fun onInitializeClient() {
        BlueprintItems.useHandler = placement::use
        BlueprintItems.libraryItems = { BlueprintLibrary.snapshot.entries.map { it.reference } }
        BlueprintItems.nameResolver = { BlueprintLibrary.name(it.path) }
        BlueprintItemInteractions.initialize()
        BlueprintPreviewSettings.initialize()
        BlueprintPlacementControls.initialize()
        BlueprintThumbnails.initialize(BlueprintPreviewSettings.current.style)
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            BlueprintItems.id("thumbnail_cache"),
            object : SimpleReloadListener<Unit>() {
                override fun prepare(state: PreparableReloadListener.SharedState) = Unit
                override fun apply(value: Unit, state: PreparableReloadListener.SharedState) {
                    BlueprintThumbnails.invalidate()
                }
            },
        )
        BlueprintLibrary.onRefresh = BlueprintThumbnails::refresh
        BlueprintHoverPreview.initialize()
        BlueprintCreativeUi.initialize()
        ClientTickEvents.START_CLIENT_TICK.register { placement.tick() }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            BlueprintPlacementControls.tick(client)
            Axiom613Adapter.inputTick()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            placement.invalidate()
            Axiom613Adapter.clearImportedPreview()
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            BlueprintPreviewSettings.close()
            placement.close()
            Axiom613Adapter.clearImportedPreview()
            BlueprintLibrary.close()
            BlueprintThumbnails.close()
        }
    }
}
