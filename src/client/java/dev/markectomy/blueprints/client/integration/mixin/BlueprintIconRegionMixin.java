package dev.markectomy.blueprints.client.integration.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.moulberry.axiom.StaticValues;
import com.moulberry.axiom.core_rendering.AxiomDrawBuffer;
import com.moulberry.axiom.render.AxiomWorldRenderContext;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.utils.RenderHelper;
import dev.markectomy.blueprints.client.integration.BlueprintIconRegionBridge;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.ref.Cleaner;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkedBlockRegion.class, remap = false)
abstract class BlueprintIconRegionMixin implements BlueprintIconRegionBridge {
    @Shadow private LongSet dirtyChunks;
    @Shadow private int packReloadIndex;
    @Shadow public abstract void dirtyAll();
    @Shadow public abstract void clear();
    @Unique private Cleaner.Cleanable blueprints$iconCleanable;
    @Unique private Map<AxiomDrawBuffer, Long> blueprints$iconBufferSizes;
    @Unique private long blueprints$iconVertexBytes;
    @Unique private boolean blueprints$iconClosed;
    @Unique private int blueprints$iconModelViewPushes;

    @Invoker("uploadDirty")
    protected abstract void blueprints$uploadIconChunks(AxiomWorldRenderContext context, Vec3 translation,
                                                       boolean translucent, boolean ambientOcclusion);

    @Inject(method = "uploadDirty", at = @At("HEAD"), cancellable = true)
    private void blueprints$reuseCompleteIconMesh(AxiomWorldRenderContext context, Vec3 translation,
                                                 boolean translucent, boolean ambientOcclusion,
                                                 CallbackInfo callback) {
        if (blueprints$iconBufferSizes != null && dirtyChunks.isEmpty()) callback.cancel();
    }

    @Override
    public void blueprints$ownIconRegion() {
        blueprints$iconBufferSizes = new IdentityHashMap<>();
    }

    @Override
    public boolean blueprints$iconReady() {
        blueprints$refreshIconModels();
        return !blueprints$iconClosed && dirtyChunks.isEmpty();
    }

    @Unique
    private void blueprints$refreshIconModels() {
        if (!blueprints$iconClosed && packReloadIndex != StaticValues.staticPackReloadIndex) {
            packReloadIndex = StaticValues.staticPackReloadIndex;
            dirtyAll();
        }
    }

    @Override
    public int blueprints$prepareIconChunks(AxiomWorldRenderContext context, Vec3 translation, int maximum) {
        RenderSystem.assertOnRenderThread();
        blueprints$refreshIconModels();
        if (blueprints$iconClosed || maximum <= 0 || dirtyChunks.isEmpty()) return 0;
        LongSet remaining = dirtyChunks;
        LongSet selected = new LongOpenHashSet(Math.min(maximum, remaining.size()));
        var iterator = remaining.longIterator();
        while (iterator.hasNext() && selected.size() < maximum) {
            selected.add(iterator.nextLong());
            iterator.remove();
        }
        int count = selected.size();
        dirtyChunks = selected;
        try {
            blueprints$uploadIconChunks(context, translation, false, true);
        } finally {
            remaining.addAll(dirtyChunks);
            dirtyChunks = remaining;
        }
        return count;
    }

    @Override
    public long blueprints$iconGpuBytes() {
        // Include conservative space for the native mesh index buffers.
        return blueprints$iconVertexBytes + blueprints$iconVertexBytes / 2L;
    }

    @Override
    public void blueprints$restoreIconModelView() {
        while (blueprints$iconModelViewPushes > 0) {
            RenderHelper.popModelViewStack();
            blueprints$iconModelViewPushes--;
        }
    }

    @Override
    public void blueprints$closeIconRegion() {
        if (blueprints$iconClosed) return;
        blueprints$iconClosed = true;
        clear();
        if (blueprints$iconCleanable != null) {
            blueprints$iconCleanable.clean();
            blueprints$iconCleanable = null;
        }
        if (blueprints$iconBufferSizes != null) blueprints$iconBufferSizes.clear();
        blueprints$iconVertexBytes = 0L;
    }

    @Redirect(method = "uploadDirty", at = @At(value = "INVOKE", target = "Ljava/lang/ref/Cleaner;register(Ljava/lang/Object;Ljava/lang/Runnable;)Ljava/lang/ref/Cleaner$Cleanable;"))
    private Cleaner.Cleanable blueprints$captureIconCleaner(Cleaner cleaner, Object owner, Runnable action) {
        Cleaner.Cleanable cleanable = cleaner.register(owner, action);
        if (blueprints$iconBufferSizes != null) blueprints$iconCleanable = cleanable;
        return cleanable;
    }

    @Redirect(method = "uploadDirty", at = @At(value = "INVOKE", target = "Lcom/moulberry/axiom/core_rendering/AxiomDrawBuffer;upload(Lcom/mojang/blaze3d/vertex/MeshData;)V"), require = 2)
    private void blueprints$measureIconMesh(AxiomDrawBuffer buffer, MeshData mesh) {
        buffer.upload(mesh);
        if (blueprints$iconBufferSizes != null) {
            long size = buffer.getVertexBuffer().size();
            Long previous = blueprints$iconBufferSizes.put(buffer, size);
            blueprints$iconVertexBytes += size - (previous == null ? 0L : previous);
        }
    }

    @Redirect(method = "render(Lcom/moulberry/axiom/render/AxiomWorldRenderContext;Lnet/minecraft/world/phys/Vec3;Lorg/joml/Quaternionf;FFZLcom/mojang/blaze3d/pipeline/RenderTarget;)V",
        at = @At(value = "INVOKE", target = "Lcom/moulberry/axiom/utils/RenderHelper;pushModelViewMatrix(Lorg/joml/Matrix4f;)V"))
    private void blueprints$trackIconModelViewPush(Matrix4f matrix) {
        RenderHelper.pushModelViewMatrix(matrix);
        if (blueprints$iconBufferSizes != null) blueprints$iconModelViewPushes++;
    }

    @Redirect(method = "render(Lcom/moulberry/axiom/render/AxiomWorldRenderContext;Lnet/minecraft/world/phys/Vec3;Lorg/joml/Quaternionf;FFZLcom/mojang/blaze3d/pipeline/RenderTarget;)V",
        at = @At(value = "INVOKE", target = "Lcom/moulberry/axiom/utils/RenderHelper;popModelViewStack()V"))
    private void blueprints$trackIconModelViewPop() {
        RenderHelper.popModelViewStack();
        if (blueprints$iconBufferSizes != null) blueprints$iconModelViewPushes--;
    }
}
