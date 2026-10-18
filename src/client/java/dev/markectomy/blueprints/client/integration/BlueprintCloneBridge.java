package dev.markectomy.blueprints.client.integration;

import com.moulberry.axiom.blueprint.RawBlueprint;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import net.minecraft.core.BlockPos;

/** Pinned Axiom 6.1.3 native Clone preview handoff. */
public interface BlueprintCloneBridge {
    void blueprints$start(RawBlueprint raw, ChunkedBlockRegion region, BlockPos position);
    void blueprints$apply();
    void blueprints$inputTick();
    boolean blueprints$isImported();
    boolean blueprints$hasPlaced();
}
