package dev.markectomy.blueprints.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.axiom.AxiomClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.markectomy.blueprints.client.mixin.CreativeScreenAccess;
import com.moulberry.axiom.UserAction;
import com.moulberry.axiom.utils.EntityDataUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import com.moulberry.axiom.blueprint.BlueprintHeader;
import com.moulberry.axiom.blueprint.BlueprintIo;
import com.moulberry.axiom.clipboard.Placement;
import com.moulberry.axiom.editor.EditorUI;
import com.moulberry.axiom.editor.tutorial.TutorialManager;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.buildertools.BuilderToolManager;
import com.moulberry.axiom.buildertools.BuilderTool;
import com.moulberry.axiom.buildertools.CloneBuilderTool;
import com.moulberry.axiom.buildertools.MoveBuilderTool;
import com.moulberry.axiom.buildertools.StackBuilderTool;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import com.moulberry.axiom.world_modification.CompressedBlockEntity;
import com.moulberry.axiom.world_modification.Dispatcher;
import dev.markectomy.blueprints.BlueprintItems;
import dev.markectomy.blueprints.client.integration.Axiom613Adapter;
import dev.markectomy.blueprints.client.library.BlueprintLibrary;
import dev.markectomy.blueprints.client.render.BlueprintThumbnails;
import dev.markectomy.blueprints.domain.BlueprintReference;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Runs only in Fabric's disposable client-game-test world and run directory. */
public final class BlueprintPlacementGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlueprintPlacementGameTest");
    private static final BlockPos MIN = new BlockPos(-8, 96, -8);
    private static final BlockPos MAX = new BlockPos(8, 108, 12);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> {
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            settings.setAllowCommands(true);
        }).create()) {
            world.getServer().runCommand("fill -8 96 -8 8 108 12 air");
            world.getServer().runCommand("tp @a 0 100 0 0 0");
            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().forEach(player -> {
                player.setNoGravity(true);
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                player.onUpdateAbilities();
            }));
            context.waitFor(client -> client.player != null && client.player.isCreative() && client.player.getAbilities().flying
                && Math.abs(client.player.getY() - 100) < 1
                && AxiomClient.isAxiomActive() && AxiomClient.hasPermission(AxiomPermission.BUILD_SECTION));
            // Axiom opens its first-run introduction over a newly created world.
            context.runOnClient(client -> {
                client.gui.setScreen(null);
                TutorialManager.skip();
            });
            context.waitTicks(2);
            Path fixture = context.computeOnClient(client -> {
                client.player.setNoGravity(true);
                client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                return Files.createTempFile(Axiom613Adapter.INSTANCE.libraryRoot(), "addon-gametest-", ".bp");
            });
            try {
                context.runOnClient(client -> writeFixture(fixture));
                BlueprintReference reference = new BlueprintReference(1, fixture.getFileName().toString(), "Placement proof");
                checkpoint("rotating preview cache and fallback");
                BlueprintPreviewChecks.run(context, fixture, reference);
                checkpoint("full-detail textured previews and smooth GUI animation");
                BlueprintHighQualityChecks.run(context, world, fixture.getParent());
                checkpoint("preview settings and optional Mod Menu integration");
                BlueprintSettingsChecks.run(context, reference);
                checkpoint("inventory persistence");
                verifyInventoryAndPersistence(world, reference);
                checkpoint("creative tab placement and collision");
                verifyCreativeTabPosition(context);
                checkpoint("creative browser");
                verifyBrowser(context, fixture, reference);
                checkpoint("thumbnail orientation");
                verifyThumbnailOrientation(context, fixture);
                checkpoint("existing builder selection preserved");
                verifyExistingSelectionPreserved(context, fixture);
                checkpoint("surface anchoring with negative, zero, and positive source Y");
                verifySurfaceAnchoring(context, world, fixture.getParent());
                checkpoint("airborne targeting beyond interaction reach");
                verifyAirborneTargeting(context, world, reference);
                context.runOnClient(client -> {
                    selectBuilderTool(MoveBuilderTool.class);
                    ItemStack stack = BlueprintItems.INSTANCE.createStack(reference);
                    client.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    stack.use(client.level, client.player, InteractionHand.MAIN_HAND);
                });
                checkpoint("waiting for native Clone preview");
                context.waitFor(client -> Axiom613Adapter.INSTANCE.isPlacing());
                context.runOnClient(BlueprintPlacementGameTest::assertBuilderMode);
                assertWorldEmpty(world);
                context.takeScreenshot("blueprint-pending-preview");
                verifyUnreadableSourcesPreservePlacement(context, fixture);
                context.runOnClient(client -> {
                    ChunkedBlockRegion existing = cloneRegion();
                    client.player.getMainHandItem().use(client.level, client.player, InteractionHand.MAIN_HAND);
                    require(existing == cloneRegion(), "Repeated use replaced the pending placement");
                    require(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem()).equals(reference), "Item reference changed after use");
                    require(client.player.getMainHandItem().getCount() == 1, "Item was consumed");
                    BlockPos before = cloneOffset().immutable();
                    require(cloneTool().scroll(1), "Clone did not handle scroll movement");
                    require(!before.equals(cloneOffset()), "Scroll did not move the native preview");
                    cloneTool().middleClick(new net.minecraft.world.phys.BlockHitResult(
                        new net.minecraft.world.phys.Vec3(3.5, 99, 3.5), net.minecraft.core.Direction.UP, new BlockPos(3, 98, 3), false));
                    require(!before.equals(cloneOffset()), "Middle click did not preserve movable preview");
                });
                checkpoint("native Clone rotation and flip");
                verifyNativeTransforms(context);
                assertWorldEmpty(world);
                checkpoint("native Clone cancel");
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing());
                assertWorldEmpty(world);
                assertRestoredTool(context, MoveBuilderTool.class);
                context.runOnClient(client -> {
                    require(Axiom613Adapter.INSTANCE.start(Axiom613Adapter.INSTANCE.prepare(fixture), new BlockPos(0, 100, 0)),
                        "Could not start tool-switch cancellation proof");
                    selectBuilderTool(StackBuilderTool.class);
                    require(!Axiom613Adapter.INSTANCE.isPlacing(), "Tool switch left an imported preview pending");
                });
                context.waitTicks(2);
                assertRestoredTool(context, StackBuilderTool.class);
                assertWorldEmpty(world);
                context.runOnClient(client -> BuilderToolManager.setToolSlotActive(false));
                context.waitTicks(2);
                context.runOnClient(client -> {
                    ItemStack stack = client.player.getMainHandItem();
                    client.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    client.player.setItemInHand(InteractionHand.OFF_HAND, stack);
                    stack.use(client.level, client.player, InteractionHand.OFF_HAND);
                });
                checkpoint("waiting for native Clone preview");
                context.waitFor(client -> Axiom613Adapter.INSTANCE.isPlacing());
                context.runOnClient(BlueprintPlacementGameTest::assertBuilderMode);
                assertWorldEmpty(world);
                world.getServer().runCommand("summon pig 0.5 0 1 {Tags:[\"blueprint_ambient\"],NoAI:1b,NoGravity:1b,Invulnerable:1b}");
                context.runOnClient(client -> BuilderToolManager.copyEntities = true);
                context.waitTicks(2);
                checkpoint("native Clone confirm with saved entity");
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                world.getServer().waitFor(server -> countFixtureBlocks(server.overworld()) == 3
                    && countTaggedEntities(server.overworld(), "blueprint_saved") == 1);
                world.getServer().runOnServer(server -> require(countTaggedEntities(server.overworld(), "blueprint_ambient") == 1,
                    "Imported Clone copied an unrelated entity from source-world bounds"));
                checkpoint("previous tool restored after confirmation");
                assertRestoredTool(context, StackBuilderTool.class);
                context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing());
                context.waitFor(client -> Dispatcher.getHistoryDataCount() > 0);
                checkpoint("native Clone undo blocks and saved entity");
                context.runOnClient(client -> UserAction.UNDO.call(null));
                world.getServer().waitFor(server -> countFixtureBlocks(server.overworld()) == 0
                    && countTaggedEntities(server.overworld(), "blueprint_saved") == 0);
                world.getServer().runOnServer(server -> require(countTaggedEntities(server.overworld(), "blueprint_ambient") == 1,
                    "Undo modified an unrelated ambient entity"));
                assertWorldEmpty(world);
                checkpoint("same-tool restoration, slot exit, and disconnect cleanup");
                verifyPlacementCleanup(context, world, fixture);
                checkpoint("all regressions passed");
            } finally {
                context.runOnClient(client -> {
                    cloneTool().reset(false);
                    BuilderToolManager.setToolSlotActive(false);
                    Placement.INSTANCE.stopPlacement();
                    EditorUI.disable();
                    BlueprintThumbnails.INSTANCE.invalidate();
                });
                Files.deleteIfExists(fixture);
            }
        } catch (IOException exception) {
            throw new AssertionError("Unable to create isolated blueprint fixture", exception);
        }
    }

    private static void verifyBrowser(ClientGameTestContext context, Path fixture, BlueprintReference reference) throws IOException {
        Path second = Files.createTempFile(fixture.getParent(), "addon-second-", ".bp");
        Path invalid = Files.createTempFile(fixture.getParent(), "addon-bad-browser-", ".bp");
        try {
            context.runOnClient(client -> {
                writeFixture(second, "Second blueprint", List.of("test"));
                Files.write(invalid, new byte[] {1, 2, 3});
                client.player.setItemInHand(InteractionHand.MAIN_HAND, BlueprintItems.INSTANCE.createStack(reference));
                BlueprintLibrary.INSTANCE.refresh();
            });
            context.waitFor(client -> !BlueprintLibrary.INSTANCE.getSnapshot().getLoading()
                && BlueprintLibrary.INSTANCE.getSnapshot().getEntries().stream().anyMatch(entry -> entry.getReference().equals(reference)));
            context.runOnClient(client -> {
                require(BlueprintLibrary.INSTANCE.getSnapshot().getErrorCount() >= 1, "Unreadable library file was not counted");
                client.options.guiScale().set(2);
                client.getWindow().setWindowed(1280, 720);
                client.resizeGui();
                client.gui.setScreen(new CreativeModeInventoryScreen(client.player, client.level.enabledFeatures(), false));
                var screen = (CreativeModeInventoryScreen) client.gui.screen();
                selectLibraryTab(screen);
            });
            context.waitTicks(4);
            context.waitFor(client -> browserContains(client, reference) && !BlueprintLibrary.INSTANCE.getSnapshot().getLoading());
            context.waitFor(client -> BlueprintThumbnails.INSTANCE.texture(reference) != null);
            context.takeScreenshot(TestScreenshotOptions.of("blueprints-creative-tab").withSize(1280, 720));
            checkpoint("resource-pack sidebar recoloring");
            verifyResourcePackSidebar(context);
            checkpoint("browser search/tag filters and refresh");
            context.runOnClient(client -> search(client).setValue("nothing matches this name"));
            context.waitFor(client -> ((CreativeModeInventoryScreen) client.gui.screen()).getMenu().items.isEmpty());
            verifySelectedLibraryRebuild(context);
            context.runOnClient(client -> search(client).setValue("PLACEMENT"));
            context.waitFor(client -> browserContains(client, reference)
                && ((CreativeModeInventoryScreen) client.gui.screen()).getMenu().items.size() == 1);
            context.clickScreenButton("Clear");
            context.waitFor(client -> ((CreativeModeInventoryScreen) client.gui.screen()).getMenu().items.size() >= 2);
            context.clickScreenButton("[ ] test");
            context.clickScreenButton("[ ] stone");
            context.waitFor(client -> browserContains(client, reference)
                && ((CreativeModeInventoryScreen) client.gui.screen()).getMenu().items.size() == 1);
            context.clickScreenButton("Refresh");
            context.waitFor(client -> !BlueprintLibrary.INSTANCE.getSnapshot().getLoading() && browserContains(client, reference));
            context.runOnClient(client -> {
                require(reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())), "Filtering/refresh changed an inventory blueprint reference");
                require(((CreativeModeInventoryScreen) client.gui.screen()).getMenu().items.size() == 1, "Refresh lost the tag AND filter");
            });
            context.clickScreenButton("Clear");
            checkpoint("browser small-window layout");
            context.runOnClient(client -> {
                client.getWindow().setWindowed(640, 480);
                client.options.guiScale().set(2);
                client.resizeGui();
            });
            context.waitTicks(4);
            context.runOnClient(client -> {
                var field = search(client);
                require(field.getX() >= 0 && field.getX() + field.getWidth() <= client.gui.screen().width,
                    "Sidebar search falls outside a small window");
                require(browserContains(client, reference), "Resizing lost the blueprint catalog");
                var left = ((dev.markectomy.blueprints.client.mixin.ContainerScreenAccess) client.gui.screen()).blueprints$getLeft();
                for (var widget : Screens.getWidgets(client.gui.screen())) {
                    if (widget.getClass().getName().contains("FabricCreativeGuiComponents$CreativeModeTabButton")) {
                        require(widget.getX() >= left && widget.getX() + widget.getWidth() <= left + 195,
                            "Creative pagination overlaps the sidebar after resizing");
                    }
                }
                var creative = (CreativeModeInventoryScreen) client.gui.screen();
                ItemStack carried = BlueprintItems.INSTANCE.createStack(reference);
                creative.getMenu().setCarried(carried);
                creative.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(field.getX() - 4, field.getY() + 105,
                    new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
                require(creative.getMenu().getCarried() == carried, "Clicking sidebar background dropped the carried blueprint");
                creative.getMenu().setCarried(ItemStack.EMPTY);
            });
            context.takeScreenshot(TestScreenshotOptions.of("blueprints-small-window").withSize(640, 480));
            context.runOnClient(client -> {
                client.getWindow().setWindowed(1280, 720);
                client.resizeGui();
            });
            context.runOnClient(client -> client.gui.setScreen(null));
        } finally {
            Files.deleteIfExists(second);
            Files.deleteIfExists(invalid);
        }
    }

    private static void verifyResourcePackSidebar(ClientGameTestContext context) throws IOException {
        Path packRoot = context.computeOnClient(client -> {
            Files.createDirectories(client.getResourcePackDirectory());
            return Files.createTempDirectory(client.getResourcePackDirectory(), "blueprint-dark-test-");
        });
        List<String> originalPacks = context.computeOnClient(client -> List.copyOf(client.getResourcePackRepository().getSelectedIds()));
        try {
            Files.writeString(packRoot.resolve("pack.mcmeta"),
                "{\"pack\":{\"description\":\"Blueprint sidebar dark regression\",\"min_format\":[88,0],\"max_format\":[88,0]}}");
            Path texture = packRoot.resolve("assets/minecraft/textures/gui/container/inventory.png");
            Files.createDirectories(texture.getParent());
            try (NativeImage dark = new NativeImage(256, 256, true)) {
                for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) dark.setPixel(x, y, 0xff202020);
                dark.writeToFile(texture);
            }
            var loaded = context.computeOnClient(client -> {
                var repository = client.getResourcePackRepository();
                repository.reload();
                var selected = new java.util.ArrayList<>(originalPacks);
                selected.add("file/" + packRoot.getFileName());
                repository.setSelected(selected);
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> loaded.isDone());
            loaded.join();
            context.waitFor(client -> client.gui.overlay() == null);
            context.waitTicks(2);
            int[] sample = context.computeOnClient(client -> new int[] {
                search(client).getX() - 4, search(client).getY() - 18,
                client.gui.screen().width, client.gui.screen().height
            });
            Path screenshot = context.takeScreenshot(TestScreenshotOptions.of("blueprints-dark-resource-pack").withSize(1280, 720));
            try (NativeImage captured = NativeImage.read(Files.readAllBytes(screenshot))) {
                int x = sample[0] * captured.getWidth() / sample[2];
                int y = sample[1] * captured.getHeight() / sample[3];
                require((captured.getPixel(x, y) & 0xffffff) == 0x202020,
                    "Sidebar did not render the resource-pack inventory texture");
            }
        } finally {
            var restored = context.computeOnClient(client -> {
                client.getResourcePackRepository().setSelected(originalPacks);
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> restored.isDone());
            restored.join();
            context.waitFor(client -> client.gui.overlay() == null);
            try (var files = Files.walk(packRoot)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void verifyCreativeTabPosition(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.gui.setScreen(new CreativeModeInventoryScreen(client.player, client.level.enabledFeatures(), false));
            CreativeModeTab library = BlueprintItems.INSTANCE.getTAB();
            require(tabPage(library) == 0 && library.row() == CreativeModeTab.Row.BOTTOM && library.column() == 5,
                "Blueprints did not occupy the available first-page bottom slot");
            client.gui.setScreen(new CreativeModeInventoryScreen(client.player, client.level.enabledFeatures(), true));
            CreativeModeTab operator = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
                .getValueOrThrow(net.minecraft.world.item.CreativeModeTabs.OP_BLOCKS);
            require(operator.shouldDisplay(), "Collision fixture operator tab is not visible");
            require(tabPage(library) != tabPage(operator) || library.row() != operator.row() || library.column() != operator.column(),
                "Blueprints overlaps an existing visible tab");
            require(tabPage(library) > 0, "Occupied preferred slot did not fall back to another creative page");
            selectLibraryTab((CreativeModeInventoryScreen) client.gui.screen());
            client.gui.setScreen(null);
        });
    }

    private static int tabPage(CreativeModeTab tab) {
        return ((net.fabricmc.fabric.impl.creativetab.FabricCreativeModeTabImpl) tab).fabric_getPage();
    }

    private static void verifySelectedLibraryRebuild(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var screen = (CreativeModeInventoryScreen) client.gui.screen();
            var tabs = (net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen) screen;
            CreativeModeTab library = BlueprintItems.INSTANCE.getTAB();
            CreativeModeTab operator = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
                .getValueOrThrow(net.minecraft.world.item.CreativeModeTabs.OP_BLOCKS);
            try {
                var refresh = CreativeModeInventoryScreen.class.getDeclaredMethod("tryRefreshInvalidatedTabs",
                    net.minecraft.world.flag.FeatureFlagSet.class, boolean.class, net.minecraft.core.HolderLookup.Provider.class);
                refresh.setAccessible(true);
                for (boolean permissions : new boolean[] {true, false}) {
                    refresh.invoke(screen, client.level.enabledFeatures(), permissions, client.level.registryAccess());
                    require(operator.shouldDisplay() == permissions, "Selected library prevented vanilla permission rebuild");
                    require((tabPage(library) > 0) == permissions, "Selected library did not update collision fallback");
                    require(tabs.getCurrentPage() == tabPage(library), "Selected library moved to an inaccessible creative page");
                    require(tabs.getSelectedTab() == library, "Vanilla rebuild replaced the selected library tab");
                    require(screen.getMenu().items.isEmpty(), "Vanilla rebuild replaced the no-match catalog");
                    require(search(client).getValue().equals("nothing matches this name"), "Vanilla rebuild reset name search");
                }
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Cannot exercise selected-library tab rebuild", exception);
            }
        });
    }

    private static void selectLibraryTab(CreativeModeInventoryScreen screen) {
        CreativeModeTab library = BlueprintItems.INSTANCE.getTAB();
        // Fabric returns false for a no-op selection; selectedTab persists across screen instances.
        boolean changed = ((net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen) screen)
            .setSelectedTab(library);
        require(changed || CreativeScreenAccess.blueprints$getSelectedTab() == library,
            "Blueprints is inaccessible on its creative page");
    }

    private static void checkpoint(String name) {
        LOGGER.info("Blueprint regression checkpoint: {}", name);
    }

    private static boolean browserContains(Minecraft client, BlueprintReference reference) {
        return client.gui.screen() instanceof CreativeModeInventoryScreen screen
            && screen.getMenu().items.stream().anyMatch(stack -> reference.equals(BlueprintItems.INSTANCE.reference(stack)));
    }

    private static EditBox search(Minecraft client) {
        return Screens.getWidgets(client.gui.screen()).stream()
            .filter(widget -> widget instanceof EditBox && widget.visible)
            .map(widget -> (EditBox) widget)
            .filter(widget -> widget.getMessage().getString().equals("Search blueprints"))
            .findFirst().orElseThrow(() -> new AssertionError("Blueprint name-search widget is missing"));
    }

    /** Exercises vanilla transfer/drop/pickup logic; does not claim mouse-gesture coverage. */
    private static void verifyInventoryAndPersistence(TestSingleplayerContext world, BlueprintReference reference) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            ItemStack original = BlueprintItems.INSTANCE.createStack(reference);
            var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            var encoded = ItemStack.CODEC.encodeStart(ops, original).getOrThrow();
            ItemStack decoded = ItemStack.CODEC.parse(ops, encoded).getOrThrow();
            require(reference.equals(BlueprintItems.INSTANCE.reference(decoded)), "Persistent stack codec lost blueprint identity");
            require(decoded.getMaxStackSize() == 1 && decoded.getCount() == 1, "Blueprint stack size changed");
            SimpleContainer chest = new SimpleContainer(27);
            chest.setItem(0, decoded);
            ChestMenu menu = ChestMenu.threeRows(1, player.getInventory(), chest);
            require(!menu.quickMoveStack(player, 0).isEmpty(), "Vanilla chest-to-inventory transfer failed");
            require(chest.getItem(0).isEmpty(), "Vanilla transfer did not empty chest slot");
            int transferredSlot = -1;
            for (int slot = 27; slot < 63; slot++) {
                if (reference.equals(BlueprintItems.INSTANCE.reference(menu.getSlot(slot).getItem()))) transferredSlot = slot;
            }
            require(transferredSlot >= 27, "Transferred blueprint missing from player inventory");
            require(!menu.quickMoveStack(player, transferredSlot).isEmpty(), "Vanilla inventory-to-chest transfer failed");
            require(reference.equals(BlueprintItems.INSTANCE.reference(chest.getItem(0))), "Chest transfer lost blueprint component");
            var dropped = player.drop(chest.removeItemNoUpdate(0), false, false);
            require(dropped != null, "Vanilla drop did not produce an item entity");
            require(reference.equals(BlueprintItems.INSTANCE.reference(dropped.getItem())), "Dropped item lost blueprint component");
            dropped.setNoPickUpDelay();
            dropped.playerTouch(player);
            require(dropped.isRemoved(), "Vanilla pickup did not consume the dropped entity");
            boolean found = false;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (reference.equals(BlueprintItems.INSTANCE.reference(player.getInventory().getItem(slot)))) found = true;
            }
            require(found, "Vanilla pickup lost blueprint item");
            menu.removed(player);
        });
    }

    private static void verifyUnreadableSourcesPreservePlacement(ClientGameTestContext context, Path fixture) throws IOException {
        ChunkedBlockRegion existing = context.computeOnClient(client -> cloneRegion());
        Path invalid = Files.createTempFile(fixture.getParent(), "addon-invalid-", ".bp");
        try {
            List<byte[]> malformed = List.of(
                new byte[] {1, 2},
                ByteBuffer.allocate(8).putInt(0).putInt(0).array(),
                ByteBuffer.allocate(8).putInt(182827830).putInt(1024 * 1024 + 1).array(),
                ByteBuffer.allocate(9).putInt(182827830).putInt(32).put((byte) 0).array()
            );
            for (byte[] bytes : malformed) {
                Files.write(invalid, bytes);
                boolean failed = false;
                try {
                    Axiom613Adapter.INSTANCE.prepare(invalid);
                } catch (Exception expected) {
                    require(expected instanceof IOException, "Unexpected malformed-source failure: " + expected);
                    failed = true;
                }
                require(failed, "Malformed blueprint unexpectedly prepared");
                context.runOnClient(client -> require(cloneRegion() == existing, "Malformed source changed active placement"));
            }
            Files.delete(invalid);
            boolean failed = false;
            try {
                Axiom613Adapter.INSTANCE.prepare(invalid);
            } catch (Exception expected) {
                require(expected instanceof IOException, "Unexpected missing-source failure: " + expected);
                failed = true;
            }
            require(failed, "Missing blueprint unexpectedly prepared");
            context.runOnClient(client -> require(cloneRegion() == existing, "Missing source changed active placement"));
        } finally {
            Files.deleteIfExists(invalid);
        }
    }

    private static void verifySurfaceAnchoring(ClientGameTestContext context, TestSingleplayerContext world, Path library) throws IOException {
        BlockPos support = new BlockPos(0, 98, 2);
        world.getServer().runCommand("setblock 0 98 2 bedrock");
        context.waitFor(client -> client.level.getBlockState(support).is(Blocks.BEDROCK));
        try {
            for (int minimumY : new int[] {-3, 0, 2}) {
                Path file = Files.createTempFile(library, "addon-surface-", ".bp");
                try {
                    context.runOnClient(client -> {
                        ChunkedBlockRegion region = new ChunkedBlockRegion();
                        region.addBlockWithoutDirty(0, minimumY, 0, Blocks.STONE.defaultBlockState());
                        region.addBlockWithoutDirty(1, minimumY + 2, 0, Blocks.LAPIS_BLOCK.defaultBlockState());
                        region.addBlockWithoutDirty(0, minimumY + 5, 2, Blocks.GOLD_BLOCK.defaultBlockState());
                        region.dirtyAll();
                        BlueprintHeader header = new BlueprintHeader("Surface anchor " + minimumY, "automated test", List.of(), 0, 0, false, 3, false);
                        try (NativeImage thumbnail = new NativeImage(1, 1, true); OutputStream output = Files.newOutputStream(file)) {
                            BlueprintIo.write(output, header, thumbnail, region, new Long2ObjectOpenHashMap<CompressedBlockEntity>(), List.of());
                        }
                        BuilderToolManager.setToolSlotActive(false);
                    });
                    context.runOnClient(client -> client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                        new net.minecraft.world.phys.Vec3(support.getX() + 0.5, support.getY() + 0.95, support.getZ() + 0.5)));
                    context.waitTicks(2);
                    context.waitFor(client -> client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                        && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(support)
                        && hit.getDirection() == net.minecraft.core.Direction.UP);
                    context.runOnClient(client -> {
                        BlueprintReference reference = new BlueprintReference(1, file.getFileName().toString(), "Surface anchor " + minimumY);
                        ItemStack stack = BlueprintItems.INSTANCE.createStack(reference);
                        client.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                        stack.use(client.level, client.player, InteractionHand.MAIN_HAND);
                    });
                    context.waitFor(client -> cloneRegion() != null);
                    context.runOnClient(client -> {
                        assertBuilderMode(client);
                        require(cloneRegion().min().getY() == minimumY, "Fixture lost its source vertical origin");
                        require(cloneRegion().min().getY() + cloneOffset().getY() == support.getY() + 1,
                            "Initial preview bottom did not rest on the hit surface for source minY=" + minimumY);
                    });
                    assertOnlySupport(world, support);
                    context.getInput().lookAt(0, 90);
                    context.runOnClient(client -> {
                        int bottom = cloneRegion().min().getY() + cloneOffset().getY();
                        require(cloneTool().scroll(1), "Native Clone did not handle downward scroll");
                        require(cloneRegion().min().getY() + cloneOffset().getY() == bottom - 1,
                            "Surface anchoring prevented manually lowering the preview");
                    });
                    assertOnlySupport(world, support);
                    context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing());
                    context.runOnClient(client -> require(cloneRegion() == null && cloneOffset() == null,
                        "Cancel retained surface-anchor preview data"));
                    assertOnlySupport(world, support);
                } finally {
                    context.runOnClient(client -> {
                        cloneTool().reset(false);
                        BuilderToolManager.setToolSlotActive(false);
                    });
                    Files.deleteIfExists(file);
                }
            }
        } finally {
            world.getServer().runCommand("setblock 0 98 2 air");
            context.waitFor(client -> client.level.getBlockState(support).isAir());
            context.getInput().lookAt(0, 0);
        }
        assertWorldEmpty(world);
    }

    private static void assertOnlySupport(TestSingleplayerContext world, BlockPos support) {
        world.getServer().runOnServer(server -> {
            for (BlockPos position : BlockPos.betweenClosed(MIN, MAX)) {
                var state = server.overworld().getBlockState(position);
                require(position.equals(support) ? state.is(Blocks.BEDROCK) : state.isAir(),
                    "Unconfirmed surface preview changed world at " + position);
            }
        });
    }

    private static void verifyAirborneTargeting(ClientGameTestContext context, TestSingleplayerContext world,
                                                BlueprintReference reference) {
        BlockPos support = new BlockPos(0, 98, 2);
        world.getServer().runCommand("setblock 0 98 2 bedrock");
        context.waitFor(client -> client.level.getBlockState(support).is(Blocks.BEDROCK));
        try {
            for (BlockPos position : List.of(new BlockPos(0, 180, 0), new BlockPos(-48, 180, -32), new BlockPos(0, 500, 0))) {
                world.getServer().runCommand("tp @a " + (double) position.getX() + " " + (double) position.getY() + " " + (double) position.getZ());
                context.waitFor(client -> client.player.position().distanceToSqr(
                    new net.minecraft.world.phys.Vec3(position.getX(), position.getY(), position.getZ())) < 0.01);
                context.runOnClient(client -> client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    new net.minecraft.world.phys.Vec3(support.getX() + 0.5, support.getY() + 0.95, support.getZ() + 0.5)));
                context.waitTicks(2);
                context.runOnClient(client -> {
                    require(client.hitResult != null && client.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS,
                        "Airborne fixture must aim beyond normal interaction reach");
                    ItemStack stack = BlueprintItems.INSTANCE.createStack(reference);
                    client.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    stack.use(client.level, client.player, InteractionHand.MAIN_HAND);
                });
                context.waitFor(client -> cloneRegion() != null);
                context.runOnClient(client -> {
                    assertBuilderMode(client);
                    require(cloneOffset().equals(support.above()),
                        "Airborne preview missed aimed ground from " + position + ": " + cloneOffset());
                    require(reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())),
                        "Airborne targeting changed the blueprint item");
                });
                assertOnlySupport(world, support);
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing());
                context.runOnClient(client -> BuilderToolManager.setToolSlotActive(false));
            }
            context.getInput().lookAt(0, -90);
            context.runOnClient(client -> {
                ItemStack stack = BlueprintItems.INSTANCE.createStack(reference);
                client.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                stack.use(client.level, client.player, InteractionHand.MAIN_HAND);
            });
            context.waitFor(client -> cloneRegion() != null);
            context.runOnClient(client -> require(cloneOffset().equals(BlockPos.containing(
                client.player.getEyePosition().add(client.player.getLookAngle().scale(5.0)))),
                "Aiming into empty sky no longer starts a nearby floating preview"));
            assertOnlySupport(world, support);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing());
        } finally {
            context.runOnClient(client -> {
                cloneTool().reset(false);
                BuilderToolManager.setToolSlotActive(false);
            });
            world.getServer().runCommand("setblock 0 98 2 air");
            context.waitFor(client -> client.level.getBlockState(support).isAir());
            world.getServer().runCommand("tp @a 0.0 100.0 0.0 0 0");
            context.waitFor(client -> client.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(0, 100, 0)) < 0.01);
            context.getInput().lookAt(0, 0);
        }
        assertWorldEmpty(world);
    }

    private static void writeFixture(Path file) throws IOException {
        writeFixture(file, "Placement proof", List.of("stone", "test"));
    }

    private static void writeFixture(Path file, String name, List<String> tags) throws IOException {
        ChunkedBlockRegion region = new ChunkedBlockRegion();
        region.addBlockWithoutDirty(0, 0, 0, Blocks.STONE.defaultBlockState());
        region.addBlockWithoutDirty(1, 0, 0, Blocks.LAPIS_BLOCK.defaultBlockState());
        region.addBlockWithoutDirty(0, 0, 2, Blocks.GOLD_BLOCK.defaultBlockState());
        region.dirtyAll();
        BlueprintHeader header = new BlueprintHeader(name, "automated test", tags, 0, 0, false, 3, false);
        try (NativeImage thumbnail = new NativeImage(128, 64, true); OutputStream output = Files.newOutputStream(file)) {
            for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) {
                thumbnail.setPixel(x, y, y < 32 ? 0xff0000ff : 0xffff0000);
            }
            CompoundTag entity = new CompoundTag();
            entity.putString("id", "minecraft:armor_stand");
            entity.putBoolean("NoGravity", true);
            entity.putBoolean("Invulnerable", true);
            ListTag entityTags = new ListTag();
            entityTags.add(StringTag.valueOf("blueprint_saved"));
            entity.put("Tags", entityTags);
            EntityDataUtils.setEntityPosition(entity, new net.minecraft.world.phys.Vec3(0.5, 1, 0.5));
            BlueprintIo.write(output, header, thumbnail, region, new Long2ObjectOpenHashMap<CompressedBlockEntity>(), List.of(entity));
        }
    }

    private static int countTaggedEntities(net.minecraft.server.level.ServerLevel level, String tag) {
        int count = 0;
        for (var entity : level.getAllEntities()) if (entity.entityTags().contains(tag)) count++;
        return count;
    }

    private static int countFixtureBlocks(net.minecraft.world.level.Level level) {
        int count = 0;
        for (BlockPos position : BlockPos.betweenClosed(MIN, MAX)) {
            var state = level.getBlockState(position);
            if (state.is(Blocks.STONE) || state.is(Blocks.LAPIS_BLOCK) || state.is(Blocks.GOLD_BLOCK)) count++;
        }
        return count;
    }

    private static void assertWorldEmpty(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            for (BlockPos position : BlockPos.betweenClosed(MIN, MAX)) {
                require(server.overworld().getBlockState(position).isAir(), "Pending/cancelled placement changed world at " + position);
            }
        });
    }

    private static CloneBuilderTool cloneTool() {
        return BuilderToolManager.getTools().stream().filter(CloneBuilderTool.class::isInstance)
            .map(CloneBuilderTool.class::cast).findFirst().orElseThrow();
    }

    private static Object cloneField(String name) {
        try {
            var field = CloneBuilderTool.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(cloneTool());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect native Clone " + name, exception);
        }
    }

    private static ChunkedBlockRegion cloneRegion() {
        return (ChunkedBlockRegion) cloneField("blockRegion");
    }

    private static BlockPos.MutableBlockPos cloneOffset() {
        return (BlockPos.MutableBlockPos) cloneField("regionOffset");
    }

    private static void assertBuilderMode(Minecraft client) {
        require(client.player.isCreative(), "Blueprint placement changed creative game mode");
        require(!EditorUI.isEnabled() && !EditorUI.isActive(), "Blueprint placement opened the editor");
        require(!Placement.INSTANCE.isPlacing(), "Blueprint placement used the editor clipboard");
        require(BuilderToolManager.isToolSlotActive() && BuilderToolManager.selectedToolIs(CloneBuilderTool.class),
            "Blueprint placement did not select Axiom's native tenth-slot Clone tool");
    }

    private static void selectBuilderTool(Class<? extends BuilderTool> type) {
        var tools = BuilderToolManager.getTools();
        int index = -1;
        for (int candidate = 0; candidate < tools.size(); candidate++) {
            if (type.isInstance(tools.get(candidate))) index = candidate;
        }
        require(index >= 0, "Missing native builder tool " + type.getSimpleName());
        BuilderToolManager.setToolSlotSelected(index);
    }

    private static void assertRestoredTool(ClientGameTestContext context, Class<? extends BuilderTool> type) {
        context.waitFor(client -> BuilderToolManager.selectedToolIs(type));
        context.runOnClient(client -> {
            require(client.player.isCreative() && !EditorUI.isEnabled() && !Placement.INSTANCE.isPlacing(),
                "Restoring the previous builder tool changed game mode or opened the editor");
            require(cloneRegion() == null && cloneOffset() == null, "Finished blueprint retained native Clone preview data");
        });
    }

    private static void verifyPlacementCleanup(ClientGameTestContext context, TestSingleplayerContext world, Path fixture) {
        context.runOnClient(client -> {
            selectBuilderTool(CloneBuilderTool.class);
            require(Axiom613Adapter.INSTANCE.start(Axiom613Adapter.INSTANCE.prepare(fixture), new BlockPos(0, 100, 0)),
                "Could not start same-tool restoration proof");
        });
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing());
        assertRestoredTool(context, CloneBuilderTool.class);
        context.runOnClient(client -> {
            selectBuilderTool(MoveBuilderTool.class);
            require(Axiom613Adapter.INSTANCE.start(Axiom613Adapter.INSTANCE.prepare(fixture), new BlockPos(0, 100, 0)),
                "Could not start slot-exit proof");
            BuilderToolManager.setToolSlotActive(false);
        });
        assertRestoredTool(context, MoveBuilderTool.class);
        context.runOnClient(client -> {
            require(!BuilderToolManager.isToolSlotActive(), "Restoring a tool reactivated the exited tenth slot");
            require(Axiom613Adapter.INSTANCE.start(Axiom613Adapter.INSTANCE.prepare(fixture), new BlockPos(0, 100, 0)),
                "Could not start disconnect cleanup proof");
            Axiom613Adapter.INSTANCE.clearImportedPreview();
            selectBuilderTool(StackBuilderTool.class);
        });
        context.waitTicks(2);
        assertRestoredTool(context, StackBuilderTool.class);
        assertWorldEmpty(world);
        context.runOnClient(client -> {
            selectBuilderTool(MoveBuilderTool.class);
            require(Axiom613Adapter.INSTANCE.start(Axiom613Adapter.INSTANCE.prepare(fixture), new BlockPos(0, 100, 0)),
                "Could not start Move restoration after confirmation");
        });
        context.waitTicks(2);
        context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        try {
            world.getServer().waitFor(server -> countFixtureBlocks(server.overworld()) == 3
                && countTaggedEntities(server.overworld(), "blueprint_saved") == 1);
            context.waitTicks(10);
            context.runOnClient(client -> {
                require(BuilderToolManager.selectedToolIs(CloneBuilderTool.class),
                    "Confirm click reached the previous tool before its release");
            });
            world.getServer().runOnServer(server -> require(countTaggedEntities(server.overworld(), "blueprint_saved") == 1,
                "Holding confirm pasted a second blueprint"));
        } finally {
            context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        }
        assertRestoredTool(context, MoveBuilderTool.class);
        context.runOnClient(client -> require(!Axiom613Adapter.INSTANCE.isPlacing(),
            "Confirmation left a selection active in the restored Move tool"));
        context.runOnClient(client -> UserAction.UNDO.call(null));
        world.getServer().waitFor(server -> countFixtureBlocks(server.overworld()) == 0
            && countTaggedEntities(server.overworld(), "blueprint_saved") == 0);
        assertWorldEmpty(world);
    }

    /** Axiom handles rotate/flip from keybinds during world rendering, not EditorUI actions. */
    private static void verifyNativeTransforms(ClientGameTestContext context) {
        ChunkedBlockRegion original = context.computeOnClient(client -> cloneRegion());
        context.getInput().holdControl();
        try {
            context.getInput().holdKey(GLFW.GLFW_KEY_R);
            context.waitFor(client -> cloneRegion() != original);
            context.getInput().releaseKey(GLFW.GLFW_KEY_R);
            context.waitTicks(2);
            ChunkedBlockRegion rotated = context.computeOnClient(client -> cloneRegion());
            context.getInput().holdKey(GLFW.GLFW_KEY_F);
            context.waitFor(client -> cloneRegion() != rotated);
            context.getInput().releaseKey(GLFW.GLFW_KEY_F);
        } finally {
            context.getInput().releaseKey(GLFW.GLFW_KEY_R);
            context.getInput().releaseKey(GLFW.GLFW_KEY_F);
            context.getInput().releaseControl();
        }
        context.runOnClient(client -> {
            assertBuilderMode(client);
            require(Axiom613Adapter.INSTANCE.isPlacing(), "Native transform ended the preview");
        });
    }

    private static void verifyExistingSelectionPreserved(ClientGameTestContext context, Path fixture) {
        context.runOnClient(client -> {
            CloneBuilderTool clone = cloneTool();
            clone.setPos1(new BlockPos(1, 100, 1));
            clone.setPos2(new BlockPos(2, 100, 2));
            var before = clone.getSelectionRestore();
            int selected = BuilderToolManager.getToolSlotSelected();
            boolean active = BuilderToolManager.isToolSlotActive();
            require(!Axiom613Adapter.INSTANCE.start(Axiom613Adapter.INSTANCE.prepare(fixture), new BlockPos(0, 100, 0)),
                "Blueprint overwrote an existing Clone selection");
            require(before.equals(clone.getSelectionRestore()), "Existing Clone selection changed");
            require(selected == BuilderToolManager.getToolSlotSelected() && active == BuilderToolManager.isToolSlotActive(),
                "Rejected blueprint changed selected builder tool");
            clone.reset(false);
        });
    }

    private static void verifyThumbnailOrientation(ClientGameTestContext context, Path fixture) {
        context.runOnClient(client -> {
            byte[] png = Axiom613Adapter.INSTANCE.readThumbnailPng(fixture);
            require(png != null, "Asymmetric thumbnail fixture missing");
            int[] pixels;
            try {
                var downsample = BlueprintThumbnails.class.getDeclaredMethod("downsample", byte[].class);
                downsample.setAccessible(true);
                pixels = (int[]) downsample.invoke(BlueprintThumbnails.INSTANCE, (Object) png);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Cannot inspect normalized thumbnail pixels", exception);
            }
            require(pixels[16 * 64 + 8] == 0xffff0000, "Framebuffer bottom row did not become upright thumbnail top");
            require(pixels[47 * 64 + 8] == 0xff0000ff, "Framebuffer top row did not become upright thumbnail bottom");
            require(pixels[0] == 0, "Non-square thumbnail lost transparent aspect-ratio padding");
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
