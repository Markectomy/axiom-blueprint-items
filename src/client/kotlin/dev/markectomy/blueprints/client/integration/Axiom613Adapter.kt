package dev.markectomy.blueprints.client.integration

import com.moulberry.axiom.Axiom
import com.moulberry.axiom.AxiomClient
import com.moulberry.axiom.blueprint.BlueprintIo
import com.moulberry.axiom.blueprint.BlueprintHeader
import com.moulberry.axiom.blueprint.RawBlueprint
import com.moulberry.axiom.clipboard.Placement
import com.moulberry.axiom.editor.EditorUI
import com.moulberry.axiom.buildertools.BuilderToolManager
import com.moulberry.axiom.buildertools.BuilderTool
import com.moulberry.axiom.buildertools.CloneBuilderTool
import com.moulberry.axiom.render.regions.ChunkedBlockRegion
import com.moulberry.axiom.restrictions.AxiomPermission
import com.moulberry.axiom.world_modification.BlockBuffer
import com.moulberry.axiom.world_modification.Dispatcher
import dev.markectomy.blueprints.domain.preview.VoxelPreview
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Objects

data class BlueprintMetadata(val name: String, val tags: Set<String>)

/** Exclusively owned during preparation; ownership passes to Axiom when started. */
class PreparedBlueprint internal constructor(
    internal val raw: RawBlueprint,
    internal val region: ChunkedBlockRegion,
)

/** Axiom 6.1.3 library loading and native Clone handoff. */
object Axiom613Adapter {
    private data class ImportedPlacement(
        val clone: CloneBuilderTool,
        val previousTool: BuilderTool,
        val player: LocalPlayer,
        val level: ClientLevel,
    )

    private var importedPlacement: ImportedPlacement? = null
    private const val MAGIC = 182827830
    private const val MAX_HEADER = 1024 * 1024
    private const val MAX_THUMBNAIL = 4 * 1024 * 1024
    private const val MAX_FILE = 128 * 1024 * 1024
    private const val MAX_DECOMPRESSED = 128L * 1024 * 1024
    private const val MAX_CHUNKS = 4096
    private const val MAX_PREVIEW_FILE = 32 * 1024 * 1024
    private const val MAX_PREVIEW_DECOMPRESSED = 32L * 1024 * 1024
    private const val MAX_PREVIEW_CHUNKS = 512
    private const val PREVIEW_AXIS = 24
    private const val MAP_COLORS = 64
    // Axiom's first DFU-enabled release read unversioned blueprints on Minecraft 1.20.1.
    private const val LEGACY_DATA_VERSION = 3465
    private val rawDecodeLock = Any()
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun libraryRoot(): Path = Axiom.getInstance().blueprintDirectory

    /** Worker-only: header IO, never build parsing. */
    fun readHeader(path: Path): BlueprintMetadata {
        DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
            val header = BlueprintHeader.load(readValidatedHeader(input))
            return BlueprintMetadata(header.name(), header.tags().toSet())
        }
    }

    /** Worker-only: copies the saved PNG without invoking Axiom's stack-based decoder. */
    fun readThumbnailPng(path: Path): ByteArray? {
        DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
            readValidatedHeader(input)
            val size = boundedLength(input.readInt(), MAX_THUMBNAIL, "thumbnail")
            if (size == 0) return null
            val png = input.readNBytes(size)
            if (png.size != size) throw IOException("Truncated blueprint thumbnail")
            if (png.size < 24 || !png.copyOfRange(0, 8).contentEquals(pngSignature)) return null
            val buffer = ByteBuffer.wrap(png)
            if (buffer.getInt(8) != 13 || buffer.getInt(12) != 0x49484452) return null
            val width = buffer.getInt(16)
            val height = buffer.getInt(20)
            if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 4_194_304) return null
            return png
        }
    }

    /** Worker-only: prepare private CPU data; no world mutation, texture decode or GPU upload. */
    fun prepare(path: Path): PreparedBlueprint {
        val raw = readRaw(path, MAX_FILE, MAX_DECOMPRESSED, MAX_CHUNKS)
        val region = ChunkedBlockRegion()
        for (entry in raw.blocks().long2ObjectEntrySet()) {
            if (Thread.currentThread().isInterrupted) throw IOException("Blueprint preparation cancelled")
            val key = entry.longKey
            val chunk = entry.value
            val baseX = BlockPos.getX(key) * 16
            val baseY = BlockPos.getY(key) * 16
            val baseZ = BlockPos.getZ(key) * 16
            for (z in 0..15) for (y in 0..15) for (x in 0..15) {
                val state = chunk.get(x, y, z)
                if (state !== BlockBuffer.EMPTY_STATE) region.addBlockWithoutDirty(baseX + x, baseY + y, baseZ + z, state)
            }
        }
        region.dirtyAll()
        if (region.isEmpty && raw.entities().isEmpty()) throw IOException("Blueprint has no blocks or entities")
        return PreparedBlueprint(raw, region)
    }

    /** Worker-only: retain every visible source block, excluding selection air padding. */
    fun prepareIconRegion(path: Path): ChunkedBlockRegion {
        val raw = readRaw(path, MAX_FILE, MAX_DECOMPRESSED, MAX_CHUNKS)
        val region = ChunkedBlockRegion(false)
        forEachPreviewBlock(raw) { x, y, z, state -> region.addBlockWithoutDirty(x, y, z, state) }
        if (region.isEmpty) throw IOException("Blueprint has no visible blocks")
        region.dirtyAll()
        return region
    }

    /** Worker-only: bounded color samples, independent of world state and rendering resources. */
    fun preparePreview(path: Path): VoxelPreview {
        val raw = readRaw(path, MAX_PREVIEW_FILE, MAX_PREVIEW_DECOMPRESSED, MAX_PREVIEW_CHUNKS)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        forEachPreviewBlock(raw) { x, y, z, _ ->
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            maxZ = maxOf(maxZ, z)
        }
        if (minX == Int.MAX_VALUE) throw IOException("Blueprint has no visible blocks")
        fun extent(minimum: Int, maximum: Int): Int {
            val value = maximum.toLong() - minimum + 1L
            if (value !in 1..VoxelPreview.MAX_EXTENT.toLong()) throw IOException("Blueprint preview extent exceeds its limit")
            return value.toInt()
        }
        val extentX = extent(minX, maxX)
        val extentY = extent(minY, maxY)
        val extentZ = extent(minZ, maxZ)
        val longest = maxOf(PREVIEW_AXIS, extentX, extentY, extentZ)
        fun sampleSize(extent: Int): Int = ((extent.toLong() * PREVIEW_AXIS + longest - 1L) / longest).toInt()
        val sizeX = sampleSize(extentX)
        val sizeY = sampleSize(extentY)
        val sizeZ = sampleSize(extentZ)
        val voxelCount = sizeX * sizeY * sizeZ
        val counts = IntArray(voxelCount * MAP_COLORS)
        forEachPreviewBlock(raw) { x, y, z, state ->
            val sampleX = ((x.toLong() - minX) * sizeX / extentX).toInt()
            val sampleY = ((y.toLong() - minY) * sizeY / extentY).toInt()
            val sampleZ = ((z.toLong() - minZ) * sizeZ / extentZ).toInt()
            // Minecraft 26.2 caches map colors on each immutable block state.
            val color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
            val colorId = if (color.id in 0 until MAP_COLORS) color.id else 0
            val index = (sampleY * sizeZ + sampleZ) * sizeX + sampleX
            counts[index * MAP_COLORS + colorId]++
        }
        val colors = IntArray(voxelCount)
        for (voxel in 0 until voxelCount) {
            var selected = 0
            var maximum = 0
            for (colorId in 0 until MAP_COLORS) {
                val count = counts[voxel * MAP_COLORS + colorId]
                if (count > maximum) {
                    maximum = count
                    selected = colorId
                }
            }
            if (maximum != 0) {
                val color = if (selected == MapColor.NONE.id) 0x909090 else MapColor.byId(selected).col
                colors[voxel] = color or (0xFF shl 24)
            }
        }
        return VoxelPreview(sizeX, sizeY, sizeZ, colors, extentX, extentY, extentZ)
    }

    private inline fun forEachPreviewBlock(raw: RawBlueprint, action: (Int, Int, Int, BlockState) -> Unit) {
        for (entry in raw.blocks().long2ObjectEntrySet()) {
            if (Thread.currentThread().isInterrupted) throw IOException("Blueprint preview preparation cancelled")
            val key = entry.longKey
            val chunk = entry.value ?: throw IOException("Blueprint contains a missing section")
            val baseX = BlockPos.getX(key).toLong() * 16L
            val baseY = BlockPos.getY(key).toLong() * 16L
            val baseZ = BlockPos.getZ(key).toLong() * 16L
            if (baseX < Int.MIN_VALUE || baseX + 15L > Int.MAX_VALUE ||
                baseY < Int.MIN_VALUE || baseY + 15L > Int.MAX_VALUE ||
                baseZ < Int.MIN_VALUE || baseZ + 15L > Int.MAX_VALUE
            ) throw IOException("Blueprint section coordinates exceed their limit")
            for (z in 0..15) for (y in 0..15) for (x in 0..15) {
                val state = chunk.get(x, y, z)
                if (state !== BlockBuffer.EMPTY_STATE && !state.isAir) {
                    action((baseX + x).toInt(), (baseY + y).toInt(), (baseZ + z).toInt(), state)
                }
            }
        }
    }

    fun isPlacing(): Boolean = Placement.INSTANCE.isPlacing ||
        BuilderToolManager.getTools().any { it.canBeReset() }

    fun isAvailable(): Boolean = AxiomClient.isAxiomActive() &&
        AxiomClient.hasPermission(AxiomPermission.BUILD_SECTION) &&
        AxiomClient.hasPermission(AxiomPermission.BUILDERTOOL_CLONE) &&
        BuilderToolManager.canUseBuilderTools() && !EditorUI.isEnabled()

    fun canRepeatPlacement(): Boolean = !Dispatcher.exhaustedDispatchSends()

    /** Client/render-thread-only. Caller must revalidate the prepared source and held reference. */
    fun start(prepared: PreparedBlueprint, position: BlockPos): Boolean {
        inputTick()
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        val level = client.level ?: return false
        if (importedPlacement != null || !player.isCreative || !isAvailable() || isPlacing() || prepared.region.isEmpty) return false
        val minimum = prepared.region.min() ?: return false
        val tools = BuilderToolManager.getTools()
        val previousTool = tools.getOrNull(BuilderToolManager.getToolSlotSelected()) ?: return false
        val index = tools.indexOfFirst { it is CloneBuilderTool }
        if (index < 0) return false
        // A native tool switch can apply its selection; check conflicts before switching.
        BuilderToolManager.setToolSlotSelected(index)
        BuilderToolManager.setToolSlotActive(true)
        if (!BuilderToolManager.isToolSlotActive() || !BuilderToolManager.selectedToolIs(CloneBuilderTool::class.java)) return false
        val clone = tools[index] as CloneBuilderTool
        // Saved coordinates are relative to the blueprint origin, which may be above its base.
        val offset = position.offset(0, -minimum.y, 0)
        (clone as BlueprintCloneBridge).`blueprints$start`(prepared.raw, prepared.region, offset)
        importedPlacement = ImportedPlacement(clone, previousTool, player, level)
        return true
    }

    /** Client-thread-only: one native paste and undo entry, without activating the tool slot. */
    fun placeImmediately(prepared: PreparedBlueprint, position: BlockPos): Boolean {
        inputTick()
        val player = Minecraft.getInstance().player ?: return false
        if (importedPlacement != null || !player.isCreative || !isAvailable() || isPlacing() || prepared.region.isEmpty) return false
        val minimum = prepared.region.min() ?: return false
        val clone = BuilderToolManager.getTools().filterIsInstance<CloneBuilderTool>().firstOrNull() ?: return false
        val bridge = clone as BlueprintCloneBridge
        try {
            bridge.`blueprints$start`(prepared.raw, prepared.region, position.offset(0, -minimum.y, 0))
            bridge.`blueprints$apply`()
            return bridge.`blueprints$hasPlaced`()
        } finally {
            clone.reset(false)
        }
    }

    fun validateImportedPreview() {
        val placement = importedPlacement ?: return
        val client = Minecraft.getInstance()
        if (client.player !== placement.player || client.level !== placement.level ||
            !placement.player.isCreative || !isAvailable()
        ) clearImportedPreview()
    }

    fun inputTick() {
        validateImportedPreview()
        val placement = importedPlacement ?: return
        val bridge = placement.clone as BlueprintCloneBridge
        bridge.`blueprints$inputTick`()
        if (bridge.`blueprints$isImported`()) return
        val tools = BuilderToolManager.getTools()
        val stillSelected = tools.getOrNull(BuilderToolManager.getToolSlotSelected()) === placement.clone
        // Finish must not become a held block-breaking action after leaving the tool slot.
        if (stillSelected && BuilderToolManager.isToolSlotActive() && Minecraft.getInstance().options.keyAttack.isDown) return
        importedPlacement = null
        // A manual tool switch takes precedence over the saved selection.
        if (stillSelected) {
            placement.clone.reset(false)
            BuilderToolManager.setToolSlotActive(false)
            val previousIndex = tools.indexOf(placement.previousTool)
            if (previousIndex >= 0) BuilderToolManager.setToolSlotSelected(previousIndex)
        }
    }

    fun clearImportedPreview() {
        val placement = importedPlacement ?: return
        importedPlacement = null
        if ((placement.clone as BlueprintCloneBridge).`blueprints$isImported`()) placement.clone.reset(false)
    }

    private fun readHeaderFrame(input: DataInputStream): ByteArray {
        if (input.readInt() != MAGIC) throw IOException("Not an Axiom blueprint")
        val size = boundedLength(input.readInt(), MAX_HEADER, "header")
        val header = input.readNBytes(size)
        if (header.size != size) throw IOException("Truncated blueprint header")
        return ByteBuffer.allocate(8 + size).putInt(MAGIC).putInt(size).put(header).array()
    }

    private fun boundedLength(size: Int, maximum: Int, part: String): Int {
        if (size !in 0..maximum) throw IOException("Invalid or oversized blueprint $part")
        return size
    }

    private fun readValidatedHeader(input: DataInputStream): CompoundTag {
        val frame = readHeaderFrame(input)
        return DataInputStream(ByteArrayInputStream(frame, 8, frame.size - 8)).use { data ->
            val header = NbtIo.read(data, NbtAccounter.create(MAX_HEADER.toLong()))
            // Axiom ignores the declared length; both parsers must agree on field boundaries.
            if (data.available() != 0) throw IOException("Blueprint header length does not match its NBT")
            header
        }
    }

    private fun readRaw(path: Path, maximumFile: Int, maximumDecompressed: Long, maximumChunks: Int): RawBlueprint {
        if (Files.size(path) !in 1..maximumFile.toLong()) throw IOException("Blueprint file exceeds ${maximumFile / (1024 * 1024)} MiB")
        val bytes = Files.newInputStream(path).use { input -> input.readNBytes(maximumFile + 1) }
        if (bytes.size > maximumFile) throw IOException("Blueprint file exceeds ${maximumFile / (1024 * 1024)} MiB")
        val validatedBytes = validateBuildEnvelope(bytes, maximumFile, maximumDecompressed, maximumChunks)
        val raw = synchronized(rawDecodeLock) {
            if (Thread.currentThread().isInterrupted) throw IOException("Blueprint preparation cancelled")
            BlueprintIo.readRawBlueprint(ByteArrayInputStream(validatedBytes))
        } ?: throw IOException("Blueprint could not be read")
        val blocks = raw.blocks() ?: throw IOException("Blueprint has no block data")
        if (blocks.size > maximumChunks) throw IOException("Blueprint exceeds $maximumChunks chunks")
        return raw
    }

    private fun validateBuildEnvelope(bytes: ByteArray, maximumFile: Int, maximumDecompressed: Long, maximumChunks: Int): ByteArray {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val header = readValidatedHeader(input)
            val thumbnailSize = boundedLength(input.readInt(), MAX_THUMBNAIL, "thumbnail")
            input.skipNBytes(thumbnailSize.toLong())
            val buildLengthOffset = bytes.size - input.available()
            boundedLength(input.readInt(), maximumFile, "build")
            // Enforce a quota before Axiom rereads these bytes with unlimitedHeap().
            val build = NbtIo.readCompressed(input, NbtAccounter.create(maximumDecompressed))
            if (build.getListOrEmpty("BlockRegion").size > maximumChunks) {
                throw IOException("Blueprint exceeds $maximumChunks chunks")
            }
            if (header.getIntOr("Version", 0) != 0 || build.getIntOr("DataVersion", 0) != 0) return bytes

            // Axiom 6.1.3 otherwise assumes today's data version and skips legacy block/entity fixes.
            // Normalize only this private snapshot; retain the original header, thumbnail and library file.
            build.putInt("DataVersion", LEGACY_DATA_VERSION)
            val output = BoundedBlueprintOutput(maximumFile)
            DataOutputStream(output).use { data ->
                data.write(bytes, 0, buildLengthOffset)
                data.writeInt(0)
                NbtIo.writeCompressed(build, data)
            }
            val normalized = output.toByteArray()
            ByteBuffer.wrap(normalized).putInt(buildLengthOffset, normalized.size - buildLengthOffset - Int.SIZE_BYTES)
            return normalized
        }
    }

    /** Bounds both stored bytes and buffer capacity, including the gzip trailer written on close. */
    private class BoundedBlueprintOutput(private val maximum: Int) : ByteArrayOutputStream(minOf(8192, maximum)) {
        override fun write(value: Int) {
            reserve(1)
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            Objects.checkFromIndexSize(offset, length, bytes.size)
            reserve(length)
            super.write(bytes, offset, length)
        }

        private fun reserve(additional: Int) {
            if (additional > maximum - count) {
                throw IOException("Blueprint file exceeds ${maximum / (1024 * 1024)} MiB after legacy migration")
            }
            val required = count + additional
            if (required > buf.size) {
                val capacity = minOf(maximum.toLong(), maxOf(required.toLong(), buf.size.toLong() * 2L)).toInt()
                buf = buf.copyOf(capacity)
            }
        }
    }
}
