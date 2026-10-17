package dev.markectomy.blueprints.client.ui

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings
import dev.markectomy.blueprints.client.render.HighQualityPreviews
import dev.markectomy.blueprints.client.render.BlueprintThumbnails
import dev.markectomy.blueprints.domain.BlueprintPreviewTooltip
import dev.markectomy.blueprints.domain.BlueprintReference
import dev.markectomy.blueprints.domain.PreviewStyle
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

object BlueprintHoverPreview {
    private val paper = Identifier.withDefaultNamespace("textures/item/paper.png")
    private var initialized = false

    fun initialize() {
        if (initialized) return
        ClientTooltipComponentCallback.EVENT.register { tooltip ->
            if (tooltip is BlueprintPreviewTooltip) PreviewComponent(tooltip.reference) else null
        }
        BlueprintItems.tooltipImage = { reference ->
            if (BlueprintPreviewSettings.current.style == PreviewStyle.HIGH_QUALITY) BlueprintPreviewTooltip(reference) else null
        }
        initialized = true
    }

    private class PreviewComponent(private val reference: BlueprintReference) : ClientTooltipComponent {
        override fun getHeight(font: Font): Int = size(font) + font.lineHeight + 8

        override fun getWidth(font: Font): Int = size(font)

        override fun extractImage(font: Font, x: Int, y: Int, width: Int, height: Int, graphics: GuiGraphicsExtractor) {
            val view = BlueprintThumbnails.view(reference)
            val image = if (view.highQuality || view.columns == 1) view.texture ?: paper else paper
            val texture = Minecraft.getInstance().textureManager.getTexture(image)
            val size = size(font)
            val left = x + (width - size) / 2
            val top = y + 2
            graphics.blit(
                texture.textureView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                left, top, left + size, top + size, 0f, 1f, if (view.highQuality) 1f else 0f, if (view.highQuality) 0f else 1f
            )
            if (!view.highQuality) {
                val key = if (HighQualityPreviews.unavailable(reference)) "unavailable" else "loading"
                graphics.centeredText(font, Component.translatable("tooltip.axiom_blueprint_items.preview.$key"),
                    left + size / 2, top + size + 3, 0xFFBBBBBB.toInt())
            }
        }

        private fun size(font: Font): Int {
            val window = Minecraft.getInstance().window
            return minOf(
                128,
                (window.guiScaledWidth - 16).coerceAtLeast(1),
                (window.guiScaledHeight - font.lineHeight * 4 - 20).coerceAtLeast(1)
            )
        }
    }
}
