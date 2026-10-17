package dev.markectomy.blueprints.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.markectomy.blueprints.BlueprintItems;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemModelResolver.class)
abstract class BlueprintItemModelMixin {
    @Unique
    private static final Identifier BLUEPRINTS_MODEL = Identifier.fromNamespaceAndPath("axiom_blueprint_items", "blueprint");

    @ModifyExpressionValue(
            method = {"appendItemLayers", "shouldPlaySwapAnimation", "swapAnimationScale"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;")
    )
    private Object blueprints$localModel(Object original, @Local(argsOnly = true) ItemStack stack) {
        return BlueprintItems.INSTANCE.reference(stack) == null ? original : BLUEPRINTS_MODEL;
    }
}
