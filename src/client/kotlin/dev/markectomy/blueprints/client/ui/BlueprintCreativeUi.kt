package dev.markectomy.blueprints.client.ui

import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings
import dev.markectomy.blueprints.client.library.BlueprintLibrary
import dev.markectomy.blueprints.client.mixin.ContainerScreenAccess
import dev.markectomy.blueprints.client.mixin.CreativeScreenAccess
import dev.markectomy.blueprints.domain.BlueprintFilters
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.network.chat.Component
import kotlin.math.sign

/** Inventory and cursor operations remain vanilla. */
object BlueprintCreativeUi {
    private var controller: Sidebar? = null
    private var query = ""
    private val selectedTags = linkedSetOf<String>()

    fun initialize() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is CreativeModeInventoryScreen) {
                val sidebar = Sidebar(screen)
                controller = sidebar
                sidebar.updateSelection()
                ScreenEvents.afterTick(screen).register { if (controller === sidebar) sidebar.tick() }
                ScreenEvents.afterBackground(screen).register { _, graphics, mouseX, mouseY, _ ->
                    if (controller === sidebar) sidebar.draw(graphics, mouseX, mouseY)
                }
                ScreenEvents.remove(screen).register { if (controller === sidebar) controller = null }
            }
        }
    }

    fun isLibraryTab(): Boolean = CreativeScreenAccess.`blueprints$getSelectedTab`() === BlueprintItems.TAB

    fun initializing(screen: CreativeModeInventoryScreen) {
        // Resize reuses the screen; discard the sidebar before widgets rebuild.
        if (controller?.screen === screen) controller = null
    }

    fun selected(screen: CreativeModeInventoryScreen) { forScreen(screen)?.updateSelection() }
    fun contains(screen: CreativeModeInventoryScreen, x: Double, y: Double): Boolean = forScreen(screen)?.contains(x, y) == true
    fun scroll(screen: CreativeModeInventoryScreen, x: Double, y: Double, amount: Double): Boolean =
        forScreen(screen)?.scroll(x, y, amount) == true
    fun searchFocused(screen: CreativeModeInventoryScreen): Boolean = forScreen(screen)?.searchFocused() == true
    fun character(screen: CreativeModeInventoryScreen, event: CharacterEvent): Boolean = forScreen(screen)?.search?.charTyped(event) == true
    fun key(screen: CreativeModeInventoryScreen, event: KeyEvent) { forScreen(screen)?.search?.keyPressed(event) }
    fun preedit(screen: CreativeModeInventoryScreen, event: PreeditEvent?): Boolean = forScreen(screen)?.search?.preeditUpdated(event) == true

    private fun forScreen(screen: CreativeModeInventoryScreen): Sidebar? = controller?.takeIf { it.screen === screen }

    private class Sidebar(val screen: CreativeModeInventoryScreen) {
        private val access = screen as ContainerScreenAccess
        private val font = screen.font
        private val anchoredWidgets = Screens.getWidgets(screen).toList()
        private val widgets = mutableListOf<AbstractWidget>()
        private var active = false
        private var revision = Long.MIN_VALUE
        private var tagOffset = 0
        private var tags: List<String> = emptyList()
        private var matched = 0
        private var panelX = 0
        private var panelY = 0
        val search = EditBox(font, 0, 0, 104, 16, Component.literal("Search blueprints"))
        private val clear: Button
        private val refresh: Button
        private val tagButtons: List<Button>
        private var airOnly = airOnlyCheckbox()

        init {
            search.setMaxLength(100)
            search.setHint(Component.literal("Search name…"))
            search.value = query
            search.setResponder { value ->
                query = value
                if (active) updateItems()
            }
            widgets.add(search)
            clear = button("Clear", 50, 16) {
                selectedTags.clear()
                tagOffset = 0
                search.value = ""
                updateItems()
                updateTagButtons()
            }
            clear.setTooltip(Tooltip.create(Component.literal("Clear name search and all selected tags")))
            refresh = button("Refresh", 50, 16) { BlueprintLibrary.refresh() }
            refresh.setTooltip(Tooltip.create(Component.literal("Reload your saved Axiom blueprints")))
            widgets.add(airOnly)
            tagButtons = List(TAG_ROWS) { row ->
                button("", 104, 14) {
                    tags.getOrNull(tagOffset + row)?.let { tag ->
                        if (!selectedTags.add(tag)) selectedTags.remove(tag)
                        updateItems()
                        updateTagButtons()
                    }
                }
            }
            Screens.getWidgets(screen).addAll(widgets)
        }

        private fun button(label: String, width: Int, height: Int, action: () -> Unit): Button =
            Button.builder(Component.literal(label)) { action() }.bounds(0, 0, width, height).build().also { widgets.add(it) }

        private fun airOnlyCheckbox(): Checkbox = Checkbox.builder(
            Component.translatable("config.axiom_blueprint_items.air_only"), font
        ).maxWidth(104)
            .selected(BlueprintPreviewSettings.current.airOnly)
            .onValueChange { _, enabled -> BlueprintPreviewSettings.setAirOnly(enabled) }
            .tooltip(Tooltip.create(Component.translatable("config.axiom_blueprint_items.air_only.description")))
            .build()

        private fun updateAirOnly() {
            if (airOnly.selected() == BlueprintPreviewSettings.current.airOnly) return
            val previous = airOnly
            airOnly = airOnlyCheckbox().apply {
                setPosition(previous.x, previous.y)
                visible = previous.visible
                active = previous.active
            }
            widgets[widgets.indexOf(previous)] = airOnly
            val screenWidgets = Screens.getWidgets(screen)
            screenWidgets[screenWidgets.indexOf(previous)] = airOnly
            if (screen.focused === previous) screen.setFocused(airOnly)
        }

        fun updateSelection() {
            val wasActive = active
            active = isLibraryTab()
            // Center the complete 317-pixel surface on the smallest supported GUI width (320).
            val oldLeft = access.`blueprints$getLeft`()
            val newLeft = if (active) ((screen.width - TOTAL_WIDTH) / 2).coerceAtLeast(1) else (screen.width - 195) / 2
            access.`blueprints$setLeft`(newLeft)
            // Init positions Fabric's page arrows and Axiom's tabs relative to the canvas.
            val delta = newLeft - oldLeft
            anchoredWidgets.forEach { it.setX(it.x + delta) }
            panelX = access.`blueprints$getLeft`() + 201
            panelY = access.`blueprints$getTop`()
            search.setX(panelX + 6)
            search.setY(panelY + 20)
            clear.setX(panelX + 6)
            clear.setY(panelY + 40)
            refresh.setX(panelX + 60)
            refresh.setY(panelY + 40)
            airOnly.setX(panelX + 6)
            airOnly.setY(panelY + 60)
            tagButtons.forEachIndexed { row, button ->
                button.setX(panelX + 6)
                button.setY(panelY + 92 + row * 15)
            }
            widgets.forEach { it.visible = active; it.active = active }
            if (!active) search.isFocused = false
            if (active) {
                revision = Long.MIN_VALUE
                tick()
                if (!wasActive) BlueprintLibrary.refresh()
            }
        }

        fun tick() {
            if (!active) return
            updateAirOnly()
            val snapshot = BlueprintLibrary.snapshot
            refresh.active = !snapshot.loading
            if (snapshot.revision == revision) return
            revision = snapshot.revision
            tags = (snapshot.entries.flatMap { it.tags } + selectedTags).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            tagOffset = tagOffset.coerceIn(0, (tags.size - TAG_ROWS).coerceAtLeast(0))
            updateTagButtons()
            updateItems()
        }

        private fun updateItems() {
            if (!active) return
            val items = BlueprintLibrary.snapshot.entries.asSequence()
                .filter { BlueprintFilters.matches(it, query, selectedTags) }
                .map { BlueprintItems.createStack(it.reference) }.toList()
            matched = items.size
            (screen as CreativeScreenAccess).`blueprints$refreshItems`(items)
        }

        private fun updateTagButtons() {
            tagButtons.forEachIndexed { row, button ->
                val tag = tags.getOrNull(tagOffset + row)
                button.visible = active && tag != null
                button.active = active && tag != null
                if (tag != null) {
                    val prefix = if (tag in selectedTags) "[x] " else "[ ] "
                    button.message = Component.literal(prefix + font.plainSubstrByWidth(tag, 80))
                    button.setTooltip(Tooltip.create(Component.literal("$tag — match all selected tags")))
                }
            }
        }

        fun contains(x: Double, y: Double): Boolean = active && x >= panelX && x < panelX + PANEL_WIDTH && y >= panelY && y < panelY + PANEL_HEIGHT
        fun searchFocused(): Boolean = active && search.isFocused

        fun scroll(x: Double, y: Double, amount: Double): Boolean {
            if (!contains(x, y)) return false
            if (y >= panelY + 88 && y < panelY + 139 && amount != 0.0) {
                tagOffset = (tagOffset - amount.sign.toInt()).coerceIn(0, (tags.size - TAG_ROWS).coerceAtLeast(0))
                updateTagButtons()
            }
            return true
        }

        private fun drawInventoryPanel(graphics: GuiGraphicsExtractor) {
            val border = 4
            val sourceX = intArrayOf(0, border, 176 - border)
            val sourceY = intArrayOf(0, border, 166 - border)
            val sourceWidths = intArrayOf(border, 176 - border * 2, border)
            val sourceHeights = intArrayOf(border, 166 - border * 2, border)
            val targetWidths = intArrayOf(border, PANEL_WIDTH - border * 2, border)
            val targetHeights = intArrayOf(border, PANEL_HEIGHT - border * 2, border)
            var y = panelY
            for (row in 0..2) {
                var x = panelX
                for (column in 0..2) {
                    // The center must use the plain margin; the inventory center contains slots.
                    val u = if (row == 1 && column == 1) 4 else sourceX[column]
                    val v = if (row == 1 && column == 1) 4 else sourceY[row]
                    val sourceWidth = if (row == 1 && column == 1) 1 else sourceWidths[column]
                    val sourceHeight = if (row == 1 && column == 1) 1 else sourceHeights[row]
                    graphics.blit(
                        RenderPipelines.GUI_TEXTURED, INVENTORY_TEXTURE, x, y,
                        u.toFloat(), v.toFloat(), targetWidths[column], targetHeights[row],
                        sourceWidth, sourceHeight, 256, 256,
                    )
                    x += targetWidths[column]
                }
                y += targetHeights[row]
            }
        }

        fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
            if (!active) return
            drawInventoryPanel(graphics)
            graphics.text(font, "Filter library", panelX + 6, panelY + 7, TEXT_COLOR, true)
            val tagLabel = if (tags.size > TAG_ROWS) "Tags · ${tagOffset + 1}–${(tagOffset + TAG_ROWS).coerceAtMost(tags.size)}/${tags.size}" else "Tags · match all"
            graphics.text(font, font.plainSubstrByWidth(tagLabel, 104), panelX + 6, panelY + 81, TEXT_COLOR, true)
            if (tags.isEmpty()) graphics.text(font, "No saved tags", panelX + 6, panelY + 97, TEXT_COLOR, true)
            if (tags.size > TAG_ROWS) {
                val trackX = panelX + 112
                val thumbY = panelY + 92 + (tagOffset * 33 / (tags.size - TAG_ROWS))
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, trackX, thumbY, 2, 10)
            }
            val snapshot = BlueprintLibrary.snapshot
            val status = when {
                snapshot.loading -> "Loading…"
                snapshot.error != null -> "Library unavailable"
                snapshot.entries.isEmpty() -> "No blueprints saved"
                matched == 0 -> "No matches"
                else -> "$matched / ${snapshot.entries.size} blueprints"
            }
            val footer = if (snapshot.errorCount > 0 && !snapshot.loading && snapshot.error == null) {
                "$matched / ${snapshot.entries.size} · ${snapshot.errorCount} skipped"
            } else status
            graphics.text(font, font.plainSubstrByWidth(footer, 104), panelX + 6, panelY + 143, TEXT_COLOR, true)
            if (contains(mouseX.toDouble(), mouseY.toDouble()) && mouseY >= panelY + 141) {
                val detail = snapshot.error ?: if (snapshot.errorCount > 0) "$status. ${snapshot.errorCount} unreadable files skipped; check the client log." else status
                graphics.setTooltipForNextFrame(font, Component.literal(detail), mouseX, mouseY)
            }
        }
    }

    private val INVENTORY_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/inventory.png")
    private val SCROLLER = Identifier.withDefaultNamespace("container/creative_inventory/scroller")
    private const val TEXT_COLOR = -1
    private const val PANEL_WIDTH = 116
    private const val PANEL_HEIGHT = 156
    private const val TOTAL_WIDTH = 317
    private const val TAG_ROWS = 3
}
