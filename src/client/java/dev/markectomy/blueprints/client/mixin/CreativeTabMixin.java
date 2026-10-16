package dev.markectomy.blueprints.client.mixin;

import dev.markectomy.blueprints.BlueprintItems;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CreativeModeTab.class)
abstract class CreativeTabMixin {
    @Inject(method = "shouldDisplay", at = @At("HEAD"), cancellable = true)
    private void blueprints$keepLibraryVisible(CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this == BlueprintItems.INSTANCE.getTAB()) callback.setReturnValue(true);
    }
}
