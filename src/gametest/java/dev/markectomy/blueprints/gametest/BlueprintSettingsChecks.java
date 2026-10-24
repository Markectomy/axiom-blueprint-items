package dev.markectomy.blueprints.gametest;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.markectomy.blueprints.BlueprintItems;
import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings;
import dev.markectomy.blueprints.client.config.PreviewSettings;
import dev.markectomy.blueprints.client.render.BlueprintThumbnails;
import dev.markectomy.blueprints.client.ui.BlueprintSettingsScreen;
import dev.markectomy.blueprints.domain.BlueprintReference;
import dev.markectomy.blueprints.domain.InstantPlacementSpeed;
import dev.markectomy.blueprints.domain.PreviewStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

final class BlueprintSettingsChecks {
    private BlueprintSettingsChecks() {
    }

    static void run(ClientGameTestContext context, BlueprintReference reference) throws IOException {
        Path settings = FabricLoader.getInstance().getConfigDir().resolve(PreviewSettings.FILE_NAME);
        byte[] original = Files.exists(settings) ? Files.readAllBytes(settings) : null;
        PreviewStyle previousStyle = context.computeOnClient(client -> BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle());
        InstantPlacementSpeed previousSpeed = context.computeOnClient(client ->
            BlueprintPreviewSettings.INSTANCE.getCurrent().getInstantPlacementSpeed());
        boolean previousAirOnly = context.computeOnClient(client -> BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly());
        Screen previous = context.computeOnClient(client -> client.gui.screen());
        Screen parent = context.computeOnClient(client -> new ParentScreen());
        int[] window = context.computeOnClient(client -> new int[] {
            client.getWindow().getWidth(), client.getWindow().getHeight(), client.options.guiScale().get()
        });
        try {
            context.runOnClient(client -> {
                BlueprintPreviewSettings.INSTANCE.setStyle(PreviewStyle.HIGH_QUALITY);
                BlueprintPreviewSettings.INSTANCE.setInstantPlacementSpeed(InstantPlacementSpeed.NORMAL);
                BlueprintPreviewSettings.INSTANCE.setAirOnly(false);
                client.gui.setScreen(configurationScreen(parent));
                require(client.gui.screen() instanceof BlueprintSettingsScreen, "Config factory did not open blueprint settings");
                require(mode(client).getValue() == PreviewStyle.HIGH_QUALITY, "Settings did not show the current High quality mode");
                require(repeatSpeed(client).getValue() == InstantPlacementSpeed.NORMAL,
                    "Settings did not show the normal repeat speed");
                require(!airOnly(client).selected(), "Settings did not show the current air-only state");
            });
            waitForPreview(context, reference, PreviewStyle.HIGH_QUALITY);
            waitForSave(context);
            requireSavedStyle(settings, PreviewStyle.HIGH_QUALITY);
            var highQuality = context.computeOnClient(client -> BlueprintThumbnails.INSTANCE.view(reference));
            context.runOnClient(client -> {
                selectRepeatSpeed(client, InstantPlacementSpeed.FAST);
                require(BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle() == PreviewStyle.HIGH_QUALITY,
                    "Changing repeat speed changed the preview preset");
                var view = BlueprintThumbnails.INSTANCE.view(reference);
                require(view.getHighQuality() && view.getRevision() == highQuality.getRevision()
                    && view.getTexture().equals(highQuality.getTexture()),
                    "Changing repeat speed invalidated a loaded preview");
                require(reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())),
                    "Changing repeat speed changed an inventory link");
            });
            waitForSave(context);
            requireSavedSpeed(settings, InstantPlacementSpeed.FAST);
            context.runOnClient(client -> {
                selectRepeatSpeed(client, InstantPlacementSpeed.SLOW);
                clickControl(client, airOnly(client));
                require(BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly(), "Air-only checkbox did not apply immediately");
                require(BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle() == PreviewStyle.HIGH_QUALITY,
                    "Changing air-only placement changed the preview preset");
                require(reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())),
                    "Changing air-only placement changed an inventory link");
            });
            waitForSave(context);
            requireSavedSpeed(settings, InstantPlacementSpeed.SLOW);
            requireSavedAirOnly(settings, true);
            context.runOnClient(client -> BlueprintPreviewSettings.INSTANCE.setAirOnly(false));
            context.waitFor(client -> !airOnly(client).selected());
            context.runOnClient(client -> BlueprintPreviewSettings.INSTANCE.setAirOnly(true));
            context.waitFor(client -> airOnly(client).selected());
            context.runOnClient(client -> selectRepeatSpeed(client, InstantPlacementSpeed.FAST));
            context.runOnClient(client -> selectMode(client, PreviewStyle.LIGHTWEIGHT));
            waitForPreview(context, reference, PreviewStyle.LIGHTWEIGHT);
            waitForSave(context);
            requireSavedStyle(settings, PreviewStyle.LIGHTWEIGHT);
            NativeImage animated = context.computeOnClient(client -> pixels(client, reference));
            context.runOnClient(client -> {
                selectMode(client, PreviewStyle.STATIC);
                require(!BlueprintPreviewSettings.INSTANCE.getCurrent().getEnabled(), "Static mode did not apply immediately");
                require(animated.isClosed(), "Changing preview mode leaked the old animated atlas");
            });
            waitForPreview(context, reference, PreviewStyle.STATIC);
            waitForSave(context);
            requireSavedStyle(settings, PreviewStyle.STATIC);
            requireSavedSpeed(settings, InstantPlacementSpeed.FAST);
            requireSavedAirOnly(settings, true);
            context.runOnClient(client -> {
                var view = BlueprintThumbnails.INSTANCE.view(reference);
                require(view.getFrame() == 0, "Static settings retained an animated frame");
                require(reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())),
                    "Changing preview mode changed an existing inventory link");
            });
            verifyWindow(context, 640, 480, 2, "blueprint-settings-static-small");
            context.clickScreenButton("gui.done");
            context.runOnClient(client -> {
                require(client.gui.screen() == parent, "Done did not return to the parent screen");
                client.gui.setScreen(configurationScreen(parent));
                require(mode(client).getValue() == PreviewStyle.STATIC, "Reopening settings lost the static selection");
                require(repeatSpeed(client).getValue() == InstantPlacementSpeed.FAST,
                    "Reopening settings lost the faster repeat speed");
                require(airOnly(client).selected(), "Reopening settings lost the air-only selection");
                selectRepeatSpeed(client, InstantPlacementSpeed.NORMAL);
                require(BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle() == PreviewStyle.STATIC,
                    "Restoring normal repeat speed changed the preview preset");
                selectMode(client, PreviewStyle.HIGH_QUALITY);
                require(BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle() == PreviewStyle.HIGH_QUALITY,
                    "High quality mode did not apply immediately");
            });
            waitForPreview(context, reference, PreviewStyle.HIGH_QUALITY);
            waitForSave(context);
            requireSavedStyle(settings, PreviewStyle.HIGH_QUALITY);
            requireSavedSpeed(settings, InstantPlacementSpeed.NORMAL);
            verifyWindow(context, 1280, 720, 3, "blueprint-settings-high-quality");

            context.runOnClient(client -> {
                for (int change = 0; change < 30; change++) clickMode(client);
                require(BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle() == PreviewStyle.HIGH_QUALITY,
                    "Rapid clicks lost the final preview choice");
                require(mode(client).getValue() == PreviewStyle.HIGH_QUALITY, "Rapid clicks desynchronized the native control");
                selectRepeatSpeed(client, InstantPlacementSpeed.FAST);
                selectRepeatSpeed(client, InstantPlacementSpeed.NORMAL);
            });
            waitForSave(context);
            requireSavedStyle(settings, PreviewStyle.HIGH_QUALITY);
            requireSavedSpeed(settings, InstantPlacementSpeed.NORMAL);
            verifySaveRetry(context, settings, reference);
            context.runOnClient(client -> {
                require(client.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)), "Escape was not handled");
                require(client.gui.screen() == parent, "Escape did not return to the parent screen");
                client.gui.setScreen(new BlueprintSettingsScreen(null));
                client.gui.screen().onClose();
                require(client.gui.screen() == null, "Settings with no parent did not close");
            });
            verifySidebar(context, reference);
        } finally {
            context.runOnClient(client -> {
                BlueprintPreviewSettings.INSTANCE.setStyle(previousStyle);
                BlueprintPreviewSettings.INSTANCE.setInstantPlacementSpeed(previousSpeed);
                BlueprintPreviewSettings.INSTANCE.setAirOnly(previousAirOnly);
            });
            context.waitFor(client -> !BlueprintPreviewSettings.INSTANCE.getSaving());
            if (original == null) Files.deleteIfExists(settings);
            else Files.write(settings, original);
            context.runOnClient(client -> {
                client.options.guiScale().set(window[2]);
                client.getWindow().setWindowed(window[0], window[1]);
                client.resizeGui();
                client.gui.setScreen(previous);
            });
        }
    }

    private static void verifySaveRetry(ClientGameTestContext context, Path settings, BlueprintReference reference) throws IOException {
        byte[] valid = Files.readAllBytes(settings);
        String malformed = "{not valid json";
        Files.writeString(settings, malformed);
        context.runOnClient(client -> {
            selectMode(client, PreviewStyle.STATIC);
            selectRepeatSpeed(client, InstantPlacementSpeed.FAST);
        });
        context.waitFor(client -> !BlueprintPreviewSettings.INSTANCE.getSaving() && BlueprintPreviewSettings.INSTANCE.getSaveFailed());
        require(Files.readString(settings).equals(malformed), "A failed save overwrote the malformed config file");
        waitForPreview(context, reference, PreviewStyle.STATIC);
        context.waitFor(client -> Screens.getWidgets(client.gui.screen()).stream().anyMatch(widget -> widget.visible && widget.active
            && widget.getMessage().getString().equals(Component.translatable("config.axiom_blueprint_items.retry").getString())));
        context.takeScreenshot("blueprint-settings-save-error");
        Files.write(settings, valid);
        context.clickScreenButton("config.axiom_blueprint_items.retry");
        waitForSave(context);
        requireSavedStyle(settings, PreviewStyle.STATIC);
        requireSavedSpeed(settings, InstantPlacementSpeed.FAST);
        requireSavedAirOnly(settings, true);
        context.runOnClient(client -> {
            selectMode(client, PreviewStyle.HIGH_QUALITY);
            selectRepeatSpeed(client, InstantPlacementSpeed.NORMAL);
        });
        waitForSave(context);
        waitForPreview(context, reference, PreviewStyle.HIGH_QUALITY);
    }

    private static Screen configurationScreen(Screen parent) {
        if (!FabricLoader.getInstance().isModLoaded("modmenu")) return new BlueprintSettingsScreen(parent);
        var entrypoints = FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class);
        var entrypoint = entrypoints.stream()
            .filter(container -> container.getProvider().getMetadata().getId().equals("axiom_blueprint_items"))
            .findFirst().orElseThrow(() -> new AssertionError("Mod Menu did not discover the blueprint config entrypoint"))
            .getEntrypoint();
        try {
            Object factory = entrypoint.getClass().getMethod("getModConfigScreenFactory").invoke(entrypoint);
            Class<?> factoryType = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory");
            return (Screen) factoryType.getMethod("create", Screen.class).invoke(factory, parent);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Registered Mod Menu config factory failed", exception);
        }
    }

    private static void verifyWindow(ClientGameTestContext context, int width, int height, int scale, String screenshot) {
        context.runOnClient(client -> {
            client.getWindow().setWindowed(width, height);
            client.options.guiScale().set(scale);
            client.resizeGui();
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            Screen screen = client.gui.screen();
            var visible = Screens.getWidgets(screen).stream().filter(widget -> widget.visible).toList();
            require(visible.size() >= 4, "Settings lost a preview, repeat-speed, air-only, or Done control after resizing");
            for (int first = 0; first < visible.size(); first++) {
                AbstractWidget widget = visible.get(first);
                require(widget.getX() >= 0 && widget.getY() >= 0
                    && widget.getRight() <= screen.width && widget.getBottom() <= screen.height,
                    "A settings control falls outside the window");
                for (int second = first + 1; second < visible.size(); second++) {
                    AbstractWidget other = visible.get(second);
                    require(widget.getRight() <= other.getX() || other.getRight() <= widget.getX()
                        || widget.getBottom() <= other.getY() || other.getBottom() <= widget.getY(),
                        "Settings controls overlap after resizing");
                }
            }
        });
        context.takeScreenshot(TestScreenshotOptions.of(screenshot).withSize(width, height));
    }

    private static CycleButton<?> mode(Minecraft client) {
        return Screens.getWidgets(client.gui.screen()).stream()
            .filter(widget -> widget instanceof CycleButton<?>)
            .map(widget -> (CycleButton<?>) widget)
            .filter(widget -> widget.getValue() instanceof PreviewStyle)
            .findFirst().orElseThrow(() -> new AssertionError("Settings has no preview-mode cycle button"));
    }

    private static CycleButton<?> repeatSpeed(Minecraft client) {
        return Screens.getWidgets(client.gui.screen()).stream()
            .filter(widget -> widget instanceof CycleButton<?>)
            .map(widget -> (CycleButton<?>) widget)
            .filter(widget -> widget.getValue() instanceof InstantPlacementSpeed)
            .findFirst().orElseThrow(() -> new AssertionError("Settings has no repeat-speed cycle button"));
    }

    private static Checkbox airOnly(Minecraft client) {
        return Screens.getWidgets(client.gui.screen()).stream()
            .filter(widget -> widget instanceof Checkbox)
            .map(widget -> (Checkbox) widget)
            .filter(widget -> widget.getMessage().getString().equals(Component.translatable("config.axiom_blueprint_items.air_only").getString()))
            .findFirst().orElseThrow(() -> new AssertionError("Blueprint screen has no native air-only checkbox"));
    }

    private static void verifySidebar(ClientGameTestContext context, BlueprintReference reference) {
        context.runOnClient(client -> {
            BlueprintPreviewSettings.INSTANCE.setAirOnly(false);
            client.gui.setScreen(new CreativeModeInventoryScreen(client.player, client.level.enabledFeatures(), false));
            ((FabricCreativeModeInventoryScreen) client.gui.screen()).setSelectedTab(BlueprintItems.INSTANCE.getTAB());
        });
        context.waitFor(client -> !dev.markectomy.blueprints.client.library.BlueprintLibrary.INSTANCE.getSnapshot().getLoading()
            && ((CreativeModeInventoryScreen) client.gui.screen()).getMenu().items.stream()
                .anyMatch(stack -> reference.equals(BlueprintItems.INSTANCE.reference(stack))));
        List<ItemStack> catalog = context.computeOnClient(client ->
            ((CreativeModeInventoryScreen) client.gui.screen()).getMenu().items.stream().map(ItemStack::copy).toList());
        String query = context.computeOnClient(client -> search(client).getValue());
        context.runOnClient(client -> {
            Checkbox checkbox = airOnly(client);
            require(checkbox.visible && checkbox.active && !checkbox.selected(), "Sidebar did not show the air-only default");
            require(checkbox.getX() >= 0 && checkbox.getRight() <= client.gui.screen().width
                && checkbox.getY() >= 0 && checkbox.getBottom() <= client.gui.screen().height,
                "Sidebar air-only checkbox falls outside the window");
            clickControl(client, checkbox);
            require(BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly(), "Sidebar air-only checkbox did not apply");
            require(reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())),
                "Sidebar placement toggle changed the held blueprint");
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            var items = ((CreativeModeInventoryScreen) client.gui.screen()).getMenu().items;
            require(items.size() == catalog.size(), "Sidebar placement toggle filtered the library");
            for (int index = 0; index < items.size(); index++) {
                require(ItemStack.isSameItemSameComponents(items.get(index), catalog.get(index)),
                    "Sidebar placement toggle changed a catalog item");
            }
            require(search(client).getValue().equals(query), "Sidebar placement toggle cleared the name search");
            BlueprintPreviewSettings.INSTANCE.toggleAirOnly();
        });
        context.waitFor(client -> !airOnly(client).selected());
        context.runOnClient(client -> {
            var tabs = (FabricCreativeModeInventoryScreen) client.gui.screen();
            tabs.setSelectedTab(BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(CreativeModeTabs.SEARCH));
            require(!airOnly(client).visible && !airOnly(client).active,
                "Sidebar placement toggle remained active outside the Blueprints tab");
            BlueprintPreviewSettings.INSTANCE.setAirOnly(true);
            tabs.setSelectedTab(BlueprintItems.INSTANCE.getTAB());
            require(airOnly(client).visible && airOnly(client).active && airOnly(client).selected(),
                "Returning to the Blueprints tab did not synchronize the placement toggle");
            BlueprintPreviewSettings.INSTANCE.setAirOnly(false);
        });
        context.waitFor(client -> !airOnly(client).selected());
        context.takeScreenshot("blueprints-sidebar-air-only");
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            client.gui.setScreen(configurationScreen(null));
            require(!airOnly(client).selected(), "Mod Menu did not reflect the sidebar/keybinding setting");
            client.gui.setScreen(null);
        });
    }

    private static EditBox search(Minecraft client) {
        return Screens.getWidgets(client.gui.screen()).stream().filter(widget -> widget instanceof EditBox)
            .map(widget -> (EditBox) widget).findFirst().orElseThrow();
    }

    private static void clickMode(Minecraft client) {
        clickControl(client, mode(client));
    }

    private static void clickControl(Minecraft client, AbstractWidget widget) {
        MouseButtonEvent click = new MouseButtonEvent(widget.getX() + widget.getWidth() / 2.0,
            widget.getY() + widget.getHeight() / 2.0, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        require(client.gui.screen().mouseClicked(click, false), "Native settings control rejected a normal left click");
        client.gui.screen().mouseReleased(click);
    }

    private static void selectRepeatSpeed(Minecraft client, InstantPlacementSpeed speed) {
        for (int click = 0; click < InstantPlacementSpeed.values().length && repeatSpeed(client).getValue() != speed; click++) {
            clickControl(client, repeatSpeed(client));
        }
        require(repeatSpeed(client).getValue() == speed, "Native repeat-speed control did not select " + speed);
        require(BlueprintPreviewSettings.INSTANCE.getCurrent().getInstantPlacementSpeed() == speed,
            "Native repeat-speed control did not apply " + speed);
    }

    private static void selectMode(Minecraft client, PreviewStyle style) {
        for (int click = 0; click < 3 && mode(client).getValue() != style; click++) clickMode(client);
        require(mode(client).getValue() == style, "Native preview control did not select " + style);
        require(BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle() == style,
            "Native preview control did not apply " + style);
    }

    private static void waitForPreview(ClientGameTestContext context, BlueprintReference reference, PreviewStyle style) {
        context.waitFor(client -> {
            var view = BlueprintThumbnails.INSTANCE.view(reference);
            return view.getTexture() != null
                && view.getColumns() == (style == PreviewStyle.LIGHTWEIGHT ? 4 : 1)
                && view.getHighQuality() == (style == PreviewStyle.HIGH_QUALITY);
        });
        context.runOnClient(client -> require(
            BlueprintItems.INSTANCE.getITEM().getTooltipImage(BlueprintItems.INSTANCE.createStack(reference)).isPresent()
                == (style == PreviewStyle.HIGH_QUALITY),
            "Enlarged hover preview visibility did not match " + style));
    }

    private static void waitForSave(ClientGameTestContext context) {
        context.waitFor(client -> !BlueprintPreviewSettings.INSTANCE.getSaving() && !BlueprintPreviewSettings.INSTANCE.getSaveFailed());
    }

    private static void requireSavedStyle(Path settings, PreviewStyle style) throws IOException {
        var loaded = PreviewSettings.Companion.load(settings);
        require(loaded.getStyle() == style, "Saved preview preset did not survive reopening: " + style);
        require(loaded.getEnabled() == (style != PreviewStyle.STATIC), "Legacy preview opt-out no longer matches the preset");
        var saved = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
        require(saved.get("previewStyle").getAsString().equals(style.getSerializedName()),
            "Saved preview preset did not use its stable serialized name");
        require(saved.get("animatedPreviews").getAsBoolean() == (style != PreviewStyle.STATIC),
            "Saved legacy preview preference was not synchronized");
    }

    private static void requireSavedSpeed(Path settings, InstantPlacementSpeed speed) throws IOException {
        var loaded = PreviewSettings.Companion.load(settings);
        require(loaded.getInstantPlacementSpeed() == speed, "Repeat speed did not survive reopening: " + speed);
        var saved = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
        require(saved.get("instantPlacementSpeed").getAsString().equals(speed.getSerializedName()),
            "Saved repeat speed did not use its stable serialized name");
    }

    private static void requireSavedAirOnly(Path settings, boolean enabled) throws IOException {
        require(PreviewSettings.Companion.load(settings).getAirOnly() == enabled, "Air-only placement did not survive reopening");
        var saved = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
        require(saved.get("airOnly").getAsBoolean() == enabled, "Saved air-only preference did not match the setting");
    }

    private static NativeImage pixels(Minecraft client, BlueprintReference reference) {
        var view = BlueprintThumbnails.INSTANCE.view(reference);
        require(view.getTexture() != null, "Blueprint preview has no texture");
        var texture = client.getTextureManager().getTexture(view.getTexture());
        require(texture instanceof DynamicTexture, "Blueprint preview has no dynamic texture");
        NativeImage image = ((DynamicTexture) texture).getPixels();
        require(image != null && !image.isClosed(), "Blueprint preview pixels are already closed");
        return image;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ParentScreen extends Screen {
        private ParentScreen() {
            super(Component.literal("Settings return test"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xff202020);
        }
    }
}
