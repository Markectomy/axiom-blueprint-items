package dev.markectomy.blueprints.client.mixin;

import dev.markectomy.blueprints.client.ui.BlueprintCreativeUi;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CreativeModeInventoryScreen.class)
abstract class CreativeScreenMixin {
    @Shadow private boolean hasClickedOutside;

    @Unique
    private CreativeModeInventoryScreen blueprints$self() {
        return (CreativeModeInventoryScreen) (Object) this;
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void blueprints$rebuilding(CallbackInfo callback) {
        BlueprintCreativeUi.INSTANCE.initializing(blueprints$self());
    }

    @Inject(method = "tryRefreshInvalidatedTabs", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/CreativeModeTabs;allTabs()Ljava/util/List;"), cancellable = true)
    private void blueprints$ownLibraryRefresh(FeatureFlagSet features, boolean permissions, HolderLookup.Provider holders, CallbackInfo callback) {
        if (BlueprintCreativeUi.INSTANCE.isLibraryTab()) {
            // Keep vanilla rebuilds, but retain the catalog populated from our library snapshot.
            var tabs = (FabricCreativeModeInventoryScreen) blueprints$self();
            tabs.switchToPage(tabs.getPage(tabs.getSelectedTab()));
            callback.cancel();
        }
    }

    @Inject(method = "selectTab", at = @At("TAIL"))
    private void blueprints$tabSelected(CreativeModeTab tab, CallbackInfo callback) {
        BlueprintCreativeUi.INSTANCE.selected(blueprints$self());
    }

    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void blueprints$insideSidebar(double x, double y, int left, int top, CallbackInfoReturnable<Boolean> callback) {
        if (BlueprintCreativeUi.INSTANCE.contains(blueprints$self(), x, y)) {
            hasClickedOutside = false;
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void blueprints$scrollTags(double x, double y, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> callback) {
        if (BlueprintCreativeUi.INSTANCE.scroll(blueprints$self(), x, y, scrollY)) callback.setReturnValue(true);
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void blueprints$typeSearch(CharacterEvent event, CallbackInfoReturnable<Boolean> callback) {
        if (BlueprintCreativeUi.INSTANCE.searchFocused(blueprints$self())) {
            callback.setReturnValue(BlueprintCreativeUi.INSTANCE.character(blueprints$self(), event));
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void blueprints$searchKeys(KeyEvent event, CallbackInfoReturnable<Boolean> callback) {
        if (!event.isEscape() && BlueprintCreativeUi.INSTANCE.searchFocused(blueprints$self())) {
            BlueprintCreativeUi.INSTANCE.key(blueprints$self(), event);
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "preeditUpdated", at = @At("HEAD"), cancellable = true)
    private void blueprints$searchPreedit(PreeditEvent event, CallbackInfoReturnable<Boolean> callback) {
        if (BlueprintCreativeUi.INSTANCE.searchFocused(blueprints$self())) {
            callback.setReturnValue(BlueprintCreativeUi.INSTANCE.preedit(blueprints$self(), event));
        }
    }
}
