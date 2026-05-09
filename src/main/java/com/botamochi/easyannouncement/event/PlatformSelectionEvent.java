package com.botamochi.easyannouncement.event;

import net.minecraft.util.math.BlockPos;

import java.util.List;

public interface PlatformSelectionEvent {

    /**
     * Called when platforms are selected.
     * Note: In MTR 4.0, platform selection uses platform IDs (Long).
     * This interface uses Long for compatibility with MTR 4.0's platform system.
     */
    void onPlatformSelected(BlockPos pos, List<Long> selectedPlatformIds, int delaySeconds);

    interface Listener {
        void onPlatformSelected(BlockPos pos, List<Long> selectedPlatformIds, int delaySeconds);
    }

    net.fabricmc.fabric.api.event.Event<Listener> EVENT = net.fabricmc.fabric.api.event.EventFactory.createArrayBacked(Listener.class, listeners -> (pos, selectedPlatformIds, delaySeconds) -> {
        for (Listener listener : listeners) {
            listener.onPlatformSelected(pos, selectedPlatformIds, delaySeconds);
        }
    });
}
