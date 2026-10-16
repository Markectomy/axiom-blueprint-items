package dev.markectomy.blueprints

import dev.markectomy.blueprints.component.BlueprintReferenceCodecs
import dev.markectomy.blueprints.domain.BlueprintReference
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab
import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.level.Level

object BlueprintItems : ModInitializer {
    const val MOD_ID: String = "axiom_blueprint_items"
    val REFERENCE: DataComponentType<BlueprintReference> = Registry.register(
        BuiltInRegistries.DATA_COMPONENT_TYPE,
        id("blueprint_reference"),
        DataComponentType.builder<BlueprintReference>()
            .persistent(BlueprintReferenceCodecs.CODEC)
            .networkSynchronized(BlueprintReferenceCodecs.STREAM_CODEC)
            .build(),
    )
    val ITEM: Item = Registry.register(
        BuiltInRegistries.ITEM,
        id("blueprint"),
        BlueprintItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, id("blueprint"))).stacksTo(1)),
    )

    /** Client supplies immutable library snapshots; the integrated server needs no filesystem access. */
    var libraryItems: () -> List<BlueprintReference> = { emptyList() }
    var nameResolver: (BlueprintReference) -> String? = { null }
    val TAB: CreativeModeTab = Registry.register(
        BuiltInRegistries.CREATIVE_MODE_TAB,
        id("blueprints"),
        FabricCreativeModeTab.builder()
            .title(Component.translatable("itemGroup.axiom_blueprint_items.blueprints"))
            .icon { ItemStack(ITEM) }
            .displayItems { _, output -> libraryItems().forEach { output.accept(createStack(it)) } }
            .build(),
    )

    /** Installed by the client entrypoint. Never dereferenced on the logical server. */
    var useHandler: ((BlueprintReference) -> Unit)? = null

    override fun onInitialize() = Unit

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    fun createStack(reference: BlueprintReference): ItemStack = ItemStack(ITEM).also { it.set(REFERENCE, reference) }

    fun reference(stack: ItemStack): BlueprintReference? = if (stack.item === ITEM) stack.get(REFERENCE) else null

    private class BlueprintItem(properties: Properties) : Item(properties) {
        override fun getName(stack: ItemStack): Component =
            reference(stack)?.let { Component.literal(nameResolver(it) ?: it.name) } ?: super.getName(stack)

        override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
            if (!player.isCreative) {
                if (level.isClientSide) player.sendOverlayMessage(Component.literal("Blueprint items require creative mode."))
                return InteractionResult.FAIL
            }
            if (level.isClientSide) {
                val reference = reference(player.getItemInHand(hand))
                if (reference == null) {
                    player.sendOverlayMessage(Component.literal("This blueprint item has no valid library link."))
                } else {
                    useHandler?.invoke(reference)
                }
            }
            // Consuming the interaction stops Minecraft trying the other hand; the item is untouched.
            return InteractionResult.SUCCESS
        }
    }
}
