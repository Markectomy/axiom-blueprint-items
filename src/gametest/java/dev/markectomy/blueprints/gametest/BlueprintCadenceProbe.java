package dev.markectomy.blueprints.gametest;

import com.moulberry.axiom.world_modification.Dispatcher;
import dev.markectomy.blueprints.BlueprintItems;
import dev.markectomy.blueprints.domain.BlueprintReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** Records native history commits while simulating an accelerated held-use caller. */
final class BlueprintCadenceProbe implements AutoCloseable {
    private final List<Integer> placementTicks = new ArrayList<>();
    private final List<Integer> swingTicks = new ArrayList<>();
    private final Field useDelay;
    private final BlueprintReference reference;
    private final boolean directCalls;
    private int history;
    private int previousSwing;
    private boolean previousSwinging;
    private boolean active = true;

    BlueprintCadenceProbe(Minecraft client, BlueprintReference reference, boolean directCalls) {
        this.reference = reference;
        this.directCalls = directCalls;
        history = Dispatcher.getHistoryPosition(false);
        previousSwing = client.player.swingTime;
        previousSwinging = client.player.swinging;
        try {
            useDelay = Minecraft.class.getDeclaredField("rightClickDelay");
            useDelay.setAccessible(true);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot simulate accelerated held use", exception);
        }
        ClientTickEvents.START_CLIENT_TICK.register(this::beforeTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::afterTick);
    }

    private void beforeTick(Minecraft client) {
        if (!active) return;
        try {
            useDelay.setInt(client, 0);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Cannot simulate accelerated held use", exception);
        }
        if (directCalls && client.options.keyUse.isDown() && client.options.keyShift.isDown()) {
            // The service must enforce the same limit even if native input gating is bypassed.
            for (int attempt = 0; attempt < 8; attempt++) BlueprintItems.INSTANCE.getUseHandler().invoke(reference);
        }
    }

    private void afterTick(Minecraft client) {
        if (!active) return;
        int currentHistory = Dispatcher.getHistoryPosition(false);
        if (currentHistory != history) {
            if (currentHistory != history + 1) throw new AssertionError("Multiple blueprint commits occurred in one client tick");
            placementTicks.add(client.player.tickCount);
            history = currentHistory;
        }
        int swing = client.player.swingTime;
        if (client.player.swinging && (!previousSwinging || swing < previousSwing)) swingTicks.add(client.player.tickCount);
        previousSwing = swing;
        previousSwinging = client.player.swinging;
    }

    void requireIntervals(int minimumTicks) {
        if (placementTicks.size() < 2) throw new AssertionError("Not enough native commits to verify held-use pacing: " + placementTicks);
        if (!directCalls && swingTicks.size() < 2) throw new AssertionError("Not enough hand swings to verify held-use pacing: " + swingTicks);
        requireSpacing(placementTicks, minimumTicks, "Blueprint placement");
        requireSpacing(swingTicks, minimumTicks, "Hand swing");
    }

    private static void requireSpacing(List<Integer> ticks, int minimumTicks, String action) {
        for (int index = 1; index < ticks.size(); index++) {
            int spacing = ticks.get(index) - ticks.get(index - 1);
            if (spacing < minimumTicks) throw new AssertionError(action + " repeated after " + spacing
                + " ticks; expected at least " + minimumTicks + ": " + ticks);
        }
    }

    @Override
    public void close() {
        active = false;
    }
}
