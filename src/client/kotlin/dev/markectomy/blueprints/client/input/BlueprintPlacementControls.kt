package dev.markectomy.blueprints.client.input

import com.mojang.blaze3d.platform.InputConstants
import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

object BlueprintPlacementControls {
    private lateinit var toggleAirOnly: KeyMapping

    fun initialize() {
        val category = KeyMapping.Category.register(BlueprintItems.id("controls"))
        toggleAirOnly = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.axiom_blueprint_items.toggle_air_only", InputConstants.UNKNOWN.value, category)
        )
    }

    fun tick(client: Minecraft) {
        while (toggleAirOnly.consumeClick()) {
            val player = client.player ?: continue
            if (client.gui.screen() != null) continue
            BlueprintPreviewSettings.toggleAirOnly()
            val state = if (BlueprintPreviewSettings.current.airOnly) "enabled" else "disabled"
            player.sendOverlayMessage(Component.translatable("placement.axiom_blueprint_items.air_only_$state"))
        }
    }
}
