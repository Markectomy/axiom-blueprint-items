package dev.markectomy.blueprints.client.mixin;

import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CreativeModeTab.class)
public interface CreativeTabPositionAccess {
    @Accessor("row")
    void blueprints$setRow(CreativeModeTab.Row row);

    @Accessor("column")
    void blueprints$setColumn(int column);
}
