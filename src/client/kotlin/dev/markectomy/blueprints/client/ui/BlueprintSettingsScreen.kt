package dev.markectomy.blueprints.client.ui

import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings
import dev.markectomy.blueprints.domain.PreviewStyle
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence

class BlueprintSettingsScreen(private val parent: Screen?) :
    Screen(Component.translatable("config.axiom_blueprint_items.title")) {
    private var descriptions: Map<PreviewStyle, List<FormattedCharSequence>> = emptyMap()
    private var saveError: List<FormattedCharSequence> = emptyList()
    private var titleY = 0
    private var descriptionY = 0
    private var statusY = 0
    private var retryButton: Button? = null

    override fun init() {
        val contentWidth = (width - 24).coerceIn(20, 240)
        val left = (width - contentWidth) / 2
        descriptions = PreviewStyle.entries.associateWith { style ->
            font.split(Component.translatable("config.axiom_blueprint_items.${style.serializedName}.description"), contentWidth)
        }
        saveError = font.split(Component.translatable("config.axiom_blueprint_items.save_failed"), contentWidth)
        val descriptionHeight = descriptions.values.maxOf { it.size } * font.lineHeight
        val statusHeight = maxOf(saveError.size, 1) * font.lineHeight
        val contentHeight = font.lineHeight + 14 + 20 + 10 + descriptionHeight + 12 + statusHeight + 8 + 20 + 6 + 20
        titleY = ((height - contentHeight) / 2).coerceAtLeast(6)
        val modeY = titleY + font.lineHeight + 14
        descriptionY = modeY + 30
        statusY = descriptionY + descriptionHeight + 12
        val retryY = statusY + statusHeight + 8

        addRenderableWidget(
            CycleButton.builder<PreviewStyle>(
                { style -> Component.translatable("config.axiom_blueprint_items.${style.serializedName}") },
                BlueprintPreviewSettings.current.style
            ).withValues(listOf(PreviewStyle.HIGH_QUALITY, PreviewStyle.LIGHTWEIGHT, PreviewStyle.STATIC))
                .create(left, modeY, contentWidth, 20, Component.translatable("config.axiom_blueprint_items.mode")) { _, style ->
                    BlueprintPreviewSettings.setStyle(style)
                }
        )
        retryButton = addRenderableWidget(
            Button.builder(Component.translatable("config.axiom_blueprint_items.retry")) {
                BlueprintPreviewSettings.retrySave()
            }.bounds(left, retryY, contentWidth, 20).build()
        ).apply {
            visible = BlueprintPreviewSettings.saveFailed
            active = visible
        }
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(left, retryY + 26, contentWidth, 20).build()
        )
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        graphics.fill(0, 0, width, height, 0xC0101010.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val failed = BlueprintPreviewSettings.saveFailed
        retryButton?.apply {
            visible = failed
            active = failed && !BlueprintPreviewSettings.saving
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        graphics.centeredText(font, title, width / 2, titleY, -1)
        val description = descriptions.getValue(BlueprintPreviewSettings.current.style)
        description.forEachIndexed { index, line ->
            graphics.centeredText(font, line, width / 2, descriptionY + index * font.lineHeight, 0xFFCCCCCC.toInt())
        }
        if (failed) {
            saveError.forEachIndexed { index, line ->
                graphics.centeredText(font, line, width / 2, statusY + index * font.lineHeight, 0xFFFF8080.toInt())
            }
        } else if (BlueprintPreviewSettings.saving) {
            graphics.centeredText(
                font, Component.translatable("config.axiom_blueprint_items.saving"), width / 2, statusY, 0xFFCCCCCC.toInt()
            )
        }
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }
}
