package dev.markectomy.blueprints.client.mixin;

import java.util.Collection;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeScreenAccess {
    @Accessor("selectedTab")
    static CreativeModeTab blueprints$getSelectedTab() {
        throw new AssertionError();
    }

    @Invoker("refreshCurrentTabContents")
    void blueprints$refreshItems(Collection<ItemStack> items);
}
