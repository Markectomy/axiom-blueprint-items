package dev.markectomy.blueprints.gametest;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.markectomy.blueprints.BlueprintItems;
import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings;
import dev.markectomy.blueprints.client.config.PreviewSettings;
import dev.markectomy.blueprints.client.render.BlueprintThumbnails;
import dev.markectomy.blueprints.client.ui.BlueprintSettingsScreen;
import dev.markectomy.blueprints.domain.BlueprintReference;
import dev.markectomy.blueprints.domain.PreviewStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class BlueprintSettingsChecks {
    private BlueprintSettingsChecks() {
    }

    static void run(ClientGameTestContext context, BlueprintReference reference) throws IOException {
        Path settings = FabricLoader.getInstance().getConfigDir().resolve(PreviewSettings.FILE_NAME);
        byte[] original = Files.exists(settings) ? Files.readAllBytes(settings) : null;
        PreviewStyle previousStyle = context.computeOnClient(client -> BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle());
        Screen previous = context.computeOnClient(client -> client.gui.screen());
        Screen parent = context.computeOnClient(client -> new ParentScreen());
        int[] window = context.computeOnClient(client -> new int[] {
            client.getWindow().getWidth(), client.getWindow().getHeight(), client.options.guiScale().get()
        });
        try {
            context.runOnClient(client -> {
                BlueprintPreviewSettings.INSTANCE.setStyle(PreviewStyle.HIGH_QUALITY);
                client.gui.setScreen(configurationScreen(parent));
                require(client.gui.screen() instanceof BlueprintSettingsScreen, "Config factory did not open blueprint settings");
                require(mode(client).getValue() == PreviewStyle.HIGH_QUALITY, "Settings did not show the current High quality mode");
            });
            waitForPreview(context, reference, PreviewStyle.HIGH_QUALITY);
            waitForSave(context);
            requireSavedStyle(settings, PreviewStyle.HIGH_QUALITY);
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
                selectMode(client, PreviewStyle.HIGH_QUALITY);
                require(BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle() == PreviewStyle.HIGH_QUALITY,
                    "High quality mode did not apply immediately");
            });
            waitForPreview(context, reference, PreviewStyle.HIGH_QUALITY);
            waitForSave(context);
            requireSavedStyle(settings, PreviewStyle.HIGH_QUALITY);
            verifyWindow(context, 1280, 720, 3, "blueprint-settings-high-quality");

            context.runOnClient(client -> {
                for (int change = 0; change < 30; change++) clickMode(client);
                require(BlueprintPreviewSettings.INSTANCE.getCurrent().getStyle() == PreviewStyle.HIGH_QUALITY,
                    "Rapid clicks lost the final preview choice");
                require(mode(client).getValue() == PreviewStyle.HIGH_QUALITY, "Rapid clicks desynchronized the native control");
            });
            waitForSave(context);
            requireSavedStyle(settings, PreviewStyle.HIGH_QUALITY);
            verifySaveRetry(context, settings, reference);
            context.runOnClient(client -> {
                require(client.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0)), "Escape was not handled");
                require(client.gui.screen() == parent, "Escape did not return to the parent screen");
                client.gui.setScreen(new BlueprintSettingsScreen(null));
                client.gui.screen().onClose();
                require(client.gui.screen() == null, "Settings with no parent did not close");
            });
        } finally {
            context.runOnClient(client -> BlueprintPreviewSettings.INSTANCE.setStyle(previousStyle));
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
        context.runOnClient(client -> selectMode(client, PreviewStyle.STATIC));
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
        context.runOnClient(client -> selectMode(client, PreviewStyle.HIGH_QUALITY));
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
            require(visible.size() >= 2, "Settings lost its mode or Done control after resizing");
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
            .findFirst().orElseThrow(() -> new AssertionError("Settings has no preview-mode cycle button"));
    }

    private static void clickMode(Minecraft client) {
        AbstractWidget widget = mode(client);
        MouseButtonEvent click = new MouseButtonEvent(widget.getX() + widget.getWidth() / 2.0,
            widget.getY() + widget.getHeight() / 2.0, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        require(client.gui.screen().mouseClicked(click, false), "Native preview control rejected a normal left click");
        client.gui.screen().mouseReleased(click);
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
