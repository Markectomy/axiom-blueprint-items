package dev.markectomy.blueprints.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.serialization.MapCodec
import dev.markectomy.blueprints.BlueprintItems
import dev.markectomy.blueprints.domain.BlueprintReference
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.special.SpecialModelRenderer
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import org.joml.Vector3f
import org.joml.Vector3fc
import java.util.function.Consumer

data class ThumbnailArgument(val reference: BlueprintReference?, val image: ThumbnailView)

/** A flat, double-sided image using vanilla's generated-item display transforms. */
class BlueprintThumbnailRenderer : SpecialModelRenderer<ThumbnailArgument> {
    override fun extractArgument(stack: ItemStack): ThumbnailArgument {
        val reference = BlueprintItems.reference(stack)
        return ThumbnailArgument(reference, reference?.let(BlueprintThumbnails::view) ?: ThumbnailView(null))
    }

    override fun submit(
        argument: ThumbnailArgument?,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        lightCoords: Int,
        overlayCoords: Int,
        hasFoil: Boolean,
        outlineColor: Int,
    ) {
        val image = argument?.image
        val texture = image?.texture ?: FALLBACK
        val columns = image?.columns ?: 1
        val frame = image?.frame ?: 0
        val u0 = (frame % columns).toFloat() / columns
        val v0 = if (image?.highQuality == true) 1f else (frame / columns).toFloat() / columns
        val u1 = u0 + 1f / columns
        val v1 = if (image?.highQuality == true) 0f else v0 + 1f / columns
        submitNodeCollector.submitCustomGeometry(poseStack, if (image?.highQuality == true) RenderTypes.itemTranslucent(texture) else RenderTypes.itemCutout(texture)) { pose, buffer ->
            fun vertex(x: Float, y: Float, u: Float, v: Float, z: Float, normal: Float) {
                buffer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v)
                    .setOverlay(overlayCoords).setLight(lightCoords).setNormal(pose, 0f, 0f, normal)
            }
            vertex(0f, 0f, u0, v1, 0.501f, 1f)
            vertex(1f, 0f, u1, v1, 0.501f, 1f)
            vertex(1f, 1f, u1, v0, 0.501f, 1f)
            vertex(0f, 1f, u0, v0, 0.501f, 1f)
            vertex(1f, 0f, u0, v1, 0.499f, -1f)
            vertex(0f, 0f, u1, v1, 0.499f, -1f)
            vertex(0f, 1f, u1, v0, 0.499f, -1f)
            vertex(1f, 1f, u0, v0, 0.499f, -1f)
        }
    }

    override fun getExtents(output: Consumer<Vector3fc>) {
        for (x in 0..1) for (y in 0..1) for (z in 0..1) output.accept(Vector3f(x.toFloat(), y.toFloat(), 0.499f + z * 0.002f))
    }

    class Unbaked : SpecialModelRenderer.Unbaked<ThumbnailArgument> {
        override fun bake(context: SpecialModelRenderer.BakingContext): BlueprintThumbnailRenderer = BlueprintThumbnailRenderer()
        override fun type(): MapCodec<Unbaked> = CODEC

        companion object {
            val CODEC: MapCodec<Unbaked> = MapCodec.unit(Unbaked())
        }
    }

    companion object {
        private val FALLBACK = Identifier.withDefaultNamespace("textures/item/paper.png")
    }
}
