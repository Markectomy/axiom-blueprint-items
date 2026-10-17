package dev.markectomy.blueprints.gametest;

import dev.markectomy.blueprints.BlueprintItems;
import dev.markectomy.blueprints.domain.BlueprintReference;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;

/** Checks portable item payloads with Minecraft's actual item and component registries. */
public final class BlueprintPaperChecks {
    private BlueprintPaperChecks() {}

    public static void run(ClientGameTestContext context, BlueprintReference reference) {
        context.runOnClient(client -> {
            BlueprintItems items = BlueprintItems.INSTANCE;
            ItemStack paper = items.createStack(reference);
            require(paper.is(Items.PAPER), "New blueprint link is not vanilla paper");
            require(paper.getCount() == 1 && paper.getMaxStackSize() == 1,
                "Blueprint paper does not have a single-item stack limit");
            require(reference.equals(items.reference(paper)), "Paper lost its blueprint reference");
            require(paper.getItemName().getString().equals(reference.getName()),
                "Paper lost its fallback blueprint name");
            require(!paper.hasNonDefault(DataComponents.ITEM_MODEL),
                "Paper adds a custom item-model component to the remote payload");
            require(paper.getComponents().keySet().stream().allMatch(type ->
                BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).getNamespace().equals("minecraft")),
                "Paper contains a non-vanilla data component");
            require(items.portableStack(paper) == paper, "Safe paper was needlessly rewritten");
            require(items.portableStack(ItemStack.EMPTY) == ItemStack.EMPTY, "Empty packet stack was rejected");

            ItemStack legacy = new ItemStack(items.getITEM());
            legacy.set(items.getREFERENCE(), reference);
            ItemStack converted = items.portableStack(legacy);
            require(converted != null && converted.is(Items.PAPER) && converted.getCount() == legacy.getCount()
                    && reference.equals(items.reference(converted)),
                "Legacy blueprint was not converted to a portable paper link");
            require(legacy.is(items.getITEM()) && reference.equals(legacy.get(items.getREFERENCE())),
                "Packet conversion changed the original inventory item");
            require(converted != legacy && !converted.has(items.getREFERENCE()),
                "Converted paper retained its legacy component");
            require(items.portableStack(new ItemStack(items.getITEM())) == null,
                "Legacy item without a reference escaped into a remote packet");
            require(items.portableStack(legacy.copyWithCount(2)) == null,
                "Invalid legacy stack count escaped into a remote packet");

            ItemStack container = new ItemStack(Items.SHULKER_BOX);
            container.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(legacy)));
            require(items.portableStack(container) == null, "Container leaked a nested legacy blueprint");
            ItemStack bundle = new ItemStack(Items.BUNDLE);
            bundle.set(DataComponents.BUNDLE_CONTENTS,
                new BundleContents(List.of(ItemStackTemplate.fromNonEmptyStack(legacy))));
            require(items.portableStack(bundle) == null, "Bundle leaked a nested legacy blueprint");
            ItemStack crossbow = new ItemStack(Items.CROSSBOW);
            crossbow.set(DataComponents.CHARGED_PROJECTILES,
                ChargedProjectiles.of(ItemStackTemplate.fromNonEmptyStack(legacy)));
            require(items.portableStack(crossbow) == null, "Crossbow leaked a nested legacy blueprint");
            container.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(paper)));
            require(items.portableStack(container) == container, "Container rejected a portable paper link");

            ItemStack attached = paper.copy();
            attached.set(items.getREFERENCE(), reference);
            require(items.portableStack(attached) == null,
                "Vanilla item carrying the legacy component escaped into a remote packet");
            ItemStackTemplate removed = new ItemStackTemplate(Items.PAPER,
                DataComponentPatch.builder().remove(items.getREFERENCE()).build());
            bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(removed)));
            require(items.portableStack(bundle) == null,
                "Nested removed legacy-component ID escaped into a remote packet");

            ItemStack stone = new ItemStack(Items.STONE, 32);
            require(items.portableStack(stone) == stone, "Unrelated vanilla stack was rewritten");
            CustomData data = paper.get(DataComponents.CUSTOM_DATA);
            require(data != null, "Paper has no vanilla custom-data payload");
            paper.set(DataComponents.CUSTOM_DATA, data.update(root ->
                root.getCompoundOrEmpty(BlueprintItems.MOD_ID).putString("path", "../escape.bp")));
            require(items.reference(paper) == null, "Malformed paper reference was accepted");
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
