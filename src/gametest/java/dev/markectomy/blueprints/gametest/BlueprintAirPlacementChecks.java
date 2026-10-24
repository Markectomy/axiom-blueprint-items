package dev.markectomy.blueprints.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.axiom.UserAction;
import com.moulberry.axiom.blueprint.BlueprintHeader;
import com.moulberry.axiom.blueprint.BlueprintIo;
import com.moulberry.axiom.buildertools.BuilderToolManager;
import com.moulberry.axiom.buildertools.CloneBuilderTool;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.world_modification.CompressedBlockEntity;
import com.moulberry.axiom.world_modification.Dispatcher;
import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings;
import dev.markectomy.blueprints.client.integration.Axiom613Adapter;
import dev.markectomy.blueprints.client.integration.BlueprintCloneBridge;
import dev.markectomy.blueprints.client.integration.PreparedBlueprint;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Exercises native edits and block-entity preservation in the disposable test world. */
final class BlueprintAirPlacementChecks {
    private static final BlockPos FIRST = new BlockPos(0, 100, 3);
    private static final BlockPos SECOND = new BlockPos(0, 100, 7);

    private BlueprintAirPlacementChecks() {
    }

    static void run(ClientGameTestContext context, TestSingleplayerContext world, Path library) throws IOException {
        Path fixture = Files.createTempFile(library, "addon-air-only-", ".bp");
        boolean previousAirOnly = context.computeOnClient(client -> BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly());
        boolean previousCopyAir = context.computeOnClient(client -> BuilderToolManager.copyAir);
        boolean previousKeepExisting = context.computeOnClient(client -> BuilderToolManager.keepExisting);
        boolean previousCopyEntities = context.computeOnClient(client -> BuilderToolManager.copyEntities);
        try {
            context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing() && Axiom613Adapter.INSTANCE.canRepeatPlacement());
            context.runOnClient(client -> {
                client.gui.setScreen(null);
                BuilderToolManager.setToolSlotActive(false);
                BuilderToolManager.copyAir = true;
                BuilderToolManager.keepExisting = false;
                BuilderToolManager.copyEntities = false;
                writeFixture(client, fixture);
            });
            PreparedBlueprint prepared = context.computeOnClient(client -> Axiom613Adapter.INSTANCE.prepare(fixture));
            setUp(context, world, FIRST);
            context.runOnClient(client -> BlueprintPreviewSettings.INSTANCE.setAirOnly(true));
            int history = history(context);
            immediate(context, prepared, FIRST);
            assertPlacement(context, world, FIRST, true);
            undo(context, world, FIRST, history);

            // A setting changed while a preview is open applies to its next confirmation.
            setUp(context, world, SECOND);
            context.runOnClient(client -> {
                BlueprintPreviewSettings.INSTANCE.setAirOnly(false);
                require(Axiom613Adapter.INSTANCE.start(prepared, FIRST), "Air-only preview did not start");
                BlueprintPreviewSettings.INSTANCE.setAirOnly(true);
                bridge().blueprints$apply();
            });
            assertPlacement(context, world, FIRST, true);
            context.runOnClient(client -> {
                require(bridge().blueprints$isImported(), "First confirmation discarded the reusable preview");
                require(!BuilderToolManager.keepExisting, "Air-only placement changed the native Keep Existing option");
                offset().set(SECOND);
                BlueprintPreviewSettings.INSTANCE.setAirOnly(false);
                bridge().blueprints$apply();
            });
            assertPlacement(context, world, SECOND, false);
            context.runOnClient(client -> {
                require(bridge().blueprints$isImported(), "Second confirmation discarded the reusable preview");
                require(!BuilderToolManager.keepExisting, "Unmasked confirmation changed the native Keep Existing option");
                tool().reset(false);
            });
            context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing());
            undo(context, world, SECOND, history + 1);
            world.getServer().runOnServer(server -> require(placed(server.overworld(), FIRST, true),
                "Undoing the second copy changed the first masked copy"));
            undo(context, world, FIRST, history);

            context.runOnClient(client -> {
                BuilderToolManager.setToolSlotActive(false);
                BlueprintPreviewSettings.INSTANCE.setAirOnly(false);
                BuilderToolManager.keepExisting = true;
            });
            immediate(context, prepared, FIRST);
            assertPlacement(context, world, FIRST, true);
            context.runOnClient(client -> require(BuilderToolManager.keepExisting,
                "Blueprint placement reset the user's native Keep Existing option"));
            undo(context, world, FIRST, history);

            context.runOnClient(client -> BuilderToolManager.keepExisting = false);
            immediate(context, prepared, FIRST);
            assertPlacement(context, world, FIRST, false);
            undo(context, world, FIRST, history);

            context.runOnClient(client -> {
                BlueprintPreviewSettings.INSTANCE.setAirOnly(true);
                require(Axiom613Adapter.INSTANCE.start(prepared, FIRST), "Cancellation preview did not start");
                tool().reset(false);
            });
            context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing());
            world.getServer().runOnServer(server -> require(original(server.overworld(), FIRST),
                "Cancelling an air-only preview changed the world"));
            require(history(context) == history, "Cancelling an air-only preview added undo history");
        } finally {
            context.runOnClient(client -> {
                tool().reset(false);
                BuilderToolManager.setToolSlotActive(false);
                BuilderToolManager.copyAir = previousCopyAir;
                BuilderToolManager.keepExisting = previousKeepExisting;
                BuilderToolManager.copyEntities = previousCopyEntities;
                BlueprintPreviewSettings.INSTANCE.setAirOnly(previousAirOnly);
            });
            clear(world, FIRST);
            clear(world, SECOND);
            Files.deleteIfExists(fixture);
        }
    }

    private static void writeFixture(Minecraft client, Path file) throws IOException {
        ChunkedBlockRegion region = new ChunkedBlockRegion();
        Long2ObjectOpenHashMap<CompressedBlockEntity> entities = new Long2ObjectOpenHashMap<>();
        for (int x = 0; x <= 8; x++) {
            var state = x == 0 || x == 7 ? Blocks.CHEST.defaultBlockState()
                : x == 8 ? Blocks.AIR.defaultBlockState() : Blocks.LAPIS_BLOCK.defaultBlockState();
            region.addBlockWithoutDirty(x, 0, 0, state);
            if (x == 0 || x == 7) {
                BlockPos position = new BlockPos(x, 0, 0);
                ChestBlockEntity chest = new ChestBlockEntity(position, state);
                chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
                entities.put(position.asLong(), CompressedBlockEntity.compress(
                    chest.saveWithFullMetadata(client.level.registryAccess()), new ByteArrayOutputStream()));
            }
        }
        region.dirtyAll();
        BlueprintHeader header = new BlueprintHeader("Air-only fixture", "", List.of(), 0, 0, false, 8, false);
        try (NativeImage thumbnail = new NativeImage(1, 1, true); OutputStream output = Files.newOutputStream(file)) {
            thumbnail.setPixel(0, 0, 0xffffffff);
            BlueprintIo.write(output, header, thumbnail, region, entities, List.of());
        }
    }

    private static void setUp(ClientGameTestContext context, TestSingleplayerContext world, BlockPos target) {
        clear(world, target);
        world.getServer().runOnServer(server -> {
            Level level = server.overworld();
            for (int x : new int[] {1, 2, 4}) level.setBlock(target.offset(x, 0, 0), Blocks.OBSIDIAN.defaultBlockState(), 2);
            level.setBlock(target.offset(3, -1, 0), Blocks.BEDROCK.defaultBlockState(), 2);
            level.setBlock(target.offset(3, 0, -1), Blocks.BEDROCK.defaultBlockState(), 2);
            level.setBlock(target.offset(3, 0, 1), Blocks.BEDROCK.defaultBlockState(), 2);
            level.setBlock(target.offset(3, 0, 0), Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(target.offset(5, -1, 0), Blocks.DIRT.defaultBlockState(), 2);
            level.setBlock(target.offset(5, 0, 0), Blocks.SHORT_GRASS.defaultBlockState(), 2);
            level.setBlock(target.offset(6, 0, 0), Blocks.CAVE_AIR.defaultBlockState(), 2);
            level.setBlock(target.offset(8, 0, 0), Blocks.IRON_BLOCK.defaultBlockState(), 2);
            level.setBlock(target, Blocks.CHEST.defaultBlockState(), 2);
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(target);
            require(chest != null, "Occupied chest fixture has no block entity");
            chest.setItem(0, new ItemStack(Items.EMERALD, 5));
            chest.setChanged();
        });
        context.waitFor(client -> original(client.level, target));
    }

    private static void immediate(ClientGameTestContext context, PreparedBlueprint prepared, BlockPos target) {
        context.waitFor(client -> Axiom613Adapter.INSTANCE.canRepeatPlacement());
        context.runOnClient(client -> require(Axiom613Adapter.INSTANCE.placeImmediately(prepared, target),
            "Immediate air-only fixture paste was refused"));
    }

    private static void assertPlacement(ClientGameTestContext context, TestSingleplayerContext world,
                                        BlockPos target, boolean masked) {
        world.getServer().waitFor(server -> placed(server.overworld(), target, masked));
        context.waitFor(client -> client.level.getBlockState(target.offset(6, 0, 0)).is(Blocks.LAPIS_BLOCK)
            && client.level.getBlockState(target.offset(7, 0, 0)).is(Blocks.CHEST));
        world.getServer().runOnServer(server -> {
            require(chestContains(server.overworld(), target.offset(7, 0, 0), Items.DIAMOND, 3),
                "An inserted chest lost its saved contents");
            require(chestContains(server.overworld(), target, masked ? Items.EMERALD : Items.DIAMOND, masked ? 5 : 3),
                masked ? "Air-only placement replaced existing chest contents" : "Disabling air-only did not replace chest contents");
        });
    }

    private static void undo(ClientGameTestContext context, TestSingleplayerContext world, BlockPos target, int expectedHistory) {
        context.waitFor(client -> Dispatcher.getHistoryPosition(false) == expectedHistory + 1);
        context.runOnClient(client -> UserAction.UNDO.call(null));
        world.getServer().waitFor(server -> original(server.overworld(), target));
        world.getServer().runOnServer(server -> require(chestContains(server.overworld(), target, Items.EMERALD, 5),
            "Undo did not preserve the occupied chest's original contents"));
        context.waitFor(client -> original(client.level, target));
        context.waitFor(client -> Dispatcher.getHistoryPosition(false) == expectedHistory);
    }

    private static boolean placed(Level level, BlockPos target, boolean masked) {
        if (!level.getBlockState(target.offset(6, 0, 0)).is(Blocks.LAPIS_BLOCK)
            || !level.getBlockState(target.offset(7, 0, 0)).is(Blocks.CHEST)) return false;
        if (masked) {
            return level.getBlockState(target.offset(1, 0, 0)).is(Blocks.OBSIDIAN)
                && level.getBlockState(target.offset(2, 0, 0)).is(Blocks.OBSIDIAN)
                && level.getBlockState(target.offset(3, 0, 0)).is(Blocks.WATER)
                && level.getBlockState(target.offset(4, 0, 0)).is(Blocks.OBSIDIAN)
                && level.getBlockState(target.offset(5, 0, 0)).is(Blocks.SHORT_GRASS)
                && level.getBlockState(target.offset(8, 0, 0)).is(Blocks.IRON_BLOCK);
        }
        for (int x = 1; x <= 6; x++) if (!level.getBlockState(target.offset(x, 0, 0)).is(Blocks.LAPIS_BLOCK)) return false;
        return level.getBlockState(target.offset(8, 0, 0)).isAir();
    }

    private static boolean original(Level level, BlockPos target) {
        return level.getBlockState(target).is(Blocks.CHEST)
            && level.getBlockState(target.offset(1, 0, 0)).is(Blocks.OBSIDIAN)
            && level.getBlockState(target.offset(2, 0, 0)).is(Blocks.OBSIDIAN)
            && level.getBlockState(target.offset(3, 0, 0)).is(Blocks.WATER)
            && level.getBlockState(target.offset(4, 0, 0)).is(Blocks.OBSIDIAN)
            && level.getBlockState(target.offset(5, 0, 0)).is(Blocks.SHORT_GRASS)
            && level.getBlockState(target.offset(6, 0, 0)).isAir()
            && level.getBlockState(target.offset(7, 0, 0)).isAir()
            && level.getBlockState(target.offset(8, 0, 0)).is(Blocks.IRON_BLOCK);
    }

    private static boolean chestContains(Level level, BlockPos target, net.minecraft.world.item.Item item, int count) {
        return level.getBlockEntity(target) instanceof ChestBlockEntity chest
            && chest.getItem(0).is(item) && chest.getItem(0).getCount() == count;
    }

    private static void clear(TestSingleplayerContext world, BlockPos target) {
        world.getServer().runOnServer(server -> {
            for (BlockPos position : BlockPos.betweenClosed(target.offset(0, -1, -1), target.offset(8, 0, 1))) {
                server.overworld().setBlock(position, Blocks.AIR.defaultBlockState(), 2);
            }
        });
    }

    private static int history(ClientGameTestContext context) {
        return context.computeOnClient(client -> Dispatcher.getHistoryPosition(false));
    }

    private static CloneBuilderTool tool() {
        return BuilderToolManager.getTools().stream().filter(CloneBuilderTool.class::isInstance)
            .map(CloneBuilderTool.class::cast).findFirst().orElseThrow();
    }

    private static BlueprintCloneBridge bridge() {
        return (BlueprintCloneBridge) tool();
    }

    private static BlockPos.MutableBlockPos offset() {
        try {
            var field = CloneBuilderTool.class.getDeclaredField("regionOffset");
            field.setAccessible(true);
            return (BlockPos.MutableBlockPos) field.get(tool());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect native Clone offset", exception);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
