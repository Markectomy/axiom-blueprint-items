package dev.markectomy.blueprints.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.markectomy.blueprints.BlueprintItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
abstract class BlueprintCreativePacketMixin {
    @Shadow @Final private Minecraft minecraft;

    @WrapOperation(
            method = {"handleCreativeModeItemAdd", "handleCreativeModeItemDrop"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V")
    )
    private void blueprints$portableCreativeStack(ClientPacketListener connection, Packet<?> packet, Operation<Void> original) {
        if (minecraft.isLocalServer() || !(packet instanceof ServerboundSetCreativeModeSlotPacket creative)) {
            original.call(connection, packet);
            return;
        }
        ItemStack portable = BlueprintItems.INSTANCE.portableStack(creative.itemStack());
        if (portable == null) {
            if (minecraft.player != null) {
                minecraft.player.sendOverlayMessage(Component.literal("This item contains an old blueprint item. Take a fresh blueprint from the Blueprints tab."));
            }
            return;
        }
        if (portable != creative.itemStack() && minecraft.player != null
                && creative.slotNum() >= 0 && creative.slotNum() < minecraft.player.inventoryMenu.slots.size()) {
            var slot = minecraft.player.inventoryMenu.getSlot(creative.slotNum());
            // Accepted creative writes are not echoed back to the client.
            if (ItemStack.isSameItemSameComponents(slot.getItem(), creative.itemStack())) slot.set(portable.copy());
        }
        original.call(connection, portable == creative.itemStack()
                ? packet : new ServerboundSetCreativeModeSlotPacket(creative.slotNum(), portable));
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void blueprints$useLocally(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callback) {
        blueprints$useBlueprint(player, hand, callback);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void blueprints$useOnBlockLocally(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> callback) {
        blueprints$useBlueprint(player, hand, callback);
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void blueprints$useOnEntityLocally(Player player, Entity entity, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callback) {
        blueprints$useBlueprint(player, hand, callback);
    }

    @Unique
    private void blueprints$useBlueprint(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callback) {
        ItemStack stack = player.getItemInHand(hand);
        if (BlueprintItems.INSTANCE.reference(stack) != null) {
            callback.setReturnValue(stack.use(player.level(), player, hand));
        }
    }
}
