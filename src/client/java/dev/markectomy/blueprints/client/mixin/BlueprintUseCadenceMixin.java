package dev.markectomy.blueprints.client.mixin;

import dev.markectomy.blueprints.client.BlueprintItemsClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, priority = 1100)
abstract class BlueprintUseCadenceMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void blueprints$waitForNextPlacement(CallbackInfo callback) {
        if (BlueprintItemsClient.INSTANCE.getPlacement().suppressRepeatedUse()) callback.cancel();
    }
}
