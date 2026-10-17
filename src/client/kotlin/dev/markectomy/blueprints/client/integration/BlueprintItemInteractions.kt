package dev.markectomy.blueprints.client.integration

import dev.markectomy.blueprints.BlueprintItems
import net.fabricmc.fabric.api.event.player.ItemEvents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult

object BlueprintItemInteractions {
    private var initialized = false

    fun initialize() {
        if (initialized) return
        ItemEvents.USE.register { level, player, hand ->
            if (!level.isClientSide) return@register null
            val reference = BlueprintItems.reference(player.getItemInHand(hand)) ?: return@register null
            if (!player.isCreative) {
                player.sendOverlayMessage(Component.literal("Blueprint items require creative mode."))
                return@register InteractionResult.FAIL
            }
            BlueprintItems.useHandler?.invoke(reference)
            InteractionResult.SUCCESS
        }
        initialized = true
    }
}
