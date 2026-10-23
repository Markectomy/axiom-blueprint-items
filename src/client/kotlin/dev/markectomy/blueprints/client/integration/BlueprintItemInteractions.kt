package dev.markectomy.blueprints.client.integration

import dev.markectomy.blueprints.BlueprintItems
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.event.player.ItemEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.InteractionResult

object BlueprintItemInteractions {
    private var initialized = false

    fun initialize() {
        if (initialized) return
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            val reference = BlueprintItems.reference(stack) ?: return@register
            val client = Minecraft.getInstance()
            val font = client.font
            val width = minOf(240, (client.window.guiScaledWidth - 24).coerceAtLeast(1))
            val source = Component.translatable("tooltip.axiom_blueprint_items.source", reference.path).withStyle(ChatFormatting.GRAY)
            if (font.width(source) <= width) {
                lines.add(source)
            } else {
                val wrapped = font.splitter.splitLines(source, width, Style.EMPTY)
                wrapped.take(3).forEachIndexed { index, line ->
                    var text = font.plainSubstrByWidth(line.string, width)
                    if (index == 2 && wrapped.size > 3) {
                        val ellipsis = font.plainSubstrByWidth("…", width)
                        text = font.plainSubstrByWidth(text, (width - font.width(ellipsis)).coerceAtLeast(0)) + ellipsis
                    }
                    lines.add(Component.literal(text).withStyle(ChatFormatting.GRAY))
                }
            }
        }
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
