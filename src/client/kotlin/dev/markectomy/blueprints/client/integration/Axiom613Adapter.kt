package dev.markectomy.blueprints.client.integration

import com.moulberry.axiom.Axiom
import com.moulberry.axiom.AxiomClient
import com.moulberry.axiom.blueprint.BlueprintIo
import com.moulberry.axiom.blueprint.BlueprintHeader
import com.moulberry.axiom.blueprint.RawBlueprint
import com.moulberry.axiom.clipboard.Placement
import com.moulberry.axiom.editor.EditorUI
import com.moulberry.axiom.buildertools.BuilderToolManager
import com.moulberry.axiom.buildertools.CloneBuilderTool
import com.moulberry.axiom.render.regions.ChunkedBlockRegion
import com.moulberry.axiom.restrictions.AxiomPermission
import com.moulberry.axiom.world_modification.BlockBuffer
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

data class BlueprintMetadata(val name: String, val tags: Set<String>)

/** Exclusively owned during preparation; ownership passes to Axiom when started. */
class PreparedBlueprint internal constructor(
    internal val raw: RawBlueprint,
    internal val region: ChunkedBlockRegion,
)

/** Axiom 6.1.3 library loading and native Clone handoff. */
object Axiom613Adapter {
    private var importedClone: CloneBuilderTool? = null
    private const val MAGIC = 182827830
    private const val MAX_HEADER = 1024 * 1024
    private const val MAX_THUMBNAIL = 4 * 1024 * 1024
    private const val MAX_FILE = 128 * 1024 * 1024
    private const val MAX_DECOMPRESSED = 128L * 1024 * 1024
    private const val MAX_CHUNKS = 4096
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
        if (Files.size(path) !in 1..MAX_FILE.toLong()) throw IOException("Blueprint file exceeds 128 MiB")
        val bytes = Files.newInputStream(path).use { input -> input.readNBytes(MAX_FILE + 1) }
        if (bytes.size > MAX_FILE) throw IOException("Blueprint file exceeds 128 MiB")
        validateBuildEnvelope(bytes)
        val raw = BlueprintIo.readRawBlueprint(ByteArrayInputStream(bytes))
        if (raw.blocks().size > MAX_CHUNKS) throw IOException("Blueprint exceeds 4096 chunks")
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

    fun isPlacing(): Boolean = Placement.INSTANCE.isPlacing ||
        BuilderToolManager.getTools().any { it.canBeReset() }

    fun isAvailable(): Boolean = AxiomClient.isAxiomActive() &&
        AxiomClient.hasPermission(AxiomPermission.BUILD_SECTION) &&
        AxiomClient.hasPermission(AxiomPermission.BUILDERTOOL_CLONE) &&
        BuilderToolManager.canUseBuilderTools() && !EditorUI.isEnabled()

    /** Client/render-thread-only. Caller must revalidate player/session and creative singleplayer. */
    fun start(prepared: PreparedBlueprint, position: BlockPos): Boolean {
        if (!isAvailable() || isPlacing() || prepared.region.isEmpty) return false
        val minimum = prepared.region.min() ?: return false
        val tools = BuilderToolManager.getTools()
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
        importedClone = clone
        return true
    }

    fun inputTick() {
        (importedClone as? BlueprintCloneBridge)?.`blueprints$inputTick`()
    }

    fun clearImportedPreview() {
        val clone = importedClone ?: return
        if ((clone as BlueprintCloneBridge).`blueprints$isImported`()) clone.reset(false)
        importedClone = null
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

    private fun validateBuildEnvelope(bytes: ByteArray) {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            readValidatedHeader(input)
            val thumbnailSize = boundedLength(input.readInt(), MAX_THUMBNAIL, "thumbnail")
            input.skipNBytes(thumbnailSize.toLong())
            boundedLength(input.readInt(), MAX_FILE, "build")
            // Enforce a quota before Axiom rereads these bytes with unlimitedHeap().
            val build = NbtIo.readCompressed(input, NbtAccounter.create(MAX_DECOMPRESSED))
            if (build.getListOrEmpty("BlockRegion").size > MAX_CHUNKS) {
                throw IOException("Blueprint exceeds 4096 chunks")
            }
        }
    }
}
