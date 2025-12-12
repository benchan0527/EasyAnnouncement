package com.botamochi.easyannouncement.integration;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MTR Mod Integration Service for EasyAnnouncement
 * Provides access to real-time train information, station data, schedules, and delays
 */
public class MTRIntegrationService {
    
    private static MTRIntegrationService instance;
    private boolean mtrModLoaded = false;
    private Class<?> railwayDataClass;
    private Class<?> stationClass;
    private Class<?> routeClass;
    private Class<?> trainClientClass;
    private Class<?> scheduleEntryClass;
    private Class<?> trainDelayClass;
    private Class<?> clientDataClass;
    
    // Cached reflection methods
    private Method getRailwayDataMethod;
    private Method getStationAtPositionMethod;
    private Method getStationNameMethod;
    private Method getStationZoneMethod;
    private Field clientDataStationsField;
    private Field clientDataPlatformsField;
    private Field clientDataRoutesField;
    private Field clientDataTrainsField;
    private Field clientDataSchedulesField;
    
    private MTRIntegrationService() {
        initializeMTRIntegration();
    }
    
    public static MTRIntegrationService getInstance() {
        if (instance == null) {
            instance = new MTRIntegrationService();
        }
        return instance;
    }
    
    private void initializeMTRIntegration() {
        try {
            // Check if MTR mod is loaded
            railwayDataClass = Class.forName("mtr.data.RailwayData");
            stationClass = Class.forName("mtr.data.Station");
            routeClass = Class.forName("mtr.data.Route");
            trainClientClass = Class.forName("mtr.data.TrainClient");
            scheduleEntryClass = Class.forName("mtr.data.ScheduleEntry");
            trainDelayClass = Class.forName("mtr.data.TrainDelay");
            clientDataClass = Class.forName("mtr.client.ClientData");
            
            // Initialize reflection methods and fields
            initializeReflectionMembers();
            
            mtrModLoaded = true;
            // Removed normal log: MTR integration loaded
            
        } catch (ClassNotFoundException e) {
            mtrModLoaded = false;
            // Removed normal log: MTR mod not found, integration disabled
        } catch (Exception e) {
            mtrModLoaded = false;
            System.err.println("MTR Integration: Error initializing MTR integration: " + e.getMessage());
        }
    }
    
    private void initializeReflectionMembers() throws Exception {
        // Get ClientData static fields
        clientDataStationsField = clientDataClass.getField("STATIONS");
        clientDataPlatformsField = clientDataClass.getField("PLATFORMS");
        clientDataRoutesField = clientDataClass.getField("ROUTES");
        clientDataTrainsField = clientDataClass.getField("TRAINS");
        clientDataSchedulesField = clientDataClass.getField("SCHEDULES_FOR_PLATFORM");
    }
    
    public boolean isMTRModLoaded() {
        return mtrModLoaded;
    }
    
    /**
     * Get current game time information
     */
    public MTRTimeInfo getCurrentTimeInfo(World world) {
        if (!mtrModLoaded || world == null) return null;
        
        try {
            long gameTime = world.getTime();
            long dayTime = world.getTimeOfDay();
            
            // Convert to MTR time format (24-hour cycle)
            int hour = (int) ((dayTime / 1000 + 6) % 24);
            int minute = (int) ((dayTime % 1000) * 60 / 1000);
            
            return new MTRTimeInfo(gameTime, dayTime, hour, minute);
            
        } catch (Exception e) {
            System.err.println("MTR Integration: Error getting time info: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Get station information at specific position
     */
    public MTRStationInfo getStationInfo(BlockPos pos) {
        if (!mtrModLoaded) return null;
        
        try {
            Set<?> stations = (Set<?>) clientDataStationsField.get(null);
            
            for (Object station : stations) {
                if (isPositionInStation(station, pos)) {
                    String name = getStationName(station);
                    int zone = getStationZone(station);
                    Map<String, List<String>> exits = getStationExits(station);
                    
                    return new MTRStationInfo(name, zone, exits, pos);
                }
            }
            
        } catch (Exception e) {
            System.err.println("MTR Integration: Error getting station info: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Get nearby train information for a player
     */
    public MTRTrainInfo getNearbyTrainInfo(PlayerEntity player) {
        if (!mtrModLoaded || player == null) return null;
        
        try {
            Set<?> trains = (Set<?>) clientDataTrainsField.get(null);
            BlockPos playerPos = player.getBlockPos();
            
            // Find the closest train within reasonable distance
            Object closestTrain = null;
            double closestDistance = Double.MAX_VALUE;
            
            for (Object train : trains) {
                double distance = getDistanceToTrain(train, playerPos);
                if (distance < closestDistance && distance < 100) { // Within 100 blocks
                    closestDistance = distance;
                    closestTrain = train;
                }
            }
            
            if (closestTrain != null) {
                return createTrainInfo(closestTrain);
            }
            
        } catch (Exception e) {
            System.err.println("MTR Integration: Error getting nearby train info: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Get platform schedule information
     */
    public List<MTRScheduleInfo> getPlatformSchedule(BlockPos pos, int maxEntries) {
        if (!mtrModLoaded) return Collections.emptyList();
        
        try {
            Map<?, ?> schedulesForPlatform = (Map<?, ?>) clientDataSchedulesField.get(null);
            
            // Find platform at position
            Long platformId = findPlatformIdAtPosition(pos);
            if (platformId == null) return Collections.emptyList();
            
            Set<?> scheduleEntries = (Set<?>) schedulesForPlatform.get(platformId);
            if (scheduleEntries == null) return Collections.emptyList();
            
            return scheduleEntries.stream()
                .limit(maxEntries)
                .map(this::createScheduleInfo)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
        } catch (Exception e) {
            System.err.println("MTR Integration: Error getting platform schedule: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Get delay information for trains at a position
     */
    public MTRDelayInfo getDelayInfo(BlockPos pos) {
        if (!mtrModLoaded) return null;
        
        try {
            // Access train delay data through RailwayData
            // This requires server-side access, so we'll provide a basic implementation
            // that estimates delays based on schedule vs actual arrival times
            
            List<MTRScheduleInfo> schedule = getPlatformSchedule(pos, 5);
            if (schedule.isEmpty()) return null;
            
            long currentTime = System.currentTimeMillis();
            int totalDelay = 0;
            int delayedTrains = 0;
            
            for (MTRScheduleInfo entry : schedule) {
                long expectedArrival = entry.getArrivalMillis();
                if (expectedArrival < currentTime) {
                    // This train should have arrived already
                    long delay = currentTime - expectedArrival;
                    if (delay > 30000) { // More than 30 seconds late
                        totalDelay += (int) (delay / 1000);
                        delayedTrains++;
                    }
                }
            }
            
            int averageDelay = delayedTrains > 0 ? totalDelay / delayedTrains : 0;
            return new MTRDelayInfo(averageDelay, delayedTrains, totalDelay);
            
        } catch (Exception e) {
            System.err.println("MTR Integration: Error getting delay info: " + e.getMessage());
            return null;
        }
    }
    
    // Helper methods using reflection
    
    private boolean isPositionInStation(Object station, BlockPos pos) {
        try {
            // Check if position is within station bounds
            Field corner1Field = stationClass.getField("corner1");
            Field corner2Field = stationClass.getField("corner2");
            
            BlockPos corner1 = (BlockPos) corner1Field.get(station);
            BlockPos corner2 = (BlockPos) corner2Field.get(station);
            
            int minX = Math.min(corner1.getX(), corner2.getX());
            int maxX = Math.max(corner1.getX(), corner2.getX());
            int minY = Math.min(corner1.getY(), corner2.getY());
            int maxY = Math.max(corner1.getY(), corner2.getY());
            int minZ = Math.min(corner1.getZ(), corner2.getZ());
            int maxZ = Math.max(corner1.getZ(), corner2.getZ());
            
            return pos.getX() >= minX && pos.getX() <= maxX &&
                   pos.getY() >= minY && pos.getY() <= maxY &&
                   pos.getZ() >= minZ && pos.getZ() <= maxZ;
                   
        } catch (Exception e) {
            return false;
        }
    }
    
    private String getStationName(Object station) {
        try {
            Field nameField = stationClass.getField("name");
            return (String) nameField.get(station);
        } catch (Exception e) {
            return "Unknown Station";
        }
    }
    
    private int getStationZone(Object station) {
        try {
            Field zoneField = stationClass.getField("zone");
            return zoneField.getInt(station);
        } catch (Exception e) {
            return 1;
        }
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> getStationExits(Object station) {
        try {
            Field exitsField = stationClass.getField("exits");
            return (Map<String, List<String>>) exitsField.get(station);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
    
    private double getDistanceToTrain(Object train, BlockPos pos) {
        try {
            // Get train position through reflection
            Method getTrainPositionMethod = trainClientClass.getMethod("getViewOffset", float.class);
            // This is a simplified implementation - actual train position calculation is more complex
            return 50.0; // Placeholder distance
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }
    
    private MTRTrainInfo createTrainInfo(Object train) {
        try {
            Field speedField = trainClientClass.getField("speed");
            Field trainIdField = trainClientClass.getField("trainId");
            Field baseTrainTypeField = trainClientClass.getField("baseTrainType");
            
            float speed = speedField.getFloat(train) * 72; // Convert to km/h
            String trainId = (String) trainIdField.get(train);
            String trainType = (String) baseTrainTypeField.get(train);
            
            return new MTRTrainInfo(trainId, trainType, speed, "Unknown", "Unknown", false);
            
        } catch (Exception e) {
            return null;
        }
    }
    
    private Long findPlatformIdAtPosition(BlockPos pos) {
        try {
            Set<?> platforms = (Set<?>) clientDataPlatformsField.get(null);
            
            for (Object platform : platforms) {
                if (isPositionInPlatform(platform, pos)) {
                    Field idField = platform.getClass().getField("id");
                    return idField.getLong(platform);
                }
            }
            
        } catch (Exception e) {
            System.err.println("MTR Integration: Error finding platform ID: " + e.getMessage());
        }
        
        return null;
    }
    
    private boolean isPositionInPlatform(Object platform, BlockPos pos) {
        try {
            // Similar to station position checking
            Field corner1Field = platform.getClass().getField("corner1");
            Field corner2Field = platform.getClass().getField("corner2");
            
            BlockPos corner1 = (BlockPos) corner1Field.get(platform);
            BlockPos corner2 = (BlockPos) corner2Field.get(platform);
            
            int minX = Math.min(corner1.getX(), corner2.getX());
            int maxX = Math.max(corner1.getX(), corner2.getX());
            int minZ = Math.min(corner1.getZ(), corner2.getZ());
            int maxZ = Math.max(corner1.getZ(), corner2.getZ());
            
            return pos.getX() >= minX && pos.getX() <= maxX &&
                   pos.getZ() >= minZ && pos.getZ() <= maxZ;
                   
        } catch (Exception e) {
            return false;
        }
    }
    
    private MTRScheduleInfo createScheduleInfo(Object scheduleEntry) {
        try {
            Field arrivalMillisField = scheduleEntryClass.getField("arrivalMillis");
            Field trainCarsField = scheduleEntryClass.getField("trainCars");
            Field routeIdField = scheduleEntryClass.getField("routeId");
            Field currentStationIndexField = scheduleEntryClass.getField("currentStationIndex");
            
            long arrivalMillis = arrivalMillisField.getLong(scheduleEntry);
            int trainCars = trainCarsField.getInt(scheduleEntry);
            long routeId = routeIdField.getLong(scheduleEntry);
            int currentStationIndex = currentStationIndexField.getInt(scheduleEntry);
            
            // Get route name
            String routeName = getRouteNameById(routeId);
            String destination = getRouteDestination(routeId);
            
            return new MTRScheduleInfo(arrivalMillis, trainCars, routeName, destination, currentStationIndex);
            
        } catch (Exception e) {
            return null;
        }
    }
    
    private String getRouteNameById(long routeId) {
        try {
            Set<?> routes = (Set<?>) clientDataRoutesField.get(null);
            
            for (Object route : routes) {
                Field idField = route.getClass().getField("id");
                if (idField.getLong(route) == routeId) {
                    Field nameField = route.getClass().getField("name");
                    return (String) nameField.get(route);
                }
            }
            
        } catch (Exception e) {
            // Ignore
        }
        
        return "Unknown Route";
    }
    
    private String getRouteDestination(long routeId) {
        try {
            Set<?> routes = (Set<?>) clientDataRoutesField.get(null);
            
            for (Object route : routes) {
                Field idField = route.getClass().getField("id");
                if (idField.getLong(route) == routeId) {
                    // Get last platform in route as destination
                    Field platformIdsField = route.getClass().getField("platformIds");
                    List<?> platformIds = (List<?>) platformIdsField.get(route);
                    
                    if (!platformIds.isEmpty()) {
                        // This is a simplified implementation
                        return "Terminal Station";
                    }
                }
            }
            
        } catch (Exception e) {
            // Ignore
        }
        
        return "Unknown Destination";
    }
    
    // Data classes
    
    public static class MTRTimeInfo {
        private final long gameTime;
        private final long dayTime;
        private final int hour;
        private final int minute;
        
        public MTRTimeInfo(long gameTime, long dayTime, int hour, int minute) {
            this.gameTime = gameTime;
            this.dayTime = dayTime;
            this.hour = hour;
            this.minute = minute;
        }
        
        public long getGameTime() { return gameTime; }
        public long getDayTime() { return dayTime; }
        public int getHour() { return hour; }
        public int getMinute() { return minute; }
        public String getFormattedTime() { return String.format("%02d:%02d", hour, minute); }
    }
    
    public static class MTRStationInfo {
        private final String name;
        private final int zone;
        private final Map<String, List<String>> exits;
        private final BlockPos position;
        
        public MTRStationInfo(String name, int zone, Map<String, List<String>> exits, BlockPos position) {
            this.name = name;
            this.zone = zone;
            this.exits = exits;
            this.position = position;
        }
        
        public String getName() { return name; }
        public int getZone() { return zone; }
        public Map<String, List<String>> getExits() { return exits; }
        public BlockPos getPosition() { return position; }
    }
    
    public static class MTRTrainInfo {
        private final String trainId;
        private final String trainType;
        private final float speed;
        private final String currentStation;
        private final String nextStation;
        private final boolean doorsOpen;
        
        public MTRTrainInfo(String trainId, String trainType, float speed, String currentStation, String nextStation, boolean doorsOpen) {
            this.trainId = trainId;
            this.trainType = trainType;
            this.speed = speed;
            this.currentStation = currentStation;
            this.nextStation = nextStation;
            this.doorsOpen = doorsOpen;
        }
        
        public String getTrainId() { return trainId; }
        public String getTrainType() { return trainType; }
        public float getSpeed() { return speed; }
        public String getCurrentStation() { return currentStation; }
        public String getNextStation() { return nextStation; }
        public boolean areDoorsOpen() { return doorsOpen; }
    }
    
    public static class MTRScheduleInfo {
        private final long arrivalMillis;
        private final int trainCars;
        private final String routeName;
        private final String destination;
        private final int currentStationIndex;
        
        public MTRScheduleInfo(long arrivalMillis, int trainCars, String routeName, String destination, int currentStationIndex) {
            this.arrivalMillis = arrivalMillis;
            this.trainCars = trainCars;
            this.routeName = routeName;
            this.destination = destination;
            this.currentStationIndex = currentStationIndex;
        }
        
        public long getArrivalMillis() { return arrivalMillis; }
        public int getTrainCars() { return trainCars; }
        public String getRouteName() { return routeName; }
        public String getDestination() { return destination; }
        public int getCurrentStationIndex() { return currentStationIndex; }
        
        public long getTimeUntilArrival() {
            return Math.max(0, arrivalMillis - System.currentTimeMillis());
        }
        
        public String getFormattedTimeUntilArrival() {
            long seconds = getTimeUntilArrival() / 1000;
            if (seconds < 60) {
                return seconds + "秒";
            } else {
                return (seconds / 60) + "分鐘";
            }
        }
    }
    
    public static class MTRDelayInfo {
        private final int averageDelaySeconds;
        private final int delayedTrains;
        private final int totalDelaySeconds;
        
        public MTRDelayInfo(int averageDelaySeconds, int delayedTrains, int totalDelaySeconds) {
            this.averageDelaySeconds = averageDelaySeconds;
            this.delayedTrains = delayedTrains;
            this.totalDelaySeconds = totalDelaySeconds;
        }
        
        public int getAverageDelaySeconds() { return averageDelaySeconds; }
        public int getDelayedTrains() { return delayedTrains; }
        public int getTotalDelaySeconds() { return totalDelaySeconds; }
        public boolean hasDelays() { return averageDelaySeconds > 0; }
        
        public String getFormattedDelay() {
            if (averageDelaySeconds < 60) {
                return averageDelaySeconds + "秒";
            } else {
                return (averageDelaySeconds / 60) + "分鐘";
            }
        }
    }
} 