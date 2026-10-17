package dev.markectomy.blueprints.client.integration.mixin;

import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.moulberry.axiom.core_rendering.AxiomRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AxiomRenderer.class, remap = false)
public interface AxiomIconRenderStateAccess {
    @Accessor("mainTexture")
    static GpuTextureView blueprints$getMainTexture() {
        throw new AssertionError();
    }

    @Accessor("colour")
    static Vector4f blueprints$getColour() {
        throw new AssertionError();
    }

    @Accessor("scissorState")
    static ScissorState blueprints$getScissor() {
        throw new AssertionError();
    }
}
