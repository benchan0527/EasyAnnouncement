package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.Easyannouncement;
import com.botamochi.easyannouncement.data.AnnouncementEntry;
import com.botamochi.easyannouncement.network.AnnounceSendToClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AnnounceReceiveFromServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAnnouncement");

    // Data class for queued announcements
    public static class QueuedAnnouncement {
        public final List<AnnouncementEntry> entries;
        public final AnnouncementContext context;
        public final boolean isRepeat;

        public QueuedAnnouncement(List<AnnouncementEntry> entries, AnnouncementContext context, boolean isRepeat) {
            this.entries = entries;
            this.context = context;
            this.isRepeat = isRepeat;
        }
    }

    private static final long MIN_ANNOUNCE_INTERVAL = 500;
    private static final Map<BlockPos, Long> lastAnnounceTime = new ConcurrentHashMap<>();
    private static final Map<BlockPos, List<Thread>> activeThreads = new ConcurrentHashMap<>();
    private static final Map<BlockPos, List<SoundInstance>> activeSounds = new ConcurrentHashMap<>();

    // Prevent multiple announcements from playing at the same time for the same block
    private static final Set<BlockPos> playingLock = Collections.synchronizedSet(new HashSet<>());

    // Queue for pending announcements (when current one is playing)
    private static final Map<BlockPos, List<QueuedAnnouncement>> announcementQueue = new ConcurrentHashMap<>();

    // Track how many triggers are currently playing
    private static final Map<BlockPos, Integer> triggerCount = new ConcurrentHashMap<>();

    // Client-side MTR monitoring
    private static final Map<BlockPos, ClientTriggerState> clientTriggerStates = new ConcurrentHashMap<>();
    private static volatile boolean mtrMonitoringActive = false;
    private static Thread mtrMonitorThread = null;

    // String deserialization - MUST match server's writeString exactly
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

    // String serialization - MUST match server's writeString exactly
    private static void writeString(PacketByteBuf buf, String str) {
        if (str == null) str = "";
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }

    // Cooldown period to prevent same arrival from triggering multiple times (30 seconds)
    private static final long ARRIVAL_COOLDOWN_MS = 30000;

    // ========================================================================
    // CLIENT TRIGGER STATE - For MTR 4.0 client-side monitoring
    // ========================================================================
    private static class ClientTriggerState {
        BlockPos pos;
        int thresholdSeconds;
        List<Long> platformIds;
        List<AnnouncementEntry> announcementEntries;
        List<AnnouncementEntry> repeatEntries;
        int repeatIntervalSeconds = 60;
        int soundRange;
        float soundVolume;
        boolean boundingBoxEnabled;
        int startX, startY, startZ, endX, endY, endZ;
        boolean excludePlayersAbove;
        Set<String> triggeredArrivals = Collections.synchronizedSet(new HashSet<>());
        
        // Track last repeat trigger time per arrival
        Map<String, Long> lastRepeatTriggerTime = new ConcurrentHashMap<>();

        // Per-arrival cooldown to prevent same train triggering multiple times
        // Key: platformId:arrivalTime, Value: last trigger time
        Map<String, Long> arrivalTriggerCooldown = new ConcurrentHashMap<>();

        // Track if current playing announcement is a repeat (repeat should not be interrupted)
        volatile boolean currentlyPlayingRepeat = false;

        // Sound config
        SoundInstance.AttenuationType attenuationType = SoundInstance.AttenuationType.LINEAR;

        ClientTriggerState() {}
    }

    // ========================================================================
    // MTR Arrival Data (using reflection to avoid hard dependency)
    // ========================================================================
    private static class MTRArrivalData {
        long arrivalTime;
        long routeId;
        String destination;
        String routeName;
        String routeNumber;
        int routeColor;
        String platformName;
    }

    private static MTRArrivalData getMTRArrivalData(long platformId) {
        try {
            Class<?> arrivalsCacheClientClass = Class.forName("org.mtr.mod.data.ArrivalsCacheClient");
            java.lang.reflect.Field instanceField = arrivalsCacheClientClass.getField("INSTANCE");
            Object arrivalsCacheClient = instanceField.get(null);

            Class<?> longCollectionClass = Class.forName("it.unimi.dsi.fastutil.longs.LongCollection");
            java.lang.reflect.Method requestArrivalsMethod = arrivalsCacheClientClass.getMethod("requestArrivals", longCollectionClass);

            Class<?> longArrayListClass = Class.forName("it.unimi.dsi.fastutil.longs.LongArrayList");
            Object platformList = longArrayListClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method addMethod = longArrayListClass.getMethod("add", long.class);
            addMethod.invoke(platformList, platformId);

            Object arrivals = requestArrivalsMethod.invoke(arrivalsCacheClient, platformList);
            if (arrivals == null) return null;

            java.lang.reflect.Method getMillisOffsetMethod = arrivalsCacheClientClass.getMethod("getMillisOffset");
            long millisOffset = (Long) getMillisOffsetMethod.invoke(arrivalsCacheClient);
            long currentTime = System.currentTimeMillis();

            java.lang.reflect.Method iteratorMethod = arrivals.getClass().getMethod("iterator");
            java.util.Iterator<?> arrivalIter = (java.util.Iterator<?>) iteratorMethod.invoke(arrivals);

            while (arrivalIter.hasNext()) {
                Object arrival = arrivalIter.next();
                java.lang.reflect.Method getArrivalMethod = arrival.getClass().getMethod("getArrival");
                long arrivalTime = (Long) getArrivalMethod.invoke(arrival) - millisOffset;

                if (arrivalTime > currentTime) {
                    MTRArrivalData data = new MTRArrivalData();
                    data.arrivalTime = arrivalTime;
                    try {
                        java.lang.reflect.Method getDestMethod = arrival.getClass().getMethod("getDestination");
                        data.destination = (String) getDestMethod.invoke(arrival);
                        java.lang.reflect.Method getRouteNameMethod = arrival.getClass().getMethod("getRouteName");
                        data.routeName = (String) getRouteNameMethod.invoke(arrival);
                        java.lang.reflect.Method getRouteIdMethod = arrival.getClass().getMethod("getRouteId");
                        data.routeId = (Long) getRouteIdMethod.invoke(arrival);
                        java.lang.reflect.Method getPlatformNameMethod = arrival.getClass().getMethod("getPlatformName");
                        data.platformName = (String) getPlatformNameMethod.invoke(arrival);
                    } catch (Exception e) { /* ignore */ }
                    return data;
                }
            }
        } catch (Exception e) {
            // MTR not available
        }
        return null;
    }

    // ========================================================================
    // CLIENT MTR MONITORING - Using MTR 4.0 client API
    // ========================================================================

    /**
     * Get real MTR arrival data using MTR 4.0 client-side API.
     * Based on MTR 4.0's RenderPIDS implementation.
     * API: ArrivalsCacheClient.INSTANCE.requestArrivals(LongCollection)
     * This is the SYNCHRONOUS method that returns cached data directly!
     */
    private static List<MTRArrivalInfo> getMTRArrivals(List<Long> platformIds) {
        List<MTRArrivalInfo> arrivals = new ArrayList<>();
        try {

            // Step 1: Get ArrivalsCacheClient.INSTANCE
            Class<?> arrivalsCacheClientClass = Class.forName("org.mtr.mod.data.ArrivalsCacheClient");
            java.lang.reflect.Field instanceField = arrivalsCacheClientClass.getField("INSTANCE");
            Object arrivalsCacheClient = instanceField.get(null);

            if (arrivalsCacheClient == null) {
                return arrivals;
            }

            // Step 2: Create LongCollection (use LongAVLTreeSet) for platform IDs
            // Try using LongAVLTreeSet which is what MTR uses internally
            Class<?> longAVLTreeSetClass = Class.forName("org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet");
            Object platformSet = longAVLTreeSetClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method addMethod = longAVLTreeSetClass.getMethod("add", long.class);
            for (Long pid : platformIds) {
                addMethod.invoke(platformSet, pid);
            }

            // Step 3: Find the correct requestArrivals method by enumerating all methods
            // This avoids method signature issues with different LongCollection implementations
            
            java.lang.reflect.Method foundMethod = null;
            for (java.lang.reflect.Method method : arrivalsCacheClientClass.getMethods()) {
                if ("requestArrivals".equals(method.getName())) {
                    if (method.getParameterCount() == 1) {
                        // Check if the parameter is some kind of Long collection
                        Class<?> paramType = method.getParameterTypes()[0];
                        String paramName = paramType.getName();
                        if (paramName.contains("LongCollection") || paramName.contains("LongSet") || paramName.contains("Collection")) {
                            foundMethod = method;
                            break;
                        }
                    }
                }
            }
            
            if (foundMethod == null) {
                return arrivals;
            }
            
            // Step 4: Call the found method
            Object arrivalResponseList = foundMethod.invoke(arrivalsCacheClient, platformSet);
            
            if (arrivalResponseList == null) {
                return arrivals;
            }
            

            // Step 5: Get millis offset for time conversion
            java.lang.reflect.Method getMillisOffsetMethod = arrivalsCacheClientClass.getMethod("getMillisOffset");
            long millisOffset = (Long) getMillisOffsetMethod.invoke(arrivalsCacheClient);
            long currentTime = System.currentTimeMillis();

            // Step 6: Process results - iterate over the ObjectArrayList
            // Need to use setAccessible(true) to access internal class methods
            java.lang.reflect.Method iteratorMethod = arrivalResponseList.getClass().getMethod("iterator");
            iteratorMethod.setAccessible(true);
            Object iterator = iteratorMethod.invoke(arrivalResponseList);
            
            java.lang.reflect.Method hasNextMethod = iterator.getClass().getMethod("hasNext");
            hasNextMethod.setAccessible(true);
            java.lang.reflect.Method nextMethod = iterator.getClass().getMethod("next");
            nextMethod.setAccessible(true);
            
            int processedCount = 0;
            while ((Boolean) hasNextMethod.invoke(iterator)) {
                Object arrivalResponse = nextMethod.invoke(iterator);
                try {
                    // ArrivalResponse.getArrival() returns the arrival time in ms
                    java.lang.reflect.Method getArrivalMethod = arrivalResponse.getClass().getMethod("getArrival");
                    getArrivalMethod.setAccessible(true);
                    long arrivalTimeMs = (Long) getArrivalMethod.invoke(arrivalResponse) - millisOffset;

                    MTRArrivalInfo info = new MTRArrivalInfo();
                    info.arrivalTimeMs = arrivalTimeMs;
                    info.platformId = getLongMethod(arrivalResponse, "getPlatformId");
                    info.routeId = getLongMethod(arrivalResponse, "getRouteId");
                    info.destination = getStringMethod(arrivalResponse, "getDestination");
                    info.routeName = getStringMethod(arrivalResponse, "getRouteName");
                    info.routeNumber = getStringMethod(arrivalResponse, "getRouteNumber");
                    info.routeColor = getIntMethod(arrivalResponse, "getRouteColor");
                    info.platformName = getStringMethod(arrivalResponse, "getPlatformName");

                    if (arrivalTimeMs > currentTime) {
                        arrivals.add(info);
                    }
                    processedCount++;
                } catch (Exception e) {
                }
            }


        } catch (Exception e) {
            e.printStackTrace();
        }
        return arrivals;
    }

    // Helper methods for reflection
    private static long getLongMethod(Object obj, String methodName) {
        try {
            java.lang.reflect.Method method = obj.getClass().getMethod(methodName);
            Object result = method.invoke(obj);
            if (result instanceof Number) {
                return ((Number) result).longValue();
            }
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    private static String getStringMethod(Object obj, String methodName) {
        try {
            java.lang.reflect.Method method = obj.getClass().getMethod(methodName);
            Object result = method.invoke(obj);
            if (result instanceof String) {
                return (String) result;
            }
        } catch (Exception e) { /* ignore */ }
        return "";
    }

    private static int getIntMethod(Object obj, String methodName) {
        try {
            java.lang.reflect.Method method = obj.getClass().getMethod(methodName);
            Object result = method.invoke(obj);
            if (result instanceof Number) {
                return ((Number) result).intValue();
            }
        } catch (Exception e) { /* ignore */ }
        return 0x808080;
    }

    private static class MTRArrivalInfo {
        long arrivalTimeMs;
        long platformId;
        long routeId;
        String destination = "";
        String routeName = "";
        String routeNumber = "";  // Line number from MTR (e.g., "1", "A")
        int routeColor = 0x808080; // default gray
        String platformName = "";
    }

    /**
     * Start the client-side MTR monitoring thread
     */
    private static void startMTRMonitoring() {
        if (mtrMonitoringActive) return;
        mtrMonitoringActive = true;

        mtrMonitorThread = new Thread(() -> {
            while (mtrMonitoringActive && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(100); // Check every 100ms (improved from 1000ms for better timing precision)
                    checkAndTriggerAnnouncements();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                }
            }
        }, "EA-MTR-Monitor");
        mtrMonitorThread.setDaemon(true);
        mtrMonitorThread.start();
    }

    /**
     * Check all tracked blocks for announcement triggers
     */
    private static void checkAndTriggerAnnouncements() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        long currentTime = System.currentTimeMillis();

        for (Map.Entry<BlockPos, ClientTriggerState> entry : clientTriggerStates.entrySet()) {
            BlockPos pos = entry.getKey();
            ClientTriggerState state = entry.getValue();

            if (state.announcementEntries.isEmpty()) {
                continue;
            }

            // Check if player is in range
            if (!isPlayerInRange(client, pos, state)) {
                continue;
            }

            // Get MTR arrivals for all selected platforms
            List<MTRArrivalInfo> arrivals = getMTRArrivals(state.platformIds);

            for (MTRArrivalInfo arrival : arrivals) {
                long timeUntilArrival = arrival.arrivalTimeMs - currentTime;
                long thresholdMs = state.thresholdSeconds * 1000L;

                // Build arrival key for deduplication
                String arrivalKey = arrival.platformId + ":" + arrival.arrivalTimeMs;

                // Calculate repeat intervals
                int repeatIntervalMs = state.repeatIntervalSeconds * 1000;
                long lastTrigger = state.lastRepeatTriggerTime.getOrDefault(arrivalKey, 0L);

                // Determine if we should trigger (initial trigger or repeat)
                boolean shouldTrigger = false;

                if (state.thresholdSeconds > 0) {
                    // Positive threshold: play when within threshold range (±1 tick tolerance)
                    boolean alreadyTriggered = state.triggeredArrivals.contains(arrivalKey);
                    if (!alreadyTriggered && Math.abs(timeUntilArrival - thresholdMs) <= 50) {
                        shouldTrigger = true;
                    } else if (repeatIntervalMs > 0 && state.repeatEntries != null && !state.repeatEntries.isEmpty()) {
                        // Repeat trigger - check if enough time has passed
                        if (currentTime - lastTrigger >= repeatIntervalMs) {
                            // Check if train is still approaching (not passed yet)
                            // Also skip if a repeat announcement is already playing
                            if (timeUntilArrival > -10000 && !state.currentlyPlayingRepeat) {
                                shouldTrigger = true;
                            }
                        }
                    }
                } else if (state.thresholdSeconds == 0) {
                    // Zero threshold means "play only when train arrives"
                    // Only trigger ONCE exactly when train arrives (within 0.5 second window)
                    
                    // Check cooldown to prevent same arrival triggering multiple times
                    Long lastTriggered = state.arrivalTriggerCooldown.get(arrivalKey);
                    if (lastTriggered != null && (currentTime - lastTriggered) < ARRIVAL_COOLDOWN_MS) {
                        // Still in cooldown, skip
                        continue;
                    }
                    
                    // Only trigger when train has just arrived (arrived or about to arrive within 0.5s)
                    if (!state.triggeredArrivals.contains(arrivalKey)) {
                        if (timeUntilArrival <= 500 && timeUntilArrival > -1000) {
                            shouldTrigger = true;
                        }
                    }
                } else {
                    // Negative seconds means "play exactly when train arrives" (legacy behavior)
                    if (!state.triggeredArrivals.contains(arrivalKey)) {
                        shouldTrigger = timeUntilArrival <= 0 && timeUntilArrival > -3000;
                    }
                }

                if (shouldTrigger) {
                    // Check if a repeat announcement is currently playing
                    // If so, only skip if this is also a REPEAT trigger (repeat should not interrupt repeat)
                    // Trigger announcements are allowed to interrupt repeat announcements
                    boolean isRepeat = state.triggeredArrivals.contains(arrivalKey);
                    if (state.currentlyPlayingRepeat && isRepeat) {
                        // This is a repeat trigger and a repeat is already playing - skip
                        continue;
                    }


                    // Update cooldown to prevent re-triggering the same arrival
                    state.arrivalTriggerCooldown.put(arrivalKey, currentTime);
                    
                    // Track this trigger
                    state.triggeredArrivals.add(arrivalKey);
                    state.lastRepeatTriggerTime.put(arrivalKey, currentTime);

                    // Send trigger request to server to update server-side cache with real MTR data
                    // Also play announcement directly on client (client monitoring handles immediate playback)
                    sendClientTriggerRequest(pos, arrival, state, arrivals);

                    // Play announcement directly on client
                    // If repeat is playing and this is also repeat, it will be blocked above
                    triggerAnnouncement(client, pos, arrival, state, isRepeat);
                } else {
                    // Continue to next arrival if this one is in cooldown
                }
            }

            // Clean up old triggered arrivals (keep last 100)
            if (state.triggeredArrivals.size() > 100) {
                List<String> toRemove = new ArrayList<>(state.triggeredArrivals);
                state.triggeredArrivals.clear();
                state.triggeredArrivals.addAll(toRemove.subList(toRemove.size() - 50, toRemove.size()));
            }
            
            // Clean up old cooldown entries (older than 2 minutes)
            long cutoffTime = currentTime - 120000;
            state.arrivalTriggerCooldown.entrySet().removeIf(cooldownEntry -> cooldownEntry.getValue() < cutoffTime);
        }
    }

    private static boolean isPlayerInRange(MinecraftClient client, BlockPos pos, ClientTriggerState state) {
        if (client.player == null) return false;

        double distSq = client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > (double) state.soundRange * state.soundRange) {
            return false;
        }

        if (state.boundingBoxEnabled) {
            double px = client.player.getX();
            double py = client.player.getY();
            double pz = client.player.getZ();
            Box box = new Box(
                pos.getX() + state.startX, pos.getY() + state.startY, pos.getZ() + state.startZ,
                pos.getX() + state.endX, pos.getY() + state.endY, pos.getZ() + state.endZ
            );
            if (!box.contains(px, py, pz)) {
                return false;
            }
        }

        if (state.excludePlayersAbove && client.player.getY() > pos.getY()) {
            return false;
        }

        return true;
    }

    private static void sendClientTriggerRequest(BlockPos pos, MTRArrivalInfo arrival, ClientTriggerState state, List<MTRArrivalInfo> allArrivals) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(pos);
            buf.writeLong(arrival.arrivalTimeMs);
            buf.writeLong(arrival.platformId);
            buf.writeLong(arrival.routeId);
            buf.writeInt(-1); // currentStationIndex placeholder
            writeString(buf, arrival.destination);
            writeString(buf, arrival.routeName != null ? arrival.routeName : "");  // FIX: use arrival.routeName, not json name!
            String hh = String.format("%02d", (int) ((arrival.arrivalTimeMs / 3600000) % 24));
            String mm = String.format("%02d", (int) ((arrival.arrivalTimeMs / 60000) % 60));
            writeString(buf, hh);
            writeString(buf, mm);

            // Send all arrivals for server-side trigger cache
            int count = allArrivals != null ? Math.min(allArrivals.size(), 20) : 0;
            buf.writeInt(count);
            for (int i = 0; i < count; i++) {
                MTRArrivalInfo a = allArrivals.get(i);
                buf.writeLong(a.arrivalTimeMs);
                buf.writeLong(a.platformId);
                buf.writeLong(a.routeId);
                buf.writeInt(-1);
                writeString(buf, a.destination);  // destination (not json name!)
                writeString(buf, a.routeName != null ? a.routeName : "");
                writeString(buf, a.platformName != null ? a.platformName : "");
            }

            ClientPlayNetworking.send(AnnounceSendToClient.CLIENT_TRIGGER_REQUEST_ID, buf);
        } catch (Exception e) {
        }
    }

    private static void triggerAnnouncement(MinecraftClient client, BlockPos pos, MTRArrivalInfo arrival, ClientTriggerState state, boolean isRepeat) {
        // Don't set currentlyPlayingRepeat here - it's set in playAnnouncements when playback actually starts

        if (isRepeat) {
            if (state.repeatEntries.isEmpty()) return;
        } else {
            if (state.announcementEntries.isEmpty()) return;
        }

        // If an announcement is already playing, stop it first (for trigger announcements)
        if (playingLock.contains(pos) && !isRepeat) {
            stopActiveAnnouncements(client, pos);
        }

        long now = System.currentTimeMillis();
        if (lastAnnounceTime.containsKey(pos) && now - lastAnnounceTime.get(pos) < MIN_ANNOUNCE_INTERVAL) {
            return;
        }
        lastAnnounceTime.put(pos, now);

        // Build announcement context
        String hh = String.format("%02d", (int) ((arrival.arrivalTimeMs / 3600000) % 24));
        String mm = String.format("%02d", (int) ((arrival.arrivalTimeMs / 60000) % 60));
        String jsonName = (isRepeat ? state.repeatEntries : state.announcementEntries).isEmpty() 
            ? "" : (isRepeat ? state.repeatEntries : state.announcementEntries).get(0).getJsonName();

        AnnouncementContext context = new AnnouncementContext(
            state.platformIds,
            arrival.destination,
            jsonName,
            hh, mm,
            arrival.platformId,
            arrival.routeId,
            -1,
            arrival.platformName,
            arrival.routeName,
            arrival.routeNumber
        );

        playAnnouncements(client, pos, isRepeat ? state.repeatEntries : state.announcementEntries, context, isRepeat);
    }

    // ========================================================================
    // REGISTER PACKET HANDLERS
    // ========================================================================
    public static void register() {
        // Config update packet: Server -> Client
        ClientPlayNetworking.registerGlobalReceiver(AnnounceSendToClient.ID, (client, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int seconds = buf.readInt();
            long[] platformIds = buf.readLongArray();
            List<Long> selectedPlatforms = new ArrayList<>();
            for (long pid : platformIds) {
                selectedPlatforms.add(pid);
            }

            // Read entryCount and entries (MUST match server write order)
            List<AnnouncementEntry> entries = new ArrayList<>();
            int entryCount = buf.readInt();
            for (int i = 0; i < entryCount; i++) {
                String jsonName = readString(buf);
                int delaySeconds = buf.readInt();
                entries.add(new AnnouncementEntry(jsonName, delaySeconds));
            }

            // Read repeat entries (MUST match server write order)
            List<AnnouncementEntry> repeatEntries = new ArrayList<>();
            int repeatCount = buf.readInt();
            for (int i = 0; i < repeatCount; i++) {
                String jsonName = readString(buf);
                int delaySeconds = buf.readInt();
                repeatEntries.add(new AnnouncementEntry(jsonName, delaySeconds));
            }

            // Read sound config (MUST match server write order)
            float volume = buf.readFloat();
            int range = buf.readInt();
            String attenuationType = readString(buf);

            // Read bounding box (MUST match server write order)
            boolean boundingBoxEnabled = buf.readBoolean();
            int startX = buf.readInt(), startY = buf.readInt(), startZ = buf.readInt();
            int endX = buf.readInt(), endY = buf.readInt(), endZ = buf.readInt();

            // Read trigger mode (MUST match server write order)
            String triggerMode = readString(buf);
            boolean excludePlayersAbove = buf.readBoolean();
            int repeatIntervalSeconds = buf.readInt();
            boolean needsLegacyMigration = buf.readBoolean();

            client.execute(() -> {
                // Log debug info

                // Update tile data if it exists
                if (client.world != null && client.world.getBlockEntity(pos) instanceof com.botamochi.easyannouncement.tile.AnnounceTile tile) {
                    tile.receiveConfigUpdate(selectedPlatforms, entries, repeatEntries,
                        volume, range, attenuationType, boundingBoxEnabled,
                        startX, startY, startZ, endX, endY, endZ,
                        triggerMode, excludePlayersAbove, repeatIntervalSeconds, needsLegacyMigration);
                } else {
                }

                // Update client-side trigger state for MTR monitoring
                if (!selectedPlatforms.isEmpty() && !entries.isEmpty()) {
                    ClientTriggerState state = new ClientTriggerState();
                    state.pos = pos;
                    state.thresholdSeconds = seconds;
                    state.platformIds = new ArrayList<>(selectedPlatforms);
                    state.announcementEntries = new ArrayList<>(entries);
                    state.repeatEntries = new ArrayList<>(repeatEntries);
                    state.repeatIntervalSeconds = repeatIntervalSeconds;
                    state.soundRange = range;
                    state.soundVolume = volume;
                    state.boundingBoxEnabled = boundingBoxEnabled;
                    state.startX = startX;
                    state.startY = startY;
                    state.startZ = startZ;
                    state.endX = endX;
                    state.endY = endY;
                    state.endZ = endZ;
                    state.excludePlayersAbove = excludePlayersAbove;
                    try {
                        state.attenuationType = SoundInstance.AttenuationType.valueOf(attenuationType);
                    } catch (IllegalArgumentException e) {
                        state.attenuationType = SoundInstance.AttenuationType.LINEAR;
                    }

                    clientTriggerStates.put(pos, state);

                    // Start MTR monitoring if not already running
                    startMTRMonitoring();
                }
            });
        });

        // Announcement start packet: Server -> Client
        ClientPlayNetworking.registerGlobalReceiver(AnnounceSendToClient.ANNOUNCE_START_ID, (client, handler, buf, responseSender) -> {
            try {
                BlockPos pos = buf.readBlockPos();
                long[] platformIdArray = buf.readLongArray();
                List<Long> selectedPlatforms = new ArrayList<>();
                for (long pid : platformIdArray) {
                    selectedPlatforms.add(pid);
                }

                int formatVersion = buf.readByte() & 0xFF;
                boolean isRepeat = false;
                if (formatVersion >= 1) {
                    isRepeat = buf.readBoolean();
                }

                List<AnnouncementEntry> entries = new ArrayList<>();
                int entryCount = buf.readInt();
                for (int i = 0; i < entryCount; i++) {
                    String jsonName = readString(buf);
                    int delaySeconds = buf.readInt();
                    entries.add(new AnnouncementEntry(jsonName, delaySeconds));
                }

                String destination = readString(buf);
                String routeType = readString(buf);
                String hh = readString(buf);
                String mm = readString(buf);
                long chosenPlatformId = buf.readLong();
                long chosenRouteId = buf.readLong();
                int chosenCurrentStationIndex = buf.readInt();
                
                // Format version 2: read platformName and routeName
                String platformNameFromServer = "";
                String routeNameFromServer = "";
                if (formatVersion >= 2) {
                    platformNameFromServer = readString(buf);
                    routeNameFromServer = readString(buf);
                }

                final String finalRouteName = routeNameFromServer.isEmpty() ? routeType : routeNameFromServer;
                final String finalDestination = destination;
                final String finalHh = hh;
                final String finalMm = mm;
                final String finalPlatformName = platformNameFromServer;

                AnnouncementContext context = new AnnouncementContext(
                    selectedPlatforms, destination, routeType, hh, mm,
                    chosenPlatformId, chosenRouteId, chosenCurrentStationIndex,
                    platformNameFromServer, routeNameFromServer, ""
                );

                final List<AnnouncementEntry> finalEntries = entries;
                final long finalPlatformId = chosenPlatformId;
                client.execute(() -> {
                    long now = System.currentTimeMillis();
                    if (!lastAnnounceTime.containsKey(pos) || now - lastAnnounceTime.get(pos) > MIN_ANNOUNCE_INTERVAL) {
                        lastAnnounceTime.put(pos, now);
                        // Log what will play
                        for (int i = 0; i < finalEntries.size(); i++) {
                            AnnouncementEntry entry = finalEntries.get(i);
                        }
                        playAnnouncements(client, pos, finalEntries, context);
                    }
                });
            } catch (Exception e) {
            }
        });

        // Stop packet: Server -> Client - immediately stop all active sounds
        // Also notify server so isTriggerPlaying/isRepeatPlaying is reset
        ClientPlayNetworking.registerGlobalReceiver(AnnounceSendToClient.ANNOUNCE_STOP_ID, (client, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            client.execute(() -> {
                stopActiveAnnouncements(client, pos);
            });
        });

        // Request MTR Data packet: Server -> Client
        // Server requests Client to fetch MTR data and respond
        ClientPlayNetworking.registerGlobalReceiver(AnnounceSendToClient.REQUEST_MTR_DATA_ID, (client, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int platformCount = buf.readInt();
            List<Long> platformIds = new ArrayList<>();
            for (int i = 0; i < platformCount; i++) {
                platformIds.add(buf.readLong());
            }


            client.execute(() -> {
                // Fetch MTR arrivals for the requested platforms
                for (Long platformId : platformIds) {
                    MTRArrivalData mtrData = getMTRArrivalData(platformId);
                    if (mtrData != null && mtrData.arrivalTime > 0) {
                        String hh = String.format("%02d", (int) ((mtrData.arrivalTime / 3600000) % 24));
                        String mm = String.format("%02d", (int) ((mtrData.arrivalTime / 60000) % 60));


                        // Send response to server directly using ClientPlayNetworking
                        PacketByteBuf responseBuf = PacketByteBufs.create();
                        responseBuf.writeBlockPos(pos);
                        responseBuf.writeLong(mtrData.arrivalTime);
                        responseBuf.writeLong(platformId);
                        responseBuf.writeLong(mtrData.routeId);
                        responseBuf.writeInt(-1); // currentStationIndex
                        writeString(responseBuf, mtrData.destination != null ? mtrData.destination : "");
                        writeString(responseBuf, mtrData.routeName != null ? mtrData.routeName : "");
                        writeString(responseBuf, hh);
                        writeString(responseBuf, mm);
                        ClientPlayNetworking.send(AnnounceSendToClient.MTR_DATA_RESPONSE_ID, responseBuf);
                        break; // Only send first arrival for now
                    }
                }
            });
        });

        // Auto-detect request: Server -> Client
        // Server requests Client to auto-detect nearby MTR platforms
        ClientPlayNetworking.registerGlobalReceiver(AnnounceSendToClient.AUTO_DETECT_REQUEST_ID, (client, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();

            client.execute(() -> {
                List<Long> detectedPlatformIds = detectNearbyPlatforms(client, pos);


                if (!detectedPlatformIds.isEmpty()) {
                    // Found platforms - send combined response with MTR data
                    // This is like MTR Schedule Sensor: client-side detection + immediate MTR data
                    PacketByteBuf responseBuf = PacketByteBufs.create();
                    responseBuf.writeBlockPos(pos);
                    responseBuf.writeInt(detectedPlatformIds.size());
                    for (Long platformId : detectedPlatformIds) {
                        responseBuf.writeLong(platformId);
                    }

                    // Immediately get MTR arrival data for the detected platforms
                    List<MTRArrivalInfo> arrivals = getMTRArrivals(detectedPlatformIds);

                    // Write first arrival data (or -1 if none)
                    if (!arrivals.isEmpty()) {
                        MTRArrivalInfo firstArrival = arrivals.get(0);
                        responseBuf.writeLong(firstArrival.arrivalTimeMs);
                        responseBuf.writeLong(firstArrival.platformId);
                        responseBuf.writeLong(firstArrival.routeId);
                        responseBuf.writeInt(-1); // currentStationIndex
                        writeString(responseBuf, firstArrival.destination != null ? firstArrival.destination : "");
                        writeString(responseBuf, firstArrival.routeName != null ? firstArrival.routeName : "");
                        String hh = String.format("%02d", (int) ((firstArrival.arrivalTimeMs / 3600000) % 24));
                        String mm = String.format("%02d", (int) ((firstArrival.arrivalTimeMs / 60000) % 60));
                        writeString(responseBuf, hh);
                        writeString(responseBuf, mm);
                        // Write all arrivals count for cache
                        responseBuf.writeInt(arrivals.size());
                        for (MTRArrivalInfo arrival : arrivals) {
                            responseBuf.writeLong(arrival.arrivalTimeMs);
                            responseBuf.writeLong(arrival.platformId);
                            responseBuf.writeLong(arrival.routeId);
                            responseBuf.writeInt(-1);
                            writeString(responseBuf, arrival.destination != null ? arrival.destination : "");
                            writeString(responseBuf, arrival.routeName != null ? arrival.routeName : "");
                            writeString(responseBuf, arrival.platformName != null ? arrival.platformName : "");
                        }
                    } else {
                        // No arrivals - write placeholder
                        responseBuf.writeLong(0); // arrivalTimeMillis
                        responseBuf.writeLong(0); // platformId
                        responseBuf.writeLong(0); // routeId
                        responseBuf.writeInt(-1);
                        writeString(responseBuf, "");
                        writeString(responseBuf, "");
                        writeString(responseBuf, "00");
                        writeString(responseBuf, "00");
                        responseBuf.writeInt(0); // arrivals count
                    }

                    ClientPlayNetworking.send(AnnounceSendToClient.AUTO_DETECT_RESPONSE_ID, responseBuf);
                } else {
                    // No platforms found - send empty response
                    PacketByteBuf responseBuf = PacketByteBufs.create();
                    responseBuf.writeBlockPos(pos);
                    responseBuf.writeInt(0);
                    ClientPlayNetworking.send(AnnounceSendToClient.AUTO_DETECT_RESPONSE_ID, responseBuf);
                }
            });
        });
    }

    // ========================================================================
    // AUTO DETECT PLATFORM DISCOVERY
    // ========================================================================
    private static List<Long> detectNearbyPlatforms(MinecraftClient client, BlockPos pos) {
        List<Long> platformIds = new ArrayList<>();
        try {
            Class<?> initClientClass = Class.forName("org.mtr.mod.InitClient");
            Class<?> initClass = Class.forName("org.mtr.mod.Init");
            Class<?> mappingBlockPosClass = Class.forName("org.mtr.mapping.holder.BlockPos");

            java.lang.reflect.Method newBlockPosMethod = initClass.getMethod("newBlockPos", double.class, double.class, double.class);

            double[][] positions = {
                {pos.getX(), pos.getY(), pos.getZ()},
                {pos.getX(), pos.getY() - 1, pos.getZ()},
                {pos.getX(), pos.getY() - 2, pos.getZ()},
                {pos.getX(), pos.getY() - 3, pos.getZ()},
                {pos.getX(), pos.getY() - 4, pos.getZ()},
            };

            for (double[] p : positions) {
                Object mtrBlockPos = newBlockPosMethod.invoke(null, p[0], p[1], p[2]);

                java.lang.reflect.Method findCloseMethod = initClientClass.getMethod("findClosePlatform",
                    mappingBlockPosClass, int.class, java.util.function.Consumer.class);

                final Object[] foundPlatform = new Object[1];
                java.util.function.Consumer<Object> consumer = platform -> foundPlatform[0] = platform;
                findCloseMethod.invoke(null, mtrBlockPos, 10, consumer);

                if (foundPlatform[0] != null) {
                    long platformId = getPlatformIdFromPlatform(foundPlatform[0]);
                    if (platformId > 0 && !platformIds.contains(platformId)) {
                        platformIds.add(platformId);
                        break; // Only take the first one found
                    }
                }
            }
        } catch (Exception e) {
        }
        return platformIds;
    }

    private static long getPlatformIdFromPlatform(Object platform) {
        try {
            java.lang.reflect.Method getPlatformIdMethod = platform.getClass().getMethod("getPlatformId");
            return (Long) getPlatformIdMethod.invoke(platform);
        } catch (Exception e) {
            try {
                java.lang.reflect.Field platformIdField = platform.getClass().getField("platformId");
                return (Long) platformIdField.get(platform);
            } catch (Exception ignored) {}
        }
        return 0;
    }

    // ========================================================================
    // ANNOUNCEMENT PLAYBACK
    // ========================================================================
    private static void playAnnouncements(MinecraftClient client, BlockPos pos, List<AnnouncementEntry> entries, AnnouncementContext context) {
        playAnnouncements(client, pos, entries, context, false);
    }

    private static void playAnnouncements(MinecraftClient client, BlockPos pos, List<AnnouncementEntry> entries, AnnouncementContext context, boolean isRepeat) {
        if (client == null || entries == null) {
            return;
        }
        if (entries.isEmpty()) {
            return;
        }


        // Check current state
        ClientTriggerState state = clientTriggerStates.get(pos);
        boolean repeatCurrentlyPlaying = (state != null && state.currentlyPlayingRepeat);
        int currentTriggerCount = triggerCount.getOrDefault(pos, 0);

        // Queue logic:
        // - Trigger always queues if something is playing (trigger takes priority)
        // - Repeat queues if triggers are playing/waiting (repeat waits for all triggers)
        // - Repeat plays immediately only if nothing else is playing
        boolean currentlyPlaying = playingLock.contains(pos);
        boolean shouldQueue = false;

        if (currentlyPlaying) {
            if (isRepeat) {
                // Repeat queues if triggers are playing or queue has pending triggers
                List<QueuedAnnouncement> queue = announcementQueue.get(pos);
                boolean queueHasTriggers = queue != null && queue.stream().anyMatch(q -> !q.isRepeat);
                if (currentTriggerCount > 0 || queueHasTriggers) {
                    shouldQueue = true;
                } else {
                    // No triggers, stop any playing repeat and play this repeat
                    stopActiveAnnouncements(client, pos);
                    // Continue to play this repeat (stopActiveAnnouncements doesn't release playingLock)
                    // The old thread's finally block will release it when interrupted
                }
            } else {
                // Trigger queues behind current (triggers have priority)
                shouldQueue = true;
            }
        }

        if (shouldQueue) {
            announcementQueue.computeIfAbsent(pos, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new QueuedAnnouncement(new ArrayList<>(entries), context, isRepeat));
            return;
        }

        // Check if stopActiveAnnouncements was called (for repeat replacement)
        // If so, wait briefly for the old lock to be released
        if (playingLock.contains(pos)) {
            // Wait for the interrupted thread to release the lock
            int waitCount = 0;
            while (playingLock.contains(pos) && waitCount < 100) {
                try {
                    Thread.sleep(50);
                    waitCount++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (playingLock.contains(pos)) {
                return;
            }
        }

        float volume = 2.0F;
        int range = 64;
        SoundInstance.AttenuationType attenuationType = SoundInstance.AttenuationType.LINEAR;

        if (client.world != null && client.world.getBlockEntity(pos) instanceof com.botamochi.easyannouncement.tile.AnnounceTile tile) {
            volume = tile.getSoundVolume();
            range = tile.getSoundRange();
            try {
                attenuationType = SoundInstance.AttenuationType.valueOf(tile.getAttenuationType());
            } catch (IllegalArgumentException e) {
                attenuationType = SoundInstance.AttenuationType.LINEAR;
            }
        }

        // If player is too far, skip
        if (client.player != null) {
            double distSq = client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            double dist = Math.sqrt(distSq);
            if (distSq > (double) range * range) {
                return;
            }
        }


        final float finalVolume = volume;
        final SoundInstance.AttenuationType finalAttenuationType = attenuationType;
        final boolean finalIsRepeat = isRepeat;
        final ClientTriggerState finalState = state;

        Thread schedulerThread = new Thread(() -> {
            try {
                // Acquire the playing lock - if already playing, abort (shouldn't happen due to queue logic)
                if (!playingLock.add(pos)) {
                    return;
                }

                // Track trigger count
                if (!isRepeat) {
                    triggerCount.merge(pos, 1, Integer::sum);
                }

                // Set the repeat flag AFTER lock acquired - playback is guaranteed to start
                if (finalState != null) {
                    finalState.currentlyPlayingRepeat = finalIsRepeat;
                }

                long startTime = System.currentTimeMillis();
                List<Thread> entryThreads = new ArrayList<>();

                for (int i = 0; i < entries.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) break;

                    AnnouncementEntry entry = entries.get(i);
                    if (entry.isEmpty()) continue;

                    long targetTime = startTime + (entry.getDelaySeconds() * 1000L);
                    long waitTime = targetTime - System.currentTimeMillis();

                    if (waitTime > 0) {
                        long remainingWait = waitTime;
                        while (remainingWait > 0 && !Thread.currentThread().isInterrupted()) {
                            Thread.sleep(Math.min(remainingWait, 100));
                            remainingWait = targetTime - System.currentTimeMillis();
                        }
                    }

                    if (Thread.currentThread().isInterrupted()) break;

                    List<SoundData> soundDataList = loadAnnouncementSequence(entry.getJsonName(), context);
                    if (soundDataList == null || soundDataList.isEmpty()) continue;

                    final List<SoundData> finalSoundDataList = new ArrayList<>(soundDataList);
                    Thread entryThread = new Thread(() -> {
                        playSounds(client, pos, finalSoundDataList, finalVolume, finalAttenuationType);
                    }, "EA-Entry-" + i);
                    entryThread.setDaemon(true);
                    activeThreads.computeIfAbsent(pos, k -> Collections.synchronizedList(new ArrayList<>())).add(entryThread);
                    entryThreads.add(entryThread);
                    entryThread.start();
                }

                for (Thread t : entryThreads) {
                    t.join();
                }

                final BlockPos finalPos = pos;
                client.execute(() -> {
                    try {
                        PacketByteBuf packetBuf = PacketByteBufs.create();
                        packetBuf.writeBlockPos(finalPos);
                        ClientPlayNetworking.send(AnnounceSendToClient.ANNOUNCEMENT_FINISHED_ID, packetBuf);
                    } catch (Exception e) {
                        // ignore
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // ignore
            } finally {
                // Decrease trigger count
                if (!isRepeat) {
                    triggerCount.computeIfPresent(pos, (k, v) -> v > 0 ? v - 1 : 0);
                    // Clean up if no more triggers
                    if (triggerCount.getOrDefault(pos, 0) == 0) {
                        triggerCount.remove(pos);
                    }
                }

                // Clear the repeat playing flag
                ClientTriggerState st = clientTriggerStates.get(pos);
                if (st != null) {
                    st.currentlyPlayingRepeat = false;
                }

                // Always release the playing lock
                playingLock.remove(pos);

                // Process queue - find next announcement to play
                // Priority: Triggers in queue > Repeats in queue
                List<QueuedAnnouncement> queue = announcementQueue.get(pos);
                if (queue != null && !queue.isEmpty()) {
                    // Find the first trigger in queue (priority), otherwise take first item (repeat)
                    QueuedAnnouncement nextToPlay = null;
                    int triggerIndex = -1;
                    for (int i = 0; i < queue.size(); i++) {
                        if (!queue.get(i).isRepeat) {
                            triggerIndex = i;
                            break;
                        }
                    }

                    if (triggerIndex >= 0) {
                        nextToPlay = queue.remove(triggerIndex);
                    } else {
                        // No triggers, play repeat if allowed
                        nextToPlay = queue.remove(0);
                        if (triggerCount.getOrDefault(pos, 0) == 0) {
                        } else {
                            // Put it back and check again (shouldn't happen with no triggers)
                            queue.add(0, nextToPlay);
                            return;
                        }
                    }

                    final QueuedAnnouncement finalNext = nextToPlay;
                    client.execute(() -> {
                        playAnnouncements(client, pos, finalNext.entries, finalNext.context, finalNext.isRepeat);
                    });
                }
            }
        }, "EA-Scheduler");
        schedulerThread.setDaemon(true);
        activeThreads.computeIfAbsent(pos, k -> Collections.synchronizedList(new ArrayList<>())).add(schedulerThread);
        schedulerThread.start();
    }

    private static void stopActiveAnnouncements(MinecraftClient client, BlockPos pos) {
        List<Thread> threads = activeThreads.remove(pos);
        if (threads != null) {
            for (Thread t : threads) {
                if (t.isAlive()) t.interrupt();
            }
        }
        List<SoundInstance> sounds = activeSounds.remove(pos);
        if (sounds != null && client.getSoundManager() != null) {
            for (SoundInstance si : sounds) {
                try {
                    client.submit(() -> client.getSoundManager().stop(si));
                } catch (Exception ignored) {}
            }
        }
        // Clear queue and trigger count on stop
        announcementQueue.remove(pos);
        triggerCount.remove(pos);
        // Note: Don't remove from playingLock here - let the interrupted thread's finally block handle it
        // This prevents race conditions where stopActiveAnnouncements and the old thread both try to release the lock
    }

    private static void playSounds(MinecraftClient client, BlockPos pos, List<SoundData> soundDataList, float volume, SoundInstance.AttenuationType attenuationType) {
        if (soundDataList == null || soundDataList.isEmpty()) return;
        SoundManager soundManager = client.getSoundManager();
        Random random = Random.create();

        for (SoundData soundData : soundDataList) {
            if (Thread.currentThread().isInterrupted()) break;

            Identifier soundId;
            if (soundData.soundPath.contains(":")) {
                soundId = Identifier.tryParse(soundData.soundPath);
            } else {
                soundId = new Identifier(Easyannouncement.MOD_ID, soundData.soundPath);
            }

            if (soundId == null) continue;

            SoundEvent soundEvent = Registries.SOUND_EVENT.getOrEmpty(soundId).orElse(null);
            if (soundEvent == null) {
                try {
                    soundEvent = SoundEvent.of(soundId);
                } catch (Exception e) {
                    continue;
                }
            }

            PositionedSoundInstance instance = new PositionedSoundInstance(
                soundEvent.getId(), net.minecraft.sound.SoundCategory.MASTER,
                volume, 1.0F, random, false, 0,
                attenuationType, pos.getX(), pos.getY(), pos.getZ(), false
            );

            activeSounds.computeIfAbsent(pos, k -> Collections.synchronizedList(new ArrayList<>())).add(instance);

            try {
                client.submit(() -> soundManager.play(instance)).get();
            } catch (Exception e) {
                continue;
            }

            long start = System.currentTimeMillis();
            while (soundManager.isPlaying(instance)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    final SoundInstance toStop = instance;
                    client.submit(() -> soundManager.stop(toStop));
                    Thread.currentThread().interrupt();
                    return;
                }
                if (System.currentTimeMillis() - start > 30000) break;
            }
        }
    }

    // ========================================================================
    // SOUND LOADING
    // ========================================================================
    private static List<SoundData> loadAnnouncementSequence(String jsonName, AnnouncementContext context) {
        List<SoundData> soundDataList = new ArrayList<>();
        String trimmed = jsonName == null ? "" : jsonName.trim();
        if (trimmed.isEmpty()) return soundDataList;

        Identifier jsonId;
        String jsonNamespace;
        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":", 2);
            jsonNamespace = parts[0];
            jsonId = new Identifier(parts[0], "sounds/" + parts[1] + ".json");
        } else {
            jsonNamespace = Easyannouncement.MOD_ID;
            jsonId = new Identifier(Easyannouncement.MOD_ID, "sounds/" + trimmed + ".json");
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return soundDataList;

        try {
            Optional<Resource> resourceOpt = client.getResourceManager().getResource(jsonId);
            if (resourceOpt.isEmpty()) {
                // Also do variable substitution for direct sound events
                List<String> formatted = getFormattedAnnouncement(trimmed, context);
                for (String path : formatted) {
                    Identifier soundId = path.contains(":") ? Identifier.tryParse(path) : new Identifier(Easyannouncement.MOD_ID, path);
                    if (soundId != null) {
                        soundDataList.add(new SoundData(soundId.toString(), 0.0));
                    }
                }
                return soundDataList;
            }

            try (InputStream is = resourceOpt.get().getInputStream()) {
                Gson gson = new Gson();
                JsonObject jsonObj = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), JsonObject.class);
                if (!jsonObj.has("sounds")) return soundDataList;

                JsonArray soundsArr = jsonObj.getAsJsonArray("sounds");
                if (soundsArr == null || soundsArr.size() == 0) return soundDataList;

                for (int i = 0; i < soundsArr.size(); i++) {
                    try {
                        JsonObject soundObj = soundsArr.get(i).getAsJsonObject();
                        if (!soundObj.has("soundPath")) continue;

                        String rawPath = soundObj.get("soundPath").getAsString();
                        if (!rawPath.contains(":")) {
                            rawPath = jsonNamespace + ":" + rawPath;
                        }

                        List<String> formatted = getFormattedAnnouncement(rawPath, context);
                        for (String path : formatted) {
                            soundDataList.add(new SoundData(path, 0.0));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return soundDataList;
    }

    private static List<String> getFormattedAnnouncement(String soundPath, AnnouncementContext context) {
        List<String> results = new ArrayList<>();
        
        // Get platformName and routeName - from context first, fallback to MTR API lookup
        String platformName = context.platformName;
        String routeName = context.routeName;
        
        // If only ONE platform is selected, use that platform's name directly
        // Otherwise, use the arrival's platform name (might be different per arrival)
        if (context.platformIds.size() == 1) {
            // Single platform selected - use its name directly
            if (platformName == null || platformName.isEmpty()) {
                platformName = lookupPlatformNameDirect(context.platformIds.get(0));
            }
        } else {
            // Multiple platforms - use arrival's platform name (might differ per train)
            if (platformName == null || platformName.isEmpty()) {
                platformName = lookupPlatformName(context.chosenPlatformId);
            }
        }
        
        if (routeName == null || routeName.isEmpty()) {
            // Try lookupRouteName first (by routeId)
            routeName = lookupRouteName(context.chosenRouteId);
            // If still empty, try to get from MTR arrivals directly
            if (routeName == null || routeName.isEmpty()) {
                MTRArrivalData mtrData = getMTRArrivalData(context.chosenPlatformId);
                if (mtrData != null && mtrData.routeName != null && !mtrData.routeName.isEmpty()) {
                    routeName = mtrData.routeName;
                }
            }
        }
        
        if (platformName == null) platformName = "";
        if (routeName == null) routeName = "";

        String formatted = soundPath
            .replace("($track)", getTrackNumber(context.platformIds, context.platformIds.isEmpty() ? -1L : context.platformIds.get(0)))
            .replace("($boundfor)", extractEnglishNoSpace(context.destination))
            .replace("($routetype)", extractEnglishNoSpace(context.routeNumber))
            .replace("($route)", extractEnglishNoSpace(routeName))
            .replace("($hh)", context.hh != null ? context.hh : "00")
            .replace("($mm)", context.mm != null ? context.mm : "00")
            .replace("($currentstation)", getCurrentStationName(context))
            .replace("($nextstation)", getNextStationName(context));

        formatted = formatted.replaceAll("[ <>?,.()]", "");
        if (!formatted.isEmpty()) {
            results.add(formatted);
        }
        return results;
    }
    
    /**
     * Get platform name directly from platformIdMap (for single platform selection)
     * Does NOT query arrival data
     */
    private static String lookupPlatformNameDirect(long platformId) {
        if (platformId == -1L) return "";
        try {
            Class<?> clientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = clientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);
            
            if (clientData != null) {
                // Try platformIdMap first
                try {
                    java.lang.reflect.Field platformIdMapField = clientDataClass.getField("platformIdMap");
                    Object platformIdMap = platformIdMapField.get(clientData);
                    java.lang.reflect.Method getMethod = platformIdMap.getClass().getMethod("get", long.class);
                    Object platform = getMethod.invoke(platformIdMap, platformId);
                    if (platform != null) {
                        java.lang.reflect.Method getNameMethod = platform.getClass().getMethod("getName");
                        String name = (String) getNameMethod.invoke(platform);
                        if (name != null && !name.isEmpty()) {
                            return name;  // Return raw name, let extractEnglishNoSpace handle pipe format later
                        }
                    }
                } catch (NoSuchFieldException e) {
                    // Fallback: iterate through platforms
                    java.lang.reflect.Method getPlatformsMethod = clientDataClass.getMethod("getPlatforms");
                    Object platforms = getPlatformsMethod.invoke(clientData);
                    if (platforms != null) {
                        java.util.Iterator<?> iterator = (java.util.Iterator<?>) platforms.getClass().getMethod("iterator").invoke(platforms);
                        while ((Boolean) iterator.getClass().getMethod("hasNext").invoke(iterator)) {
                            Object platform = iterator.getClass().getMethod("next").invoke(iterator);
                            java.lang.reflect.Method getIdMethod = platform.getClass().getMethod("getId");
                            Long id = (Long) getIdMethod.invoke(platform);
                            if (id != null && id == platformId) {
                                java.lang.reflect.Method getNameMethod = platform.getClass().getMethod("getName");
                                String name = (String) getNameMethod.invoke(platform);
                                if (name != null && !name.isEmpty()) {
                                    return name;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }
        return "";
    }
    
    private static String lookupPlatformName(long platformId) {
        if (platformId == -1L) return "";
        try {
            Class<?> clientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = clientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);
            
            if (clientData != null) {
                // Try platformIdMap first
                try {
                    java.lang.reflect.Field platformIdMapField = clientDataClass.getField("platformIdMap");
                    Object platformIdMap = platformIdMapField.get(clientData);
                    java.lang.reflect.Method getMethod = platformIdMap.getClass().getMethod("get", long.class);
                    Object platform = getMethod.invoke(platformIdMap, platformId);
                    if (platform != null) {
                        java.lang.reflect.Method getNameMethod = platform.getClass().getMethod("getName");
                        String name = (String) getNameMethod.invoke(platform);
                        if (name != null && !name.isEmpty()) {
                            return cleanMtrText(name);
                        }
                    }
                } catch (NoSuchFieldException e) {
                    // Try getPlatforms() method
                    java.lang.reflect.Method getPlatformsMethod = clientDataClass.getMethod("getPlatforms");
                    Object platforms = getPlatformsMethod.invoke(clientData);
                    if (platforms != null) {
                        java.lang.reflect.Method iteratorMethod = platforms.getClass().getMethod("iterator");
                        Object iterator = iteratorMethod.invoke(platforms);
                        java.lang.reflect.Method hasNextMethod = iterator.getClass().getMethod("hasNext");
                        java.lang.reflect.Method nextMethod = iterator.getClass().getMethod("next");
                        while ((Boolean) hasNextMethod.invoke(iterator)) {
                            Object platform = nextMethod.invoke(iterator);
                            java.lang.reflect.Method getIdMethod = platform.getClass().getMethod("getId");
                            Long id = (Long) getIdMethod.invoke(platform);
                            if (id != null && id == platformId) {
                                java.lang.reflect.Method getNameMethod = platform.getClass().getMethod("getName");
                                String name = (String) getNameMethod.invoke(platform);
                                if (name != null && !name.isEmpty()) {
                                    return cleanMtrText(name);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return "";
    }
    
    private static String lookupRouteName(long routeId) {
        if (routeId == -1L) return "";
        try {
            Class<?> clientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = clientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);
            
            if (clientData != null) {
                // Try simplifiedRouteIdMap first
                try {
                    java.lang.reflect.Field routeIdMapField = clientDataClass.getField("simplifiedRouteIdMap");
                    Object routeIdMap = routeIdMapField.get(clientData);
                    java.lang.reflect.Method getMethod = routeIdMap.getClass().getMethod("get", long.class);
                    Object route = getMethod.invoke(routeIdMap, routeId);
                    if (route != null) {
                        java.lang.reflect.Method getNameMethod = route.getClass().getMethod("getName");
                        String name = (String) getNameMethod.invoke(route);
                        if (name != null && !name.isEmpty()) {
                            return cleanRouteText(name);
                        }
                    }
                } catch (NoSuchFieldException e) {
                    // Try getSimplifiedRoutes() method
                    java.lang.reflect.Method getRoutesMethod = clientDataClass.getMethod("getSimplifiedRoutes");
                    Object routes = getRoutesMethod.invoke(clientData);
                    if (routes != null) {
                        java.lang.reflect.Method iteratorMethod = routes.getClass().getMethod("iterator");
                        Object iterator = iteratorMethod.invoke(routes);
                        java.lang.reflect.Method hasNextMethod = iterator.getClass().getMethod("hasNext");
                        java.lang.reflect.Method nextMethod = iterator.getClass().getMethod("next");
                        while ((Boolean) hasNextMethod.invoke(iterator)) {
                            Object route = nextMethod.invoke(iterator);
                            java.lang.reflect.Method getIdMethod = route.getClass().getMethod("getId");
                            Long id = (Long) getIdMethod.invoke(route);
                            if (id != null && id == routeId) {
                                java.lang.reflect.Method getNameMethod = route.getClass().getMethod("getName");
                                String name = (String) getNameMethod.invoke(route);
                                if (name != null && !name.isEmpty()) {
                                    return cleanRouteText(name);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return "";
    }

    private static String getCurrentStationName(AnnouncementContext context) {
        // First, try to get from platformName which might be "迪土尼|Distoland"
        String platformName = context.platformName;
        if (platformName != null && platformName.contains("|")) {
            // Format: "迪土尼|Distoland" -> take part after |
            String afterPipe = platformName.substring(platformName.lastIndexOf('|') + 1).trim();
            if (!afterPipe.isEmpty()) {
                return cleanMtrText(afterPipe);
            }
        }
        
        try {
            if (context.chosenPlatformId != -1L) {
                Class<?> clientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
                java.lang.reflect.Method getInstanceMethod = clientDataClass.getMethod("getInstance");
                Object clientData = getInstanceMethod.invoke(null);

                if (clientData != null) {
                    java.lang.reflect.Field platformIdMapField = clientDataClass.getField("platformIdMap");
                    Object platformIdMap = platformIdMapField.get(clientData);

                    java.lang.reflect.Method getMethod = platformIdMap.getClass().getMethod("get", long.class);
                    Object platform = getMethod.invoke(platformIdMap, context.chosenPlatformId);

                    if (platform != null) {
                        try {
                            java.lang.reflect.Field areaField = platform.getClass().getField("area");
                            Object area = areaField.get(platform);
                            if (area != null) {
                                java.lang.reflect.Method getNameMethod = area.getClass().getMethod("getName");
                                String name = (String) getNameMethod.invoke(area);
                                if (name != null && !name.isEmpty()) {
                                    // Handle pipe format: "迪土尼|Distoland" -> "distoland"
                                    if (name.contains("|")) {
                                        String afterPipe = name.substring(name.lastIndexOf('|') + 1).trim();
                                        if (!afterPipe.isEmpty()) return cleanMtrText(afterPipe);
                                    }
                                    return cleanMtrText(name);
                                }
                            }
                        } catch (Exception e) {
                            java.lang.reflect.Method getNameMethod = platform.getClass().getMethod("getName");
                            String name = (String) getNameMethod.invoke(platform);
                            if (name != null && !name.isEmpty()) {
                                // Handle pipe format: "迪土尼|Distoland" -> "distoland"
                                if (name.contains("|")) {
                                    String afterPipe = name.substring(name.lastIndexOf('|') + 1).trim();
                                    if (!afterPipe.isEmpty()) return cleanMtrText(afterPipe);
                                }
                                return cleanMtrText(name);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // MTR not available
        }
        return "station_unknown";
    }

    private static String getNextStationName(AnnouncementContext context) {
        return "next_station";
    }

    /**
     * Get the actual track/platform number from MTR platform name.
     * Extracts the number from platform name like "Platform 1" or "迪土尼|Distoland"
     * Falls back to list index if lookup fails.
     * @param platformIds The list of all selected platform IDs
     * @param chosenPlatformId The specific platform ID to find
     * @return "1", "2", etc from actual MTR platform name, or list index as fallback
     */
    private static String getTrackNumber(List<Long> platformIds, long chosenPlatformId) {
        if (chosenPlatformId == -1L) return "";
        
        // Try to get actual platform name from MTR
        String platformName = lookupPlatformNameDirect(chosenPlatformId);
        if (platformName != null && !platformName.isEmpty()) {
            // Extract number from platform name
            // Handle pipe format: "迪土尼|Distoland" -> try to get number
            // First try to extract a number directly
            String number = extractNumberFromText(platformName);
            if (number != null && !number.isEmpty()) {
                return number;
            }
            
            // Handle pipe format - try to get the part after | if it looks like a number
            if (platformName.contains("|")) {
                String afterPipe = platformName.substring(platformName.lastIndexOf('|') + 1).trim();
                number = extractNumberFromText(afterPipe);
                if (number != null && !number.isEmpty()) {
                    return number;
                }
            }
            
            // If platform name doesn't contain a number, use list index as fallback
        }
        
        // Fallback: use list index
        if (platformIds != null && !platformIds.isEmpty()) {
            for (int i = 0; i < platformIds.size(); i++) {
                if (platformIds.get(i) == chosenPlatformId) {
                    return String.valueOf(i + 1);
                }
            }
        }
        
        return "";
    }
    
    /**
     * Extract first number found in text.
     * @param text The text to search
     * @return The first number found, or null if none
     */
    private static String extractNumberFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        boolean foundDigit = false;
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(c);
                foundDigit = true;
            } else if (foundDigit) {
                // Stop at first non-digit after finding digits
                break;
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String cleanMtrText(String text) {
        if (text == null || text.isEmpty()) return text;
        // Remove pipe character and other special chars, then lowercase
        return text.replaceAll("[(),.&| ]", "").toLowerCase();
    }

    /**
     * Extract English letters only and remove all spaces from MTR text.
     * Handles pipe format: "迪土尼|Distoland" -> "distoland"
     * "東中央線|East Central Line" -> "eastcentralline"
     */
    private static String extractEnglishNoSpace(String text) {
        if (text == null || text.isEmpty()) return "";
        // Handle pipe format: "南北綫|North South Line|目的地" -> "NorthSouthLine" (take English after first pipe)
        if (text.contains("|")) {
            String afterPipe = text.substring(text.indexOf('|') + 1).trim();
            // If the part after first pipe still contains pipes, only take before the next pipe
            if (afterPipe.contains("|")) {
                afterPipe = afterPipe.substring(0, afterPipe.indexOf('|')).trim();
            }
            if (!afterPipe.isEmpty()) text = afterPipe;
        }
        // Extract English letters only (A-Z, a-z) and remove all spaces, lowercase
        StringBuilder english = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                english.append(Character.toLowerCase(c));
            }
        }
        return english.toString();
    }

    private static String cleanRouteText(String text) {
        if (text == null || text.isEmpty()) return text;
        // Handle pipe format: "南北綫|North South Line|目的地" -> "NorthSouthLine" (take English after first pipe)
        if (text.contains("|")) {
            String afterPipe = text.substring(text.indexOf('|') + 1).trim();
            // If the part after first pipe still contains pipes, only take before the next pipe
            if (afterPipe.contains("|")) {
                afterPipe = afterPipe.substring(0, afterPipe.indexOf('|')).trim();
            }
            if (!afterPipe.isEmpty()) text = afterPipe;
        }
        // Remove special chars and spaces, lowercase
        String removed = text.replaceAll("[(),.&| ]", "");
        String lowered = removed.toLowerCase(java.util.Locale.ENGLISH);
        String underscored = lowered.replaceAll("[\\s-]+", "_");
        String filtered = underscored.replaceAll("[^a-z0-9_/]", "");
        return filtered.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
    }

    public static int calculateRepeatIntervalSeconds(List<AnnouncementEntry> repeatEntries) {
        if (repeatEntries == null || repeatEntries.isEmpty()) return 60;
        int total = 0;
        for (AnnouncementEntry entry : repeatEntries) {
            total += entry.getDelaySeconds();
        }
        return Math.max(total, 60);
    }

    // ========================================================================
    // INNER CLASSES
    // ========================================================================
    public static class AnnouncementContext {
        public final List<Long> platformIds;
        public final String destination;
        public final String routeType;
        public final String hh;
        public final String mm;
        public final long chosenPlatformId;
        public final long chosenRouteId;
        public final int chosenCurrentStationIndex;
        public final String platformName;  // From ArrivalResponse.getPlatformName()
        public final String routeName;     // From ArrivalResponse.getRouteName()
        public final String routeNumber;  // From ArrivalResponse.getRouteNumber() - the line number (e.g., "1", "A")

        public AnnouncementContext(List<Long> platformIds, String destination, String routeType,
                                 String hh, String mm, long chosenPlatformId, long chosenRouteId,
                                 int chosenCurrentStationIndex, String platformName, String routeName,
                                 String routeNumber) {
            this.platformIds = platformIds;
            this.destination = destination;
            this.routeType = routeType;
            this.hh = hh;
            this.mm = mm;
            this.chosenPlatformId = chosenPlatformId;
            this.chosenRouteId = chosenRouteId;
            this.chosenCurrentStationIndex = chosenCurrentStationIndex;
            this.platformName = platformName;
            this.routeName = routeName;
            this.routeNumber = routeNumber != null ? routeNumber : "";
        }
    }

    private static class SoundData {
        String soundPath;
        double duration;
        public SoundData(String soundPath, double duration) {
            this.soundPath = soundPath;
            this.duration = duration;
        }
    }
}
