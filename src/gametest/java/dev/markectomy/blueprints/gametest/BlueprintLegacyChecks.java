package dev.markectomy.blueprints.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.axiom.UserAction;
import com.moulberry.axiom.blueprint.BlueprintHeader;
import com.moulberry.axiom.blueprint.BlueprintIo;
import com.moulberry.axiom.buildertools.BuilderToolManager;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.world_modification.CompressedBlockEntity;
import com.moulberry.axiom.world_modification.Dispatcher;
import dev.markectomy.blueprints.client.integration.Axiom613Adapter;
import dev.markectomy.blueprints.client.integration.PreparedBlueprint;
import dev.markectomy.blueprints.domain.preview.VoxelPreview;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Synthetic legacy data lives only in Fabric's disposable run directory. */
final class BlueprintLegacyChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlueprintLegacyChecks");
    private static final int MAGIC = 182827830;
    private static final int MAX_FILE = 128 * 1024 * 1024;
    private static final long MAX_NBT = 128L * 1024 * 1024;
    private static final BlockPos TARGET = new BlockPos(0, 100, 3);

    private BlueprintLegacyChecks() {
    }

    static void run(ClientGameTestContext context, TestSingleplayerContext world, Path disposableLibrary) throws IOException {
        List<Path> fixtures = new ArrayList<>();
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "blueprint-legacy-regression");
            thread.setDaemon(true);
            return thread;
        });
        Future<PreparedBlueprint> preparation = null;
        try {
            Path modern = fixture(disposableLibrary, fixtures, "modern");
            Path legacy = fixture(disposableLibrary, fixtures, "legacy");
            Path zero = fixture(disposableLibrary, fixtures, "legacy-zero");
            Path unknown = fixture(disposableLibrary, fixtures, "unknown-block");
            Path modernUnversioned = fixture(disposableLibrary, fixtures, "modern-unversioned");
            context.runOnClient(client -> writeModernFixture(modern));
            preparation = worker.submit(() -> {
                PreparedBlueprint prepared = verifyLoaders(modern, legacy, zero, unknown, modernUnversioned);
                auditRequestedLibrary();
                return prepared;
            });
            PreparedBlueprint prepared = await(context, preparation);
            verifyNativePlacement(context, world, prepared);
            LOGGER.info("Legacy migration, strict missing-block rejection, preview geometry and native paste/undo checks passed");
        } finally {
            if (preparation != null) preparation.cancel(true);
            worker.shutdownNow();
            for (Path fixture : fixtures) Files.deleteIfExists(fixture);
        }
    }

    private static Path fixture(Path library, List<Path> fixtures, String suffix) throws IOException {
        Path result = Files.createTempFile(library, "addon-" + suffix + "-", ".bp");
        fixtures.add(result);
        return result;
    }

    private static void writeModernFixture(Path file) throws IOException {
        ChunkedBlockRegion region = new ChunkedBlockRegion();
        region.addBlockWithoutDirty(0, 0, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        region.addBlockWithoutDirty(0, 1, 0, Blocks.SHORT_GRASS.defaultBlockState());
        region.addBlockWithoutDirty(1, 0, 0, Blocks.STONE.defaultBlockState());
        region.dirtyAll();
        BlueprintHeader header = new BlueprintHeader("Synthetic legacy migration", "automated test", List.of(), 0, 0, false, 3, false);
        try (NativeImage thumbnail = new NativeImage(1, 1, true); OutputStream output = Files.newOutputStream(file)) {
            thumbnail.setPixel(0, 0, 0xff00ff00);
            BlueprintIo.write(output, header, thumbnail, region, new Long2ObjectOpenHashMap<CompressedBlockEntity>(), List.of());
        }
    }

    private static PreparedBlueprint verifyLoaders(Path modern, Path legacy, Path zero, Path unknown,
                                                   Path modernUnversioned) throws IOException {
        byte[] modernBytes = Files.readAllBytes(modern);
        Frame original = readFrame(modernBytes);
        int modernVersion = original.build().getIntOr("DataVersion", 0);
        require(original.header().getIntOr("Version", 0) == 2 && modernVersion > 3465,
            "Native fixture did not declare its modern format and source data version");
        require(validate(modernBytes, MAX_FILE, MAX_NBT, 4096) == modernBytes,
            "A versioned modern blueprint was needlessly normalized");
        verifyPrepared(Axiom613Adapter.INSTANCE.prepare(modern));
        require(readFrame(Files.readAllBytes(modern)).build().getIntOr("DataVersion", 0) == modernVersion,
            "Loading changed a modern source data version");

        Frame historical = historicalFrame(original);
        byte[] legacyBytes = writeFrame(historical);
        Files.write(legacy, legacyBytes);
        byte[] before = sha256(legacy);
        byte[] normalized = validate(legacyBytes, MAX_FILE, MAX_NBT, 4096);
        require(normalized != legacyBytes && readFrame(normalized).build().getIntOr("DataVersion", 0) == 3465,
            "The unversioned legacy snapshot did not receive the historical data version");
        int prefix = buildLengthOffset(legacyBytes);
        require(Arrays.equals(Arrays.copyOf(legacyBytes, prefix), Arrays.copyOf(normalized, prefix)),
            "Legacy normalization changed the original header or encoded thumbnail");
        require(ByteBuffer.wrap(normalized).getInt(prefix) == normalized.length - prefix - Integer.BYTES,
            "Legacy normalization did not update the compressed build length");
        PreparedBlueprint prepared = Axiom613Adapter.INSTANCE.prepare(legacy);
        verifyPrepared(prepared);
        verifyRegion(Axiom613Adapter.INSTANCE.prepareIconRegion(legacy));
        verifyPreview(Axiom613Adapter.INSTANCE.preparePreview(legacy));
        require(Arrays.equals(before, sha256(legacy)), "Loading or previewing rewrote the legacy library file");

        Frame explicitZero = copyFrame(historical);
        explicitZero.build().putInt("DataVersion", 0);
        Files.write(zero, writeFrame(explicitZero));
        byte[] zeroBefore = sha256(zero);
        verifyPrepared(Axiom613Adapter.INSTANCE.prepare(zero));
        require(Arrays.equals(zeroBefore, sha256(zero)), "Explicit-zero migration rewrote its source file");

        Frame missingModernVersion = copyFrame(original);
        missingModernVersion.build().remove("DataVersion");
        byte[] modernUnversionedBytes = writeFrame(missingModernVersion);
        Files.write(modernUnversioned, modernUnversionedBytes);
        require(validate(modernUnversionedBytes, MAX_FILE, MAX_NBT, 4096) == modernUnversionedBytes,
            "An unversioned modern-format blueprint was retroactively treated as legacy");
        verifyPrepared(Axiom613Adapter.INSTANCE.prepare(modernUnversioned));

        Frame unavailable = copyFrame(historical);
        require(renamePaletteBlock(unavailable.build(), "minecraft:grass", "blueprint_legacy_test:missing_block") == 1,
            "The missing-block fixture did not replace exactly one palette entry");
        Files.write(unknown, writeFrame(unavailable));
        byte[] unknownBefore = sha256(unknown);
        expectUnknownBlock(() -> Axiom613Adapter.INSTANCE.prepare(unknown));
        expectUnknownBlock(() -> Axiom613Adapter.INSTANCE.preparePreview(unknown));
        expectUnknownBlock(() -> Axiom613Adapter.INSTANCE.prepareIconRegion(unknown));
        require(Arrays.equals(unknownBefore, sha256(unknown)), "Rejected missing-block data rewrote its source file");

        Frame versionOne = copyFrame(original);
        versionOne.header().putInt("Version", 1);
        versionOne.build().remove("DataVersion");
        byte[] versionOneBytes = writeFrame(versionOne);
        require(validate(versionOneBytes, MAX_FILE, MAX_NBT, 4096) == versionOneBytes,
            "An unproven version-one format was retroactively treated as version zero");
        expectRejected(() -> validate(legacyBytes, MAX_FILE, MAX_NBT, 0), "chunks", "A zero section quota was ignored");
        expectRejected(() -> validate(legacyBytes, MAX_FILE, 1, 4096), "NBT", "The decompressed NBT quota was ignored");
        expectRejected(() -> validate(legacyBytes, normalized.length - 1, MAX_NBT, 4096), "after legacy migration",
            "The rebuilt snapshot exceeded its output size limit");
        byte[] badMagic = legacyBytes.clone();
        ByteBuffer.wrap(badMagic).putInt(0, 0);
        expectRejected(() -> validate(badMagic, MAX_FILE, MAX_NBT, 4096), "Not an Axiom blueprint",
            "Invalid magic passed envelope validation");
        return prepared;
    }

    private static Frame historicalFrame(Frame modern) {
        Frame historical = copyFrame(modern);
        historical.header().putInt("Version", 0);
        historical.build().remove("DataVersion");
        for (Tag sectionTag : historical.build().getListOrEmpty("BlockRegion")) {
            CompoundTag states = ((CompoundTag) sectionTag).getCompoundOrEmpty("BlockStates");
            ListTag palette = states.getListOrEmpty("palette");
            for (int index = 0; index < palette.size(); index++) {
                Tag entry = palette.get(index);
                CompoundTag legacyEntry = new CompoundTag();
                if (entry instanceof CompoundTag compound) {
                    String name = compound.getStringOr("Name", compound.getStringOr("id", ""));
                    require(!name.isEmpty(), "Native fixture palette has no identifiable block name");
                    legacyEntry.putString("Name", name);
                    Tag properties = compound.get("Properties");
                    if (properties == null) properties = compound.get("properties");
                    if (properties != null) legacyEntry.put("Properties", properties.copy());
                } else {
                    legacyEntry.putString("Name", entry.asString().orElseThrow());
                }
                palette.set(index, legacyEntry);
            }
        }
        require(renamePaletteBlock(historical.build(), "minecraft:short_grass", "minecraft:grass") == 1,
            "Native fixture did not contain exactly one short-grass palette entry");
        require(renamePaletteBlock(historical.build(), "minecraft:void_air", "minecraft:structure_void") > 0,
            "Native fixture did not exercise the historical empty-state sentinel");
        return historical;
    }

    private static int renamePaletteBlock(CompoundTag build, String from, String to) {
        int count = 0;
        for (Tag sectionTag : build.getListOrEmpty("BlockRegion")) {
            for (Tag entry : ((CompoundTag) sectionTag).getCompoundOrEmpty("BlockStates").getListOrEmpty("palette")) {
                CompoundTag state = (CompoundTag) entry;
                if (from.equals(state.getStringOr("Name", ""))) {
                    state.putString("Name", to);
                    count++;
                }
            }
        }
        return count;
    }

    private static void verifyPrepared(PreparedBlueprint prepared) {
        verifyRegion((ChunkedBlockRegion) field(prepared, "region"));
    }

    private static void verifyRegion(ChunkedBlockRegion region) {
        require(region.count() == 3, "Migration lost blocks or turned selection padding into visible geometry");
        require(region.getBlockStateOrAir(0, 0, 0).is(Blocks.GRASS_BLOCK), "Migration changed the grass support block");
        require(region.getBlockStateOrAir(0, 1, 0).is(Blocks.SHORT_GRASS), "Legacy grass did not migrate to short grass");
        require(region.getBlockStateOrAir(1, 0, 0).is(Blocks.STONE), "Migration changed the stone block");
    }

    private static void verifyPreview(VoxelPreview preview) {
        require(preview.getExtentX() == 2 && preview.getExtentY() == 2 && preview.getExtentZ() == 1,
            "Legacy preview included selection padding in its visible bounds");
        int[] colors = (int[]) field(preview, "colors");
        require(Arrays.stream(colors).filter(color -> color != 0).count() == 3,
            "Legacy preview lost visible blocks or rendered the historical empty sentinel");
    }

    private static void verifyNativePlacement(ClientGameTestContext context, TestSingleplayerContext world,
                                               PreparedBlueprint prepared) {
        context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing() && Axiom613Adapter.INSTANCE.canRepeatPlacement());
        world.getServer().runOnServer(server -> require(targetEmpty(server.overworld()), "Legacy paste target was not empty"));
        int historyBefore = context.computeOnClient(client -> {
            client.gui.setScreen(null);
            BuilderToolManager.setToolSlotActive(false);
            return Dispatcher.getHistoryPosition(false);
        });
        context.runOnClient(client -> require(Axiom613Adapter.INSTANCE.placeImmediately(prepared, TARGET),
            "Native immediate legacy paste was refused"));
        world.getServer().waitFor(server -> targetPlaced(server.overworld()));
        context.waitFor(client -> Dispatcher.getHistoryPosition(false) == historyBefore + 1);
        context.runOnClient(client -> UserAction.UNDO.call(null));
        world.getServer().waitFor(server -> targetEmpty(server.overworld()));
        context.waitFor(client -> Dispatcher.getHistoryPosition(false) == historyBefore);
    }

    private static boolean targetPlaced(Level level) {
        return level.getBlockState(TARGET).is(Blocks.GRASS_BLOCK)
            && level.getBlockState(TARGET.above()).is(Blocks.SHORT_GRASS)
            && level.getBlockState(TARGET.east()).is(Blocks.STONE);
    }

    private static boolean targetEmpty(Level level) {
        return level.getBlockState(TARGET).isAir() && level.getBlockState(TARGET.above()).isAir()
            && level.getBlockState(TARGET.east()).isAir();
    }

    private static void auditRequestedLibrary() throws IOException {
        String configured = System.getProperty("blueprints.libraryAuditRoot");
        if (configured == null || configured.isBlank()) return;
        Path root = Path.of(configured);
        require(Files.isDirectory(root), "The explicitly requested read-only library audit root is not a directory");
        long started = System.nanoTime();
        List<Path> sources;
        try (var paths = Files.walk(root)) {
            sources = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bp")).sorted().toList();
        }
        require(!sources.isEmpty(), "The explicitly requested read-only library audit found no blueprints");
        int decoded = 0;
        int errors = 0;
        int unchanged = 0;
        for (Path source : sources) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Read-only library audit cancelled");
            byte[] before = sha256(source);
            try {
                Axiom613Adapter.INSTANCE.prepare(source);
                decoded++;
            } catch (Exception exception) {
                errors++;
            }
            if (Arrays.equals(before, sha256(source))) unchanged++;
        }
        LOGGER.info("Read-only library audit: {} sources, {} prepared, {} rejected, {} unchanged, {} ms",
            sources.size(), decoded, errors, unchanged, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        require(errors == 0, "The read-only library audit rejected " + errors + " blueprint files");
        require(unchanged == sources.size(), "Source hashes changed during the read-only library audit");
    }

    private static PreparedBlueprint await(ClientGameTestContext context, Future<PreparedBlueprint> future) throws IOException {
        context.waitFor(client -> future.isDone(), ClientGameTestContext.DEFAULT_TIMEOUT * 60);
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Legacy blueprint regression interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError("Legacy blueprint regression worker failed", cause);
        }
    }

    private static byte[] validate(byte[] bytes, int maximumFile, long maximumNbt, int maximumChunks) throws IOException {
        try {
            Method method = Axiom613Adapter.class.getDeclaredMethod("validateBuildEnvelope", byte[].class, int.class,
                long.class, int.class);
            method.setAccessible(true);
            return (byte[]) method.invoke(Axiom613Adapter.INSTANCE, bytes, maximumFile, maximumNbt, maximumChunks);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError("Unexpected envelope validation failure", cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect the bounded blueprint envelope", exception);
        }
    }

    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect prepared blueprint data", exception);
        }
    }

    private static Frame readFrame(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            require(input.readInt() == MAGIC, "Synthetic fixture lost its native magic");
            int headerSize = input.readInt();
            require(headerSize > 0 && headerSize <= 1024 * 1024, "Synthetic fixture has an invalid header size");
            byte[] headerBytes = input.readNBytes(headerSize);
            CompoundTag header;
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(headerBytes))) {
                header = NbtIo.read(data, NbtAccounter.create(1024 * 1024));
                require(data.available() == 0, "Synthetic fixture header size disagrees with its NBT");
            }
            int thumbnailSize = input.readInt();
            require(thumbnailSize >= 0 && thumbnailSize <= 4 * 1024 * 1024, "Synthetic fixture thumbnail is oversized");
            byte[] thumbnail = input.readNBytes(thumbnailSize);
            int buildSize = input.readInt();
            require(buildSize == input.available(), "Synthetic fixture build size disagrees with its envelope");
            return new Frame(header, thumbnail, NbtIo.readCompressed(input, NbtAccounter.create(MAX_NBT)));
        }
    }

    private static byte[] writeFrame(Frame frame) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(header)) {
            NbtIo.write(frame.header(), data);
        }
        ByteArrayOutputStream build = new ByteArrayOutputStream();
        NbtIo.writeCompressed(frame.build(), build);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(header.size());
            header.writeTo(output);
            output.writeInt(frame.thumbnail().length);
            output.write(frame.thumbnail());
            output.writeInt(build.size());
            build.writeTo(output);
        }
        return bytes.toByteArray();
    }

    private static Frame copyFrame(Frame frame) {
        return new Frame(frame.header().copy(), frame.thumbnail().clone(), frame.build().copy());
    }

    private static int buildLengthOffset(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            input.readInt();
            input.skipNBytes(input.readInt());
            input.skipNBytes(input.readInt());
            return bytes.length - input.available();
        }
    }

    private static byte[] sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("The Java runtime does not provide SHA-256", exception);
        }
    }

    private static void expectUnknownBlock(CheckedAction action) throws IOException {
        try {
            action.run();
        } catch (IllegalStateException exception) {
            require(exception.getMessage() != null && exception.getMessage().contains("blueprint_legacy_test:missing_block"),
                "The missing-block fixture failed for an unrelated reason");
            return;
        }
        throw new AssertionError("An unavailable block was silently accepted or replaced with air");
    }

    private static void expectRejected(CheckedAction action, String expectedReason, String message) throws IOException {
        try {
            action.run();
        } catch (IOException | RuntimeException expected) {
            require(expected.getMessage() != null && expected.getMessage().contains(expectedReason),
                message + "; validation failed for an unrelated reason");
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Frame(CompoundTag header, byte[] thumbnail, CompoundTag build) {
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws IOException;
    }
}
