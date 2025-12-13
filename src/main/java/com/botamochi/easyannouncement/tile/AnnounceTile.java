package com.botamochi.easyannouncement.tile;

import com.botamochi.easyannouncement.Easyannouncement;
import com.botamochi.easyannouncement.data.AnnouncementEntry;
import com.botamochi.easyannouncement.network.AnnounceSendToClient;
import com.botamochi.easyannouncement.registry.EATile;
import com.botamochi.easyannouncement.screen.MainScreenHandler;
import mtr.data.*;
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
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
// Removed SoundInstance import - using string storage instead

import java.util.ArrayList;
import java.util.List;

public class AnnounceTile extends BlockEntity implements ExtendedScreenHandlerFactory {
    private int seconds = 0;  // 放送前の時間
    private List<Long> selectedPlatformIds = new ArrayList<>();
    private List<AnnouncementEntry> announcementEntries = new ArrayList<>();
    private long lastMarkDirtyTime = 0;
    private static final long MARK_DIRTY_INTERVAL = 1000; // 1 秒ごとに markDirty() を呼び出す
    public static final Identifier ANNOUNCE_START_ID = new Identifier(Easyannouncement.MOD_ID, "announce_start");
    private long lastAnnounceTriggerTime = 0;
    private static final long MIN_TRIGGER_INTERVAL = 1000; // 例: 1 秒間隔
    
    // Sound configuration fields
    private float soundVolume = 2.0F;  // Default volume (0.1 - 3.0)
    private int soundRange = 64;       // Sound range in blocks (16 - 128)
    private String attenuationType = "LINEAR"; // Sound attenuation type
    
    // Bounding box coordinates for announcement area
    private boolean boundingBoxEnabled = false; // Enable/disable bounding box check
    private int startX = -100;  // Default start X coordinate
    private int startY = -64;   // Default start Y coordinate (bedrock level)
    private int startZ = -100;  // Default start Z coordinate
    private int endX = 100;     // Default end X coordinate
    private int endY = 320;     // Default end Y coordinate (build height)
    private int endZ = 100;     // Default end Z coordinate
    
    // Trigger mode and edge detection
    private String triggerMode = "EXACT"; // EXACT, AT_OR_BEFORE, AT_OR_AFTER
    private long lastTriggeredArrivalMillis = -1L;

    public AnnounceTile(BlockPos pos, BlockState state) {
        super(EATile.EA_BLOCK_TILE, pos, state);
        Easyannouncement.registerAnnounceTilePosition(pos);
    }

    public static RailwayData getRailwayData(World world) {
        return RailwayData.getInstance(world);
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            AnnounceSendToClient.sendToClient(serverPlayer, pos, seconds, selectedPlatformIds, announcementEntries,
                soundVolume, soundRange, attenuationType, boundingBoxEnabled,
                startX, startY, startZ, endX, endY, endZ, triggerMode);
        }
        return new MainScreenHandler(syncId, inv, this);
    }

    public void startAnnouncement(ServerPlayerEntity player) {
        if (!world.isClient) {
            // Ensure we have valid announcement entries
            if (announcementEntries.isEmpty()) {
    
                return;
            }
            
            // Compute chosen schedule with its originating platform
            RailwayData railwayData = RailwayData.getInstance(world);
            ChosenSchedule chosen = getNextScheduleEntryWithPlatform(railwayData, selectedPlatformIds);
            
            String calculatedDestination;
            String calculatedRouteType;
            String hh;
            String mm;
            long chosenPlatformId = -1L;
            long chosenRouteId = -1L;
            int chosenCurrentStationIndex = -1;
            
            if (chosen != null && chosen.entry != null) {
                chosenPlatformId = chosen.platformId;
                chosenRouteId = chosen.entry.routeId;
                chosenCurrentStationIndex = chosen.entry.currentStationIndex;
                
                // Derive destination based on the chosen route and current station index
                final mtr.data.Route route = railwayData != null ? railwayData.dataCache.routeIdMap.get(chosen.entry.routeId) : null;
                if (route != null) {
                    String customDest = route.getDestination(Math.min(chosen.entry.currentStationIndex, route.platformIds.size() - 1));
                    if (customDest != null && !customDest.isEmpty()) {
                        int lastPipeIndex = customDest.lastIndexOf('|');
                        calculatedDestination = (lastPipeIndex != -1 && lastPipeIndex < customDest.length() - 1
                                ? customDest.substring(lastPipeIndex + 1)
                                : customDest).toLowerCase().trim();
                    } else {
                        String finalDestination = null;
                        long lastPlatformId = route.getLastPlatformId();
                        mtr.data.Platform lastPlatform = railwayData.dataCache.platformIdMap.get(lastPlatformId);
                        if (lastPlatform != null) {
                            finalDestination = railwayData.dataCache.platformIdToStation.get(lastPlatformId).name;
                        }
                        if (finalDestination != null) {
                            int lastPipeIndex = finalDestination.lastIndexOf('|');
                            if (lastPipeIndex != -1 && lastPipeIndex < finalDestination.length() - 1) {
                                calculatedDestination = finalDestination.substring(lastPipeIndex + 1).toLowerCase().trim();
                            } else {
                                calculatedDestination = finalDestination.toLowerCase().trim();
                            }
                        } else {
                            calculatedDestination = "destination_unknown";
                        }
                    }
                    // Derive route type consistent with chosen route
                    if (route.routeType != null) {
                        switch (route.routeType) {
                            case LIGHT_RAIL:
                                if (route.lightRailRouteNumber != null && !route.lightRailRouteNumber.isEmpty()) {
                                    int lastPipeIndex = route.lightRailRouteNumber.lastIndexOf('|');
                                    String code = (lastPipeIndex != -1 && lastPipeIndex < route.lightRailRouteNumber.length() - 1)
                                            ? route.lightRailRouteNumber.substring(lastPipeIndex + 1)
                                            : route.lightRailRouteNumber;
                                    calculatedRouteType = code.toLowerCase().trim();
                                } else {
                                    calculatedRouteType = "light_rail";
                                }
                                break;
                            case HIGH_SPEED:
                                calculatedRouteType = "high_speed";
                                break;
                            case NORMAL:
                            default:
                                calculatedRouteType = "";
                                break;
                        }
                    } else if (route.lightRailRouteNumber != null && !route.lightRailRouteNumber.isEmpty()) {
                        int lastPipeIndex = route.lightRailRouteNumber.lastIndexOf('|');
                        String result;
                        if (lastPipeIndex != -1 && lastPipeIndex < route.lightRailRouteNumber.length() - 1) {
                            result = route.lightRailRouteNumber.substring(lastPipeIndex + 1).toLowerCase().trim();
                        } else {
                            result = route.lightRailRouteNumber.toLowerCase().trim();
                        }
                        calculatedRouteType = result;
                    } else {
                        calculatedRouteType = "";
                    }
                } else {
                    calculatedDestination = "route_unknown";
                    calculatedRouteType = "";
                }
                // HH:MM from the chosen entry arrivalMillis
                java.time.ZonedDateTime zdt = java.time.Instant.ofEpochMilli(chosen.entry.arrivalMillis).atZone(java.time.ZoneId.systemDefault());
                hh = String.format("%02d", zdt.getHour());
                mm = String.format("%02d", zdt.getMinute());
            } else {
                // Fallback to existing computations if no schedule found
                String calculatedDestinationFallback = getDestination(selectedPlatformIds);
                String calculatedRouteTypeFallback = getRouteType(selectedPlatformIds);
                String[] hhmm = getNextArrivalHhMm(world, selectedPlatformIds);
                hh = hhmm[0];
                mm = hhmm[1];
                calculatedDestination = calculatedDestinationFallback;
                calculatedRouteType = calculatedRouteTypeFallback;
            }
            
            AnnounceSendToClient.sendAnnounceStartPacket(player, selectedPlatformIds, pos, announcementEntries, calculatedDestination, calculatedRouteType, hh, mm, chosenPlatformId, chosenRouteId, chosenCurrentStationIndex);
        }
    }

    public void tick(World world, BlockPos pos, BlockState state) { // インスタンスメソッドに変更
        if (world.isClient || world.getServer().isStopping()) return; // ポーズ中は早期リターン

        long currentTime = System.currentTimeMillis();
        RailwayData railwayData = RailwayData.getInstance(world);
        final ScheduleEntry next = getNextScheduleEntry(railwayData, selectedPlatformIds);
        if (next != null) {
            long ticksUntilArrival = (next.arrivalMillis - currentTime) / 50; // ms -> ticks
            long threshold = (long) getSeconds() * 20L;
            boolean conditionMet;
            switch (triggerMode) {
                case "AT_OR_BEFORE":
                    conditionMet = ticksUntilArrival <= threshold;
                    break;
                case "AT_OR_AFTER":
                    conditionMet = ticksUntilArrival >= threshold;
                    break;
                case "EXACT":
                default:
                    // 使用範圍比較而非精確匹配，避免時間精度問題
                    // 允許 ±1 tick 的誤差範圍
                    conditionMet = Math.abs(ticksUntilArrival - threshold) <= 1;
            }
            if (conditionMet && lastTriggeredArrivalMillis != next.arrivalMillis) {
                if (currentTime - lastAnnounceTriggerTime >= MIN_TRIGGER_INTERVAL) {
                    if (world.getServer() != null) {
                        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                            startAnnouncement(player);
                        }
                        lastAnnounceTriggerTime = currentTime; // トリガー時間を更新
                        lastTriggeredArrivalMillis = next.arrivalMillis; // 1 本の列車につき 1 回のみ
                    }
                }
            }
        }
    }

    public static void tick(World world, BlockPos pos, BlockState state, AnnounceTile announceTile) { // 追加: AnnounceTileインスタンスからtickメソッドを呼ぶためのstaticメソッド
        announceTile.tick(world, pos, state);
    }

    private static boolean checkTrainApproaching(World world, long platformId, int secondsBefore) {
        RailwayData railwayData = RailwayData.getInstance(world);
        if (railwayData == null) return false;

        List<ScheduleEntry> schedules = railwayData.getSchedulesAtPlatform(platformId);
        if (schedules == null || schedules.isEmpty()) return false;

        long currentTime = System.currentTimeMillis();
        for (ScheduleEntry entry : schedules) {
            if ((entry.arrivalMillis - currentTime) / 50 == secondsBefore * 20) {
                return true;
            }
        }
        return false;
    }

    private ScheduleEntry getNextScheduleEntry(RailwayData railwayData, List<Long> platformIds) {
        if (railwayData == null || platformIds == null || platformIds.isEmpty()) return null;

        final List<ScheduleEntry> all = new ArrayList<>();
        for (long pid : platformIds) {
            final List<ScheduleEntry> schedules = railwayData.getSchedulesAtPlatform(pid);
            if (schedules != null) {
                all.addAll(schedules);
            }
        }
        if (all.isEmpty()) return null;

        final long now = System.currentTimeMillis();
        all.removeIf(se -> se.arrivalMillis < now);
        if (all.isEmpty()) return null;

        all.sort((a, b) -> Long.compare(a.arrivalMillis, b.arrivalMillis));

        // Prefer non-terminating trains (like PIDS), fallback to terminating if none
        ScheduleEntry best = null;
        ScheduleEntry bestTerminating = null;
        for (final ScheduleEntry se : all) {
            final Route r = railwayData.dataCache.routeIdMap.get(se.routeId);
            if (r == null) continue;
            final boolean isTerminating = se.currentStationIndex >= r.platformIds.size() - 1;
            if (!isTerminating) {
                best = se;
                break;
            } else if (bestTerminating == null) {
                bestTerminating = se;
            }
        }
        return best != null ? best : bestTerminating;
    }

    // Helper class and method to return chosen schedule with its originating platformId
    private static final class ChosenSchedule {
        private final long platformId;
        private final ScheduleEntry entry;
        private ChosenSchedule(long platformId, ScheduleEntry entry) {
            this.platformId = platformId;
            this.entry = entry;
        }
    }

    private ChosenSchedule getNextScheduleEntryWithPlatform(RailwayData railwayData, List<Long> platformIds) {
        if (railwayData == null || platformIds == null || platformIds.isEmpty()) return null;
        final java.util.List<ChosenSchedule> all = new java.util.ArrayList<>();
        final long now = System.currentTimeMillis();
        for (long pid : platformIds) {
            final java.util.List<ScheduleEntry> schedules = railwayData.getSchedulesAtPlatform(pid);
            if (schedules != null) {
                for (ScheduleEntry se : schedules) {
                    if (se.arrivalMillis >= now) {
                        all.add(new ChosenSchedule(pid, se));
                    }
                }
            }
        }
        if (all.isEmpty()) return null;
        all.sort((a, b) -> Long.compare(a.entry.arrivalMillis, b.entry.arrivalMillis));
        ChosenSchedule best = null;
        ChosenSchedule bestTerminating = null;
        for (final ChosenSchedule cs : all) {
            final Route r = railwayData.dataCache.routeIdMap.get(cs.entry.routeId);
            if (r == null) continue;
            final boolean isTerminating = cs.entry.currentStationIndex >= r.platformIds.size() - 1;
            if (!isTerminating) {
                best = cs;
                break;
            } else if (bestTerminating == null) {
                bestTerminating = cs;
            }
        }
        return best != null ? best : bestTerminating;
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable(getCachedState().getBlock().getTranslationKey());
    }

    public List<Long> getSelectedPlatformIds() {
        return new ArrayList<>(selectedPlatformIds);
    }

    public void setSelectedPlatformIds(List<Long> selectedPlatformIds) {
        if (!this.selectedPlatformIds.equals(selectedPlatformIds)) {
            this.selectedPlatformIds = new ArrayList<>(selectedPlatformIds);
            markDirty();
        }
    }

    public int getSeconds() {
        return seconds;
    }

    public void setSeconds(int seconds) {
        if (this.seconds != seconds) {
            this.seconds = seconds;
            markDirty();
        }
    }

    // Sound configuration getters and setters
    public float getSoundVolume() {
        return soundVolume;
    }

    public void setSoundVolume(float soundVolume) {
        if (this.soundVolume != soundVolume) {
            this.soundVolume = Math.max(0.1F, Math.min(3.0F, soundVolume)); // Clamp between 0.1 and 3.0
            markDirty();
        }
    }

    public int getSoundRange() {
        return soundRange;
    }

    public void setSoundRange(int soundRange) {
        if (this.soundRange != soundRange) {
            this.soundRange = Math.max(16, Math.min(128, soundRange)); // Clamp between 16 and 128
            markDirty();
        }
    }

    public String getAttenuationType() {
        return attenuationType;
    }

    public void setAttenuationType(String attenuationType) {
        if (!this.attenuationType.equals(attenuationType)) {
            this.attenuationType = attenuationType;
            markDirty();
        }
    }

    // Bounding box coordinate getters and setters
    public int getStartX() { return startX; }
    public void setStartX(int startX) {
        if (this.startX != startX) {
            this.startX = startX;
            markDirty();
        }
    }

    public int getStartY() { return startY; }
    public void setStartY(int startY) {
        if (this.startY != startY) {
            this.startY = startY;
            markDirty();
        }
    }

    public int getStartZ() { return startZ; }
    public void setStartZ(int startZ) {
        if (this.startZ != startZ) {
            this.startZ = startZ;
            markDirty();
        }
    }

    public int getEndX() { return endX; }
    public void setEndX(int endX) {
        if (this.endX != endX) {
            this.endX = endX;
            markDirty();
        }
    }

    public int getEndY() { return endY; }
    public void setEndY(int endY) {
        if (this.endY != endY) {
            this.endY = endY;
            markDirty();
        }
    }

    public int getEndZ() { return endZ; }
    public void setEndZ(int endZ) {
        if (this.endZ != endZ) {
            this.endZ = endZ;
            markDirty();
        }
    }

    public boolean isBoundingBoxEnabled() { return boundingBoxEnabled; }

    public void setBoundingBoxEnabled(boolean boundingBoxEnabled) {
        if (this.boundingBoxEnabled != boundingBoxEnabled) {
            this.boundingBoxEnabled = boundingBoxEnabled;
            markDirty();
        }
    }

    public String getTriggerMode() { return triggerMode; }

    public void setTriggerMode(String triggerMode) {
        if (triggerMode == null) return;
        if (!triggerMode.equals(this.triggerMode)) {
            this.triggerMode = triggerMode;
            markDirty();
        }
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLongArray("PlatformId", selectedPlatformIds.stream().mapToLong(Long::longValue).toArray());
        nbt.putInt("TimeBeforeAnnounce", seconds);
        
        // Save announcement entries
        NbtList entriesList = new NbtList();
        for (AnnouncementEntry entry : announcementEntries) {
            NbtCompound entryNbt = new NbtCompound();
            entry.writeNbt(entryNbt);
            entriesList.add(entryNbt);
        }
        nbt.put("AnnouncementEntries", entriesList);
        
        // Legacy support for old selectedJson format
        if (!announcementEntries.isEmpty()) {
            nbt.putString("SelectedJson", announcementEntries.get(0).getJsonName());
        }
        
        // Save sound configuration
        nbt.putFloat("SoundVolume", soundVolume);
        nbt.putInt("SoundRange", soundRange);
        nbt.putString("AttenuationType", attenuationType);
        
        // Save bounding box coordinates
        nbt.putBoolean("BoundingBoxEnabled", boundingBoxEnabled);
        nbt.putInt("StartX", startX);
        nbt.putInt("StartY", startY);
        nbt.putInt("StartZ", startZ);
        nbt.putInt("EndX", endX);
        nbt.putInt("EndY", endY);
        nbt.putInt("EndZ", endZ);
        
        // Save trigger mode
        nbt.putString("TriggerMode", triggerMode);

    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        selectedPlatformIds.clear();
        for (long id : nbt.getLongArray("PlatformId")) {
            selectedPlatformIds.add(id);
        }
        seconds = nbt.getInt("TimeBeforeAnnounce");
        
        // Read announcement entries
        announcementEntries.clear();
        if (nbt.contains("AnnouncementEntries")) {
            NbtList entriesList = nbt.getList("AnnouncementEntries", 10); // 10 = NbtCompound type
            for (int i = 0; i < entriesList.size(); i++) {
                NbtCompound entryNbt = entriesList.getCompound(i);
                AnnouncementEntry entry = new AnnouncementEntry();
                entry.readNbt(entryNbt);
                announcementEntries.add(entry);
            }
        } else if (nbt.contains("SelectedJson")) {
            // Legacy support: convert old selectedJson to new format
            String legacyJson = nbt.getString("SelectedJson");
            if (!legacyJson.isEmpty()) {
                announcementEntries.add(new AnnouncementEntry(legacyJson, 0));
            }
        }
        
        // Safety check: ensure we always have at least one default entry for very old blocks
        if (announcementEntries.isEmpty() && !selectedPlatformIds.isEmpty()) {
            announcementEntries.add(new AnnouncementEntry("station_bell", 0));
        }
        
        // Load sound configuration with defaults
        soundVolume = nbt.contains("SoundVolume") ? nbt.getFloat("SoundVolume") : 2.0F;
        soundRange = nbt.contains("SoundRange") ? nbt.getInt("SoundRange") : 64;
        attenuationType = nbt.contains("AttenuationType") ? nbt.getString("AttenuationType") : "LINEAR";
        
        // Load bounding box coordinates with defaults
        boundingBoxEnabled = nbt.contains("BoundingBoxEnabled") ? nbt.getBoolean("BoundingBoxEnabled") : false;
        startX = nbt.contains("StartX") ? nbt.getInt("StartX") : -100;
        startY = nbt.contains("StartY") ? nbt.getInt("StartY") : -64;
        startZ = nbt.contains("StartZ") ? nbt.getInt("StartZ") : -100;
        endX = nbt.contains("EndX") ? nbt.getInt("EndX") : 100;
        endY = nbt.contains("EndY") ? nbt.getInt("EndY") : 320;
        endZ = nbt.contains("EndZ") ? nbt.getInt("EndZ") : 100;
        
        // Load trigger mode
        triggerMode = nbt.contains("TriggerMode") ? nbt.getString("TriggerMode") : "EXACT";
        // Reset runtime-only state
        lastTriggeredArrivalMillis = -1L;
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity serverPlayerEntity, PacketByteBuf packetByteBuf) {
        packetByteBuf.writeBlockPos(this.pos);
    }

    @Override
    public void markDirty() {
        long currentTime = System.currentTimeMillis();
        // Always mark the chunk as dirty so changes are persisted to disk
        super.markDirty();
        // Rate-limit only the world updates and network sync to avoid spam
        if (world != null && !world.isClient) {
            if (currentTime - lastMarkDirtyTime >= MARK_DIRTY_INTERVAL) {
                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), Block.NOTIFY_ALL);
                if (world.getServer() != null) {
                    for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
                        AnnounceSendToClient.sendToClient(player, pos, seconds, selectedPlatformIds, announcementEntries,
                            soundVolume, soundRange, attenuationType, boundingBoxEnabled,
                            startX, startY, startZ, endX, endY, endZ, triggerMode);
                    }
                }
                lastMarkDirtyTime = currentTime;
            }
        }
    }

    public List<AnnouncementEntry> getAnnouncementEntries() {
        return new ArrayList<>(announcementEntries);
    }

    public void setAnnouncementEntries(List<AnnouncementEntry> entries) {
        if (!this.announcementEntries.equals(entries)) {
            this.announcementEntries = new ArrayList<>(entries);
            markDirty();
        }
    }
    
    // Legacy support method for backward compatibility
    public String getSelectedJson() {
        if (announcementEntries.isEmpty()) {
            return "";
        }
        return announcementEntries.get(0).getJsonName();
    }

    // Legacy support method for backward compatibility
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

    public World getWorld() {
        return world;
    }

    private String getDestination(List<Long> selectedPlatforms) {
        if (selectedPlatforms.isEmpty()) {

            return "destination_not_found";
        }
        RailwayData railwayData = AnnounceTile.getRailwayData(world);
        if (railwayData == null) return "railwaydata_unknown";
        final ScheduleEntry next = getNextScheduleEntry(railwayData, selectedPlatforms);
        if (next == null) return "schedules_unknown";
        final Route route = railwayData.dataCache.routeIdMap.get(next.routeId);
        if (route == null) return "route_unknown";

            // Prefer custom destination relative to current station index
            String customDest = route.getDestination(Math.min(next.currentStationIndex, route.platformIds.size() - 1));
            if (customDest != null && !customDest.isEmpty()) {
                int lastPipeIndex = customDest.lastIndexOf('|');
                return (lastPipeIndex != -1 && lastPipeIndex < customDest.length() - 1
                        ? customDest.substring(lastPipeIndex + 1)
                        : customDest).toLowerCase().trim();
            }

            // Fallback: use the last platform's station name
            String finalDestination = null;
            long lastPlatformId = route.getLastPlatformId();
            Platform lastPlatform = railwayData.dataCache.platformIdMap.get(lastPlatformId);
            if (lastPlatform != null) {
                finalDestination = railwayData.dataCache.platformIdToStation.get(lastPlatformId).name;
            }
            if (finalDestination != null) {
                int lastPipeIndex = finalDestination.lastIndexOf('|');
                String result;
                if (lastPipeIndex != -1 && lastPipeIndex < finalDestination.length() - 1) {
                    result = finalDestination.substring(lastPipeIndex + 1).toLowerCase().trim();
                } else {
                    result = finalDestination.toLowerCase().trim();
                }
                return result;
            } else {
                return "destination_unknown";
            }
        
    }

    private String getRouteType(List<Long> selectedPlatforms) {
        if (selectedPlatforms.isEmpty()) {

            return "route_type_not_found";
        }
        RailwayData railwayData = RailwayData.getInstance(world);
        if (railwayData == null) {
            return "railwaydata_unknown";
        }
        final ScheduleEntry next = getNextScheduleEntry(railwayData, selectedPlatforms);
        if (next == null) return "schedules_unknown";
        final Route route = railwayData.dataCache.routeIdMap.get(next.routeId);
        if (route != null) {
                // Prefer explicit routeType first
                if (route.routeType != null) {
                    switch (route.routeType) {
                        case LIGHT_RAIL:
                            // Use light rail route number if available; else generic label
                            if (route.lightRailRouteNumber != null && !route.lightRailRouteNumber.isEmpty()) {
                                int lastPipeIndex = route.lightRailRouteNumber.lastIndexOf('|');
                                String code = (lastPipeIndex != -1 && lastPipeIndex < route.lightRailRouteNumber.length() - 1)
                                        ? route.lightRailRouteNumber.substring(lastPipeIndex + 1)
                                        : route.lightRailRouteNumber;
                                return code.toLowerCase().trim();
                            } else {
                                return "light_rail";
                            }
                        case HIGH_SPEED:
                            return "high_speed";
                        case NORMAL:
                        default:
                            // For normal routes, return blank type
                            return "";
                    }
                }

                // Fallback: try to use lightRailRouteNumber if set
                if (route.lightRailRouteNumber != null && !route.lightRailRouteNumber.isEmpty()) {
                    int lastPipeIndex = route.lightRailRouteNumber.lastIndexOf('|');
                    String result;
                    if (lastPipeIndex != -1 && lastPipeIndex < route.lightRailRouteNumber.length() - 1) {
                        result = route.lightRailRouteNumber.substring(lastPipeIndex + 1).toLowerCase().trim();
                    } else {
                        result = route.lightRailRouteNumber.toLowerCase().trim();
                    }
                    return result;
                }
        }
        return "route_type_unknown";
    }

    private String[] getNextArrivalHhMm(World world, List<Long> selectedPlatforms) {
        String[] result = new String[]{"00", "00"};
        if (world == null || selectedPlatforms == null || selectedPlatforms.isEmpty()) {
            return result;
        }
        RailwayData railwayData = RailwayData.getInstance(world);
        if (railwayData == null) {
            return result;
        }
        final ScheduleEntry next = getNextScheduleEntry(railwayData, selectedPlatforms);
        if (next == null) return result;
        java.time.ZonedDateTime zdt = java.time.Instant.ofEpochMilli(next.arrivalMillis).atZone(java.time.ZoneId.systemDefault());
        String hh = String.format("%02d", zdt.getHour());
        String mm = String.format("%02d", zdt.getMinute());
        return new String[]{hh, mm};
    }
}