package dev.markectomy.blueprints.client.mixin;

import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings;
import dev.markectomy.blueprints.client.render.BlueprintThumbnailRenderer;
import dev.markectomy.blueprints.domain.PreviewStyle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpecialModelWrapper.class)
abstract class BlueprintAnimationMixin {
    @Shadow @Final private SpecialModelRenderer<?> specialRenderer;

    @Inject(method = "update", at = @At("TAIL"))
    private void blueprints$animatePreview(
            ItemStackRenderState state,
            ItemStack stack,
            ItemModelResolver resolver,
            ItemDisplayContext context,
            ClientLevel level,
            ItemOwner owner,
            int seed,
            CallbackInfo callback
    ) {
        if (specialRenderer instanceof BlueprintThumbnailRenderer
                && BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle() == PreviewStyle.HIGH_QUALITY) {
            state.setAnimated();
        }
    }
}
