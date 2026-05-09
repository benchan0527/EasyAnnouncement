package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.Easyannouncement;
import com.botamochi.easyannouncement.network.AnnounceSendToClient;
import com.botamochi.easyannouncement.tile.AnnounceTile;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class RouteSelectionScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAnnouncement");
    private final BlockPos blockPos;
    private final Set<Integer> selectedRouteColors = new HashSet<>();
    private List<Long> preselectedPlatformIds;
    private boolean selectAllMode = false; // If true, select all stations by default

    private int scrollOffset = 0;
    private int maxScroll = 0;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 5;
    private static final int VISIBLE_BUTTONS = 6;
    private static final int BUTTON_WIDTH = 220;

    // Flag to show "no nearby platforms" message
    private boolean showNoNearbyMessage = false;

    // MTR 4.0 station data with route info
    private List<MTRStationInfo> mtrStations = new ArrayList<>();

    // Helper class for MTR station information
    private static class MTRStationInfo {
        long stationId;
        long routeId;
        String stationName;
        String routeName;
        int color;

        MTRStationInfo(long stationId, long routeId, String stationName, String routeName, int color) {
            this.stationId = stationId;
            this.routeId = routeId;
            this.stationName = stationName;
            this.routeName = routeName;
            this.color = color;
        }
    }

    public RouteSelectionScreen(BlockPos blockPos, List<Long> preselectedPlatformIds) {
        super(Text.translatable("gui.easyannouncement.route_selection"));
        this.blockPos = blockPos;
        this.preselectedPlatformIds = preselectedPlatformIds != null ? new ArrayList<>(preselectedPlatformIds) : new ArrayList<>();
        // If no preselected platforms (legacy data), keep empty
        this.selectAllMode = false;
    }

    public RouteSelectionScreen(BlockPos blockPos, List<Long> preselectedPlatformIds, boolean forceSelectAll) {
        super(Text.translatable("gui.easyannouncement.route_selection"));
        this.blockPos = blockPos;
        this.preselectedPlatformIds = preselectedPlatformIds != null ? new ArrayList<>(preselectedPlatformIds) : new ArrayList<>();
        this.selectAllMode = forceSelectAll;
    }

    @Override
    protected void init() {
        super.init();
        loadMTRStations();

        // If user has selected platforms, auto-select all routes that pass through those platforms
        if (preselectedPlatformIds != null && !preselectedPlatformIds.isEmpty()) {
            // Auto-select all routes for selected platforms
            for (MTRStationInfo station : mtrStations) {
                selectedRouteColors.add((int) station.routeId);
            }
        } else if (selectAllMode) {
            // Legacy selectAllMode
            for (MTRStationInfo station : mtrStations) {
                selectedRouteColors.add((int) station.routeId);
            }
        }

        updateButtons();
    }

    /**
     * Load routes for the specific station from nearby platforms
     * Shows routes that pass through the selected platforms (if any)
     * or the station where the announcement block is placed
     */
    private void loadMTRStations() {
        mtrStations.clear();

        try {
            // If user has selected platforms, use those to filter routes
            if (preselectedPlatformIds != null && !preselectedPlatformIds.isEmpty()) {
                loadRoutesForSelectedPlatforms(preselectedPlatformIds);
            } else {
                // Fall back to finding station from announcement block position
                Object station = findStationAtPosition();

                if (station == null) {
                    showNoNearbyMessage = true;
                    mtrStations.clear();
                    return;
                }

                // Get station info
                long stationId = getStationId(station);
                String stationName = getStationName(station);


                // Now load routes that serve this station
                loadRoutesForStation(station, stationId, stationName);
            }

            if (mtrStations.isEmpty() && !showNoNearbyMessage) {
                loadFallbackStations();
            }

        } catch (Exception e) {
            LOGGER.error("[EA] Error loading MTR stations: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            loadFallbackStations();
        }
    }

    /**
     * Load routes that pass through the selected platforms
     * Shows routes that serve ANY of the selected platforms
     */
    private void loadRoutesForSelectedPlatforms(List<Long> platformIds) {
        try {
            Class<?> minecraftClientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = minecraftClientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);

            if (clientData == null) return;

            // Get platformIdMap to look up platform info
            java.lang.reflect.Field platformIdMapField = minecraftClientDataClass.getField("platformIdMap");
            Object platformIdMap = platformIdMapField.get(clientData);
            java.lang.reflect.Method getPlatformMethod = platformIdMap.getClass().getMethod("get", long.class);

            // Get simplifiedRoutes
            java.lang.reflect.Field simplifiedRoutesField = minecraftClientDataClass.getField("simplifiedRoutes");
            Object simplifiedRoutes = (Iterable<?>) simplifiedRoutesField.get(clientData);
            java.lang.reflect.Method routeIteratorMethod = simplifiedRoutes.getClass().getMethod("iterator");
            java.util.Iterator<?> routeIter = (java.util.Iterator<?>) routeIteratorMethod.invoke(simplifiedRoutes);

            // Find routes that pass through ANY of the selected platforms
            Set<Long> addedRoutes = new HashSet<>();
            int routesFound = 0;

            while (routeIter.hasNext()) {
                Object route = routeIter.next();

                java.lang.reflect.Method getRouteIdMethod = route.getClass().getMethod("getId");
                long routeId = (Long) getRouteIdMethod.invoke(route);

                // Get route name and color
                String routeName = "";
                int routeColor = 0x808080;
                try {
                    java.lang.reflect.Method getNameMethod = route.getClass().getMethod("getName");
                    routeName = (String) getNameMethod.invoke(route);
                    if (routeName == null) routeName = "";

                    java.lang.reflect.Method getColorMethod = route.getClass().getMethod("getColor");
                    routeColor = (Integer) getColorMethod.invoke(route);
                } catch (Exception e) {
                    // Skip if methods not found
                }

                // Check if this route serves ANY of the selected platforms
                java.lang.reflect.Method getPlatformsMethod = route.getClass().getMethod("getPlatforms");
                Object routePlatforms = getPlatformsMethod.invoke(route);

                if (routePlatforms != null) {
                    java.util.Iterator<?> platformIter = (java.util.Iterator<?>) routePlatforms.getClass().getMethod("iterator").invoke(routePlatforms);

                    // Count how many selected platforms this route serves
                    int platformsServed = 0;
                    Set<Long> stationsServed = new HashSet<>();
                    List<String> platformNames = new ArrayList<>();

                    while (platformIter.hasNext()) {
                        Object routePlatform = platformIter.next();
                        try {
                            java.lang.reflect.Method getPlatformIdMethod = routePlatform.getClass().getMethod("getPlatformId");
                            long platformId = (Long) getPlatformIdMethod.invoke(routePlatform);

                            // Check if this platform is in our selection
                            if (platformIds.contains(platformId)) {
                                platformsServed++;

                                // Get station ID and name for this platform
                                try {
                                    java.lang.reflect.Method getStationIdMethod = routePlatform.getClass().getMethod("getStationId");
                                    long stationId = (Long) getStationIdMethod.invoke(routePlatform);
                                    stationsServed.add(stationId);

                                    // Get platform name
                                    Object platform = getPlatformMethod.invoke(platformIdMap, platformId);
                                    if (platform != null) {
                                        try {
                                            java.lang.reflect.Method getNameMethod = platform.getClass().getMethod("getName");
                                            String name = (String) getNameMethod.invoke(platform);
                                            if (name != null && !name.isEmpty()) {
                                                platformNames.add(name);
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    }

                    // Add route if it serves at least one selected platform
                    if (platformsServed > 0 && !addedRoutes.contains(routeId)) {
                        addedRoutes.add(routeId);

                        // Build station name from all platforms served
                        String stationName;
                        if (platformNames.size() > 1) {
                            stationName = platformNames.get(0) + " +" + (platformNames.size() - 1) + " more";
                        } else if (!platformNames.isEmpty()) {
                            stationName = platformNames.get(0);
                        } else {
                            stationName = "Selected Platform(s)";
                        }

                        long firstStationId = stationsServed.isEmpty() ? 0 : stationsServed.iterator().next();

                        mtrStations.add(new MTRStationInfo(
                            firstStationId,
                            routeId,
                            stationName,
                            routeName,
                            routeColor
                        ));
                        routesFound++;
                    }
                }
            }

            // Sort by route name
            mtrStations.sort(Comparator.comparing(s -> s.routeName));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Find station at the announcement block position using InitClient.findStation()
     * Falls back to findClosePlatform() if not found
     */
    private Object findStationAtPosition() {
        try {
            Class<?> initClientClass = Class.forName("org.mtr.mod.InitClient");
            Class<?> initClass = Class.forName("org.mtr.mod.Init");
            Class<?> mappingBlockPosClass = Class.forName("org.mtr.mapping.holder.BlockPos");
            
            // Use Init.newBlockPos(double, double, double) to create MTR BlockPos
            java.lang.reflect.Method newBlockPosMethod = initClass.getMethod("newBlockPos", double.class, double.class, double.class);
            
            // Get the MTR BlockPos from vanilla position
            Object mtrBlockPos = newBlockPosMethod.invoke(null, (double)blockPos.getX(), (double)blockPos.getY(), (double)blockPos.getZ());
            
            // Try findStation first
            java.lang.reflect.Method findStationMethod = initClientClass.getMethod("findStation", mappingBlockPosClass);
            Object station = findStationMethod.invoke(null, mtrBlockPos);
            
            if (station != null) {
                String name = getStationName(station);
                return station;
            }
            
            // Try with position offset (like Joban does: down(4))
            Object mtrBlockPosDown = newBlockPosMethod.invoke(null, (double)blockPos.getX(), (double)blockPos.getY() - 4, (double)blockPos.getZ());
            station = findStationMethod.invoke(null, mtrBlockPosDown);
            
            if (station != null) {
                String name = getStationName(station);
                return station;
            }
            
            
            // Fall back to findClosePlatform
            return findStationFromNearbyPlatform();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Fallback: Find station by looking up nearby platform's stationId
     * Uses findClosePlatform which finds the SINGLE closest platform
     */
    private Object findStationFromNearbyPlatform() {
        try {
            Class<?> initClientClass = Class.forName("org.mtr.mod.InitClient");
            Class<?> initClass = Class.forName("org.mtr.mod.Init");
            Class<?> mappingBlockPosClass = Class.forName("org.mtr.mapping.holder.BlockPos");
            
            // Use Init.newBlockPos(double, double, double) to create MTR BlockPos
            java.lang.reflect.Method newBlockPosMethod = initClass.getMethod("newBlockPos", double.class, double.class, double.class);
            
            // Try multiple positions
            double[][] positions = {
                {blockPos.getX(), blockPos.getY(), blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 4, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 1, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 2, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 3, blockPos.getZ()},
            };
            
            for (double[] pos : positions) {
                Object mtrBlockPos = newBlockPosMethod.invoke(null, pos[0], pos[1], pos[2]);
                
                // findClosePlatform takes (BlockPos, int, Consumer<Platform>)
                // It finds the SINGLE closest platform
                java.lang.reflect.Method findCloseMethod = initClientClass.getMethod("findClosePlatform",
                    mappingBlockPosClass, int.class, java.util.function.Consumer.class);
                
                // Create a holder for the found platform
                final Object[] foundPlatform = new Object[1];
                java.util.function.Consumer<Object> consumer = platform -> foundPlatform[0] = platform;
                
                findCloseMethod.invoke(null, mtrBlockPos, 10, consumer);
                
                if (foundPlatform[0] != null) {
                    
                    // Get station ID from the platform
                    long stationId = getStationIdFromPlatform(foundPlatform[0]);
                    
                    // Look up station from stationIdMap
                    Class<?> minecraftClientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
                    java.lang.reflect.Method getInstanceMethod = minecraftClientDataClass.getMethod("getInstance");
                    Object clientData = getInstanceMethod.invoke(null);
                    
                    if (clientData != null) {
                        java.lang.reflect.Field stationIdMapField = minecraftClientDataClass.getField("stationIdMap");
                        Object stationIdMap = stationIdMapField.get(clientData);
                        
                        java.lang.reflect.Method getStationMethod = stationIdMap.getClass().getMethod("get", long.class);
                        Object station = getStationMethod.invoke(stationIdMap, stationId);
                        
                        if (station != null) {
                            String name = getStationName(station);
                            return station;
                        }
                    }
                }
            }
            
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Get station ID from Station object
     */
    private long getStationId(Object station) {
        try {
            java.lang.reflect.Method getIdMethod = station.getClass().getMethod("getId");
            return (Long) getIdMethod.invoke(station);
        } catch (Exception e) {
        }
        return 0;
    }

    /**
     * Get station name from Station object
     */
    private String getStationName(Object station) {
        try {
            java.lang.reflect.Method getNameMethod = station.getClass().getMethod("getName");
            String name = (String) getNameMethod.invoke(station);
            return name != null ? name : "Unknown Station";
        } catch (Exception e) {
        }
        return "Unknown Station";
    }

    /**
     * Load nearby platforms using InitClient.findClosePlatform
     */
    private void loadNearbyPlatforms(List<Object> nearbyPlatforms) {
        try {
            Class<?> initClientClass = Class.forName("org.mtr.mod.InitClient");
            Class<?> initClass = Class.forName("org.mtr.mod.Init");
            Class<?> mappingBlockPosClass = Class.forName("org.mtr.mapping.holder.BlockPos");

            // Use Init.newBlockPos(double, double, double)
            java.lang.reflect.Method newBlockPosMethod = initClass.getMethod("newBlockPos", double.class, double.class, double.class);

            // Try multiple positions
            double[][] positions = {
                {blockPos.getX(), blockPos.getY(), blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 4, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 1, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 2, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 3, blockPos.getZ()},
            };

            for (double[] pos : positions) {
                nearbyPlatforms.clear();
                Object mtrBlockPos = newBlockPosMethod.invoke(null, pos[0], pos[1], pos[2]);

                java.lang.reflect.Method findCloseMethod = initClientClass.getMethod("findClosePlatform",
                    mappingBlockPosClass, int.class, java.util.function.Consumer.class);

                final Object[] foundPlatform = new Object[1];
                java.util.function.Consumer<Object> consumer = platform -> foundPlatform[0] = platform;
                findCloseMethod.invoke(null, mtrBlockPos, 10, consumer);

                if (foundPlatform[0] != null) {
                    nearbyPlatforms.add(foundPlatform[0]);
                    return;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Extract stationId from a platform object
     */
    private long getStationIdFromPlatform(Object platform) {
        try {
            try {
                java.lang.reflect.Field stationIdField = platform.getClass().getField("stationId");
                return (Long) stationIdField.get(platform);
            } catch (Exception e) {
                try {
                    java.lang.reflect.Method getStationIdMethod = platform.getClass().getMethod("getStationId");
                    return (Long) getStationIdMethod.invoke(platform);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
        }
        return 0;
    }

    /**
     * Load all routes that serve a specific station
     */
    private void loadRoutesForStation(Object station, long stationId, String stationName) {
        try {
            Class<?> minecraftClientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = minecraftClientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);

            if (clientData == null) return;

            java.lang.reflect.Field simplifiedRoutesField = minecraftClientDataClass.getField("simplifiedRoutes");
            Object simplifiedRoutes = (Iterable<?>) simplifiedRoutesField.get(clientData);

            // Iterate through routes to find those serving this station
            java.lang.reflect.Method routeIteratorMethod = simplifiedRoutes.getClass().getMethod("iterator");
            java.util.Iterator<?> routeIter = (java.util.Iterator<?>) routeIteratorMethod.invoke(simplifiedRoutes);

            // Use a set to avoid duplicate routes
            Set<Long> addedRoutes = new HashSet<>();
            int routesFound = 0;

            while (routeIter.hasNext()) {
                Object route = routeIter.next();

                java.lang.reflect.Method getRouteIdMethod = route.getClass().getMethod("getId");
                long routeId = (Long) getRouteIdMethod.invoke(route);

                // Get route name and color
                String routeName = "";
                int routeColor = 0x808080;
                try {
                    java.lang.reflect.Method getNameMethod = route.getClass().getMethod("getName");
                    routeName = (String) getNameMethod.invoke(route);
                    if (routeName == null) routeName = "";

                    java.lang.reflect.Method getColorMethod = route.getClass().getMethod("getColor");
                    routeColor = (Integer) getColorMethod.invoke(route);
                } catch (Exception e) {
                    // Skip if methods not found
                }

                // Check if this route serves the target station
                java.lang.reflect.Method getPlatformsMethod = route.getClass().getMethod("getPlatforms");
                Object platforms = getPlatformsMethod.invoke(route);

                if (platforms != null) {
                    java.util.Iterator<?> platformIter = (java.util.Iterator<?>) platforms.getClass().getMethod("iterator").invoke(platforms);

                    boolean stationInRoute = false;
                    while (platformIter.hasNext() && !stationInRoute) {
                        Object routePlatform = platformIter.next();
                        try {
                            java.lang.reflect.Method getStationIdMethod = routePlatform.getClass().getMethod("getStationId");
                            long thisStationId = (Long) getStationIdMethod.invoke(routePlatform);

                            if (thisStationId == stationId) {
                                stationInRoute = true;
                            }
                        } catch (Exception ignored) {}
                    }

                    // If this route serves the target station, add it
                    if (stationInRoute && !addedRoutes.contains(routeId)) {
                        addedRoutes.add(routeId);
                        mtrStations.add(new MTRStationInfo(
                            stationId,
                            routeId,
                            stationName,
                            routeName,
                            routeColor
                        ));
                        routesFound++;
                    }
                }
            }

            // Sort by route name
            mtrStations.sort(Comparator.comparing(s -> s.routeName));

        } catch (Exception e) {
        }
    }

    /**
     * Fallback to demo stations if MTR data is not available
     * Only adds demo stations if no real data has been loaded
     */
    private void loadFallbackStations() {
        // Only use fallback if no stations have been loaded at all
        if (!mtrStations.isEmpty()) {
            return;
        }
        mtrStations.add(new MTRStationInfo(1, 1, "Station 1", "Red Line", 0xFF0000));
        mtrStations.add(new MTRStationInfo(1, 2, "Station 1", "Green Line", 0x00FF00));
        mtrStations.add(new MTRStationInfo(2, 1, "Station 2", "Red Line", 0xFF0000));
        mtrStations.add(new MTRStationInfo(2, 2, "Station 2", "Green Line", 0x00FF00));
        mtrStations.add(new MTRStationInfo(3, 3, "Station 3", "Blue Line", 0x0000FF));
        mtrStations.add(new MTRStationInfo(4, 4, "Station 4", "Yellow Line", 0xFFFF00));
    }

    private void updateButtons() {
        this.clearChildren();
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int yStart = this.height / 4;
        int yOffset = BUTTON_HEIGHT + BUTTON_SPACING;

        int totalStations = mtrStations.size();
        maxScroll = Math.max(0, totalStations - VISIBLE_BUTTONS);

        // Show header text or "no nearby platforms" message
        if (showNoNearbyMessage) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("No nearby platforms!"), button -> {}).dimensions(x, yStart - 25, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        } else {
            // Show header based on whether we're filtering by selected platforms
            String headerText;
            if (preselectedPlatformIds != null && !preselectedPlatformIds.isEmpty()) {
                headerText = "Routes for selected platform(s): " + preselectedPlatformIds.size();
            } else {
                headerText = "Routes at this station:";
            }
            this.addDrawableChild(ButtonWidget.builder(Text.literal(headerText + " (" + totalStations + ")"), button -> {}).dimensions(x, yStart - 25, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        for (int i = 0; i < VISIBLE_BUTTONS && i + scrollOffset < totalStations; i++) {
            int stationIndex = i + scrollOffset;
            MTRStationInfo station = mtrStations.get(stationIndex);
            // Use routeId as selection key (routeId is unique per route)
            boolean isSelected = selectedRouteColors.contains((int) station.routeId);

            // Format: "Station Name (Route Name)" with route color indicator
            String displayText = station.routeName + " @ " + station.stationName;

            // Show colored route indicator
            int displayColor = station.color;
            Text buttonText = Text.literal(displayText + (isSelected ? "  [Selected]" : "")).setStyle(Style.EMPTY.withColor(displayColor));

            this.addDrawableChild(ButtonWidget.builder(buttonText, button -> {
                if (isSelected) {
                    selectedRouteColors.remove((int) station.routeId);
                } else {
                    selectedRouteColors.add((int) station.routeId);
                }
                updateButtons();
            }).dimensions(x, yStart + i * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        addScrollButtons(x, yStart, yOffset, totalStations);

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.easyannouncement.save"), button -> {
            saveSelectionAndClose();
        }).dimensions(x, yStart + (VISIBLE_BUTTONS + 1) * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void addScrollButtons(int x, int yStart, int yOffset, int count) {
        if (scrollOffset > 0) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> {
                scrollOffset = Math.max(0, scrollOffset - 1);
                updateButtons();
            }).dimensions(x - 30, yStart, 25, BUTTON_HEIGHT).build());
        }
        if (scrollOffset < maxScroll) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> {
                scrollOffset = Math.min(maxScroll, scrollOffset + 1);
                updateButtons();
            }).dimensions(x + BUTTON_WIDTH + 5, yStart, 25, BUTTON_HEIGHT).build());
        }
    }

    private void saveSelectionAndClose() {
        try {
            // Start with preselected platforms
            List<Long> finalPlatformIds = new ArrayList<>(preselectedPlatformIds != null ? preselectedPlatformIds : new ArrayList<>());

            Class<?> minecraftClientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = minecraftClientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);

            if (clientData != null) {
                // Get platformIdMap to look up platforms
                java.lang.reflect.Field platformIdMapField = minecraftClientDataClass.getField("platformIdMap");
                Object platformIdMap = platformIdMapField.get(clientData);
                java.lang.reflect.Method getPlatformMethod = platformIdMap.getClass().getMethod("get", long.class);

                // Get simplifiedRoutes
                java.lang.reflect.Field simplifiedRoutesField = minecraftClientDataClass.getField("simplifiedRoutes");
                Object simplifiedRoutes = (Iterable<?>) simplifiedRoutesField.get(clientData);
                java.lang.reflect.Method routeIteratorMethod = simplifiedRoutes.getClass().getMethod("iterator");
                java.util.Iterator<?> routeIter = (java.util.Iterator<?>) routeIteratorMethod.invoke(simplifiedRoutes);

                while (routeIter.hasNext()) {
                    Object route = routeIter.next();

                    java.lang.reflect.Method getRouteIdMethod = route.getClass().getMethod("getId");
                    long routeId = (Long) getRouteIdMethod.invoke(route);

                    // Check if this route is selected (by routeId)
                    boolean isRouteSelected = false;
                    for (Integer selectedColor : selectedRouteColors) {
                        if ((long) selectedColor == routeId) {
                            isRouteSelected = true;
                            break;
                        }
                    }

                    if (isRouteSelected) {
                        // Find ALL platforms for this route (served by the selected route)
                        java.lang.reflect.Method getPlatformsMethod = route.getClass().getMethod("getPlatforms");
                        Object platforms = getPlatformsMethod.invoke(route);

                        if (platforms != null) {
                            java.util.Iterator<?> platformIter = (java.util.Iterator<?>) platforms.getClass().getMethod("iterator").invoke(platforms);

                            while (platformIter.hasNext()) {
                                Object routePlatform = platformIter.next();
                                try {
                                    java.lang.reflect.Method getPlatformIdMethod = routePlatform.getClass().getMethod("getPlatformId");
                                    long platformId = (Long) getPlatformIdMethod.invoke(routePlatform);

                                    // Add this platform if it's one of our selected platforms
                                    if (preselectedPlatformIds != null && preselectedPlatformIds.contains(platformId)) {
                                        if (!finalPlatformIds.contains(platformId)) {
                                            finalPlatformIds.add(platformId);
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }

            // If still empty, use preselected platforms
            if (finalPlatformIds.isEmpty() && preselectedPlatformIds != null && !preselectedPlatformIds.isEmpty()) {
                finalPlatformIds = new ArrayList<>(preselectedPlatformIds);
            }

            // Only send if we have platforms
            if (!finalPlatformIds.isEmpty()) {

                // Send packet to server
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(blockPos);
                long[] platformIdArray = finalPlatformIds.stream().mapToLong(Long::longValue).toArray();
                buf.writeLongArray(platformIdArray);
                ClientPlayNetworking.send(AnnounceSendToClient.PLATFORM_SELECTION_ID, buf);

                // Also fetch MTR arrival data and trigger announcement immediately
                fetchAndSendMTRData(blockPos, finalPlatformIds);

            } else {
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        MinecraftClient.getInstance().setScreen(null);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);

        // Show "no nearby platforms" message on screen
        if (showNoNearbyMessage) {
            String message = "Place the announcement block near MTR platforms";
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(message), this.width / 2, this.height / 2 - 30, 0xFFAA00);
        }
    }

    // Fetch MTR arrival data and send to server to trigger announcement
    private void fetchAndSendMTRData(BlockPos blockPos, List<Long> platformIds) {
        try {
            Class<?> arrivalsCacheClientClass = Class.forName("org.mtr.mod.data.ArrivalsCacheClient");
            java.lang.reflect.Field instanceField = arrivalsCacheClientClass.getField("INSTANCE");
            Object arrivalsCacheClient = instanceField.get(null);

            Class<?> longCollectionClass = Class.forName("it.unimi.dsi.fastutil.longs.LongCollection");
            java.lang.reflect.Method requestArrivalsMethod = arrivalsCacheClientClass.getMethod("requestArrivals", longCollectionClass);

            Class<?> longArrayListClass = Class.forName("it.unimi.dsi.fastutil.longs.LongArrayList");
            Object platformList = longArrayListClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method addMethod = longArrayListClass.getMethod("add", long.class);
            for (Long platformId : platformIds) {
                addMethod.invoke(platformList, platformId);
            }

            Object arrivals = requestArrivalsMethod.invoke(arrivalsCacheClient, platformList);
            if (arrivals == null) {
                return;
            }

            // Check if arrivals list is empty
            java.lang.reflect.Method sizeMethod = arrivals.getClass().getMethod("size");
            int arrivalSize = (Integer) sizeMethod.invoke(arrivals);
            if (arrivalSize == 0) {
                return;
            }

            java.lang.reflect.Method getMillisOffsetMethod = arrivalsCacheClientClass.getMethod("getMillisOffset");
            long millisOffset = (Long) getMillisOffsetMethod.invoke(arrivalsCacheClient);
            long currentTime = System.currentTimeMillis();

            java.lang.reflect.Method iteratorMethod = arrivals.getClass().getMethod("iterator");
            java.util.Iterator<?> arrivalIter = (java.util.Iterator<?>) iteratorMethod.invoke(arrivals);

            int arrivalCount = 0;
            while (arrivalIter.hasNext()) {
                arrivalCount++;
                Object arrival = arrivalIter.next();
                java.lang.reflect.Method getArrivalMethod = arrival.getClass().getMethod("getArrival");
                long arrivalTime = (Long) getArrivalMethod.invoke(arrival) - millisOffset;

                if (arrivalTime > currentTime) {
                    String destination = "";
                    String routeName = "";
                    long routeId = 0L;

                    try {
                        java.lang.reflect.Method getDestMethod = arrival.getClass().getMethod("getDestination");
                        destination = (String) getDestMethod.invoke(arrival);
                        java.lang.reflect.Method getRouteNameMethod = arrival.getClass().getMethod("getRouteName");
                        routeName = (String) getRouteNameMethod.invoke(arrival);
                        java.lang.reflect.Method getRouteIdMethod = arrival.getClass().getMethod("getRouteId");
                        routeId = (Long) getRouteIdMethod.invoke(arrival);
                    } catch (Exception e) {
                    }

                    String hh = String.format("%02d", (int) ((arrivalTime / 3600000) % 24));
                    String mm = String.format("%02d", (int) ((arrivalTime / 60000) % 60));


                    // Send MTR data response to server
                    PacketByteBuf responseBuf = PacketByteBufs.create();
                    responseBuf.writeBlockPos(blockPos);
                    responseBuf.writeLong(arrivalTime);
                    responseBuf.writeLong(platformIds.get(0)); // Use first platform
                    responseBuf.writeLong(routeId);
                    responseBuf.writeInt(-1); // currentStationIndex
                    writeString(responseBuf, destination != null ? destination : "");
                    writeString(responseBuf, routeName != null ? routeName : "");
                    writeString(responseBuf, hh);
                    writeString(responseBuf, mm);
                    ClientPlayNetworking.send(AnnounceSendToClient.MTR_DATA_RESPONSE_ID, responseBuf);
                    break; // Only send first arrival
                }
            }
        } catch (Exception e) {
        }
    }

    private static void writeString(PacketByteBuf buf, String str) {
        if (str == null) str = "";
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }
}
