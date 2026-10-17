package dev.markectomy.blueprints.client.mixin;

import dev.markectomy.blueprints.BlueprintItems;
import dev.markectomy.blueprints.domain.BlueprintReference;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ItemStack.class)
abstract class BlueprintStackMixin {
    @Inject(method = "getItemName", at = @At("HEAD"), cancellable = true)
    private void blueprints$libraryName(CallbackInfoReturnable<Component> callback) {
        BlueprintReference reference = BlueprintItems.INSTANCE.reference((ItemStack) (Object) this);
        if (reference == null) return;
        String name = BlueprintItems.INSTANCE.getNameResolver().invoke(reference);
        callback.setReturnValue(Component.literal(name == null ? reference.getName() : name));
    }

    @Inject(method = "getTooltipImage", at = @At("HEAD"), cancellable = true)
    private void blueprints$libraryPreview(CallbackInfoReturnable<Optional<TooltipComponent>> callback) {
        BlueprintReference reference = BlueprintItems.INSTANCE.reference((ItemStack) (Object) this);
        if (reference != null) {
            callback.setReturnValue(Optional.ofNullable(BlueprintItems.INSTANCE.getTooltipImage().invoke(reference)));
        }
    }
}
