package dev.markectomy.blueprints.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import dev.markectomy.blueprints.BlueprintItems;
import dev.markectomy.blueprints.client.config.PreviewSettings;
import dev.markectomy.blueprints.client.render.BlueprintThumbnailRenderer;
import dev.markectomy.blueprints.client.render.BlueprintThumbnails;
import dev.markectomy.blueprints.domain.BlueprintReference;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

final class BlueprintPreviewChecks {
    private BlueprintPreviewChecks() {
    }

    static void run(ClientGameTestContext context, Path fixture, BlueprintReference reference) throws IOException {
        Path oversized = Files.createTempFile(fixture.getParent(), "addon-large-preview-", ".bp");
        Path settings = oversized.resolveSibling(oversized.getFileName() + ".json");
        try {
            verifySettings(settings);
            context.runOnClient(client -> client.player.setItemInHand(InteractionHand.MAIN_HAND,
                BlueprintItems.INSTANCE.createStack(reference)));
            waitForAnimated(context, reference);
            Snapshot initial = context.computeOnClient(client -> snapshot(client, reference));
            context.runOnClient(client -> {
                verifyAtlas(initial.pixels());
                Path output = client.gameDirectory.toPath().resolve("screenshots/blueprint-turntable-atlas.png");
                Files.createDirectories(output.getParent());
                initial.pixels().writeToFile(output);
            });
            var renderer = new BlueprintThumbnailRenderer();
            var captured = context.computeOnClient(client -> renderer.extractArgument(client.player.getMainHandItem()));
            int capturedFrame = captured.getImage().getFrame();
            context.waitFor(client -> renderer.extractArgument(client.player.getMainHandItem()).getImage().getFrame() != capturedFrame);
            context.runOnClient(client -> {
                var advanced = renderer.extractArgument(client.player.getMainHandItem());
                require(!captured.equals(advanced), "Animation did not change vanilla's extracted item-model cache key");
                require(captured.getImage().getFrame() == capturedFrame, "An extracted preview changed before submission");
                require(reference.equals(advanced.getReference()), "Animated model extraction changed the blueprint reference");
            });
            verifyGuiAnimation(context, reference);
            context.runOnClient(client -> verifyRetained(client, reference, initial));
            context.runOnClient(client -> BlueprintThumbnails.INSTANCE.refresh());
            context.waitTicks(16);
            context.runOnClient(client -> verifyRetained(client, reference, initial));

            context.runOnClient(client -> {
                BlueprintThumbnails.INSTANCE.invalidate();
                require(initial.pixels().isClosed(), "Preview invalidation leaked the previous image");
            });
            waitForAnimated(context, reference);
            context.runOnClient(client -> {
                Snapshot regenerated = snapshot(client, reference);
                require(regenerated.pixels() != initial.pixels(), "Invalidation reused a closed preview image");
                verifyAtlas(regenerated.pixels());
                require(reference.equals(BlueprintItems.INSTANCE.reference(client.player.getMainHandItem())),
                    "Preview generation or reload changed the inventory reference");
            });
            verifyDisabledPreviews(context, reference);

            Files.copy(fixture, oversized, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(oversized, StandardOpenOption.WRITE)) {
                channel.position(33L * 1024L * 1024L - 1L);
                channel.write(ByteBuffer.wrap(new byte[] {0}));
            }
            BlueprintReference largeReference = new BlueprintReference(1, oversized.getFileName().toString(), "Large preview fallback");
            long before = context.computeOnClient(client -> BlueprintThumbnails.INSTANCE.view(largeReference).getRevision());
            context.waitFor(client -> {
                var view = BlueprintThumbnails.INSTANCE.view(largeReference);
                return view.getTexture() != null && view.getColumns() == 1 && view.getRevision() > before;
            });
            context.runOnClient(client -> {
                Snapshot fallback = snapshot(client, largeReference);
                require(fallback.pixels().getWidth() == 64 && fallback.pixels().getHeight() == 64,
                    "Oversized preview did not retain a bounded static thumbnail");
                require(fallback.pixels().getPixel(32, 20) == 0xffff0000
                    && fallback.pixels().getPixel(32, 44) == 0xff0000ff,
                    "Oversized preview lost or inverted its saved thumbnail fallback");
            });
        } finally {
            context.runOnClient(client -> BlueprintThumbnails.INSTANCE.invalidate());
            Files.deleteIfExists(oversized);
            Files.deleteIfExists(settings);
        }
    }

    private static void verifyGuiAnimation(ClientGameTestContext context, BlueprintReference reference) throws IOException {
        Screen previous = context.computeOnClient(client -> client.gui.screen());
        try {
            context.runOnClient(client -> client.gui.setScreen(new PreviewScreen(BlueprintItems.INSTANCE.createStack(reference))));
            context.waitTicks(2);
            int[] first = captureItem(context, "blueprint-turntable-gui-first");
            int frame = context.computeOnClient(client -> BlueprintThumbnails.INSTANCE.view(reference).getFrame());
            int opposite = (frame + 8) % 16;
            context.waitFor(client -> BlueprintThumbnails.INSTANCE.view(reference).getFrame() == opposite);
            context.waitTicks(1);
            int[] second = captureItem(context, "blueprint-turntable-gui-opposite");
            if (Arrays.equals(first, second)) {
                int quarter = (opposite + 4) % 16;
                context.waitFor(client -> BlueprintThumbnails.INSTANCE.view(reference).getFrame() == quarter);
                context.waitTicks(1);
                second = captureItem(context, "blueprint-turntable-gui-quarter");
            }
            require(!Arrays.equals(first, second), "Vanilla's GUI item cache displayed a frozen rotating preview");
        } finally {
            context.runOnClient(client -> client.gui.setScreen(previous));
        }
    }

    private static int[] captureItem(ClientGameTestContext context, String name) throws IOException {
        int[] dimensions = context.computeOnClient(client -> new int[] {client.gui.screen().width, client.gui.screen().height});
        Path screenshot = context.takeScreenshot(name);
        try (NativeImage captured = NativeImage.read(Files.readAllBytes(screenshot))) {
            int left = 10 * captured.getWidth() / dimensions[0];
            int top = 10 * captured.getHeight() / dimensions[1];
            int right = 26 * captured.getWidth() / dimensions[0];
            int bottom = 26 * captured.getHeight() / dimensions[1];
            int width = right - left;
            int height = bottom - top;
            require(width > 0 && height > 0, "GUI preview screenshot had an empty item area");
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = captured.getPixel(left + x, top + y);
                }
            }
            return pixels;
        }
    }

    private static void verifyDisabledPreviews(ClientGameTestContext context, BlueprintReference reference) {
        boolean previous = context.computeOnClient(client -> {
            boolean enabled = setAnimationEnabled(false);
            BlueprintThumbnails.INSTANCE.invalidate();
            return enabled;
        });
        try {
            context.waitFor(client -> {
                var view = BlueprintThumbnails.INSTANCE.view(reference);
                return view.getTexture() != null && view.getColumns() == 1;
            });
            Snapshot initial = context.computeOnClient(client -> snapshot(client, reference));
            context.runOnClient(client -> {
                require(initial.pixels().getWidth() == 64 && initial.pixels().getHeight() == 64,
                    "Disabling animation did not use the saved static thumbnail");
                require(initial.pixels().getPixel(32, 20) == 0xffff0000
                    && initial.pixels().getPixel(32, 44) == 0xff0000ff,
                    "Disabling animation changed the saved thumbnail orientation");
            });
            context.waitTicks(10);
            context.runOnClient(client -> {
                var view = BlueprintThumbnails.INSTANCE.view(reference);
                require(view.getColumns() == 1 && view.getFrame() == 0, "Disabled previews still animated");
                verifyRetained(client, reference, initial);
            });
        } finally {
            context.runOnClient(client -> {
                setAnimationEnabled(previous);
                BlueprintThumbnails.INSTANCE.invalidate();
            });
        }
        if (previous) waitForAnimated(context, reference);
    }

    private static boolean setAnimationEnabled(boolean enabled) {
        try {
            var field = BlueprintThumbnails.class.getDeclaredField("enabled");
            field.setAccessible(true);
            boolean previous = field.getBoolean(BlueprintThumbnails.INSTANCE);
            field.setBoolean(BlueprintThumbnails.INSTANCE, enabled);
            return previous;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot exercise the preview animation opt-out", exception);
        }
    }

    private static void verifySettings(Path path) throws IOException {
        require(PreviewSettings.Companion.load(path).getEnabled() && Files.isRegularFile(path),
            "Preview settings did not create enabled defaults");
        String disabled = "{\"animatedPreviews\":false,\"futureSetting\":7}";
        Files.writeString(path, disabled);
        require(!PreviewSettings.Companion.load(path).getEnabled(), "Preview opt-out was ignored");
        require(Files.readString(path).equals(disabled), "Loading preview settings removed an unknown key");
        String malformed = "{bad json";
        Files.writeString(path, malformed);
        require(PreviewSettings.Companion.load(path).getEnabled(), "Malformed preview settings did not use safe defaults");
        require(Files.readString(path).equals(malformed), "Loading malformed settings overwrote the user's file");
    }

    private static void waitForAnimated(ClientGameTestContext context, BlueprintReference reference) {
        context.waitFor(client -> {
            var view = BlueprintThumbnails.INSTANCE.view(reference);
            return view.getTexture() != null && view.getColumns() == 4;
        });
    }

    private static Snapshot snapshot(Minecraft client, BlueprintReference reference) {
        var view = BlueprintThumbnails.INSTANCE.view(reference);
        require(view.getTexture() != null, "Preview has no texture");
        var texture = client.getTextureManager().getTexture(view.getTexture());
        require(texture instanceof DynamicTexture, "Preview was not uploaded as a dynamic texture");
        NativeImage pixels = ((DynamicTexture) texture).getPixels();
        require(pixels != null && !pixels.isClosed(), "Preview texture has no live pixels");
        return new Snapshot(view.getTexture(), view.getRevision(), pixels);
    }

    private static void verifyRetained(Minecraft client, BlueprintReference reference, Snapshot initial) {
        Snapshot current = snapshot(client, reference);
        require(initial.texture().equals(current.texture()) && initial.pixels() == current.pixels(),
            "Animation or unchanged refresh replaced the cached atlas");
        require(initial.revision() == current.revision(), "Animation or unchanged refresh rebuilt the cached model");
    }

    private static void verifyAtlas(NativeImage pixels) {
        require(pixels.getWidth() == 256 && pixels.getHeight() == 256, "Animated preview atlas exceeded its dimensions");
        int firstOpaque = 0;
        int oppositeOpaque = 0;
        int different = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int first = pixels.getPixel(x, y);
                int opposite = pixels.getPixel(x, y + 128);
                if ((first >>> 24) != 0) firstOpaque++;
                if ((opposite >>> 24) != 0) oppositeOpaque++;
                if (first != opposite) different++;
            }
        }
        require(firstOpaque > 0 && oppositeOpaque > 0, "Rotating preview contained blank frames");
        require(different > 20, "Opposite preview angles did not show different build geometry");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Snapshot(Identifier texture, long revision, NativeImage pixels) {
    }

    private static final class PreviewScreen extends Screen {
        private final ItemStack item;

        private PreviewScreen(ItemStack item) {
            super(Component.literal("Blueprint preview test"));
            this.item = item;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xff202020);
            graphics.item(item, 10, 10);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
