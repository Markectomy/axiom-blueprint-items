package dev.markectomy.blueprints.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.moulberry.axiom.blueprint.BlueprintHeader;
import com.moulberry.axiom.blueprint.BlueprintIo;
import com.moulberry.axiom.editor.EditorUI;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.utils.FramebufferUtils;
import com.moulberry.axiom.world_modification.CompressedBlockEntity;
import dev.markectomy.blueprints.BlueprintItems;
import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings;
import dev.markectomy.blueprints.client.config.PreviewSettings;
import dev.markectomy.blueprints.client.integration.Axiom613Adapter;
import dev.markectomy.blueprints.client.integration.AxiomBlueprintIcon;
import dev.markectomy.blueprints.client.render.BlueprintThumbnailRenderer;
import dev.markectomy.blueprints.client.render.BlueprintThumbnails;
import dev.markectomy.blueprints.client.render.HighQualityPreviews;
import dev.markectomy.blueprints.client.render.ThumbnailArgument;
import dev.markectomy.blueprints.domain.BlueprintPreviewTooltip;
import dev.markectomy.blueprints.domain.BlueprintReference;
import dev.markectomy.blueprints.domain.PreviewStyle;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

final class BlueprintHighQualityChecks {
    private static final int BACKGROUND = 0xff202020;
    private static final int PREVIEW_X = 64;
    private static final int PREVIEW_Y = 32;

    private BlueprintHighQualityChecks() {
    }

    static void run(ClientGameTestContext context, TestSingleplayerContext world, Path library) throws IOException {
        Path fixture = Files.createTempFile(library, "addon-detailed-preview-", ".bp");
        Path village = Files.createTempFile(library, "addon-village-preview-", ".bp");
        Path settings = FabricLoader.getInstance().getConfigDir().resolve(PreviewSettings.FILE_NAME);
        context.waitFor(client -> !BlueprintPreviewSettings.INSTANCE.getSaving());
        byte[] previousSettings = Files.exists(settings) ? Files.readAllBytes(settings) : null;
        PreviewStyle previousStyle = context.computeOnClient(client -> BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle());
        Screen previousScreen = context.computeOnClient(client -> client.gui.screen());
        long[] dayTime = new long[1];
        world.getServer().runOnServer(server -> dayTime[0] = server.overworld().getDefaultClockTime());
        BlueprintReference reference = new BlueprintReference(1, fixture.getFileName().toString(), "Detailed manor and tower");
        try {
            int expectedBlocks = context.computeOnClient(client -> writeFixture(fixture));
            int[] worldBefore = context.computeOnClient(client -> worldSnapshot(client.level));
            ItemStack item = context.computeOnClient(client -> BlueprintItems.INSTANCE.createStack(reference));
            PreviewScreen screen = context.computeOnClient(client -> new PreviewScreen(item, reference));
            context.runOnClient(client -> {
                BlueprintPreviewSettings.INSTANCE.setStyle(PreviewStyle.HIGH_QUALITY);
                client.gui.setScreen(screen);
            });
            context.waitFor(client -> BlueprintThumbnails.INSTANCE.view(reference).getHighQuality());
            Snapshot initial = context.computeOnClient(client -> snapshot(client, item, reference));
            context.runOnClient(client -> {
                require(initial.icon().getBlockCount() == expectedBlocks,
                    "High-quality preview reduced the source geometry: " + initial.icon().getBlockCount() + "/" + expectedBlocks);
                require(initial.icon().getGpuBytes() > 512L * 512L * 8L,
                    "High-quality preview did not retain native geometry buffers");
                require(initial.texture().getTexture().getWidth(0) == 512
                    && initial.texture().getTexture().getHeight(0) == 512,
                    "High-quality preview was not rendered at 512 pixels");
                var state = new TrackingItemStackRenderState();
                client.getItemModelResolver().updateForTopItem(state, item, ItemDisplayContext.GUI, client.level, client.player, 0);
                require(state.isAnimated(), "The native GUI item cache was not marked for smooth animation");
                require(screen.preview.getWidth(client.font) > 64 && screen.preview.getWidth(client.font) <= 128,
                    "High-quality hover preview did not use the enlarged component");
            });

            Captured first = capture(context, screen, "blueprint-high-quality-detailed-first");
            verifyDetail(first.preview());
            context.runOnClient(client -> screen.yaw = (float) Math.toRadians(1));
            context.waitTicks(1);
            Captured next = capture(context, screen, "blueprint-high-quality-detailed-next");
            require(differences(first.preview(), next.preview()) > 50,
                "A one-degree camera movement did not update the full-detail preview");
            require(!Arrays.equals(first.item(), next.item()),
                "The inventory item remained frozen while its native preview rotated");
            context.runOnClient(client -> {
                Snapshot advanced = snapshot(client, item, reference);
                require(initial.argument().equals(advanced.argument()),
                    "Continuous rotation created unbounded item-model cache identities");
                require(initial.texture() == advanced.texture() && initial.icon() == advanced.icon(),
                    "Rotation replaced the native texture or regenerated the blueprint mesh");
                require(initial.gpuBytes() == advanced.gpuBytes(), "Rotating a prepared preview allocated more geometry");
            });
            context.runOnClient(client -> screen.yaw = (float) Math.PI);
            context.waitTicks(1);
            Captured opposite = capture(context, screen, "blueprint-high-quality-detailed-opposite");
            require(differences(first.preview(), opposite.preview()) > 500,
                "Opposite views did not show different sides of the actual blueprint");
            verifyLighting(context, world, screen);
            exportPreview(context, initial.icon(), "blueprint-high-quality-manor-512.png");
            verifyLargeBlueprint(context, village, expectedBlocks);
            context.runOnClient(client -> {
                require(Arrays.equals(worldBefore, worldSnapshot(client.level)), "Rendering blueprint icons changed world blocks");
                require(!EditorUI.isEnabled() && !Axiom613Adapter.INSTANCE.isPlacing(),
                    "Rendering a blueprint icon activated the editor or a pending placement");
                require(reference.equals(BlueprintItems.INSTANCE.reference(item)),
                    "Rendering the full-detail icon changed its inventory reference");
            });
        } finally {
            setTime(world, dayTime[0]);
            context.runOnClient(client -> {
                client.gui.setScreen(previousScreen);
                BlueprintPreviewSettings.INSTANCE.setStyle(previousStyle);
                BlueprintThumbnails.INSTANCE.invalidate();
            });
            context.waitFor(client -> !BlueprintPreviewSettings.INSTANCE.getSaving());
            if (previousSettings == null) Files.deleteIfExists(settings);
            else Files.write(settings, previousSettings);
            Files.deleteIfExists(fixture);
            Files.deleteIfExists(village);
        }
    }

    private static void verifyLighting(ClientGameTestContext context, TestSingleplayerContext world, PreviewScreen screen) throws IOException {
        context.runOnClient(client -> screen.yaw = 0.5f);
        setTime(world, 6000);
        context.waitTicks(4);
        Captured day = capture(context, screen, "blueprint-high-quality-detailed-day");
        setTime(world, 18000);
        context.waitTicks(4);
        Captured night = capture(context, screen, "blueprint-high-quality-detailed-night");
        long dayBrightness = brightness(day.preview());
        long nightBrightness = brightness(night.preview());
        require(dayBrightness > 0 && nightBrightness * 100L / dayBrightness >= 95,
            "The full-detail icon became dark with the world's nighttime lightmap");
    }

    private static void setTime(TestSingleplayerContext world, long ticks) {
        world.getServer().runOnServer(server -> server.clockManager().setTotalTicks(
            server.overworld().dimensionType().defaultClock().orElseThrow(), ticks));
    }

    private static void verifyLargeBlueprint(ClientGameTestContext context, Path fixture, int manorBlocks) throws IOException {
        int count = context.computeOnClient(client -> writeFixture(fixture, true));
        require(count == manorBlocks * 9 && count > 40_000, "Large blueprint fixture did not contain all nine detailed buildings");
        BlueprintReference reference = new BlueprintReference(1, fixture.getFileName().toString(), "Detailed village");
        ItemStack item = context.computeOnClient(client -> BlueprintItems.INSTANCE.createStack(reference));
        PreviewScreen screen = context.computeOnClient(client -> new PreviewScreen(item, reference));
        context.runOnClient(client -> {
            screen.yaw = 0.6f;
            client.gui.setScreen(screen);
        });
        context.waitFor(client -> BlueprintThumbnails.INSTANCE.view(reference).getHighQuality());
        Snapshot initial = context.computeOnClient(client -> snapshot(client, item, reference));
        require(initial.icon().getBlockCount() == count, "The 144-block-wide village lost source blocks during preview preparation");
        Captured first = capture(context, screen, "blueprint-high-quality-village");
        verifyDetail(first.preview());
        exportPreview(context, initial.icon(), "blueprint-high-quality-village-512.png");
        context.runOnClient(client -> screen.yaw += (float) Math.PI);
        context.waitTicks(1);
        Captured opposite = capture(context, screen, "blueprint-high-quality-village-opposite");
        require(differences(first.preview(), opposite.preview()) > 500,
            "The full-detail village did not show its opposite side");
        context.runOnClient(client -> {
            Snapshot advanced = snapshot(client, item, reference);
            require(initial.icon() == advanced.icon() && initial.texture() == advanced.texture()
                && initial.gpuBytes() == advanced.gpuBytes(), "The large blueprint mesh was rebuilt during rotation");
        });
    }

    private static Snapshot snapshot(Minecraft client, ItemStack item, BlueprintReference reference) {
        var view = BlueprintThumbnails.INSTANCE.view(reference);
        require(view.getHighQuality() && view.getTexture() != null && view.getColumns() == 1,
            "The full-detail preset displayed a saved thumbnail or lightweight atlas");
        AbstractTexture texture = client.getTextureManager().getTexture(view.getTexture());
        AxiomBlueprintIcon icon = cachedIcon(reference);
        return new Snapshot(texture, icon, icon.getGpuBytes(), new BlueprintThumbnailRenderer().extractArgument(item));
    }

    private static AxiomBlueprintIcon cachedIcon(BlueprintReference reference) {
        try {
            Map<?, ?> cache = (Map<?, ?>) field(HighQualityPreviews.class, "cache").get(HighQualityPreviews.INSTANCE);
            for (var entry : cache.entrySet()) {
                if (reference.getPath().equals(field(entry.getKey().getClass(), "path").get(entry.getKey()))) {
                    Object icon = field(entry.getValue().getClass(), "icon").get(entry.getValue());
                    if (icon instanceof AxiomBlueprintIcon nativeIcon) return nativeIcon;
                }
            }
            throw new AssertionError("No prepared full-detail mesh exists for the fixture");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect full-detail preview ownership", exception);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void exportPreview(ClientGameTestContext context, AxiomBlueprintIcon icon, String filename) throws IOException {
        var download = context.computeOnClient(client -> {
            try {
                RenderTarget target = (RenderTarget) field(AxiomBlueprintIcon.class, "target").get(icon);
                require(target != null, "The full-detail preview has no framebuffer to export");
                return FramebufferUtils.downloadAsync(target);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Cannot access the full-detail preview framebuffer", exception);
            }
        });
        context.waitFor(client -> download.isDone());
        require(!download.isCompletedExceptionally(), "Unable to download the full-detail preview framebuffer: " + filename);
        Path output = context.computeOnClient(client -> client.gameDirectory.toPath().resolve("screenshots").resolve(filename));
        Files.createDirectories(output.getParent());
        try (NativeImage source = download.join()) {
            require(source != null && source.getWidth() == 512 && source.getHeight() == 512,
                "The exported preview did not contain its native 512-pixel image");
            try (NativeImage upright = new NativeImage(source.getWidth(), source.getHeight(), true)) {
                for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
                    upright.setPixel(x, y, source.getPixel(x, source.getHeight() - 1 - y));
                }
                upright.writeToFile(output);
            }
        }
    }

    private static Captured capture(ClientGameTestContext context, PreviewScreen screen, String name) throws IOException {
        context.waitTicks(1);
        int[] dimensions = context.computeOnClient(client -> new int[] {
            screen.width, screen.height, screen.preview.getWidth(client.font)
        });
        Path path = context.takeScreenshot(name);
        try (NativeImage image = NativeImage.read(Files.readAllBytes(path))) {
            return new Captured(
                crop(image, dimensions, 10, 10, 16, 16),
                crop(image, dimensions, PREVIEW_X, PREVIEW_Y + 2, dimensions[2], dimensions[2])
            );
        }
    }

    private static int[] crop(NativeImage image, int[] dimensions, int x, int y, int width, int height) {
        int left = x * image.getWidth() / dimensions[0];
        int top = y * image.getHeight() / dimensions[1];
        int right = (x + width) * image.getWidth() / dimensions[0];
        int bottom = (y + height) * image.getHeight() / dimensions[1];
        require(left >= 0 && top >= 0 && right <= image.getWidth() && bottom <= image.getHeight(),
            "Full-detail screenshot cropped outside the GUI");
        int[] result = new int[(right - left) * (bottom - top)];
        for (int py = top; py < bottom; py++) for (int px = left; px < right; px++) {
            result[(py - top) * (right - left) + px - left] = image.getPixel(px, py);
        }
        return result;
    }

    private static void verifyDetail(int[] pixels) {
        Set<Integer> colors = new HashSet<>();
        int visible = 0;
        int magenta = 0;
        for (int color : pixels) {
            if (color == BACKGROUND) continue;
            visible++;
            colors.add(color);
            if (((color >>> 16) & 255) > 210 && ((color >>> 8) & 255) < 40 && (color & 255) > 210) magenta++;
        }
        require(visible > pixels.length / 12, "The full-detail manor preview was empty or too small");
        require(colors.size() > 100, "The preview lost actual block textures and fine color variation: " + colors.size());
        require(magenta < visible / 20, "The full-detail preview displayed the fixture's checkerboard thumbnail");
    }

    private static int differences(int[] first, int[] second) {
        require(first.length == second.length, "Preview screenshot dimensions changed during rotation");
        int count = 0;
        for (int index = 0; index < first.length; index++) if (first[index] != second[index]) count++;
        return count;
    }

    private static long brightness(int[] pixels) {
        long total = 0;
        for (int color : pixels) if (color != BACKGROUND) {
            total += ((color >>> 16) & 255) + ((color >>> 8) & 255) + (color & 255);
        }
        return total;
    }

    private static int[] worldSnapshot(Level level) {
        int[] result = new int[17 * 41 * 17];
        int index = 0;
        for (BlockPos position : BlockPos.betweenClosed(-8, 96, -8, 8, 136, 8)) {
            result[index++] = net.minecraft.world.level.block.Block.getId(level.getBlockState(position));
        }
        return result;
    }

    private static int writeFixture(Path path) throws IOException {
        return writeFixture(path, false);
    }

    private static int writeFixture(Path path, boolean village) throws IOException {
        Long2ObjectOpenHashMap<BlockState> blocks = fixtureBlocks();
        if (village) {
            var repeated = new Long2ObjectOpenHashMap<BlockState>(blocks.size() * 9);
            for (int tileX = 0; tileX < 3; tileX++) for (int tileZ = 0; tileZ < 3; tileZ++) {
                for (var entry : blocks.long2ObjectEntrySet()) {
                    long position = entry.getLongKey();
                    put(repeated, BlockPos.getX(position) + tileX * 48, BlockPos.getY(position),
                        BlockPos.getZ(position) + tileZ * 48, entry.getValue());
                }
            }
            blocks = repeated;
        }
        ChunkedBlockRegion region = new ChunkedBlockRegion();
        try {
            for (var entry : blocks.long2ObjectEntrySet()) {
                long position = entry.getLongKey();
                region.addBlockWithoutDirty(BlockPos.getX(position), BlockPos.getY(position), BlockPos.getZ(position), entry.getValue());
            }
            region.dirtyAll();
            BlueprintHeader header = new BlueprintHeader(village ? "Detailed village" : "Detailed manor and tower", "fixture",
                List.of("detailed"), 0, 0, false, blocks.size(), false);
            try (NativeImage thumbnail = new NativeImage(64, 64, true); OutputStream output = Files.newOutputStream(path)) {
                for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                    thumbnail.setPixel(x, y, ((x / 8 + y / 8) & 1) == 0 ? 0xffff00ff : 0xff000000);
                }
                BlueprintIo.write(output, header, thumbnail, region, new Long2ObjectOpenHashMap<CompressedBlockEntity>(), List.of());
            }
            return blocks.size();
        } finally {
            region.clear();
        }
    }

    private static Long2ObjectOpenHashMap<BlockState> fixtureBlocks() {
        Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            put(blocks, x, 0, z, (x % 8 == 0 || z % 8 == 0) ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
        }
        for (int x = 12; x <= 35; x++) for (int z = 12; z <= 35; z++) {
            put(blocks, x, 1, z, Blocks.OAK_PLANKS.defaultBlockState());
            if (x != 12 && x != 35 && z != 12 && z != 35) continue;
            for (int y = 2; y <= 12; y++) {
                boolean pillar = (x == 12 || x == 35) && (z == 12 || z == 35)
                    || x % 6 == 0 || z % 6 == 0;
                boolean window = !pillar && (y >= 4 && y <= 6 || y >= 9 && y <= 10);
                put(blocks, x, y, z, (pillar ? Blocks.DARK_OAK_LOG : window ? Blocks.GLASS : Blocks.BRICKS).defaultBlockState());
            }
        }
        for (int row = 0; row < 14; row++) for (int x = 10; x <= 37; x++) {
            put(blocks, x, 13 + row, 10 + row,
                Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
            put(blocks, x, 13 + row, 37 - row,
                Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        }
        for (int x = 3; x <= 10; x++) for (int z = 3; z <= 10; z++) {
            if (x != 3 && x != 10 && z != 3 && z != 10) continue;
            for (int y = 1; y <= 34; y++) {
                boolean slit = y % 8 >= 3 && y % 8 <= 5 && (x == 6 || z == 6);
                put(blocks, x, y, z, (slit ? Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE)
                    : (x + y + z) % 5 == 0 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState());
            }
            if ((x + z) % 2 == 0) put(blocks, x, 35, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        }
        for (int y = 35; y <= 39; y++) put(blocks, 6, y, 6, Blocks.OAK_FENCE.defaultBlockState());
        for (int z = 7; z <= 11; z++) put(blocks, 6, 39, z, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
        for (int x = 3; x <= 8; x++) for (int z = 20; z <= 32; z++) {
            boolean rim = x == 3 || x == 8 || z == 20 || z == 32;
            put(blocks, x, 1, z, (rim ? Blocks.PRISMARINE_BRICKS : Blocks.WATER).defaultBlockState());
        }
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState().setValue(FenceBlock.EAST, true).setValue(FenceBlock.WEST, true);
        for (int x = 2; x <= 45; x++) put(blocks, x, 1, 45, fence);
        put(blocks, 39, 1, 39, Blocks.GLOWSTONE.defaultBlockState());
        put(blocks, 39, 2, 39, Blocks.GOLD_BLOCK.defaultBlockState());
        return blocks;
    }

    private static void put(Long2ObjectOpenHashMap<BlockState> blocks, int x, int y, int z, BlockState state) {
        blocks.put(BlockPos.asLong(x, y, z), state);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Snapshot(AbstractTexture texture, AxiomBlueprintIcon icon, long gpuBytes, ThumbnailArgument argument) {
    }

    private record Captured(int[] item, int[] preview) {
    }

    private static final class PreviewScreen extends Screen {
        private final ItemStack item;
        private final ClientTooltipComponent preview;
        private float yaw;

        private PreviewScreen(ItemStack item, BlueprintReference reference) {
            super(Component.literal("Full-detail blueprint preview"));
            this.item = item;
            preview = ClientTooltipComponent.create(new BlueprintPreviewTooltip(reference));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            try {
                field(HighQualityPreviews.class, "yaw").setFloat(HighQualityPreviews.INSTANCE, yaw);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Cannot fix the camera angle for preview comparisons", exception);
            }
            graphics.fill(0, 0, width, height, BACKGROUND);
            graphics.item(item, 10, 10);
            int size = preview.getWidth(font);
            preview.extractImage(font, PREVIEW_X, PREVIEW_Y, size, preview.getHeight(font), graphics);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
