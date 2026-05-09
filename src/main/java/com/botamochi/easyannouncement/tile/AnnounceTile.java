package com.botamochi.easyannouncement.tile;

import com.botamochi.easyannouncement.Easyannouncement;
import com.botamochi.easyannouncement.data.AnnouncementEntry;
import com.botamochi.easyannouncement.network.AnnounceSendToClient;
import com.botamochi.easyannouncement.registry.EATile;
import com.botamochi.easyannouncement.screen.MainScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AnnounceTile extends BlockEntity implements ExtendedScreenHandlerFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAnnouncement");
    private int seconds = 0;
    private List<Long> selectedPlatformIds = new ArrayList<>();
    private List<AnnouncementEntry> announcementEntries = new ArrayList<>();
    private long lastMarkDirtyTime = 0;
    private static final long MARK_DIRTY_INTERVAL = 1000;
    private long lastAnnounceTriggerTime = 0;
    private long lastRepeatTriggerTime = 0;
    private static final long MIN_TRIGGER_INTERVAL = 1000;

    // Sound configuration fields
    private float soundVolume = 2.0F;
    private int soundRange = 64;
    private String attenuationType = "LINEAR";

    // Bounding box coordinates
    private boolean boundingBoxEnabled = false;
    private int startX = -100, startY = -64, startZ = -100;
    private int endX = 100, endY = 320, endZ = 100;

    // Trigger mode
    private String triggerMode = "EXACT";
    private String lastTriggeredKey = "";
    
    // Legacy migration flag - tells client to auto-select all platforms
    private boolean needsLegacyMigration = false;


    // Repeat mode
    private long nextRepeatTime = 0;
    private List<AnnouncementEntry> repeatEntries = new ArrayList<>();
    private boolean isRepeatPlaying = false;
    private boolean isTriggerPlaying = false;
    private long repeatStartTime = 0;
    private long triggerStartTime = 0;
    private boolean repeatInterrupted = false;
    private boolean waitingForTriggerCallback = false;

    private boolean excludePlayersAbove = false;
    private int repeatIntervalSeconds = 60;
    private boolean registered = false;

    // Track players who have received the config packet (so client-side monitoring starts without opening menu)
    private final Set<UUID> configSentPlayers = new HashSet<>();

    // Cached arrival data for MTR 4.0
    private List<CachedArrival> cachedArrivals = new ArrayList<>();
    private long lastCacheTime = 0;
    private static final long CACHE_DURATION = 5000; // 5 seconds
    private long lastMTRRequestTime = 0;
    private static final long MTR_REQUEST_INTERVAL = 1000; // Request MTR data at most once per second

    // Client-side MTR arrival data (real data from client, used for trigger)
    private long clientArrivalTime = 0;
    private long clientPlatformId = -1;
    private long clientRouteId = -1;
    private int clientCurrentStationIndex = -1;
    private String clientDestination = "";
    private String clientRouteType = "";
    private String clientHh = "00";
    private String clientMm = "00";
    private String clientPlatformName = "";
    private String clientRouteName = "";

    // Client arrival data received from client for server-side cache
    public static class ClientArrivalData {
        public long arrivalTime;
        public long platformId;
        public long routeId;
        public int currentStationIndex;
        public String destination;
        public String routeName;
        public String platformName;
        public ClientArrivalData(long arrivalTime, long platformId, long routeId, int currentStationIndex,
                                 String destination, String routeName, String platformName) {
            this.arrivalTime = arrivalTime;
            this.platformId = platformId;
            this.routeId = routeId;
            this.currentStationIndex = currentStationIndex;
            this.destination = destination;
            this.routeName = routeName;
            this.platformName = platformName;
        }
    }

    public AnnounceTile(BlockPos pos, BlockState state) {
        super(EATile.EA_BLOCK_TILE, pos, state);
    }

    @Override
    public void setWorld(World world) {
        super.setWorld(world);
        if (!world.isClient && !registered) {
            registered = true;
            Easyannouncement.registerAnnounceTilePosition(this);
        }
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            AnnounceSendToClient.sendToClient(serverPlayer, pos, seconds, selectedPlatformIds, announcementEntries,
                repeatEntries, soundVolume, soundRange, attenuationType, boundingBoxEnabled,
                startX, startY, startZ, endX, endY, endZ, triggerMode, excludePlayersAbove, repeatIntervalSeconds,
                needsLegacyMigration);
        }
        return new MainScreenHandler(syncId, inv, this);
    }

    public void startAnnouncement(ServerPlayerEntity player) {
        if (!world.isClient) {
            if (announcementEntries.isEmpty()) return;

            // Get arrival info (from cache or generate placeholder)
            ArrivalInfo info = getNextArrivalInfo();

            String destination = info != null && info.destination != null ? info.destination : "announcement";
            String routeType = info != null && info.routeType != null ? info.routeType : "";
            String hh = info != null ? info.hh : "00";
            String mm = info != null ? info.mm : "00";

            AnnounceSendToClient.sendAnnounceStartPacket(player, selectedPlatformIds, pos, announcementEntries,
                destination, routeType, hh, mm,
                info != null ? info.platformId : -1L,
                info != null ? info.routeId : -1L,
                info != null ? info.currentStationIndex : -1, false,
                info != null ? info.platformName : "",
                info != null ? info.routeName : "");
        }
    }

    /**
     * Update server-side cache with real arrival data from client.
     * Also store the best arrival for display purposes.
     */
    public void updateClientArrivalData(List<ClientArrivalData> arrivals, long chosenPlatformId, long chosenArrivalTime,
                                        String destination, String routeName, String hh, String mm,
                                        long routeId, int currentStationIndex) {
        // Update the chosen arrival for display
        this.clientArrivalTime = chosenArrivalTime;
        this.clientPlatformId = chosenPlatformId;
        this.clientRouteId = routeId;
        this.clientCurrentStationIndex = currentStationIndex;
        this.clientDestination = destination != null ? destination : "";
        this.clientRouteName = routeName != null ? routeName : "";
        this.clientRouteType = parseRouteType(routeName);
        this.clientHh = hh != null ? hh : "00";
        this.clientMm = mm != null ? mm : "00";

        // Update server-side cached arrivals for trigger logic
        this.cachedArrivals.clear();
        if (arrivals != null) {
            for (ClientArrivalData arrival : arrivals) {
                if (arrival.arrivalTime > System.currentTimeMillis()) {
                    CachedArrival cached = new CachedArrival();
                    cached.platformId = arrival.platformId;
                    cached.arrivalTime = arrival.arrivalTime;
                    cached.destination = arrival.destination != null ? arrival.destination : "";
                    cached.routeName = arrival.routeName != null ? arrival.routeName : "";
                    cached.platformName = arrival.platformName != null ? arrival.platformName : "";
                    this.cachedArrivals.add(cached);
                }
            }
        }
        this.lastCacheTime = System.currentTimeMillis();
    }

    /**
     * Update server-side cache from MTR data response (single arrival, no full list)
     */
    public void updateClientArrivalDataWithResponse(long arrivalTimeMillis, long platformId, long routeId,
                                                    int currentStationIndex, String destination,
                                                    String routeName, String hh, String mm) {
        this.clientArrivalTime = arrivalTimeMillis;
        this.clientPlatformId = platformId;
        this.clientRouteId = routeId;
        this.clientCurrentStationIndex = currentStationIndex;
        this.clientDestination = destination != null ? destination : "";
        this.clientRouteName = routeName != null ? routeName : "";
        this.clientRouteType = parseRouteType(routeName);
        this.clientHh = hh != null ? hh : "00";
        this.clientMm = mm != null ? mm : "00";
        this.lastCacheTime = System.currentTimeMillis();
    }

    /**
     * Trigger announcement using provided data (from MTR data response)
     */
    public void triggerAnnouncementWithData(ServerPlayerEntity player, String destination, String routeName,
                                            String hh, String mm, long platformId, long routeId, int currentStationIndex) {
        if (announcementEntries.isEmpty()) return;


        AnnounceSendToClient.sendAnnounceStartPacket(player, selectedPlatformIds, pos, announcementEntries,
            destination, "", hh, mm,
            platformId, routeId, currentStationIndex, false,
            "", routeName);
    }

    /**
     * Request MTR data from client for a player entering range
     */
    public void requestMTRDataFromClient(ServerPlayerEntity player) {
        if (selectedPlatformIds.isEmpty()) return;
        AnnounceSendToClient.sendMTRDataRequest(player, pos, selectedPlatformIds);
    }

    private static class ArrivalInfo {
        long platformId = -1;
        long routeId = -1;
        int currentStationIndex = -1;
        String destination;
        String routeType;
        String platformName;
        String routeName;
        String hh = "00";
        String mm = "00";
        long arrivalTimeMillis = 0; // Absolute arrival time in millis
    }

    private static class CachedArrival {
        long platformId;
        long arrivalTime;
        String destination;
        String routeName;
        String platformName;
    }

    /**
     * Get next arrival info. Uses cached data refreshed periodically.
     * Prefer client-side real data if available, fall back to placeholder.
     */
    private ArrivalInfo getNextArrivalInfo() {
        if (selectedPlatformIds.isEmpty() || world == null || world.isClient) return null;

        long currentTime = System.currentTimeMillis();

        // Refresh cache periodically (only if no client data available)
        if (clientArrivalTime == 0 && currentTime - lastCacheTime > CACHE_DURATION) {
            refreshArrivalCache();
            lastCacheTime = currentTime;
        }

        // Prefer client-side real MTR data if available
        if (clientArrivalTime > currentTime) {
            ArrivalInfo info = new ArrivalInfo();
            info.platformId = clientPlatformId;
            info.destination = clientDestination;
            info.routeType = clientRouteType;
            info.platformName = clientPlatformName;
            info.routeName = clientRouteName;
            info.arrivalTimeMillis = clientArrivalTime;
            info.hh = clientHh;
            info.mm = clientMm;
            info.routeId = clientRouteId;
            info.currentStationIndex = clientCurrentStationIndex;
            return info;
        }

        // Fall back to cached arrivals
        for (CachedArrival arrival : cachedArrivals) {
            if (arrival.arrivalTime > currentTime) {
                ArrivalInfo info = new ArrivalInfo();
                info.platformId = arrival.platformId;
                info.destination = arrival.destination;
                info.routeName = arrival.routeName;
                info.routeType = parseRouteType(arrival.routeName);
                info.platformName = arrival.platformName;
                info.arrivalTimeMillis = arrival.arrivalTime;
                // Calculate hh:mm from the absolute arrival time
                info.hh = String.format("%02d", (int) ((arrival.arrivalTime / 3600000) % 24));
                info.mm = String.format("%02d", (int) ((arrival.arrivalTime / 60000) % 60));
                return info;
            }
        }

        return null;
    }

    private String parseRouteType(String routeName) {
        if (routeName == null || routeName.isEmpty()) return "";
        try {
            Integer.parseInt(routeName.trim());
            return routeName.trim().toLowerCase();
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /**
     * Refresh arrival cache from MTR 4.0 API.
     * Uses reflection to call MTR 4.0 methods since the mapping types are incompatible.
     */
    private void refreshArrivalCache() {
        cachedArrivals.clear();

        if (!(world instanceof ServerWorld sw)) return;

        try {
            // Try to use MTR 4.0 ArrivalsCacheServer via reflection
            Class<?> arrivalsCacheServerClass = Class.forName("org.mtr.mod.data.ArrivalsCacheServer");
            java.lang.reflect.Method getInstanceMethod = arrivalsCacheServerClass.getMethod("getInstance", Class.forName("org.mtr.mapping.holder.ServerWorld"));

            // We can't convert Fabric ServerWorld to MTR ServerWorld directly
            // So we'll use a fallback: create placeholder data

        } catch (Exception e) {
            // MTR 4.0 API not available - use placeholder
        }

        // Generate placeholder arrivals for testing
        if (cachedArrivals.isEmpty() && !selectedPlatformIds.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Long platformId : selectedPlatformIds) {
                // Create placeholder arrivals every 5 minutes
                for (int i = 1; i <= 4; i++) {
                    CachedArrival arrival = new CachedArrival();
                    arrival.platformId = platformId;
                    arrival.arrivalTime = now + (i * 300000L); // 5, 10, 15, 20 minutes
                    arrival.destination = "next_train";
                    arrival.routeName = "";
                    arrival.platformName = "platform_" + platformId;
                    cachedArrivals.add(arrival);
                }
            }
        }
    }

    private static final long TRIGGER_CALLBACK_TIMEOUT = 5000;
    private long waitingForTriggerCallbackTime = 0;
    private static final int MIN_REPEAT_INTERVAL_SECONDS = 1;
    private static final long TRIGGER_COOLDOWN_MS = 40000L;
    private static final long REPEAT_POST_TRIGGER_DELAY_MS = 5000L; // Don't restart repeat within 5s after trigger ends
    private long lastTriggerEndTime = 0; // Prevents repeat from restarting immediately after trigger ends

    public void tick(World world, BlockPos pos, BlockState state) {
        if (world.isClient || world.getServer().isStopping()) return;

        long currentTime = System.currentTimeMillis();
        boolean hasRepeatEntries = !repeatEntries.isEmpty();
        boolean hasTriggerEntries = !announcementEntries.isEmpty();

        // Send config packet to players entering range who haven't received it yet
        // This initializes client-side MTR monitoring (clientTriggerStates) without needing to open the menu
        if (hasTriggerEntries || hasRepeatEntries) {
            for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                if (isPlayerEligible(player, pos) && !configSentPlayers.contains(player.getUuid())) {
                    double distSq = player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distSq <= (double) soundRange * soundRange) {
                        AnnounceSendToClient.sendToClient(player, pos, seconds, selectedPlatformIds, announcementEntries,
                            repeatEntries, soundVolume, soundRange, attenuationType, boundingBoxEnabled,
                            startX, startY, startZ, endX, endY, endZ, triggerMode, excludePlayersAbove, repeatIntervalSeconds,
                            needsLegacyMigration);
                        configSentPlayers.add(player.getUuid());
                    }
                }
            }
            // Clean up players who left
            configSentPlayers.removeIf(uuid -> {
                ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(uuid);
                return p == null || !isPlayerEligible(p, pos);
            });
        }

        // Request MTR data from client if we don't have valid data and players are in range
        if (hasTriggerEntries) {
            int playerCount = countEligiblePlayers(world, pos);

            if (playerCount > 0) {
                if (selectedPlatformIds.isEmpty()) {
                    // === AUTO DETECTION BLOCK ===
                    // Always check if player is near and request auto-detect if no platforms selected
                    if (currentTime - lastMTRRequestTime >= MTR_REQUEST_INTERVAL) {
                        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                            if (isPlayerEligible(player, pos)) {
                                AnnounceSendToClient.sendAutoDetectRequest(player, pos);
                                lastMTRRequestTime = currentTime;
                                break;
                            }
                        }
                    }
                } else {
                    // === EXISTING MTR DATA REQUEST LOGIC ===
                    // Check if we need to request MTR data (no valid data or data is stale)
                    boolean needsData = (clientArrivalTime == 0 || clientArrivalTime <= currentTime - CACHE_DURATION);
                    // Also request if routeName is empty (means we don't have real data)
                    needsData = needsData || (clientRouteName == null || clientRouteName.isEmpty());
                    if (needsData && currentTime - lastMTRRequestTime >= MTR_REQUEST_INTERVAL) {
                        // Send request to first eligible player
                        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                            if (isPlayerEligible(player, pos)) {
                                requestMTRDataFromClient(player);
                                lastMTRRequestTime = currentTime;
                                break;
                            }
                        }
                    }
                }
            }
        } else {
            // === AUTO DETECT WITHOUT TRIGGER ENTRIES ===
            // Always try to auto-detect platforms even if no trigger entries yet
            // This allows user to set up trigger entries later and announcement will work immediately
            int playerCount = countEligiblePlayers(world, pos);
            if (playerCount > 0 && selectedPlatformIds.isEmpty()) {
                if (currentTime - lastMTRRequestTime >= MTR_REQUEST_INTERVAL) {
                    for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                        if (isPlayerEligible(player, pos)) {
                            AnnounceSendToClient.sendAutoDetectRequest(player, pos);
                            lastMTRRequestTime = currentTime;
                            break;
                        }
                    }
                }
            }
        }

        // Handle repeat announcements
        if (hasRepeatEntries) {
            if (repeatInterrupted) {
                isRepeatPlaying = false;
                repeatInterrupted = false;
            }

            if (!isRepeatPlaying && !isTriggerPlaying) {
                if (waitingForTriggerCallback) {
                    if (waitingForTriggerCallbackTime > 0 && currentTime - waitingForTriggerCallbackTime > TRIGGER_CALLBACK_TIMEOUT) {
                        waitingForTriggerCallback = false;
                        waitingForTriggerCallbackTime = 0;
                        int effectiveInterval = Math.max(repeatIntervalSeconds, MIN_REPEAT_INTERVAL_SECONDS);
                        nextRepeatTime = currentTime + (effectiveInterval * 1000L);
                    }
                } else if (isRepeatPlaying) {
                    if (repeatStartTime > 0 && currentTime - repeatStartTime > TRIGGER_CALLBACK_TIMEOUT) {
                        isRepeatPlaying = false;
                        repeatStartTime = 0;
                        int effectiveInterval = Math.max(repeatIntervalSeconds, MIN_REPEAT_INTERVAL_SECONDS);
                        nextRepeatTime = currentTime + (effectiveInterval * 1000L);
                    }
                } else {
                    // Don't restart repeat within 5s after a trigger ended
                    if (lastTriggerEndTime > 0 && currentTime - lastTriggerEndTime < REPEAT_POST_TRIGGER_DELAY_MS) {
                        // Still within post-trigger delay - skip
                    } else {
                        int effectiveInterval = Math.max(repeatIntervalSeconds, MIN_REPEAT_INTERVAL_SECONDS);
                        if (effectiveInterval > 0 && currentTime >= nextRepeatTime) {
                            if (currentTime - lastRepeatTriggerTime >= MIN_TRIGGER_INTERVAL) {
                                int playerCount = countEligiblePlayers(world, pos);
                                if (playerCount > 0) {
                                    for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                                        if (!isPlayerEligible(player, pos)) continue;
                                        startRepeatAnnouncement(player);
                                    }
                                    nextRepeatTime = currentTime + (effectiveInterval * 1000L);
                                    lastRepeatTriggerTime = currentTime;
                                    repeatStartTime = currentTime;
                                    isRepeatPlaying = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        // Handle trigger announcements
        if (hasTriggerEntries) {
            ArrivalInfo info = getNextArrivalInfo();
            if (info != null && info.arrivalTimeMillis > 0) {
                String arrivalKey = info.platformId + ":" + info.arrivalTimeMillis;
                long arrivalTime = info.arrivalTimeMillis;
                long timeUntilArrival = arrivalTime - currentTime;
                long thresholdMs = getSeconds() * 1000L;

                boolean shouldTrigger = false;

                if (thresholdMs == 0) {
                    // Exact arrival trigger: play once when train arrives (narrow window: 3 seconds before to 1 second after)
                    // Key fix: only trigger if arrival JUST happened (timeUntilArrival is small negative)
                    // and hasn't already been triggered for this arrival
                    shouldTrigger = timeUntilArrival <= 0 && timeUntilArrival > -3000 && !arrivalKey.equals(lastTriggeredKey);
                    // Also prevent re-triggering if we already triggered this same arrival
                    if (arrivalKey.equals(lastTriggeredKey)) {
                        shouldTrigger = false;
                    }
                } else {
                    // Positive: play when within threshold range (±1 tick tolerance)
                    if (!arrivalKey.equals(lastTriggeredKey)) {
                        shouldTrigger = Math.abs(timeUntilArrival - thresholdMs) <= 50;
                    }
                }

                if (shouldTrigger) {
                    if (currentTime - lastAnnounceTriggerTime >= MIN_TRIGGER_INTERVAL) {
                        int playerCount = countEligiblePlayers(world, pos);
                        if (playerCount > 0) {
                            for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                                if (!isPlayerEligible(player, pos)) continue;
                                if (hasRepeatEntries) {
                                    AnnounceSendToClient.sendStopPacket(player, pos);
                                    isRepeatPlaying = false;
                                    repeatInterrupted = true;
                                    waitingForTriggerCallback = true;
                                    waitingForTriggerCallbackTime = currentTime;
                                    nextRepeatTime = Long.MAX_VALUE;
                                }
                                isTriggerPlaying = true;
                                triggerStartTime = currentTime;
                                startAnnouncement(player);
                            }
                            if (hasRepeatEntries) {
                                waitingForTriggerCallback = false;
                                waitingForTriggerCallbackTime = 0;
                            }
                            lastAnnounceTriggerTime = currentTime;
                            lastTriggeredKey = arrivalKey;
                        }
                    }
                }
            }
        }
    }

    private int countEligiblePlayers(World world, BlockPos pos) {
        int count = 0;
        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
            if (isPlayerEligible(player, pos)) count++;
        }
        return count;
    }

    private boolean isPlayerEligible(ServerPlayerEntity player, BlockPos pos) {
        return !(excludePlayersAbove && player.getBlockY() > pos.getY());
    }

    public void startRepeatAnnouncement(ServerPlayerEntity player) {
        if (world.isClient || repeatEntries.isEmpty()) return;
        ArrivalInfo info = getNextArrivalInfo();
        String destination = info != null && info.destination != null ? info.destination : "repeat";
        String routeType = info != null && info.routeType != null ? info.routeType : "";
        String hh = info != null ? info.hh : "00";
        String mm = info != null ? info.mm : "00";

        AnnounceSendToClient.sendAnnounceStartPacket(player, selectedPlatformIds, pos, repeatEntries,
            destination, routeType, hh, mm,
            info != null ? info.platformId : -1L,
            info != null ? info.routeId : -1L,
            info != null ? info.currentStationIndex : -1, true,
            info != null ? info.platformName : "",
            info != null ? info.routeName : "");
    }

    public void onAnnouncementFinished() {
        isTriggerPlaying = false;
        isRepeatPlaying = false;

        if (waitingForTriggerCallback) {
            // Trigger was interrupted - apply cooldown before repeat resumes
            waitingForTriggerCallback = false;
            waitingForTriggerCallbackTime = 0;
            nextRepeatTime = System.currentTimeMillis() + TRIGGER_COOLDOWN_MS;
            lastTriggerEndTime = System.currentTimeMillis();
        } else {
            // Normal trigger end - add post-trigger delay before repeat resumes
            nextRepeatTime = System.currentTimeMillis() + (long) Math.max(repeatIntervalSeconds, MIN_REPEAT_INTERVAL_SECONDS) * 1000L;
            lastTriggerEndTime = System.currentTimeMillis();
        }
    }

    // Getters and setters
    @Override
    public Text getDisplayName() {
        return Text.translatable(getCachedState().getBlock().getTranslationKey());
    }

    public List<Long> getSelectedPlatformIds() { return new ArrayList<>(selectedPlatformIds); }
    public void setSelectedPlatformIds(List<Long> selectedPlatformIds) {
        if (!this.selectedPlatformIds.equals(selectedPlatformIds)) {
            this.selectedPlatformIds = new ArrayList<>(selectedPlatformIds);
            cachedArrivals.clear(); // Clear cache when platforms change
            markDirty();
        }
    }
    public int getSeconds() { return seconds; }
    public void setSeconds(int seconds) {
        if (this.seconds != seconds) { this.seconds = seconds; markDirty(); }
    }
    public float getSoundVolume() { return soundVolume; }
    public void setSoundVolume(float soundVolume) {
        this.soundVolume = Math.max(0.1F, Math.min(3.0F, soundVolume));
        markDirty();
    }
    public int getSoundRange() { return soundRange; }
    public void setSoundRange(int soundRange) {
        this.soundRange = Math.max(16, Math.min(128, soundRange));
        markDirty();
    }
    public String getAttenuationType() { return attenuationType; }
    public void setAttenuationType(String attenuationType) {
        if (!this.attenuationType.equals(attenuationType)) { this.attenuationType = attenuationType; markDirty(); }
    }
    public int getStartX() { return startX; }
    public void setStartX(int v) { if (startX != v) { startX = v; markDirty(); } }
    public int getStartY() { return startY; }
    public void setStartY(int v) { if (startY != v) { startY = v; markDirty(); } }
    public int getStartZ() { return startZ; }
    public void setStartZ(int v) { if (startZ != v) { startZ = v; markDirty(); } }
    public int getEndX() { return endX; }
    public void setEndX(int v) { if (endX != v) { endX = v; markDirty(); } }
    public int getEndY() { return endY; }
    public void setEndY(int v) { if (endY != v) { endY = v; markDirty(); } }
    public int getEndZ() { return endZ; }
    public void setEndZ(int v) { if (endZ != v) { endZ = v; markDirty(); } }
    public boolean isBoundingBoxEnabled() { return boundingBoxEnabled; }
    public void setBoundingBoxEnabled(boolean v) { if (boundingBoxEnabled != v) { boundingBoxEnabled = v; markDirty(); } }
    public String getTriggerMode() { return triggerMode; }
    public void setTriggerMode(String v) {
        if (v != null && !v.equals(triggerMode)) { triggerMode = v; markDirty(); }
    }
    public boolean hasRepeatEntries() { return !repeatEntries.isEmpty(); }
    public List<AnnouncementEntry> getRepeatEntries() { return new ArrayList<>(repeatEntries); }
    public void setRepeatEntries(List<AnnouncementEntry> entries) {
        if (!repeatEntries.equals(entries)) { repeatEntries = new ArrayList<>(entries); markDirty(); }
    }
    public boolean isExcludePlayersAbove() { return excludePlayersAbove; }
    public void setExcludePlayersAbove(boolean v) { if (excludePlayersAbove != v) { excludePlayersAbove = v; markDirty(); } }
    public int getRepeatIntervalSeconds() { return repeatIntervalSeconds; }
    public void setRepeatIntervalSeconds(int v) { if (repeatIntervalSeconds != v) { repeatIntervalSeconds = v; markDirty(); } }
    public boolean needsLegacyMigration() { return needsLegacyMigration; }
    public void clearLegacyMigration() { 
        if (needsLegacyMigration) {
            needsLegacyMigration = false;
            markDirty();
        }
    }

    // NBT
    @Override
    public void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLongArray("PlatformId", selectedPlatformIds.stream().mapToLong(Long::longValue).toArray());
        nbt.putInt("TimeBeforeAnnounce", seconds);

        NbtList entriesList = new NbtList();
        for (AnnouncementEntry entry : announcementEntries) {
            NbtCompound entryNbt = new NbtCompound();
            entry.writeNbt(entryNbt);
            entriesList.add(entryNbt);
        }
        nbt.put("AnnouncementEntries", entriesList);

        if (!announcementEntries.isEmpty()) {
            nbt.putString("SelectedJson", announcementEntries.get(0).getJsonName());
        }

        nbt.putFloat("SoundVolume", soundVolume);
        nbt.putInt("SoundRange", soundRange);
        nbt.putString("AttenuationType", attenuationType);
        nbt.putBoolean("BoundingBoxEnabled", boundingBoxEnabled);
        nbt.putInt("StartX", startX); nbt.putInt("StartY", startY); nbt.putInt("StartZ", startZ);
        nbt.putInt("EndX", endX); nbt.putInt("EndY", endY); nbt.putInt("EndZ", endZ);
        nbt.putString("TriggerMode", triggerMode);
        nbt.putBoolean("ExcludePlayersAbove", excludePlayersAbove);
        nbt.putInt("RepeatIntervalSeconds", repeatIntervalSeconds);
        nbt.putBoolean("NeedsLegacyMigration", needsLegacyMigration);

        NbtList repeatEntriesList = new NbtList();
        for (AnnouncementEntry entry : repeatEntries) {
            NbtCompound entryNbt = new NbtCompound();
            entry.writeNbt(entryNbt);
            repeatEntriesList.add(entryNbt);
        }
        nbt.put("RepeatEntries", repeatEntriesList);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        selectedPlatformIds.clear();
        for (long id : nbt.getLongArray("PlatformId")) {
            selectedPlatformIds.add(id);
        }
        seconds = nbt.getInt("TimeBeforeAnnounce");

        // Check for legacy route-based selection and migrate
        if (nbt.contains("SelectedRouteColors") && selectedPlatformIds.isEmpty()) {
            needsLegacyMigration = true;
        }

        // Also check for station-based legacy data
        if (nbt.contains("SelectedStationIds") && selectedPlatformIds.isEmpty()) {
            needsLegacyMigration = true;
        }

        announcementEntries.clear();
        if (nbt.contains("AnnouncementEntries")) {
            NbtList entriesList = nbt.getList("AnnouncementEntries", 10);
            for (int i = 0; i < entriesList.size(); i++) {
                NbtCompound entryNbt = entriesList.getCompound(i);
                AnnouncementEntry entry = new AnnouncementEntry();
                entry.readNbt(entryNbt);
                announcementEntries.add(entry);
            }
        } else if (nbt.contains("SelectedJson")) {
            String legacyJson = nbt.getString("SelectedJson");
            if (!legacyJson.isEmpty()) {
                announcementEntries.add(new AnnouncementEntry(legacyJson, 0));
            }
        }
        if (announcementEntries.isEmpty() && !selectedPlatformIds.isEmpty()) {
            announcementEntries.add(new AnnouncementEntry("station_bell", 0));
        }

        soundVolume = nbt.contains("SoundVolume") ? nbt.getFloat("SoundVolume") : 2.0F;
        soundRange = nbt.contains("SoundRange") ? nbt.getInt("SoundRange") : 64;
        attenuationType = nbt.contains("AttenuationType") ? nbt.getString("AttenuationType") : "LINEAR";
        boundingBoxEnabled = nbt.contains("BoundingBoxEnabled") && nbt.getBoolean("BoundingBoxEnabled");
        startX = nbt.contains("StartX") ? nbt.getInt("StartX") : -100;
        startY = nbt.contains("StartY") ? nbt.getInt("StartY") : -64;
        startZ = nbt.contains("StartZ") ? nbt.getInt("StartZ") : -100;
        endX = nbt.contains("EndX") ? nbt.getInt("EndX") : 100;
        endY = nbt.contains("EndY") ? nbt.getInt("EndY") : 320;
        endZ = nbt.contains("EndZ") ? nbt.getInt("EndZ") : 100;
        triggerMode = nbt.contains("TriggerMode") ? nbt.getString("TriggerMode") : "EXACT";

        repeatEntries.clear();
        if (nbt.contains("RepeatEntries")) {
            NbtList repeatEntriesList = nbt.getList("RepeatEntries", 10);
            for (int i = 0; i < repeatEntriesList.size(); i++) {
                NbtCompound entryNbt = repeatEntriesList.getCompound(i);
                AnnouncementEntry entry = new AnnouncementEntry();
                entry.readNbt(entryNbt);
                repeatEntries.add(entry);
            }
        }

        excludePlayersAbove = nbt.contains("ExcludePlayersAbove") && nbt.getBoolean("ExcludePlayersAbove");
        repeatIntervalSeconds = nbt.contains("RepeatIntervalSeconds") ? nbt.getInt("RepeatIntervalSeconds") : 60;
        needsLegacyMigration = nbt.contains("NeedsLegacyMigration") && nbt.getBoolean("NeedsLegacyMigration");

        lastTriggeredKey = "";
        cachedArrivals.clear();
        registered = false;
        waitingForTriggerCallbackTime = 0;
        configSentPlayers.clear();
    }

    /**
     * Migrate legacy route-based selection to platform IDs
     * Old format: SelectedRouteColors (list of route colors)
     * Keep existing platform IDs if available, otherwise select all
     */
    private void migrateLegacyRouteSelection(NbtCompound nbt) {
        if (nbt.contains("SelectedRouteColors")) {
            // If already has platform IDs, keep them; otherwise let UI select all
            if (selectedPlatformIds.isEmpty()) {
            } else {
            }
        }
    }

    /**
     * Migrate legacy station-based selection to platform IDs
     * Old format: SelectedStationIds (list of station IDs)
     * Keep existing platform IDs if available, otherwise select all
     */
    private void migrateLegacyStationSelection(NbtCompound nbt) {
        if (nbt.contains("SelectedStationIds")) {
            // If already has platform IDs, keep them; otherwise let UI select all
            if (selectedPlatformIds.isEmpty()) {
            } else {
            }
        }
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity serverPlayerEntity, PacketByteBuf packetByteBuf) {
        packetByteBuf.writeBlockPos(this.pos);
        packetByteBuf.writeBoolean(needsLegacyMigration);
    }

    @Override
    public void markDirty() {
        long currentTime = System.currentTimeMillis();
        super.markDirty();
        if (world != null && !world.isClient) {
            if (currentTime - lastMarkDirtyTime >= MARK_DIRTY_INTERVAL) {
                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), Block.NOTIFY_ALL);
                if (world.getServer() != null) {
                    for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                        AnnounceSendToClient.sendToClient(player, pos, seconds, selectedPlatformIds, announcementEntries,
                            repeatEntries, soundVolume, soundRange, attenuationType, boundingBoxEnabled,
                            startX, startY, startZ, endX, endY, endZ, triggerMode, excludePlayersAbove, repeatIntervalSeconds,
                            needsLegacyMigration);
                    }
                }
                configSentPlayers.clear();
                lastMarkDirtyTime = currentTime;
            }
        }
    }

    public List<AnnouncementEntry> getAnnouncementEntries() { return new ArrayList<>(announcementEntries); }
    public void setAnnouncementEntries(List<AnnouncementEntry> entries) {
        if (!announcementEntries.equals(entries)) { announcementEntries = new ArrayList<>(entries); markDirty(); }
    }
    public String getSelectedJson() {
        return announcementEntries.isEmpty() ? "" : announcementEntries.get(0).getJsonName();
    }
    public void setSelectedJson(String json) {
        announcementEntries.clear();
        if (json != null && !json.trim().isEmpty()) {
            announcementEntries.add(new AnnouncementEntry(json, 0));
        }
        markDirty();
    }
    public void sync() {
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }
    public World getWorld() { return world; }

    // ========================================================================
    // RECEIVE CONFIG UPDATE FROM SERVER (Client-side)
    // Called when client receives config packet from server
    // ========================================================================
    public void receiveConfigUpdate(List<Long> selectedPlatforms, List<AnnouncementEntry> entries,
                                   List<AnnouncementEntry> repeatEntries,
                                   float volume, int range, String attenuationType,
                                   boolean boundingBoxEnabled,
                                   int startX, int startY, int startZ,
                                   int endX, int endY, int endZ,
                                   String triggerMode, boolean excludePlayersAbove,
                                   int repeatIntervalSeconds, boolean needsLegacyMigration) {
        this.selectedPlatformIds = new ArrayList<>(selectedPlatforms);
        this.announcementEntries = new ArrayList<>(entries);
        this.repeatEntries = new ArrayList<>(repeatEntries);
        this.soundVolume = volume;
        this.soundRange = range;
        this.attenuationType = attenuationType;
        this.boundingBoxEnabled = boundingBoxEnabled;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.endX = endX;
        this.endY = endY;
        this.endZ = endZ;
        this.triggerMode = triggerMode;
        this.excludePlayersAbove = excludePlayersAbove;
        this.repeatIntervalSeconds = repeatIntervalSeconds;
        this.needsLegacyMigration = needsLegacyMigration;
    }

    // Server-side: update config from received packet
    public void updateConfig(List<Long> selectedPlatforms, List<AnnouncementEntry> entries,
                             List<AnnouncementEntry> repeatEntries,
                             float volume, int range, String attenuationType,
                             boolean boundingBoxEnabled,
                             int startX, int startY, int startZ,
                             int endX, int endY, int endZ,
                             String triggerMode, boolean excludePlayersAbove,
                             int repeatIntervalSeconds) {
        this.selectedPlatformIds = new ArrayList<>(selectedPlatforms);
        this.announcementEntries = new ArrayList<>(entries);
        this.repeatEntries = new ArrayList<>(repeatEntries);
        this.soundVolume = volume;
        this.soundRange = range;
        this.attenuationType = attenuationType;
        this.boundingBoxEnabled = boundingBoxEnabled;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.endX = endX;
        this.endY = endY;
        this.endZ = endZ;
        this.triggerMode = triggerMode;
        this.excludePlayersAbove = excludePlayersAbove;
        this.repeatIntervalSeconds = repeatIntervalSeconds;
        this.markDirty();
    }

    // Update only the selected platforms
    public void updateSelectedPlatforms(List<Long> platforms) {
        this.selectedPlatformIds = new ArrayList<>(platforms);
        // Reset request time to prevent immediate re-request on next tick
        this.lastMTRRequestTime = System.currentTimeMillis();
        if (needsLegacyMigration) {
            needsLegacyMigration = false;
        }
        this.markDirty();
    }
}
