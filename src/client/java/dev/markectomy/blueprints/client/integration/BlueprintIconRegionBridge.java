package dev.markectomy.blueprints.client.integration;

import com.moulberry.axiom.render.AxiomWorldRenderContext;
import net.minecraft.world.phys.Vec3;

/** Axiom 6.1.3 region compilation and resource ownership. */
public interface BlueprintIconRegionBridge {
    void blueprints$ownIconRegion();
    boolean blueprints$iconReady();
    int blueprints$prepareIconChunks(AxiomWorldRenderContext context, Vec3 translation, int maximum);
    long blueprints$iconGpuBytes();
    void blueprints$restoreIconModelView();
    void blueprints$closeIconRegion();
}
