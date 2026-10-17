package dev.markectomy.blueprints.client.integration.mixin;

import dev.markectomy.blueprints.client.integration.BlueprintCloneBridge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.axiom.buildertools.BuilderToolManager;
import com.moulberry.axiom.buildertools.BuilderToolSelectionState;
import com.moulberry.axiom.buildertools.CloneBuilderTool;
import com.moulberry.axiom.buildertools.PendingAction;
import com.moulberry.axiom.blueprint.RawBlueprint;
import com.moulberry.axiom.clipboard.SelectionBuffer;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.utils.EntityDataUtils;
import com.moulberry.axiom.utils.IntMatrix;
import com.moulberry.axiom.utils.RegionHelper;
import com.moulberry.axiom.packets.AxiomServerboundSpawnEntity;
import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import com.moulberry.axiom.world_modification.CompressedBlockEntity;
import com.moulberry.axiom.world_modification.undo.AdditionalUndoOperation;
import com.moulberry.axiom.world_modification.undo.DeleteEntityAdditionalUndoOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CloneBuilderTool.class, remap = false)
abstract class CloneBlueprintMixin implements BlueprintCloneBridge {
    @Shadow @Final private BuilderToolSelectionState selectionState;
    @Shadow @Final private IntMatrix transformMatrix;
    @Shadow @Final private PendingAction pendingAction;
    @Shadow private boolean reclone;
    @Shadow private int pendingCopyId;
    @Shadow private BlockPos.MutableBlockPos regionOffset;
    @Shadow private SelectionBuffer selectionBuffer;
    @Shadow private ChunkedBlockRegion blockRegion;
    @Shadow private Long2ObjectMap<CompressedBlockEntity> blockEntities;
    @Unique private List<CompoundTag> blueprints$entities;
    @Unique private boolean blueprints$waitForRelease;

    @Override
    public void blueprints$start(RawBlueprint raw, ChunkedBlockRegion region, BlockPos position) {
        selectionState.resetSelection();
        selectionState.setPos1(region.min());
        selectionState.setPos2(region.max());
        transformMatrix.identity();
        pendingAction.reset();
        pendingCopyId = 0;
        reclone = false;
        selectionBuffer = new SelectionBuffer.AABB(region.min(), region.max());
        blockRegion = region;
        blockEntities = raw.blockEntities();
        regionOffset = new BlockPos.MutableBlockPos(position.getX(), position.getY(), position.getZ());
        blueprints$entities = raw.entities();
        blueprints$waitForRelease = true;
    }

    @Override
    public void blueprints$inputTick() {
        if (!Minecraft.getInstance().options.keyUse.isDown()) blueprints$waitForRelease = false;
    }

    @Override
    public boolean blueprints$isImported() {
        return blueprints$entities != null;
    }

    @Override
    public boolean blueprints$isConfirmed() {
        return blueprints$entities != null && reclone;
    }

    @Inject(method = "rightClick", at = @At("HEAD"), cancellable = true)
    private void blueprints$separateInitialUse(HitResult hit, CallbackInfo callback) {
        if (blueprints$entities != null && blueprints$waitForRelease) callback.cancel();
    }

    @ModifyVariable(method = "reset", at = @At("HEAD"), argsOnly = true)
    private boolean blueprints$neverAutoPasteOnToolSwitch(boolean applySelection) {
        return blueprints$entities == null && applySelection;
    }

    @Inject(method = "reset", at = @At("TAIL"))
    private void blueprints$clearImportedState(boolean applySelection, CallbackInfo callback) {
        blueprints$entities = null;
        blueprints$waitForRelease = false;
    }

    @Inject(method = "getSelectionRestore", at = @At("HEAD"), cancellable = true)
    private void blueprints$doNotExportVirtualSource(CallbackInfoReturnable<BuilderToolSelectionState.Restore> callback) {
        if (blueprints$entities != null) callback.setReturnValue(null);
    }

    // Virtual source bounds must not copy unrelated entities from the current world.
    @WrapOperation(method = "apply", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Entity> blueprints$savedEntitiesOnly(ClientLevel level, Entity except, AABB bounds,
                                                    Predicate<? super Entity> predicate, Operation<List<Entity>> original) {
        return blueprints$entities == null ? original.call(level, except, bounds, predicate) : List.of();
    }

    @WrapOperation(method = "apply", at = @At(value = "INVOKE", target = "Lcom/moulberry/axiom/utils/RegionHelper;pushBlockRegionChangeOffset(Lcom/moulberry/axiom/render/regions/ChunkedBlockRegion;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;Lnet/minecraft/core/BlockPos;Ljava/lang/String;ILcom/moulberry/axiom/world_modification/undo/AdditionalUndoOperation;)V"))
    private void blueprints$pasteSavedEntities(ChunkedBlockRegion region, Long2ObjectMap<CompressedBlockEntity> data,
                                             BlockPos offset, String description, int modifiers,
                                             AdditionalUndoOperation undo, Operation<Void> original) {
        if (blueprints$entities == null || blueprints$entities.isEmpty() || !BuilderToolManager.copyEntities) {
            original.call(region, data, offset, description, modifiers, undo);
            return;
        }
        List<AxiomServerboundSpawnEntity.SpawnEntry> spawns = new ArrayList<>();
        List<AxiomServerboundManipulateEntity.ManipulateEntry> changes = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (CompoundTag tag : blueprints$entities) {
            EntityDataUtils.cloneEntity(UUID.randomUUID(), tag.copy(), Vec3.atLowerCornerOf(offset),
                transformMatrix, spawns, changes, ids);
        }
        AdditionalUndoOperation entityUndo = undo;
        if (!ids.isEmpty()) {
            var deleteEntities = new DeleteEntityAdditionalUndoOperation(ids);
            entityUndo = () -> {
                deleteEntities.perform();
                if (undo != null) undo.perform();
            };
        }
        RegionHelper.pushBlockRegionChangeOffset(region, data, offset, description, modifiers, entityUndo);
        if (!spawns.isEmpty()) new AxiomServerboundSpawnEntity(spawns).send();
        if (!changes.isEmpty()) new AxiomServerboundManipulateEntity(changes).send();
    }
}
