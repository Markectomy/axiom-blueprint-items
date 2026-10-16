package dev.markectomy.blueprints.client.mixin;

import dev.markectomy.blueprints.BlueprintItems;
import net.fabricmc.fabric.impl.creativetab.FabricCreativeModeTabImpl;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric 0.161.0 exposes pagination but no public tab-position API. */
@Mixin(CreativeModeTabs.class)
abstract class CreativeTabsLayoutMixin {
    @Inject(method = "tryRebuildTabContents", at = @At("RETURN"))
    private static void blueprints$preferAvailableFirstPageSlot(CallbackInfoReturnable<Boolean> callback) {
        // The outer return runs after Fabric's buildAllTabContents tail injection.
        if (!callback.getReturnValue()) return;
        CreativeModeTab library = BlueprintItems.INSTANCE.getTAB();
        CreativeModeTab inventory = BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(CreativeModeTabs.INVENTORY);
        int preferredColumn = inventory.column() - 1;
        if (preferredColumn < 0) return;

        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            if (tab == library || !tab.shouldDisplay()) continue;
            if (((FabricCreativeModeTabImpl) tab).fabric_getPage() == 0
                    && tab.row() == inventory.row() && tab.column() == preferredColumn) {
                // Preserve the other mod's position and Fabric's original fallback page.
                return;
            }
        }

        CreativeTabPositionAccess position = (CreativeTabPositionAccess) library;
        position.blueprints$setRow(inventory.row());
        position.blueprints$setColumn(preferredColumn);
        ((FabricCreativeModeTabImpl) library).fabric_setPage(0);
    }
}
