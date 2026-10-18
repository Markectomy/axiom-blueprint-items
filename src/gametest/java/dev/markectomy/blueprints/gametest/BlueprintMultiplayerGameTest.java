package dev.markectomy.blueprints.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.axiom.AxiomClient;
import com.moulberry.axiom.UserAction;
import com.moulberry.axiom.blueprint.BlueprintHeader;
import com.moulberry.axiom.blueprint.BlueprintIo;
import com.moulberry.axiom.buildertools.BuilderTool;
import com.moulberry.axiom.buildertools.BuilderToolManager;
import com.moulberry.axiom.buildertools.CloneBuilderTool;
import com.moulberry.axiom.buildertools.MoveBuilderTool;
import com.moulberry.axiom.clipboard.Placement;
import com.moulberry.axiom.editor.EditorUI;
import com.moulberry.axiom.editor.tutorial.TutorialManager;
import com.moulberry.axiom.render.regions.ChunkedBlockRegion;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.utils.EntityDataUtils;
import com.moulberry.axiom.world_modification.CompressedBlockEntity;
import com.moulberry.axiom.world_modification.Dispatcher;
import dev.markectomy.blueprints.BlueprintItems;
import dev.markectomy.blueprints.client.integration.Axiom613Adapter;
import dev.markectomy.blueprints.client.integration.BlueprintCloneBridge;
import dev.markectomy.blueprints.domain.BlueprintReference;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Optional smoke test against a disposable, separately running loopback server. */
public final class BlueprintMultiplayerGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlueprintMultiplayerGameTest");
    private static final BlockPos SUPPORT = new BlockPos(0, 98, 3);
    private static final BlockPos TARGET = SUPPORT.above();
    private static final String SAVED_ENTITY = "blueprint_multiplayer_saved";

    @Override
    public void runTest(ClientGameTestContext context) {
        String configuration = System.getProperty("blueprints.localhostControl");
        if (configuration == null) return;
        try (Control control = Control.load(Path.of(configuration))) {
            require(control.status().get("server_running").getAsBoolean(), "Localhost server is not running");
            connect(context, control.address);
            Path fixture = context.computeOnClient(client -> {
                client.gui.setScreen(null);
                TutorialManager.skip();
                require(!client.isLocalServer() && client.getSingleplayerServer() == null,
                    "Multiplayer smoke test unexpectedly joined an integrated server");
                return Files.createTempFile(Axiom613Adapter.INSTANCE.libraryRoot(), "addon-multiplayer-", ".bp");
            });
            boolean copyEntities = context.computeOnClient(client -> BuilderToolManager.copyEntities);
            try {
                prepareWorld(context, control);
                context.waitFor(client -> AxiomClient.isAxiomActive() && AxiomClient.hasPermission(AxiomPermission.BUILD_SECTION)
                    && Axiom613Adapter.INSTANCE.isAvailable(), 600);
                context.runOnClient(client -> writeFixture(fixture));
                BlueprintReference reference = new BlueprintReference(1, fixture.getFileName().toString(), "Multiplayer proof");
                checkpoint("old creative hotbar item converts before reaching remote server");
                verifyLegacyPacket(context, control, reference);
                checkpoint("vanilla creative token accepted by server without addon");
                giveToken(context, reference);
                control.await(context, "if items entity Player0 hotbar.0 " + predicate(reference), "creative token acceptance");
                String inventory = control.command("data get entity Player0 Inventory").toString();
                require(inventory.contains(reference.getPath()) && inventory.contains("minecraft:paper"),
                    "Server did not retain the paper token's library link");
                require(!inventory.contains("axiom_blueprint_items:blueprint")
                        && !inventory.contains("minecraft:item_model"),
                    "Token exposed a custom item, component, or model to the server");
                checkpoint("offhand swap and chest quick-move preserve identity");
                verifyInventory(context, control, reference);
                checkpoint("drop and pickup preserve identity");
                verifyDropPickup(context, control, reference);
                checkpoint("remote reconnect preserves the server inventory link");
                control.command("save-all flush");
                disconnect(context);
                connect(context, control.address);
                context.waitFor(client -> reference.equals(BlueprintItems.INSTANCE.reference(client.player.getInventory().getItem(0))));
                control.await(context, "if items entity Player0 hotbar.0 " + predicate(reference), "reconnect persistence");
                preparePlayer(context, control);
                checkpoint("native repeat placement, saved entities, finish, restoration, and separate undo");
                verifyPlacement(context, control, reference);
                checkpoint("instant Shift placement, held-use suppression, and remote undo");
                verifyInstantPlacement(context, control, reference);
                checkpoint("server game-mode change cancels a pending preview");
                verifyRevocation(context, control, reference);
                checkpoint("dimension change clears the pending preview");
                verifyDimensionChange(context, control, reference);
                checkpoint("disconnect clears the pending preview");
                startPreview(context, reference);
                assertUnchanged(context, control);
                disconnect(context);
                context.runOnClient(client -> require(!Axiom613Adapter.INSTANCE.isPlacing()
                    && cloneRegion() == null && cloneOffset() == null, "Disconnect retained native preview state"));
                control.await(context, "if block 0 99 3 air if block 1 99 3 air if block 0 99 5 air"
                    + " unless entity @e[tag=" + SAVED_ENTITY + "]", "disconnect left the server unchanged");
                checkpoint("all localhost multiplayer checks passed");
            } finally {
                context.runOnClient(client -> {
                    Axiom613Adapter.INSTANCE.clearImportedPreview();
                    BuilderToolManager.copyEntities = copyEntities;
                    BuilderToolManager.setToolSlotActive(false);
                    if (client.level != null) client.disconnect(new TitleScreen(), false);
                });
                control.command("forceload remove -8 -8 12 12");
                Files.deleteIfExists(fixture);
            }
        } catch (IOException exception) {
            throw new AssertionError("Unable to prepare localhost multiplayer smoke test", exception);
        }
    }

    private static void prepareWorld(ClientGameTestContext context, Control control) {
        control.command("gamemode creative Player0");
        control.command("execute in minecraft:overworld run tp Player0 0.0 102.0 0.0");
        context.waitFor(client -> client.level != null
            && client.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD), 600);
        control.command("forceload add -8 -8 12 12");
        control.command("clear Player0");
        control.command("kill @e[type=item]");
        control.command("kill @e[tag=" + SAVED_ENTITY + "]");
        control.command("scoreboard objectives add blueprint_test dummy");
        control.command("fill -8 96 -8 12 110 12 air");
        control.command("fill -8 98 -8 12 98 12 bedrock");
        preparePlayer(context, control);
    }

    private static void preparePlayer(ClientGameTestContext context, Control control) {
        control.command("tp Player0 0.0 102.0 0.0 0 0");
        context.waitFor(client -> client.player != null && client.player.isCreative()
            && Math.abs(client.player.getY() - 102) < 1, 600);
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            TutorialManager.skip();
            client.player.getAbilities().flying = true;
            client.player.onUpdateAbilities();
            client.player.setDeltaMovement(Vec3.ZERO);
            BuilderToolManager.setToolSlotActive(false);
            client.player.getInventory().setSelectedSlot(0);
        });
        context.waitTicks(3);
    }

    private static void giveToken(ClientGameTestContext context, BlueprintReference reference) {
        context.runOnClient(client -> {
            ItemStack token = BlueprintItems.INSTANCE.createStack(reference);
            require(token.is(Items.PAPER) && token.getMaxStackSize() == 1, "Blueprint is not a non-stackable vanilla token");
            require(!token.hasNonDefault(DataComponents.ITEM_MODEL), "Token contains a custom synchronized item model");
            token.getComponentsPatch().entrySet().forEach(entry -> require(
                BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey()).getNamespace().equals("minecraft"),
                "Token contains a non-vanilla component"));
            client.player.getInventory().setSelectedSlot(0);
            client.player.getInventory().setItem(0, token);
            client.gameMode.handleCreativeModeItemAdd(token, 36);
        });
        context.waitTicks(3);
    }

    private static void verifyLegacyPacket(ClientGameTestContext context, Control control, BlueprintReference reference) {
        context.runOnClient(client -> {
            ItemStack legacy = new ItemStack(BlueprintItems.INSTANCE.getITEM());
            legacy.set(BlueprintItems.INSTANCE.getREFERENCE(), reference);
            client.player.getInventory().setSelectedSlot(0);
            client.player.getInventory().setItem(0, legacy);
            client.gameMode.handleCreativeModeItemAdd(legacy, 36);
        });
        control.await(context, "if items entity Player0 hotbar.0 " + predicate(reference), "legacy hotbar conversion");
        context.waitFor(client -> client.player.getInventory().getItem(0).is(Items.PAPER)
            && reference.equals(BlueprintItems.INSTANCE.reference(client.player.getInventory().getItem(0))));
        context.runOnClient(client -> client.gameMode.handleCreativeModeItemAdd(new ItemStack(BlueprintItems.INSTANCE.getITEM()), 36));
        context.waitTicks(5);
        context.runOnClient(client -> require(client.player != null && client.getConnection() != null,
            "Rejecting an invalid legacy stack disconnected the remote player"));
        control.await(context, "if items entity Player0 hotbar.0 " + predicate(reference), "invalid legacy stack rejection");
    }

    private static void verifyInventory(ClientGameTestContext context, Control control, BlueprintReference reference) {
        context.runOnClient(client -> client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId,
            36, 40, ContainerInput.SWAP, client.player));
        control.await(context, "if items entity Player0 weapon.offhand " + predicate(reference), "offhand swap");
        context.waitFor(client -> reference.equals(BlueprintItems.INSTANCE.reference(client.player.getOffhandItem())));
        context.runOnClient(client -> client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId,
            36, 40, ContainerInput.SWAP, client.player));
        control.await(context, "if items entity Player0 hotbar.0 " + predicate(reference), "swap back from offhand");
        control.command("setblock 2 99 0 chest");
        context.waitFor(client -> client.level.getBlockState(new BlockPos(2, 99, 0)).is(Blocks.CHEST));
        context.runOnClient(client -> {
            client.player.getInventory().setSelectedSlot(1);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(new Vec3(2.5, 100, 0.5), Direction.UP, new BlockPos(2, 99, 0), false));
        });
        context.waitFor(client -> client.player.containerMenu instanceof ChestMenu);
        context.runOnClient(client -> {
            var menu = client.player.containerMenu;
            client.gameMode.handleContainerInput(menu.containerId, menu.slots.size() - 9, 0, ContainerInput.QUICK_MOVE, client.player);
        });
        control.await(context, "if items block 2 99 0 container.0 " + predicate(reference), "chest transfer");
        context.waitFor(client -> reference.equals(BlueprintItems.INSTANCE.reference(client.player.containerMenu.getSlot(0).getItem())));
        context.runOnClient(client -> client.gameMode.handleContainerInput(client.player.containerMenu.containerId,
            0, 0, ContainerInput.QUICK_MOVE, client.player));
        context.waitFor(client -> findTokenSlot(client, reference, 27) >= 0);
        context.runOnClient(client -> {
            var menu = client.player.containerMenu;
            int slot = findTokenSlot(client, reference, 27);
            client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.SWAP, client.player);
            client.player.closeContainer();
            client.player.getInventory().setSelectedSlot(0);
        });
        control.await(context, "if items entity Player0 hotbar.0 " + predicate(reference), "chest return");
        control.command("setblock 2 99 0 air");
        context.waitFor(client -> client.player.containerMenu == client.player.inventoryMenu);
    }

    private static int findTokenSlot(Minecraft client, BlueprintReference reference, int first) {
        var menu = client.player.containerMenu;
        for (int slot = first; slot < menu.slots.size(); slot++) {
            if (reference.equals(BlueprintItems.INSTANCE.reference(menu.getSlot(slot).getItem()))) return slot;
        }
        return -1;
    }

    private static void verifyDropPickup(ClientGameTestContext context, Control control, BlueprintReference reference) {
        context.getInput().pressKey(clientOptions -> clientOptions.keyDrop);
        control.await(context, "if entity @e[type=item,nbt={Item:{id:\"minecraft:paper\"}}]", "vanilla item drop");
        context.waitFor(client -> client.player.getMainHandItem().isEmpty());
        control.command("execute as @e[type=item,nbt={Item:{id:\"minecraft:paper\"}}] at Player0 run tp @s ~ ~ ~");
        control.command("execute as @e[type=item,nbt={Item:{id:\"minecraft:paper\"}}] run data merge entity @s {PickupDelay:0s}");
        context.waitFor(client -> reference.equals(BlueprintItems.INSTANCE.reference(client.player.getInventory().getItem(0))));
        control.await(context, "if items entity Player0 hotbar.0 " + predicate(reference), "vanilla item pickup");
    }

    private static void verifyPlacement(ClientGameTestContext context, Control control, BlueprintReference reference) {
        context.runOnClient(client -> BuilderToolManager.copyEntities = true);
        startPreview(context, reference);
        context.runOnClient(client -> {
            require(!EditorUI.isEnabled() && !Placement.INSTANCE.isPlacing(), "Remote item opened editor placement");
            require(BuilderToolManager.selectedToolIs(CloneBuilderTool.class) && BuilderToolManager.isToolSlotActive(),
                "Remote item did not start tenth-slot Clone");
            require(cloneOffset().equals(TARGET), "Remote preview did not rest on the aimed floor: " + cloneOffset());
            require(!((BlueprintCloneBridge) cloneTool()).blueprints$hasPlaced(), "Remote preview already shows Finish");
        });
        assertUnchanged(context, control);
        context.waitTicks(2);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        control.await(context, "if block 0 99 3 stone if block 1 99 3 lapis_block if block 0 99 5 gold_block"
            + " if entity @e[tag=" + SAVED_ENTITY + ",limit=1]", "native Axiom confirmation");
        control.command("scoreboard players set saved_count blueprint_test 0");
        control.command("execute as @e[tag=" + SAVED_ENTITY + "] run scoreboard players add saved_count blueprint_test 1");
        control.await(context, "if score saved_count blueprint_test matches 1", "exactly one saved entity per confirmation");
        ChunkedBlockRegion original = context.computeOnClient(client -> cloneRegion());
        context.waitTicks(3);
        context.runOnClient(client -> {
            require(Axiom613Adapter.INSTANCE.isPlacing() && BuilderToolManager.selectedToolIs(CloneBuilderTool.class),
                "Remote confirmation ended the repeat-copy preview");
            require(((BlueprintCloneBridge) cloneTool()).blueprints$hasPlaced(), "Remote confirmation did not enable Finish");
        });
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.getInput().lookAt(-90, 0);
        context.runOnClient(client -> {
            require(cloneTool().scroll(4), "Remote next-copy preview could not move");
            require(cloneOffset().equals(TARGET.offset(4, 0, 0)), "Remote next copy moved to an unexpected position");
            require(((BlueprintCloneBridge) cloneTool()).blueprints$hasPlaced(), "Remote movement reverted Finish to Cancel");
        });
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        control.await(context, "if block 4 99 3 stone if block 5 99 3 lapis_block if block 4 99 5 gold_block", "second remote copy");
        control.command("scoreboard players set saved_count blueprint_test 0");
        control.command("execute as @e[tag=" + SAVED_ENTITY + "] run scoreboard players add saved_count blueprint_test 1");
        control.await(context, "if score saved_count blueprint_test matches 2", "one saved entity per remote copy");
        context.runOnClient(client -> require(cloneRegion() == original, "Second remote copy replaced the reusable preview"));
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing() && BuilderToolManager.selectedToolIs(MoveBuilderTool.class));
        context.runOnClient(client -> {
            require(!((BlueprintCloneBridge) cloneTool()).blueprints$hasPlaced(), "Remote finish retained its hint state");
            require(!BuilderToolManager.isToolSlotActive(), "Remote finish left the tenth hotbar slot selected");
            require(client.player.getInventory().getSelectedSlot() == 0
                    && reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())),
                "Remote finish did not return to the held blueprint");
        });
        context.waitFor(client -> Dispatcher.getHistoryDataCount() > 0);
        context.runOnClient(client -> UserAction.UNDO.call(null));
        control.await(context, "if block 4 99 3 air if block 5 99 3 air if block 4 99 5 air"
            + " if block 0 99 3 stone if entity @e[tag=" + SAVED_ENTITY + ",limit=1]", "undo only the second remote copy");
        context.runOnClient(client -> UserAction.UNDO.call(null));
        control.await(context, "if block 0 99 3 air if block 1 99 3 air if block 0 99 5 air"
            + " unless entity @e[tag=" + SAVED_ENTITY + "]", "native Axiom undo");
        control.await(context, "if items entity Player0 hotbar.0 " + predicate(reference), "placement did not consume token");
        startPreview(context, reference);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitFor(client -> !Axiom613Adapter.INSTANCE.isPlacing() && !BuilderToolManager.isToolSlotActive());
        context.runOnClient(client -> {
            require(!BuilderToolManager.isToolSlotActive(), "Remote cancellation left the tenth hotbar slot selected");
            require(client.player.getInventory().getSelectedSlot() == 0
                    && reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())),
                "Remote cancellation did not return to the held blueprint");
        });
        assertUnchanged(context, control);
    }

    private static void verifyRevocation(ClientGameTestContext context, Control control, BlueprintReference reference) {
        startPreview(context, reference);
        control.command("gamemode survival Player0");
        context.waitFor(client -> !client.player.isCreative() && !Axiom613Adapter.INSTANCE.isPlacing());
        context.runOnClient(client -> require(cloneRegion() == null && cloneOffset() == null,
            "Server game-mode change retained native Clone data"));
        assertUnchanged(context, control);
        control.command("gamemode creative Player0");
        context.waitFor(client -> client.player.isCreative() && Axiom613Adapter.INSTANCE.isAvailable());
        preparePlayer(context, control);
        context.runOnClient(client -> require(!Axiom613Adapter.INSTANCE.isPlacing(), "Creative restoration revived a stale preview"));
    }

    private static void verifyInstantPlacement(ClientGameTestContext context, Control control, BlueprintReference reference) {
        context.runOnClient(client -> {
            selectBuilderTool(MoveBuilderTool.class);
            BuilderToolManager.setToolSlotActive(false);
            BuilderToolManager.copyEntities = true;
            client.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(0.5, 98.95, 3.5));
        });
        context.waitTicks(2);
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        try {
            control.await(context, "if block 0 99 3 stone if block 1 99 3 lapis_block if block 0 99 5 gold_block"
                + " if entity @e[tag=" + SAVED_ENTITY + ",limit=1]", "instant remote paste");
            context.waitTicks(16);
            context.runOnClient(client -> {
                require(!Axiom613Adapter.INSTANCE.isPlacing() && !EditorUI.isEnabled() && !Placement.INSTANCE.isPlacing()
                    && cloneRegion() == null && cloneOffset() == null, "Instant remote paste left a placement active");
                require(!BuilderToolManager.isToolSlotActive() && BuilderToolManager.selectedToolIs(MoveBuilderTool.class)
                    && client.player.getInventory().getSelectedSlot() == 0, "Instant remote paste changed the hotbar or tool");
            });
            control.command("scoreboard players set saved_count blueprint_test 0");
            control.command("execute as @e[tag=" + SAVED_ENTITY + "] run scoreboard players add saved_count blueprint_test 1");
            control.await(context, "if score saved_count blueprint_test matches 1 if block 0 100 3 air",
                "held instant-use did not repeat");
        } finally {
            context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        }
        context.waitTicks(2);
        context.runOnClient(client -> UserAction.UNDO.call(null));
        control.await(context, "if block 0 99 3 air if block 1 99 3 air if block 0 99 5 air"
            + " unless entity @e[tag=" + SAVED_ENTITY + "]", "instant remote undo");
        control.await(context, "if items entity Player0 hotbar.0 " + predicate(reference), "instant paste kept its reusable token");
    }

    private static void startPreview(ClientGameTestContext context, BlueprintReference reference) {
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            selectBuilderTool(MoveBuilderTool.class);
            BuilderToolManager.setToolSlotActive(false);
            client.player.getInventory().setSelectedSlot(0);
            require(reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())),
                "Remote inventory lost blueprint identity before use");
            client.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(0.5, 98.95, 3.5));
        });
        context.waitTicks(2);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.waitFor(client -> Axiom613Adapter.INSTANCE.isPlacing() && cloneRegion() != null);
    }

    private static void verifyDimensionChange(ClientGameTestContext context, Control control, BlueprintReference reference) {
        startPreview(context, reference);
        control.command("execute in minecraft:the_nether run tp Player0 0.0 110.0 0.0");
        context.waitFor(client -> client.level != null
            && client.level.dimension().equals(net.minecraft.world.level.Level.NETHER)
            && !Axiom613Adapter.INSTANCE.isPlacing(), 600);
        context.runOnClient(client -> require(cloneRegion() == null && cloneOffset() == null,
            "Changing dimensions retained a blueprint from the previous world"));
        assertUnchanged(context, control);
        control.await(context, "in minecraft:the_nether unless entity @e[tag=" + SAVED_ENTITY + "]",
            "dimension change did not paste the saved entity into the destination world");
        control.command("execute in minecraft:overworld run tp Player0 0.0 102.0 0.0");
        context.waitFor(client -> client.level != null
            && client.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)
            && Axiom613Adapter.INSTANCE.isAvailable(), 600);
        preparePlayer(context, control);
        context.runOnClient(client -> require(!Axiom613Adapter.INSTANCE.isPlacing(),
            "Returning to the original dimension revived a stale blueprint"));
    }

    private static void assertUnchanged(ClientGameTestContext context, Control control) {
        control.await(context, "if block 0 99 3 air if block 1 99 3 air if block 0 99 5 air"
            + " unless entity @e[tag=" + SAVED_ENTITY + "]", "unconfirmed preview left server world unchanged");
    }

    private static void connect(ClientGameTestContext context, String address) {
        context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client,
            ServerAddress.parseString(address), new ServerData("Blueprint localhost test", address, ServerData.Type.OTHER), false, null));
        context.waitFor(client -> client.player != null && client.level != null && !client.isLocalServer(), 600);
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            TutorialManager.skip();
        });
    }

    private static void disconnect(ClientGameTestContext context) {
        context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
        context.waitFor(client -> client.player == null && client.level == null);
        context.waitTicks(5);
    }

    private static String predicate(BlueprintReference reference) {
        return "minecraft:paper[minecraft:custom_data~{axiom_blueprint_items:{version:1,path:"
            + new com.google.gson.Gson().toJson(reference.getPath()) + "}},minecraft:max_stack_size=1]";
    }

    private static void writeFixture(Path file) throws IOException {
        ChunkedBlockRegion region = new ChunkedBlockRegion();
        region.addBlockWithoutDirty(0, 0, 0, Blocks.STONE.defaultBlockState());
        region.addBlockWithoutDirty(1, 0, 0, Blocks.LAPIS_BLOCK.defaultBlockState());
        region.addBlockWithoutDirty(0, 0, 2, Blocks.GOLD_BLOCK.defaultBlockState());
        region.dirtyAll();
        BlueprintHeader header = new BlueprintHeader("Multiplayer proof", "", List.of("test"), 0, 0, false, 3, false);
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        entity.putBoolean("NoGravity", true);
        entity.putBoolean("Invulnerable", true);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(SAVED_ENTITY));
        entity.put("Tags", tags);
        EntityDataUtils.setEntityPosition(entity, new Vec3(0.5, 1, 0.5));
        try (NativeImage thumbnail = new NativeImage(16, 16, true); OutputStream output = Files.newOutputStream(file)) {
            BlueprintIo.write(output, header, thumbnail, region, new Long2ObjectOpenHashMap<CompressedBlockEntity>(), List.of(entity));
        }
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

    private static BlockPos cloneOffset() {
        return (BlockPos) cloneField("regionOffset");
    }

    private static void selectBuilderTool(Class<? extends BuilderTool> type) {
        var tools = BuilderToolManager.getTools();
        for (int index = 0; index < tools.size(); index++) {
            if (type.isInstance(tools.get(index))) {
                BuilderToolManager.setToolSlotSelected(index);
                return;
            }
        }
        throw new AssertionError("Missing native builder tool " + type.getSimpleName());
    }

    private static void checkpoint(String message) {
        LOGGER.info("Multiplayer checkpoint: {}", message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Control implements AutoCloseable {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        private final URI endpoint;
        private final String token;
        private final String address;
        private final String run = UUID.randomUUID().toString().replace("-", "");
        private int sequence;

        private Control(URI endpoint, String token, String address) {
            this.endpoint = endpoint;
            this.token = token;
            this.address = address;
            require(endpoint.getScheme().equals("http") && endpoint.getHost().equals("127.0.0.1"),
                "Test controller must use loopback HTTP");
            require(address.startsWith("127.0.0.1:") || address.startsWith("localhost:"),
                "Multiplayer smoke test must connect to localhost");
        }

        private static Control load(Path file) throws IOException {
            JsonObject settings = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return new Control(URI.create(settings.get("url").getAsString()), settings.get("token").getAsString(),
                settings.get("server_address").getAsString());
        }

        private JsonObject status() {
            return request("/status", null);
        }

        private JsonObject command(String command) {
            JsonObject body = new JsonObject();
            body.addProperty("command", command);
            body.addProperty("wait", 0.15);
            return request("/command", body);
        }

        private void await(ClientGameTestContext context, String condition, String description) {
            String marker = "blueprint_mp_" + run + "_" + ++sequence;
            String command = "execute " + condition + " run say " + marker;
            JsonObject last = null;
            for (int attempt = 0; attempt < 60; attempt++) {
                last = command(command);
                for (var line : last.getAsJsonArray("lines")) {
                    if (line.getAsString().contains(marker)) return;
                }
                context.waitTicks(2);
            }
            throw new AssertionError("Localhost server failed " + description + ": " + last + "; status: " + status());
        }

        private JsonObject request(String path, JsonObject body) {
            var builder = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token);
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            try {
                var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                require(response.statusCode() == 200, "Localhost controller returned HTTP " + response.statusCode());
                JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
                require(result.get("server_running").getAsBoolean(), "Localhost server stopped during smoke test");
                return result;
            } catch (IOException exception) {
                throw new AssertionError("Localhost controller request failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Localhost controller request interrupted", exception);
            }
        }

        @Override
        public void close() {
            http.close();
        }
    }
}
