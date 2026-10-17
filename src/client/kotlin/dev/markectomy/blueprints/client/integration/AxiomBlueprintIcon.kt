package dev.markectomy.blueprints.client.integration

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.systems.ScissorState
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.PoseStack
import com.moulberry.axiom.core_rendering.AxiomRenderer
import com.moulberry.axiom.render.AxiomWorldRenderContext
import com.moulberry.axiom.render.regions.ChunkedBlockRegion
import com.moulberry.axiom.utils.FramebufferUtils
import com.moulberry.axiom.utils.ProjectionMatrixBackup
import com.moulberry.axiom.utils.RenderHelper
import dev.markectomy.blueprints.client.integration.mixin.AxiomIconRenderStateAccess
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.Sheets
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector4f
import java.io.IOException
import java.nio.file.Path
import kotlin.math.sqrt

/** Owns one full-resolution Axiom mesh and its continuously rendered item image. */
class AxiomBlueprintIcon private constructor(private val region: ChunkedBlockRegion) : AutoCloseable {
    private val bridge = region as BlueprintIconRegionBridge
    private val minimum = requireNotNull(region.min())
    private val maximum = requireNotNull(region.max())
    private val extentX = maximum.x.toDouble() - minimum.x + 1.0
    private val extentY = maximum.y.toDouble() - minimum.y + 1.0
    private val extentZ = maximum.z.toDouble() - minimum.z + 1.0
    private val scale = (2.0 / sqrt(extentX * extentX + extentY * extentY + extentZ * extentZ)).toFloat()
    private val translation = Vec3(
        -(minimum.x.toDouble() + extentX / 2.0),
        -(minimum.y.toDouble() + extentY / 2.0),
        -(minimum.z.toDouble() + extentZ / 2.0),
    )
    private val projection = Matrix4f().setPerspective(FOV_RADIANS, 1f, NEAR, FAR)
    private val prepareContext = context(PoseStack())
    private var target: RenderTarget? = null
    private var registeredTexture: Identifier? = null
    private var closed = false

    val blockCount: Int = region.count()
    val ready: Boolean get() = !closed && bridge.`blueprints$iconReady`()
    val gpuBytes: Long get() = bridge.`blueprints$iconGpuBytes`() + if (target != null) TARGET_BYTES else 0L

    init {
        bridge.`blueprints$ownIconRegion`()
    }

    /** Render-thread-only; compiles at most the caller's remaining frame budget. */
    fun prepare(maxChunks: Int): Int {
        check(!closed) { "Blueprint icon is closed" }
        return bridge.`blueprints$prepareIconChunks`(prepareContext, translation, maxChunks)
    }

    /** Render-thread-only; the geometry remains unchanged while the camera rotates. */
    fun render(textureId: Identifier, yawRadians: Float): Identifier {
        RenderSystem.assertOnRenderThread()
        check(ready) { "Blueprint icon is not prepared" }
        require(yawRadians.isFinite()) { "Blueprint icon rotation is not finite" }
        val renderTarget = target ?: TextureTarget(
            "Axiom blueprint item preview", SIZE, SIZE, true, GpuFormat.RGBA8_UNORM,
        ).also { target = it }
        if (registeredTexture != textureId) {
            registeredTexture?.let(Minecraft.getInstance().textureManager::release)
            Minecraft.getInstance().textureManager.register(textureId, PreviewTexture(renderTarget))
            registeredTexture = textureId
        }
        val pose = PoseStack()
        pose.translate(0f, 0f, -3.3f)
        pose.mulPose(Quaternionf().rotationX(PITCH_RADIANS).rotateY(yawRadians))
        pose.scale(scale, scale, scale)
        val backup = ProjectionMatrixBackup.create()
        val modelView = RenderSystem.getModelViewMatrixCopy()
        val mainTexture = AxiomIconRenderStateAccess.`blueprints$getMainTexture`()
        val color = Vector4f(AxiomIconRenderStateAccess.`blueprints$getColour`())
        val scissor = ScissorState(AxiomIconRenderStateAccess.`blueprints$getScissor`())
        try {
            FramebufferUtils.clear(renderTarget, 0)
            AxiomRenderer.setMainTexture(Sheets.BLOCKS_MAPPER.sheet())
            AxiomRenderer.disableScissor()
            RenderHelper.setPerspectiveProjectionMatrix(NEAR, FAR, FOV_DEGREES, SIZE.toFloat(), SIZE.toFloat())
            region.render(context(pose), translation, null, 1f, 0f, false, renderTarget)
        } finally {
            bridge.`blueprints$restoreIconModelView`()
            RenderSystem.getModelViewStack().set(modelView)
            backup.restore()
            AxiomRenderer.setMainTexture(mainTexture)
            AxiomRenderer.setShaderColour(color.x, color.y, color.z, color.w)
            AxiomIconRenderStateAccess.`blueprints$getScissor`().setFrom(scissor)
        }
        return textureId
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            registeredTexture?.let(Minecraft.getInstance().textureManager::release)
            registeredTexture = null
        } finally {
            try {
                target?.destroyBuffers()
                target = null
            } finally {
                bridge.`blueprints$closeIconRegion`()
            }
        }
    }

    private fun context(pose: PoseStack) = AxiomWorldRenderContext(
        pose, 0f, 0L, Vec3.ZERO, BlockPos.ZERO, 0f, 0f, Quaternionf(), projection,
    )

    private class PreviewTexture(target: RenderTarget) : AbstractTexture() {
        init {
            texture = target.colorTexture
            textureView = target.colorTextureView
            sampler = RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false,
            )
        }

        override fun close() {
            // The render target owns the borrowed texture and view.
            texture = null
            textureView = null
        }
    }

    companion object {
        private const val SIZE = 512
        private const val TARGET_BYTES = SIZE * SIZE * 8L
        private const val MAX_BLOCKS = 16_777_216
        private const val NEAR = 0.1f
        private const val FAR = 1000f
        private const val FOV_DEGREES = 40f
        private const val FOV_RADIANS = 0.6981317f
        private const val PITCH_RADIANS = 0.43633232f

        /** Worker-only; retains all source block models without voxel reduction. */
        fun load(path: Path): AxiomBlueprintIcon {
            val region = Axiom613Adapter.prepareIconRegion(path)
            if (region.isEmpty || region.count() >= MAX_BLOCKS) {
                region.clear()
                throw IOException("Blueprint cannot be rendered by Axiom's full-detail preview")
            }
            return AxiomBlueprintIcon(region)
        }
    }
}
