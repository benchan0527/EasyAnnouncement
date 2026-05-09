package com.botamochi.easyannouncement.network;

import com.botamochi.easyannouncement.Easyannouncement;
import com.botamochi.easyannouncement.data.AnnouncementEntry;
import com.botamochi.easyannouncement.tile.AnnounceTile;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AnnounceSendToClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAnnouncement");
    public static final Identifier ID = new Identifier(Easyannouncement.MOD_ID, "announce_update");
    public static final Identifier ANNOUNCE_START_ID = new Identifier(Easyannouncement.MOD_ID, "announce_start");
    public static final Identifier ANNOUNCE_STOP_ID = new Identifier(Easyannouncement.MOD_ID, "announce_stop");
    public static final Identifier PLATFORM_SELECTION_ID = new Identifier(Easyannouncement.MOD_ID, "platform_selection");
    public static final Identifier ANNOUNCEMENT_FINISHED_ID = new Identifier(Easyannouncement.MOD_ID, "announcement_finished");
    public static final Identifier CLIENT_TRIGGER_REQUEST_ID = new Identifier(Easyannouncement.MOD_ID, "client_trigger_request");
    public static final Identifier REQUEST_MTR_DATA_ID = new Identifier(Easyannouncement.MOD_ID, "request_mtr_data");
    public static final Identifier MTR_DATA_RESPONSE_ID = new Identifier(Easyannouncement.MOD_ID, "mtr_data_response");
    public static final Identifier AUTO_DETECT_REQUEST_ID = new Identifier(Easyannouncement.MOD_ID, "auto_detect_request");
    public static final Identifier AUTO_DETECT_RESPONSE_ID = new Identifier(Easyannouncement.MOD_ID, "auto_detect_response");

    // String serialization - MUST match client exactly
    private static void writeString(PacketByteBuf buf, String str) {
        if (str == null) str = "";
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(PacketByteBuf buf) {
        try {
            int length = buf.readVarInt();
            if (length < 0 || length > 32767 || buf.readableBytes() < length) {
                return "";
            }
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ========================================================================
    // SEND TO CLIENT - Server -> Client packet
    // Write order: BlockPos, seconds, platformIds, entryCount, entries,
    //              repeatCount, repeatEntries, volume, range, attenuationType,
    //              boundingBoxEnabled, XYZ, triggerMode, excludePlayersAbove, repeatInterval
    // ========================================================================
    public static void sendToClient(ServerPlayerEntity player, BlockPos pos, int seconds, List<Long> selectedPlatforms,
                                   List<AnnouncementEntry> announcementEntries, List<AnnouncementEntry> repeatEntries,
                                   float volume, int range, String attenuationType, boolean boundingBoxEnabled,
                                   int startX, int startY, int startZ, int endX, int endY, int endZ,
                                   String triggerMode, boolean excludePlayersAbove, int repeatIntervalSeconds) {
        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeBlockPos(pos);
        buf.writeInt(seconds);
        buf.writeLongArray(selectedPlatforms.stream().mapToLong(Long::longValue).toArray());

        buf.writeInt(announcementEntries.size());
        for (AnnouncementEntry entry : announcementEntries) {
            writeString(buf, entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }

        buf.writeInt(repeatEntries.size());
        for (AnnouncementEntry entry : repeatEntries) {
            writeString(buf, entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }

        buf.writeFloat(volume);
        buf.writeInt(range);
        writeString(buf, attenuationType);

        buf.writeBoolean(boundingBoxEnabled);
        buf.writeInt(startX);
        buf.writeInt(startY);
        buf.writeInt(startZ);
        buf.writeInt(endX);
        buf.writeInt(endY);
        buf.writeInt(endZ);

        writeString(buf, triggerMode);
        buf.writeBoolean(excludePlayersAbove);
        buf.writeInt(repeatIntervalSeconds);

        ServerPlayNetworking.send(player, ID, buf);
    }

    // New overload with needsLegacyMigration flag
    public static void sendToClient(ServerPlayerEntity player, BlockPos pos, int seconds, List<Long> selectedPlatforms,
                                   List<AnnouncementEntry> announcementEntries, List<AnnouncementEntry> repeatEntries,
                                   float volume, int range, String attenuationType, boolean boundingBoxEnabled,
                                   int startX, int startY, int startZ, int endX, int endY, int endZ,
                                   String triggerMode, boolean excludePlayersAbove, int repeatIntervalSeconds,
                                   boolean needsLegacyMigration) {
        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeBlockPos(pos);
        buf.writeInt(seconds);
        buf.writeLongArray(selectedPlatforms.stream().mapToLong(Long::longValue).toArray());

        buf.writeInt(announcementEntries.size());
        for (AnnouncementEntry entry : announcementEntries) {
            writeString(buf, entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }

        buf.writeInt(repeatEntries.size());
        for (AnnouncementEntry entry : repeatEntries) {
            writeString(buf, entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }

        buf.writeFloat(volume);
        buf.writeInt(range);
        writeString(buf, attenuationType);

        buf.writeBoolean(boundingBoxEnabled);
        buf.writeInt(startX);
        buf.writeInt(startY);
        buf.writeInt(startZ);
        buf.writeInt(endX);
        buf.writeInt(endY);
        buf.writeInt(endZ);

        writeString(buf, triggerMode);
        buf.writeBoolean(excludePlayersAbove);
        buf.writeInt(repeatIntervalSeconds);
        buf.writeBoolean(needsLegacyMigration);

        ServerPlayNetworking.send(player, ID, buf);
    }

    // Convenience overloads
    public static void sendToClient(ServerPlayerEntity player, BlockPos pos, int seconds, List<Long> selectedPlatforms,
                                   List<AnnouncementEntry> announcementEntries) {
        sendToClient(player, pos, seconds, selectedPlatforms, announcementEntries, new ArrayList<>(),
            2.0F, 64, "LINEAR", false, -100, -64, -100, 100, 320, 100, "EXACT", false, 0);
    }

    public static void sendToClient(ServerPlayerEntity player, BlockPos pos, int seconds, List<Long> selectedPlatforms,
                                   List<AnnouncementEntry> announcementEntries, List<AnnouncementEntry> repeatEntries) {
        sendToClient(player, pos, seconds, selectedPlatforms, announcementEntries, repeatEntries,
            2.0F, 64, "LINEAR", false, -100, -64, -100, 100, 320, 100, "EXACT", false, 0);
    }

    public static void sendToClient(ServerPlayerEntity player, BlockPos pos, int seconds, List<Long> selectedPlatforms,
                                   List<AnnouncementEntry> announcementEntries, List<AnnouncementEntry> repeatEntries,
                                   float volume, int range, String attenuationType, boolean boundingBoxEnabled,
                                   int startX, int startY, int startZ, int endX, int endY, int endZ) {
        sendToClient(player, pos, seconds, selectedPlatforms, announcementEntries, repeatEntries,
            volume, range, attenuationType, boundingBoxEnabled, startX, startY, startZ, endX, endY, endZ, "EXACT", false, 0);
    }

    // Legacy support
    public static void sendToClient(ServerPlayerEntity player, BlockPos pos, int seconds, List<Long> selectedPlatforms, String selectedJson) {
        List<AnnouncementEntry> entries = new ArrayList<>();
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            entries.add(new AnnouncementEntry(selectedJson, 0));
        }
        sendToClient(player, pos, seconds, selectedPlatforms, entries);
    }

    // ========================================================================
    // ANNOUNCE START PACKET - Server -> Client
    // ========================================================================
    public static void sendAnnounceStartPacket(ServerPlayerEntity player, List<Long> selectedPlatforms, BlockPos pos,
                                               List<AnnouncementEntry> announcementEntries, String destination,
                                               String routeType, String hh, String mm,
                                               long chosenPlatformId, long chosenRouteId, int chosenCurrentStationIndex,
                                               boolean isRepeat, String platformName, String routeName) {
        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeBlockPos(pos);
        buf.writeLongArray(selectedPlatforms.stream().mapToLong(Long::longValue).toArray());
        buf.writeByte(2);  // format version (2 = includes platformName and routeName)
        buf.writeBoolean(isRepeat);

        buf.writeInt(announcementEntries.size());
        for (AnnouncementEntry entry : announcementEntries) {
            writeString(buf, entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }

        writeString(buf, destination);
        writeString(buf, routeType);
        writeString(buf, hh);
        writeString(buf, mm);

        buf.writeLong(chosenPlatformId);
        buf.writeLong(chosenRouteId);
        buf.writeInt(chosenCurrentStationIndex);
        
        // Format version 2: include platformName and routeName
        writeString(buf, platformName);
        writeString(buf, routeName);

        ServerPlayNetworking.send(player, ANNOUNCE_START_ID, buf);
    }

    public static void sendAnnounceStartPacket(ServerPlayerEntity player, List<Long> selectedPlatforms, BlockPos pos,
                                               List<AnnouncementEntry> announcementEntries, String destination,
                                               String routeType, String hh, String mm) {
        sendAnnounceStartPacket(player, selectedPlatforms, pos, announcementEntries, destination, routeType, hh, mm, -1L, -1L, -1, false, "", "");
    }

    // ========================================================================
    // ANNOUNCE STOP PACKET - Server -> Client
    // Immediately stops all active announcement playback on the client
    // ========================================================================
    public static void sendStopPacket(ServerPlayerEntity player, BlockPos pos) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        ServerPlayNetworking.send(player, ANNOUNCE_STOP_ID, buf);
    }

    // ========================================================================
    // REQUEST MTR DATA - Server -> Client
    // Server requests Client to fetch MTR data and respond with arrival info
    // ========================================================================
    public static void sendMTRDataRequest(ServerPlayerEntity player, BlockPos pos, List<Long> platformIds) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeInt(platformIds.size());
        for (Long platformId : platformIds) {
            buf.writeLong(platformId);
        }
        ServerPlayNetworking.send(player, REQUEST_MTR_DATA_ID, buf);
    }

    // ========================================================================
    // AUTO DETECT REQUEST - Server -> Client
    // Server requests Client to auto-detect nearby MTR platforms
    // ========================================================================
    public static void sendAutoDetectRequest(ServerPlayerEntity player, BlockPos pos) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        ServerPlayNetworking.send(player, AUTO_DETECT_REQUEST_ID, buf);
    }

    // ========================================================================
    // SERVER-SIDE RECEIVERS
    // ========================================================================
    public static void registerAnnouncementFinishedHandler() {
        ServerPlayNetworking.registerGlobalReceiver(ANNOUNCEMENT_FINISHED_ID, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            server.execute(() -> {
                BlockEntity blockEntity = player.getWorld().getBlockEntity(pos);
                if (blockEntity instanceof AnnounceTile announceTile) {
                    announceTile.onAnnouncementFinished();
                }
            });
        });
    }

    // ========================================================================
    // CLIENT TRIGGER REQUEST - Client -> Server
    // Client sends MTR arrival data to update server-side trigger cache.
    // Trigger playback is handled by client monitoring thread directly.
    // ========================================================================
    public static void registerClientTriggerRequest() {
        ServerPlayNetworking.registerGlobalReceiver(CLIENT_TRIGGER_REQUEST_ID, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            long arrivalTimeMillis = buf.readLong();
            long platformId = buf.readLong();
            long routeId = buf.readLong();
            int currentStationIndex = buf.readInt();
            String destination = readString(buf);
            String routeName = readString(buf); // was routeType in packet
            String hh = readString(buf);
            String mm = readString(buf);

            // Read the full arrivals data for cache update
            int arrivalCount = buf.readInt();
            List<AnnounceTile.ClientArrivalData> arrivals = new ArrayList<>();
            for (int i = 0; i < arrivalCount; i++) {
                long apt = buf.readLong();
                long pid = buf.readLong();
                long rid = buf.readLong();
                int idx = buf.readInt();
                String dest = readString(buf);
                String rName = readString(buf);
                String pn = readString(buf);
                arrivals.add(new AnnounceTile.ClientArrivalData(apt, pid, rid, idx, dest, rName, pn));
            }


            final long finalArrivalTime = arrivalTimeMillis;
            final long finalPlatformId = platformId;
            final long finalRouteId = routeId;
            final int finalCurrentStationIndex = currentStationIndex;
            final String finalDestination = destination;
            final String finalRouteName = routeName;
            final String finalHh = hh;
            final String finalMm = mm;
            final List<AnnounceTile.ClientArrivalData> finalArrivals = arrivals;

            server.execute(() -> {
                BlockEntity blockEntity = player.getWorld().getBlockEntity(pos);
                if (blockEntity instanceof AnnounceTile announceTile) {
                    // Update the server-side arrival cache with real MTR data from client
                    announceTile.updateClientArrivalData(finalArrivals, finalPlatformId, finalArrivalTime,
                        finalDestination, finalRouteName, finalHh, finalMm, finalRouteId, finalCurrentStationIndex);
                }
            });
        });
    }

    public static void register() {
        // Receive config update from client (when player saves GUI)
        ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            int seconds = buf.readInt();
            long[] platformIds = buf.readLongArray();
            List<Long> selectedPlatforms = new ArrayList<>();
            for (long pid : platformIds) {
                selectedPlatforms.add(pid);
            }

            List<AnnouncementEntry> announcementEntries = new ArrayList<>();
            List<AnnouncementEntry> repeatEntries = new ArrayList<>();
            float volume = 2.0F;
            int range = 64;
            String attenuationType = "LINEAR";
            boolean boundingBoxEnabled = false;
            int startX = -100, startY = -64, startZ = -100, endX = 100, endY = 320, endZ = 100;
            String triggerMode = "EXACT";
            boolean excludePlayersAbove = false;
            int repeatIntervalSeconds = 0;

            try {
                int entryCount = buf.readInt();
                if (entryCount >= 0 && entryCount <= 100) {
                    for (int i = 0; i < entryCount; i++) {
                        if (buf.readableBytes() < 1) break;
                        String jsonName = readString(buf);
                        if (buf.readableBytes() < 4) break;
                        int delaySeconds = buf.readInt();
                        announcementEntries.add(new AnnouncementEntry(jsonName, delaySeconds));
                    }
                }
            } catch (Exception e) { /* ignore */ }

            try {
                int repeatCount = buf.readInt();
                if (repeatCount >= 0 && repeatCount <= 100) {
                    for (int i = 0; i < repeatCount; i++) {
                        if (buf.readableBytes() < 1) break;
                        String jsonName = readString(buf);
                        if (buf.readableBytes() < 4) break;
                        int delaySeconds = buf.readInt();
                        repeatEntries.add(new AnnouncementEntry(jsonName, delaySeconds));
                    }
                }
            } catch (Exception e) { /* ignore */ }

            try {
                if (buf.readableBytes() >= 4) volume = buf.readFloat();
                if (buf.readableBytes() >= 4) range = buf.readInt();
                if (buf.readableBytes() >= 1) attenuationType = readString(buf);
                if (buf.readableBytes() >= 1) boundingBoxEnabled = buf.readBoolean();
                if (buf.readableBytes() >= 24) {
                    startX = buf.readInt(); startY = buf.readInt(); startZ = buf.readInt();
                    endX = buf.readInt(); endY = buf.readInt(); endZ = buf.readInt();
                }
                if (buf.readableBytes() >= 1) triggerMode = readString(buf);
                if (buf.readableBytes() >= 1) excludePlayersAbove = buf.readBoolean();
                if (buf.readableBytes() >= 4) repeatIntervalSeconds = buf.readInt();
            } catch (Exception e) { /* ignore */ }

            final float volumeFinal = volume;
            final int rangeFinal = range;
            final String attenuationFinal = attenuationType;
            final boolean bbFinal = boundingBoxEnabled;
            final int[] xyz = {startX, startY, startZ, endX, endY, endZ};
            final String triggerFinal = triggerMode;
            final boolean excludeFinal = excludePlayersAbove;
            final int repeatInt = repeatIntervalSeconds;

            server.execute(() -> {
                BlockEntity be = player.getWorld().getBlockEntity(pos);
                if (be instanceof AnnounceTile tile) {
                    tile.updateConfig(selectedPlatforms, announcementEntries, repeatEntries,
                        volumeFinal, rangeFinal, attenuationFinal, bbFinal,
                        xyz[0], xyz[1], xyz[2], xyz[3], xyz[4], xyz[5],
                        triggerFinal, excludeFinal, repeatInt);
                }
            });
        });
    }

    // ========================================================================
    // PLATFORM SELECTION - Client -> Server
    // ========================================================================
    public static void registerPlatformSelection() {
        ServerPlayNetworking.registerGlobalReceiver(PLATFORM_SELECTION_ID, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            long[] platformIds = buf.readLongArray();

            final List<Long> selectedPlatforms = new ArrayList<>();
            for (long pid : platformIds) {
                selectedPlatforms.add(pid);
            }


            server.execute(() -> {
                BlockEntity be = player.getWorld().getBlockEntity(pos);
                if (be instanceof AnnounceTile tile) {
                    tile.updateSelectedPlatforms(selectedPlatforms);
                }
            });
        });
    }

    // ========================================================================
    // MTR DATA RESPONSE HANDLER - Client -> Server
    // Server receives MTR arrival data from client and triggers announcement
    // ========================================================================
    public static void registerMTRDataResponse() {
        ServerPlayNetworking.registerGlobalReceiver(MTR_DATA_RESPONSE_ID, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            long arrivalTimeMillis = buf.readLong();
            long platformId = buf.readLong();
            long routeId = buf.readLong();
            int currentStationIndex = buf.readInt();
            String destination = readString(buf);
            String routeName = readString(buf);
            String hh = readString(buf);
            String mm = readString(buf);


            final long finalArrivalTime = arrivalTimeMillis;
            final long finalPlatformId = platformId;
            final long finalRouteId = routeId;
            final int finalCurrentStationIndex = currentStationIndex;
            final String finalDestination = destination;
            final String finalRouteName = routeName;
            final String finalHh = hh;
            final String finalMm = mm;

            server.execute(() -> {
                BlockEntity be = player.getWorld().getBlockEntity(pos);
                if (be instanceof AnnounceTile tile) {
                    // Update server cache with MTR data
                    tile.updateClientArrivalDataWithResponse(finalArrivalTime, finalPlatformId, finalRouteId,
                        finalCurrentStationIndex, finalDestination, finalRouteName, finalHh, finalMm);
                    // Trigger announcement with the received data
                    tile.triggerAnnouncementWithData(player, finalDestination, finalRouteName, finalHh, finalMm,
                        finalPlatformId, finalRouteId, finalCurrentStationIndex);
                }
            });
        });
    }

    // ========================================================================
    // AUTO DETECT RESPONSE HANDLER - Client -> Server
    // Server receives detected platform IDs + MTR arrival data from client
    // ========================================================================
    public static void registerAutoDetectResponse() {
        ServerPlayNetworking.registerGlobalReceiver(AUTO_DETECT_RESPONSE_ID, (server, player, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            int count = buf.readInt();
            List<Long> detectedPlatformIds = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                detectedPlatformIds.add(buf.readLong());
            }


            final List<Long> finalPlatformIds = detectedPlatformIds;
            server.execute(() -> {
                BlockEntity be = player.getWorld().getBlockEntity(pos);
                if (be instanceof AnnounceTile tile) {
                    if (!finalPlatformIds.isEmpty()) {
                        tile.updateSelectedPlatforms(finalPlatformIds);

                        // Read MTR arrival data if present
                        if (buf.readableBytes() >= 8) {
                            long arrivalTimeMillis = buf.readLong();
                            long platformId = buf.readLong();
                            long routeId = buf.readLong();
                            int currentStationIndex = buf.readInt();
                            String destination = readString(buf);
                            String routeName = readString(buf);
                            String hh = readString(buf);
                            String mm = readString(buf);
                            int arrivalsCount = buf.readInt();

                            List<AnnounceTile.ClientArrivalData> arrivals = new ArrayList<>();
                            for (int i = 0; i < arrivalsCount; i++) {
                                long apt = buf.readLong();
                                long pid = buf.readLong();
                                long rid = buf.readLong();
                                int idx = buf.readInt();
                                String dest = readString(buf);
                                String rName = readString(buf);
                                String pn = readString(buf);
                                arrivals.add(new AnnounceTile.ClientArrivalData(apt, pid, rid, idx, dest, rName, pn));
                            }

                            if (arrivalTimeMillis > 0) {
                                tile.updateClientArrivalData(arrivals, platformId, arrivalTimeMillis,
                                    destination, routeName, hh, mm, routeId, currentStationIndex);

                                // Directly trigger announcement after auto-detect (like MTR Schedule Sensor)
                                tile.triggerAnnouncementWithData(player, destination, routeName, hh, mm,
                                    platformId, routeId, currentStationIndex);
                            }
                        }
                    }
                    // If empty, do nothing - server will keep requesting each tick
                }
            });
        });
    }
}
