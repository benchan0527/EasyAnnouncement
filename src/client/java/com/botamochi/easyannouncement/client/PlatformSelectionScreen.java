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
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class PlatformSelectionScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("EasyAnnouncement");
    private final BlockPos blockPos;
    private HashSet<Long> selectedPlatforms;
    private boolean selectAllMode = false; // If true, select all platforms by default
    private int scrollOffset = 0;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 5;
    private static final int VISIBLE_BUTTONS = 5;
    private static final int BUTTON_WIDTH = 200;
    private int maxScroll = 0;
    private int listX;
    private int listYStart;
    private int listYOffset;

    // Flag to show "no nearby platforms" message
    private boolean showNoNearbyMessage = false;

    // MTR 4.0 platform data - nearby platforms first
    private List<MTRPlatformInfo> mtrPlatforms = new ArrayList<>();
    private boolean hasNearbyPlatforms = false;

    // Helper class for MTR platform information
    private static class MTRPlatformInfo {
        long platformId;
        long stationId;
        String stationName;
        String platformDisplayName;
        int color;
        boolean isNearby;

        MTRPlatformInfo(long platformId, long stationId, String stationName, String platformDisplayName, int color, boolean isNearby) {
            this.platformId = platformId;
            this.stationId = stationId;
            this.stationName = stationName;
            this.platformDisplayName = platformDisplayName;
            this.color = color;
            this.isNearby = isNearby;
        }
    }

    public PlatformSelectionScreen(BlockPos blockPos, List<Long> selectedPlatforms) {
        super(Text.translatable("gui.easyannouncement.platform_selection"));
        this.blockPos = blockPos;
        this.selectedPlatforms = new HashSet<>(selectedPlatforms);
        // If no preselected platforms (legacy data), keep empty
        this.selectAllMode = false;
    }

    public PlatformSelectionScreen(BlockPos blockPos, List<Long> selectedPlatforms, boolean forceSelectAll) {
        super(Text.translatable("gui.easyannouncement.platform_selection"));
        this.blockPos = blockPos;
        this.selectedPlatforms = new HashSet<>(selectedPlatforms);
        this.selectAllMode = forceSelectAll;
    }

    @Override
    protected void init() {
        super.init();
        loadMTRPlatforms();

        // If selectAllMode is true, pre-select all platforms
        if (selectAllMode && !mtrPlatforms.isEmpty()) {
            for (MTRPlatformInfo platform : mtrPlatforms) {
                selectedPlatforms.add(platform.platformId);
            }
        }

        updateButtons();
    }

    /**
     * Load platforms from MTR 4.0 API
     * Uses InitClient.findStation() to directly find the station at this position
     */
    private void loadMTRPlatforms() {
        mtrPlatforms.clear();
        hasNearbyPlatforms = false;

        try {
            

            // Use InitClient.findStation() to directly find the station
            Object station = findStationAtPosition();

            if (station != null) {
                hasNearbyPlatforms = true;

                // Get station name
                String stationName = getStationName(station);
                long stationId = getStationId(station);


                // Load platforms from this station using savedRails
                loadPlatformsFromStation(station, stationName);

            } else {
                // No station found at this position
                showNoNearbyMessage = true;
            }

            if (mtrPlatforms.isEmpty() && !showNoNearbyMessage) {
                loadFallbackPlatforms();
            }


        } catch (Exception e) {
            e.printStackTrace();
            loadFallbackPlatforms();
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

            // Log total platforms in MinecraftClientData first
            logMinecraftClientDataStatus();

            // Try findStation at original position
            Object mtrBlockPos = newBlockPosMethod.invoke(null, (double)blockPos.getX(), (double)blockPos.getY(), (double)blockPos.getZ());
            java.lang.reflect.Method findStationMethod = initClientClass.getMethod("findStation", mappingBlockPosClass);
            Object station = findStationMethod.invoke(null, mtrBlockPos);

            if (station != null) {
                String name = getStationName(station);
                logStationSavedRails(station);
                return station;
            }

            // Try with position offset (like Joban does: down(4))
            Object mtrBlockPosDown = newBlockPosMethod.invoke(null, (double)blockPos.getX(), (double)blockPos.getY() - 4, (double)blockPos.getZ());
            station = findStationMethod.invoke(null, mtrBlockPosDown);

            if (station != null) {
                String name = getStationName(station);
                logStationSavedRails(station);
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
     * Log MinecraftClientData status - total platforms count
     */
    private void logMinecraftClientDataStatus() {
        try {
            Class<?> minecraftClientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = minecraftClientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);

            if (clientData == null) {
                return;
            }

            java.lang.reflect.Field platformsField = minecraftClientDataClass.getField("platforms");
            Object platforms = platformsField.get(clientData);
            java.lang.reflect.Method sizeMethod = platforms.getClass().getMethod("size");
            int platformsCount = (Integer) sizeMethod.invoke(platforms);

            java.lang.reflect.Field stationsField = minecraftClientDataClass.getField("stations");
            Object stations = stationsField.get(clientData);
            java.lang.reflect.Method stationsSizeMethod = stations.getClass().getMethod("size");
            int stationsCount = (Integer) stationsSizeMethod.invoke(stations);


        } catch (Exception e) {
        }
    }

    /**
     * Log station savedRails count
     */
    private void logStationSavedRails(Object station) {
        try {
            if (station == null) return;

            String stationName = getStationName(station);
            java.lang.reflect.Field savedRailsField = station.getClass().getField("savedRails");
            Object savedRails = savedRailsField.get(station);

            if (savedRails == null) {
                return;
            }

            java.lang.reflect.Method sizeMethod = savedRails.getClass().getMethod("size");
            int size = (Integer) sizeMethod.invoke(savedRails);

            if (size == 0) {
            }

        } catch (Exception e) {
        }
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

            java.lang.reflect.Method newBlockPosMethod = initClass.getMethod("newBlockPos", double.class, double.class, double.class);

            // findClosePlatform takes (BlockPos, int, Consumer<Platform>)
            java.lang.reflect.Method findCloseMethod = initClientClass.getMethod("findClosePlatform",
                mappingBlockPosClass, int.class, java.util.function.Consumer.class);

            // Try multiple positions - LOG EACH ATTEMPT
            double[][] positions = {
                {blockPos.getX(), blockPos.getY(), blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 4, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 1, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 2, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() - 3, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() + 1, blockPos.getZ()},
                {blockPos.getX(), blockPos.getY() + 2, blockPos.getZ()},
            };

            for (double[] pos : positions) {
                Object mtrBlockPos = newBlockPosMethod.invoke(null, pos[0], pos[1], pos[2]);

                // Create a holder for the found platform
                final Object[] foundPlatform = new Object[1];
                java.util.function.Consumer<Object> consumer = platform -> {
                    foundPlatform[0] = platform;
                };

                findCloseMethod.invoke(null, mtrBlockPos, 10, consumer);

                if (foundPlatform[0] != null) {

                    // Get platform ID
                    java.lang.reflect.Method getIdMethod = foundPlatform[0].getClass().getMethod("getId");
                    long platformId = (Long) getIdMethod.invoke(foundPlatform[0]);

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
                        } else {
                        }
                    }
                } else {
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
     * Load platforms from a Station object using savedRails
     * Falls back to findClosePlatform if savedRails is empty
     */
    private void loadPlatformsFromStation(Object station, String stationName) {
        try {
            Class<?> minecraftClientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = minecraftClientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);

            if (clientData == null) {
                loadPlatformsFromNearby(blockPos, stationName);
                return;
            }

            // Get platformIdMap for platform names
            java.lang.reflect.Field platformIdMapField = minecraftClientDataClass.getField("platformIdMap");
            Object platformIdMap = platformIdMapField.get(clientData);

            // Get savedRails (direct list of Platform objects) from the station
            java.lang.reflect.Field savedRailsField = station.getClass().getField("savedRails");
            Object savedRails = savedRailsField.get(station);

            if (savedRails == null) {
                loadPlatformsFromNearby(blockPos, stationName);
                return;
            }

            // Check if savedRails has elements
            java.lang.reflect.Method sizeMethod = savedRails.getClass().getMethod("size");
            int size = (Integer) sizeMethod.invoke(savedRails);


            if (size == 0) {
                loadPlatformsFromNearby(blockPos, stationName);
                return;
            }

            // Iterate through savedRails - each element IS a Platform
            java.lang.reflect.Method iteratorMethod = savedRails.getClass().getMethod("iterator");
            java.util.Iterator<?> platformIter = (java.util.Iterator<?>) iteratorMethod.invoke(savedRails);

            int platformsFound = 0;

            while (platformIter.hasNext()) {
                Object platform = platformIter.next();
                try {
                    // Get platform ID - Platform has getId() method
                    java.lang.reflect.Method getIdMethod = platform.getClass().getMethod("getId");
                    long platformId = (Long) getIdMethod.invoke(platform);

                    // Get platform name
                    String platformDisplayName = "";
                    try {
                        java.lang.reflect.Method getNameMethod = platform.getClass().getMethod("getName");
                        platformDisplayName = (String) getNameMethod.invoke(platform);
                    } catch (Exception e) {
                        // Try from platformIdMap
                        if (platformIdMap != null) {
                            java.lang.reflect.Method getPlatformMethod = platformIdMap.getClass().getMethod("get", long.class);
                            Object lookupPlatform = getPlatformMethod.invoke(platformIdMap, platformId);
                            if (lookupPlatform != null) {
                                try {
                                    java.lang.reflect.Method getNameMethod = lookupPlatform.getClass().getMethod("getName");
                                    platformDisplayName = (String) getNameMethod.invoke(lookupPlatform);
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    if (platformDisplayName == null || platformDisplayName.isEmpty()) {
                        platformDisplayName = String.valueOf((int)(platformId % 100));
                    }

                    // Check if already added
                    boolean alreadyAdded = false;
                    for (MTRPlatformInfo existing : mtrPlatforms) {
                        if (existing.platformId == platformId) {
                            alreadyAdded = true;
                            break;
                        }
                    }

                    if (!alreadyAdded) {
                        mtrPlatforms.add(new MTRPlatformInfo(
                            platformId, 0, stationName, platformDisplayName, 0x808080, true
                        ));
                        platformsFound++;
                    }
                } catch (Exception e) {
                }
            }

            mtrPlatforms.sort(Comparator.comparing(p -> p.platformDisplayName));

            // If still empty, use fallback
            if (mtrPlatforms.isEmpty()) {
                loadPlatformsFromNearby(blockPos, stationName);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Fallback: Find nearby platforms using findClosePlatform
     */
    private void loadPlatformsFromNearby(net.minecraft.util.math.BlockPos searchPos, String stationName) {
        try {
            Class<?> initClientClass = Class.forName("org.mtr.mod.InitClient");
            Class<?> initClass = Class.forName("org.mtr.mod.Init");
            Class<?> mappingBlockPosClass = Class.forName("org.mtr.mapping.holder.BlockPos");

            java.lang.reflect.Method newBlockPosMethod = initClass.getMethod("newBlockPos", double.class, double.class, double.class);

            Class<?> minecraftClientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = minecraftClientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);

            java.lang.reflect.Field platformIdMapField = minecraftClientDataClass.getField("platformIdMap");
            Object platformIdMap = clientData != null ? platformIdMapField.get(clientData) : null;

            java.lang.reflect.Field stationIdMapField = minecraftClientDataClass.getField("stationIdMap");
            Object stationIdMap = clientData != null ? stationIdMapField.get(clientData) : null;

            // Log total platforms in MinecraftClientData
            java.lang.reflect.Field platformsField = minecraftClientDataClass.getField("platforms");
            Object platforms = platformsField.get(clientData);
            java.lang.reflect.Method platformsSizeMethod = platforms.getClass().getMethod("size");
            int platformsCount = (Integer) platformsSizeMethod.invoke(platforms);

            // Try multiple positions with INCREASED radius
            double[][] positions = {
                {searchPos.getX(), searchPos.getY(), searchPos.getZ()},
                {searchPos.getX(), searchPos.getY() - 4, searchPos.getZ()},
                {searchPos.getX(), searchPos.getY() - 1, searchPos.getZ()},
                {searchPos.getX(), searchPos.getY() - 2, searchPos.getZ()},
                {searchPos.getX(), searchPos.getY() - 3, searchPos.getZ()},
                {searchPos.getX(), searchPos.getY() + 1, searchPos.getZ()},
                {searchPos.getX(), searchPos.getY() + 2, searchPos.getZ()},
            };

            java.lang.reflect.Method findCloseMethod = initClientClass.getMethod("findClosePlatform",
                mappingBlockPosClass, int.class, java.util.function.Consumer.class);

            boolean foundAny = false;
            for (double[] pos : positions) {
                Object mtrBlockPos = newBlockPosMethod.invoke(null, pos[0], pos[1], pos[2]);

                final Object[] foundPlatform = new Object[1];
                java.util.function.Consumer<Object> consumer = platform -> {
                    foundPlatform[0] = platform;
                };

                findCloseMethod.invoke(null, mtrBlockPos, 50, consumer);

                if (foundPlatform[0] != null) {
                    foundAny = true;
                    // Get platform ID
                    java.lang.reflect.Method getIdMethod = foundPlatform[0].getClass().getMethod("getId");
                    long platformId = (Long) getIdMethod.invoke(foundPlatform[0]);

                    // Get station ID from platform
                    long stationId = getStationIdFromPlatform(foundPlatform[0]);


                    // Try to get station name if stationId is valid
                    if (stationId != 0 && stationIdMap != null) {
                        try {
                            java.lang.reflect.Method getStationMethod = stationIdMap.getClass().getMethod("get", long.class);
                            Object station = getStationMethod.invoke(stationIdMap, stationId);
                            if (station != null) {
                                java.lang.reflect.Method getNameMethod = station.getClass().getMethod("getName");
                                String name = (String) getNameMethod.invoke(station);
                                if (name != null && !name.isEmpty()) {
                                    stationName = name;
                                }
                            }
                        } catch (Exception e) {
                            // Keep existing station name
                        }
                    }

                    // Get platform display name
                    String platformDisplayName = "";
                    if (platformIdMap != null) {
                        try {
                            java.lang.reflect.Method getPlatformMethod = platformIdMap.getClass().getMethod("get", long.class);
                            Object platform = getPlatformMethod.invoke(platformIdMap, platformId);
                            if (platform != null) {
                                java.lang.reflect.Method getPlatformNameMethod = platform.getClass().getMethod("getName");
                                platformDisplayName = (String) getPlatformNameMethod.invoke(platform);
                            }
                        } catch (Exception e) {
                            // Try direct method on platform
                            try {
                                java.lang.reflect.Method getNameMethod = foundPlatform[0].getClass().getMethod("getName");
                                platformDisplayName = (String) getNameMethod.invoke(foundPlatform[0]);
                            } catch (Exception ignored) {}
                        }
                    }
                    if (platformDisplayName == null || platformDisplayName.isEmpty()) {
                        platformDisplayName = String.valueOf((int)(platformId % 100));
                    }

                    // Check if already added
                    boolean alreadyAdded = false;
                    for (MTRPlatformInfo existing : mtrPlatforms) {
                        if (existing.platformId == platformId) {
                            alreadyAdded = true;
                            break;
                        }
                    }

                    if (!alreadyAdded) {
                        mtrPlatforms.add(new MTRPlatformInfo(
                            platformId, stationId, stationName, platformDisplayName, 0x808080, true
                        ));
                    }
                } else {
                }
            }

            if (!foundAny) {
            }

            mtrPlatforms.sort(Comparator.comparing(p -> p.platformDisplayName));

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
     * Extract platformId from a platform object
     */
    private long getPlatformId(Object platform) {
        try {
            try {
                java.lang.reflect.Field idField = platform.getClass().getField("id");
                return (Long) idField.get(platform);
            } catch (Exception e) {
                try {
                    java.lang.reflect.Method getIdMethod = platform.getClass().getMethod("getId");
                    return (Long) getIdMethod.invoke(platform);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
        }
        return 0;
    }

    /**
     * Load all platforms for a specific station from simplifiedRoutes
     * @param stationId The station ID to filter platforms
     */
    private void loadPlatformsFromStation(long stationId) {
        try {
            Class<?> minecraftClientDataClass = Class.forName("org.mtr.mod.client.MinecraftClientData");
            java.lang.reflect.Method getInstanceMethod = minecraftClientDataClass.getMethod("getInstance");
            Object clientData = getInstanceMethod.invoke(null);

            if (clientData == null) return;

            java.lang.reflect.Field simplifiedRoutesField = minecraftClientDataClass.getField("simplifiedRoutes");
            Object simplifiedRoutes = (Iterable<?>) simplifiedRoutesField.get(clientData);

            java.lang.reflect.Field stationIdMapField = minecraftClientDataClass.getField("stationIdMap");
            Object stationIdMap = stationIdMapField.get(clientData);

            java.lang.reflect.Field platformIdMapField = minecraftClientDataClass.getField("platformIdMap");
            Object platformIdMap = platformIdMapField.get(clientData);

            // Get station name
            String stationName = "";
            java.lang.reflect.Method getStationMethod = stationIdMap.getClass().getMethod("get", long.class);
            Object station = getStationMethod.invoke(stationIdMap, stationId);
            if (station != null) {
                java.lang.reflect.Method getNameMethod = station.getClass().getMethod("getName");
                stationName = (String) getNameMethod.invoke(station);
            }
            if (stationName == null || stationName.isEmpty()) {
                stationName = "Unknown Station";
            }

            // Iterate through routes to find platforms for this station
            java.lang.reflect.Method routeIteratorMethod = simplifiedRoutes.getClass().getMethod("iterator");
            java.util.Iterator<?> routeIter = (java.util.Iterator<?>) routeIteratorMethod.invoke(simplifiedRoutes);

            int platformsFound = 0;

            while (routeIter.hasNext()) {
                Object route = routeIter.next();

                java.lang.reflect.Method getColorMethod = route.getClass().getMethod("getColor");
                int routeColor = (Integer) getColorMethod.invoke(route);

                java.lang.reflect.Method getPlatformsMethod = route.getClass().getMethod("getPlatforms");
                Object platforms = getPlatformsMethod.invoke(route);

                java.lang.reflect.Method platformIteratorMethod = platforms.getClass().getMethod("iterator");
                java.util.Iterator<?> platformIter = (java.util.Iterator<?>) platformIteratorMethod.invoke(platforms);

                while (platformIter.hasNext()) {
                    Object routePlatform = platformIter.next();
                    try {
                        java.lang.reflect.Method getPlatformIdMethod = routePlatform.getClass().getMethod("getPlatformId");
                        long platformId = (Long) getPlatformIdMethod.invoke(routePlatform);

                        java.lang.reflect.Method getStationIdMethod = routePlatform.getClass().getMethod("getStationId");
                        long thisStationId = (Long) getStationIdMethod.invoke(routePlatform);

                        // Only include platforms from the target station
                        if (thisStationId != stationId) {
                            continue;
                        }

                        // Get platform display name
                        String platformDisplayName = "";
                        if (platformIdMap != null) {
                            java.lang.reflect.Method getPlatformMethod = platformIdMap.getClass().getMethod("get", long.class);
                            Object platform = getPlatformMethod.invoke(platformIdMap, platformId);
                            if (platform != null) {
                                java.lang.reflect.Method getPlatformNameMethod = platform.getClass().getMethod("getName");
                                platformDisplayName = (String) getPlatformNameMethod.invoke(platform);
                            }
                        }

                        if (platformDisplayName == null || platformDisplayName.isEmpty()) {
                            platformDisplayName = String.valueOf((int)(platformId % 100));
                        }

                        mtrPlatforms.add(new MTRPlatformInfo(
                            platformId, stationId, stationName, platformDisplayName, routeColor, true
                        ));
                        platformsFound++;

                    } catch (Exception e) {
                        // Skip this platform
                    }
                }
            }

            // Sort by platform display name
            mtrPlatforms.sort(Comparator.comparing(p -> p.platformDisplayName));

        } catch (Exception e) {
        }
    }

    /**
     * Fallback to demo platforms if MTR data is not available
     * Only adds demo platforms if no real data has been loaded
     */
    private void loadFallbackPlatforms() {
        // Only use fallback if no platforms have been loaded at all
        if (!mtrPlatforms.isEmpty()) {
            return;
        }
        mtrPlatforms.add(new MTRPlatformInfo(1, 1, "Station 1", "1", 0xFF0000, false));
        mtrPlatforms.add(new MTRPlatformInfo(2, 1, "Station 1", "2", 0xFF0000, false));
        mtrPlatforms.add(new MTRPlatformInfo(3, 2, "Station 2", "1", 0x00FF00, false));
        mtrPlatforms.add(new MTRPlatformInfo(4, 2, "Station 2", "2", 0x00FF00, false));
        mtrPlatforms.add(new MTRPlatformInfo(5, 3, "Station 3", "1", 0x0000FF, false));
    }

    private int getPlatformCount() {
        return mtrPlatforms.size();
    }

    private void updateButtons() {
        this.clearChildren();

        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int yStart = this.height / 4;
        int yOffset = BUTTON_HEIGHT + BUTTON_SPACING;
        listX = x;
        listYStart = yStart;
        listYOffset = yOffset;

        int platformCount = getPlatformCount();
        maxScroll = Math.max(0, platformCount - VISIBLE_BUTTONS);

        // Show header text or "no nearby platforms" message
        if (showNoNearbyMessage) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("No nearby platforms!"), button -> {}).dimensions(x, yStart - 25, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        } else {
            String headerText = hasNearbyPlatforms ? "Nearby Platforms:" : "All Platforms:";
            this.addDrawableChild(ButtonWidget.builder(Text.literal(headerText + " (" + platformCount + ")"), button -> {}).dimensions(x, yStart - 25, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        for (int i = 0; i < VISIBLE_BUTTONS && i + scrollOffset < platformCount; i++) {
            int platformIndex = i + scrollOffset;
            MTRPlatformInfo platform = mtrPlatforms.get(platformIndex);
            boolean isSelected = selectedPlatforms.contains(platform.platformId);

            // Show station name + platform number
            String displayText = platform.stationName + " - Platform " + platform.platformDisplayName;

            this.addDrawableChild(ButtonWidget.builder(Text.literal(displayText + (isSelected ? "  [Selected]" : "")), button -> {
                if (selectedPlatforms.contains(platform.platformId)) {
                    selectedPlatforms.remove(platform.platformId);
                } else {
                    // Single selection mode: if already have one selected, replace it
                    selectedPlatforms.clear();
                    selectedPlatforms.add(platform.platformId);
                }
                updateButtons();
            }).dimensions(x, yStart + i * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        addScrollButtons(x, yStart, yOffset, platformCount);

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.easyannouncement.save"), button -> {
            saveSelectionAndClose();
        }).dimensions(x, yStart + (VISIBLE_BUTTONS + 1) * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void addScrollButtons(int x, int yStart, int yOffset, int totalCount) {
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
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(blockPos);
        long[] platformIdArray = selectedPlatforms.stream().mapToLong(Long::longValue).toArray();
        buf.writeLongArray(platformIdArray);
        ClientPlayNetworking.send(AnnounceSendToClient.PLATFORM_SELECTION_ID, buf);
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
}
