package com.botamochi.easyannouncement.network;

import com.botamochi.easyannouncement.Easyannouncement;
import com.botamochi.easyannouncement.data.AnnouncementEntry;
import com.botamochi.easyannouncement.tile.AnnounceTile;
import mtr.data.RailwayData;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class AnnounceSendToClient {
    public static final Identifier ID = new Identifier(Easyannouncement.MOD_ID, "announce_update");
    public static final Identifier ANNOUNCE_START_ID = new Identifier(Easyannouncement.MOD_ID, "announce_start");

    public static void sendToClient(ServerPlayerEntity player, BlockPos pos, int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries) {
        // Use default values for backward compatibility
        sendToClient(player, pos, seconds, selectedPlatforms, announcementEntries, 2.0F, 64, "LINEAR", false, -100, -64, -100, 100, 320, 100, "EXACT");
    }
    
    public static void sendToClient(ServerPlayerEntity player, BlockPos pos, int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries, 
                                   float volume, int range, String attenuationType, boolean boundingBoxEnabled,
                                   int startX, int startY, int startZ, int endX, int endY, int endZ, String triggerMode) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeInt(seconds);
        buf.writeLongArray(selectedPlatforms.stream().mapToLong(Long::longValue).toArray());
        
        // Write announcement entries
        buf.writeInt(announcementEntries.size());
        for (AnnouncementEntry entry : announcementEntries) {
            buf.writeString(entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }
        
        // Write sound configuration
        buf.writeFloat(volume);
        buf.writeInt(range);
        buf.writeString(attenuationType);
        
        // Write bounding box configuration
        buf.writeBoolean(boundingBoxEnabled);
        buf.writeInt(startX);
        buf.writeInt(startY);
        buf.writeInt(startZ);
        buf.writeInt(endX);
        buf.writeInt(endY);
        buf.writeInt(endZ);
        
        // Write trigger mode (new)
        buf.writeString(triggerMode == null ? "EXACT" : triggerMode);
        
        ServerPlayNetworking.send(player, ID, buf);
    }

    // Legacy support method
    public static void sendToClient(ServerPlayerEntity player, BlockPos pos, int seconds, List<Long> selectedPlatforms, String selectedJson) {
        List<AnnouncementEntry> entries = new ArrayList<>();
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            entries.add(new AnnouncementEntry(selectedJson, 0));
        }
        sendToClient(player, pos, seconds, selectedPlatforms, entries);
    }

    public static void sendAnnounceStartPacket(ServerPlayerEntity player, List<Long> selectedPlatforms, BlockPos pos, List<AnnouncementEntry> announcementEntries, String destination, String routeType, String hh, String mm) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeLongArray(selectedPlatforms.stream().mapToLong(Long::longValue).toArray());
        
        // Write announcement entries
        buf.writeInt(announcementEntries.size());

        for (AnnouncementEntry entry : announcementEntries) {
            buf.writeString(entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());

        }
        
        buf.writeString(destination);
        buf.writeString(routeType);
        buf.writeString(hh);
        buf.writeString(mm);
        int bufferSize = buf.writerIndex();

        ServerPlayNetworking.send(player, ANNOUNCE_START_ID, buf);
    }
    
    // New method including chosen identifiers
    public static void sendAnnounceStartPacket(ServerPlayerEntity player, List<Long> selectedPlatforms, BlockPos pos, List<AnnouncementEntry> announcementEntries, String destination, String routeType, String hh, String mm, long chosenPlatformId, long chosenRouteId, int chosenCurrentStationIndex) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeLongArray(selectedPlatforms.stream().mapToLong(Long::longValue).toArray());
        
        // Write announcement entries
        buf.writeInt(announcementEntries.size());
        for (AnnouncementEntry entry : announcementEntries) {
            buf.writeString(entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }
        
        // Existing placeholders
        buf.writeString(destination);
        buf.writeString(routeType);
        buf.writeString(hh);
        buf.writeString(mm);
        
        // New chosen identifiers appended for backward compatibility
        buf.writeLong(chosenPlatformId);
        buf.writeLong(chosenRouteId);
        buf.writeInt(chosenCurrentStationIndex);
        
        ServerPlayNetworking.send(player, ANNOUNCE_START_ID, buf);
    }
    
    // Legacy support method
    public static void sendAnnounceStartPacket(ServerPlayerEntity player, List<Long> selectedPlatforms, BlockPos pos, String selectedJson, String destination, String routeType, String hh, String mm) {
        List<AnnouncementEntry> entries = new ArrayList<>();
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            entries.add(new AnnouncementEntry(selectedJson, 0));
        }
        sendAnnounceStartPacket(player, selectedPlatforms, pos, entries, destination, routeType, hh, mm);
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            int seconds = buf.readInt();
            long[] platformIds = buf.readLongArray();
            List<Long> selectedPlatforms = new ArrayList<>();
            for (long platformId : platformIds) {
                selectedPlatforms.add(platformId);
            }
            
            List<AnnouncementEntry> announcementEntries = new ArrayList<>();
            float volume = 2.0F;
            int range = 64;
            String attenuationType = "LINEAR";
            boolean boundingBoxEnabled = false;
            int startX = -100, startY = -64, startZ = -100, endX = 100, endY = 320, endZ = 100;
            String triggerMode = "EXACT";
            
            // Backward-compatible parsing
            if (buf.readableBytes() >= 4) {
                int entryCount = buf.readInt();
                if (entryCount >= 0 && entryCount <= 100) {
                    for (int i = 0; i < entryCount; i++) {
                        if (buf.readableBytes() < 4) break; // not enough for string length
                        String jsonName = buf.readString();
                        if (buf.readableBytes() < 4) break; // not enough for delay
                        int delaySeconds = buf.readInt();
                        announcementEntries.add(new AnnouncementEntry(jsonName, delaySeconds));
                    }
                } else {
                    // Legacy: entryCount didn't exist; interpret the int as string length of selectedJson is unsafe; instead, treat as no entries
                    announcementEntries.clear();
                }
            } else {
                // Very old: treat as 0 entries
            }
            
            // Sound config (optional in older clients)
            if (buf.readableBytes() >= 4) {
                volume = buf.readFloat();
            }
            if (buf.readableBytes() >= 4) {
                range = buf.readInt();
            }
            if (buf.readableBytes() >= 4) {
                attenuationType = buf.readString();
            }
            
            // Bounding box enabled (optional in older clients)
            if (buf.readableBytes() >= 1) {
                boundingBoxEnabled = buf.readBoolean();
            }
            
            // XYZ (optional in older clients)
            if (buf.readableBytes() >= 24) { // 6 ints
                startX = buf.readInt();
                startY = buf.readInt();
                startZ = buf.readInt();
                endX = buf.readInt();
                endY = buf.readInt();
                endZ = buf.readInt();
            }
            
            // Trigger mode (optional in older clients)
            if (buf.readableBytes() >= 1) { // string has at least its length varint, but readableBytes check is conservative
                try {
                    triggerMode = buf.readString();
                } catch (Exception ignored) {}
            }

            final float volumeFinal = volume;
            final int rangeFinal = range;
            final String attenuationTypeFinal = attenuationType;
            final boolean boundingBoxEnabledFinal = boundingBoxEnabled;
            final int startXFinal = startX;
            final int startYFinal = startY;
            final int startZFinal = startZ;
            final int endXFinal = endX;
            final int endYFinal = endY;
            final int endZFinal = endZ;
            final String triggerModeFinal = triggerMode;

            server.execute(() -> {
                if (player.getWorld().getBlockEntity(pos) instanceof AnnounceTile announceTile) {
                    if (selectedPlatforms != null) {
                        announceTile.setSeconds(seconds);
                        announceTile.setSelectedPlatformIds(selectedPlatforms);
                        announceTile.setAnnouncementEntries(announcementEntries);
                        announceTile.setSoundVolume(volumeFinal);
                        announceTile.setSoundRange(rangeFinal);
                        announceTile.setAttenuationType(attenuationTypeFinal);
                        announceTile.setBoundingBoxEnabled(boundingBoxEnabledFinal);
                        announceTile.setStartX(startXFinal);
                        announceTile.setStartY(startYFinal);
                        announceTile.setStartZ(startZFinal);
                        announceTile.setEndX(endXFinal);
                        announceTile.setEndY(endYFinal);
                        announceTile.setEndZ(endZFinal);
                        announceTile.setTriggerMode(triggerModeFinal);
                    }
                    announceTile.markDirty();
                }
            });
        });
    }
    
    // Removed parseAttenuationType method - using string storage instead
}