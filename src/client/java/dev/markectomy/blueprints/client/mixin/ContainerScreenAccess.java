package dev.markectomy.blueprints.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {
    @Accessor("leftPos")
    int blueprints$getLeft();

    @Accessor("leftPos")
    void blueprints$setLeft(int value);

    @Accessor("topPos")
    int blueprints$getTop();
}
