package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.integration.MTRIntegrationService;
import com.botamochi.easyannouncement.integration.MTRIntegrationService.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Enhanced Announcement Helper with MTR Integration
 * Generates intelligent announcements based on real-time MTR data
 */
public class EnhancedAnnouncementHelper {
    
    private static final MTRIntegrationService mtrService = MTRIntegrationService.getInstance();
    
    /**
     * Generate a comprehensive station announcement with MTR data
     */
    public static String generateStationAnnouncement(World world, BlockPos pos, String selectedJson) {
        if (!mtrService.isMTRModLoaded()) {
            return generateBasicAnnouncement(selectedJson);
        }
        
        try {
            MTRStationInfo stationInfo = mtrService.getStationInfo(pos);
            MTRTimeInfo timeInfo = mtrService.getCurrentTimeInfo(world);
            List<MTRScheduleInfo> schedule = mtrService.getPlatformSchedule(pos, 3);
            MTRDelayInfo delayInfo = mtrService.getDelayInfo(pos);
            
            return buildIntelligentAnnouncement(stationInfo, timeInfo, schedule, delayInfo, selectedJson);
            
        } catch (Exception e) {
            System.err.println("Enhanced Announcement: Error generating announcement: " + e.getMessage());
            return generateBasicAnnouncement(selectedJson);
        }
    }
    
    /**
     * Generate train-specific announcements when player is on a train
     */
    public static String generateTrainAnnouncement(ClientPlayerEntity player, String selectedJson) {
        if (!mtrService.isMTRModLoaded() || player == null) {
            return generateBasicAnnouncement(selectedJson);
        }
        
        try {
            MTRTrainInfo trainInfo = mtrService.getNearbyTrainInfo(player);
            if (trainInfo != null) {
                return buildTrainAnnouncement(trainInfo, selectedJson);
            }
            
        } catch (Exception e) {
            System.err.println("Enhanced Announcement: Error generating train announcement: " + e.getMessage());
        }
        
        return generateBasicAnnouncement(selectedJson);
    }
    
    /**
     * Build intelligent station announcement
     */
    private static String buildIntelligentAnnouncement(MTRStationInfo stationInfo, MTRTimeInfo timeInfo, 
                                                     List<MTRScheduleInfo> schedule, MTRDelayInfo delayInfo, 
                                                     String selectedJson) {
        StringBuilder announcement = new StringBuilder();
        
        // Time and station greeting
        if (timeInfo != null && stationInfo != null) {
            announcement.append(String.format("現在時間%s，", timeInfo.getFormattedTime()));
            announcement.append(String.format("歡迎來到%s站", stationInfo.getName()));
            
            if (stationInfo.getZone() > 1) {
                announcement.append(String.format("，第%d區", stationInfo.getZone()));
            }
            announcement.append("。");
        }
        
        // Upcoming arrivals
        if (!schedule.isEmpty()) {
            announcement.append("即將到達的列車：");
            
            for (int i = 0; i < Math.min(schedule.size(), 2); i++) {
                MTRScheduleInfo entry = schedule.get(i);
                long timeUntil = entry.getTimeUntilArrival() / 1000; // Convert to seconds
                
                if (i > 0) announcement.append("，");
                
                if (timeUntil <= 60) {
                    announcement.append(String.format("%s線開往%s，約%d秒後到達",
                        entry.getRouteName(), entry.getDestination(), timeUntil));
                } else {
                    announcement.append(String.format("%s線開往%s，約%d分鐘後到達",
                        entry.getRouteName(), entry.getDestination(), timeUntil / 60));
                }
                
                if (entry.getTrainCars() > 0) {
                    announcement.append(String.format("，%d節車廂", entry.getTrainCars()));
                }
            }
            announcement.append("。");
        }
        
        // Delay information
        if (delayInfo != null && delayInfo.hasDelays()) {
            if (delayInfo.getDelayedTrains() == 1) {
                announcement.append(String.format("由於營運調整，列車服務延誤約%s。", delayInfo.getFormattedDelay()));
            } else {
                announcement.append(String.format("由於營運調整，%d班列車延誤，平均延誤%s。", 
                    delayInfo.getDelayedTrains(), delayInfo.getFormattedDelay()));
            }
        }
        
        // Safety reminder
        announcement.append("請注意月台間隙，讓路予下車乘客。");
        
        // Add custom announcement if provided
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            announcement.append(" ").append(selectedJson);
        }
        
        return announcement.toString();
    }
    
    /**
     * Build train-specific announcement
     */
    private static String buildTrainAnnouncement(MTRTrainInfo trainInfo, String selectedJson) {
        StringBuilder announcement = new StringBuilder();
        
        // Train identification
        if (trainInfo.getTrainId() != null && !trainInfo.getTrainId().isEmpty()) {
            announcement.append(String.format("歡迎乘搭%s號列車。", trainInfo.getTrainId()));
        }
        
        // Speed and status
        if (trainInfo.getSpeed() > 5) {
            announcement.append(String.format("列車現正以時速%.0f公里行駛。", trainInfo.getSpeed()));
        } else if (trainInfo.areDoorsOpen()) {
            announcement.append("列車現正停站，車門已開啟。請快速上落車。");
        }
        
        // Next station
        if (trainInfo.getNextStation() != null && !trainInfo.getNextStation().equals("Unknown")) {
            announcement.append(String.format("下一站：%s。", trainInfo.getNextStation()));
        }
        
        // Safety reminders for moving train
        if (trainInfo.getSpeed() > 10) {
            announcement.append("列車行駛期間，請握好扶手，注意安全。");
        }
        
        // Add custom announcement if provided
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            announcement.append(" ").append(selectedJson);
        }
        
        return announcement.toString();
    }
    
    /**
     * Generate basic announcement when MTR integration is not available
     */
    private static String generateBasicAnnouncement(String selectedJson) {
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            return selectedJson;
        }
        return "歡迎乘搭本系統。請注意安全，讓路予下車乘客。";
    }
    
    /**
     * Generate platform information display (PIDS style)
     */
    public static List<String> generatePlatformDisplay(World world, BlockPos pos) {
        List<String> display = new ArrayList<>();
        
        if (!mtrService.isMTRModLoaded()) {
            display.add("車站資訊系統");
            display.add("系統載入中...");
            return display;
        }
        
        try {
            MTRStationInfo stationInfo = mtrService.getStationInfo(pos);
            MTRTimeInfo timeInfo = mtrService.getCurrentTimeInfo(world);
            List<MTRScheduleInfo> schedule = mtrService.getPlatformSchedule(pos, 5);
            
            // Header
            if (stationInfo != null) {
                display.add(String.format("=== %s站 ===", stationInfo.getName()));
                if (stationInfo.getZone() > 1) {
                    display.add(String.format("第%d區", stationInfo.getZone()));
                }
            } else {
                display.add("=== 車站資訊系統 ===");
            }
            
            // Current time
            if (timeInfo != null) {
                display.add(String.format("現在時間：%s", timeInfo.getFormattedTime()));
            }
            
            display.add(""); // Empty line
            display.add("即將到達列車：");
            
            // Schedule information
            if (schedule.isEmpty()) {
                display.add("暫無列車資訊");
            } else {
                for (MTRScheduleInfo entry : schedule) {
                    String timeStr = entry.getFormattedTimeUntilArrival();
                    String routeStr = entry.getRouteName();
                    String destStr = entry.getDestination();
                    
                    if (entry.getTrainCars() > 0) {
                        display.add(String.format("%s  %s → %s  (%d節)",
                            timeStr, routeStr, destStr, entry.getTrainCars()));
                    } else {
                        display.add(String.format("%s  %s → %s",
                            timeStr, routeStr, destStr));
                    }
                }
            }
            
            // Delay information
            MTRDelayInfo delayInfo = mtrService.getDelayInfo(pos);
            if (delayInfo != null && delayInfo.hasDelays()) {
                display.add("");
                display.add(String.format("服務延誤：平均%s", delayInfo.getFormattedDelay()));
            }
            
        } catch (Exception e) {
            display.clear();
            display.add("車站資訊系統");
            display.add("系統錯誤");
            display.add("請稍後再試");
        }
        
        return display;
    }
    
    /**
     * Get contextual announcement based on time and situation
     */
    public static String getContextualAnnouncement(World world, BlockPos pos, String selectedJson) {
        if (!mtrService.isMTRModLoaded()) {
            return generateBasicAnnouncement(selectedJson);
        }
        
        MTRTimeInfo timeInfo = mtrService.getCurrentTimeInfo(world);
        if (timeInfo == null) {
            return generateStationAnnouncement(world, pos, selectedJson);
        }
        
        int hour = timeInfo.getHour();
        String timeOfDay;
        
        if (hour >= 6 && hour < 12) {
            timeOfDay = "早上";
        } else if (hour >= 12 && hour < 18) {
            timeOfDay = "下午";
        } else if (hour >= 18 && hour < 22) {
            timeOfDay = "黃昏";
        } else {
            timeOfDay = "深夜";
        }
        
        // Add time-specific greetings
        String baseAnnouncement = generateStationAnnouncement(world, pos, selectedJson);
        
        if (hour >= 22 || hour < 6) {
            // Late night service reminder
            baseAnnouncement += " 現時為深夜時段，請注意班次或有調整。";
        } else if (hour >= 7 && hour <= 9) {
            // Rush hour
            baseAnnouncement += " 現時為繁忙時間，請耐心等候。";
        } else if (hour >= 17 && hour <= 19) {
            // Evening rush hour
            baseAnnouncement += " 現時為黃昏繁忙時間，請注意安全。";
        }
        
        return baseAnnouncement;
    }
} 