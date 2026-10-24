package dev.markectomy.blueprints.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.markectomy.blueprints.client.config.BlueprintPreviewSettings;
import dev.markectomy.blueprints.client.ui.BlueprintSettingsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

final class BlueprintPlacementControlChecks {
    private BlueprintPlacementControlChecks() {
    }

    static void run(ClientGameTestContext context) {
        KeyMapping mapping = context.computeOnClient(client -> KeyMapping.get("key.axiom_blueprint_items.toggle_air_only"));
        String previousKey = context.computeOnClient(client -> mapping.saveString());
        boolean previousAirOnly = context.computeOnClient(client -> BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly());
        try {
            context.runOnClient(client -> {
                require(mapping.getDefaultKey().equals(InputConstants.UNKNOWN), "Air-only shortcut is not unbound by default");
                require(mapping.getCategory().id().equals(dev.markectomy.blueprints.BlueprintItems.INSTANCE.id("controls")),
                    "Air-only shortcut is in the wrong Controls category");
                mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_G));
                KeyMapping.resetMapping();
                BlueprintPreviewSettings.INSTANCE.setAirOnly(false);
                client.gui.setScreen(null);
            });
            context.getInput().holdKey(mapping);
            context.waitTicks(2);
            context.runOnClient(client -> require(BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly(),
                "Assigned shortcut did not enable air-only placement"));
            context.waitTicks(8);
            context.runOnClient(client -> require(BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly(),
                "Holding the shortcut repeatedly toggled air-only placement"));
            context.getInput().releaseKey(mapping);
            context.getInput().pressKey(mapping);
            context.runOnClient(client -> require(!BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly(),
                "Second shortcut press did not disable air-only placement"));
            context.runOnClient(client -> {
                client.gui.setScreen(new BlueprintSettingsScreen(null));
                KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_G));
            });
            context.waitTicks(2);
            context.runOnClient(client -> require(!BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly(),
                "Gameplay shortcut toggled air-only while a screen was open"));
            context.runOnClient(client -> client.gui.setScreen(null));
            context.waitTicks(2);
            context.runOnClient(client -> require(!BlueprintPreviewSettings.INSTANCE.getCurrent().getAirOnly(),
                "Ignoring a screen key press queued a later toggle"));
        } finally {
            context.getInput().releaseKey(mapping);
            context.runOnClient(client -> {
                client.gui.setScreen(null);
                mapping.setKey(InputConstants.getKey(previousKey));
                KeyMapping.resetMapping();
                BlueprintPreviewSettings.INSTANCE.setAirOnly(previousAirOnly);
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
