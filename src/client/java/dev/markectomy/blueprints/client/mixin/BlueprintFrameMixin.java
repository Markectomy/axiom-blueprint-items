package dev.markectomy.blueprints.client.mixin;

import dev.markectomy.blueprints.client.render.HighQualityPreviews;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class BlueprintFrameMixin {
    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void blueprints$beginFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        HighQualityPreviews.INSTANCE.beginFrame();
    }
}
