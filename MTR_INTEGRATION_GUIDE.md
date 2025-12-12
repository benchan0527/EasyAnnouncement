# 🚄 MTR Integration Guide for EasyAnnouncement

This guide explains how to use the new MTR integration features in the EasyAnnouncement mod.

## 🌟 Features

The EasyAnnouncement mod now intelligently integrates with the MTR mod to provide:

### 📊 Real-time Information Access
- **Current Time**: Game time with 24-hour format display
- **Station Information**: Station names, zones, and exit information
- **Train Tracking**: Real-time train positions, speeds, and status
- **Schedule Data**: Upcoming arrivals with route and destination info
- **Delay Information**: Train delays and service disruptions

### 🎙️ Enhanced Announcements

#### Station Announcements
When you place an EasyAnnouncement block near an MTR station, it automatically detects:
- Station name and zone
- Upcoming train arrivals (next 2-3 trains)
- Route names and destinations
- Train car counts
- Service delays

**Example Output:**
```
現在時間14:25，歡迎來到中環站，第1區。
即將到達的列車：荃灣線開往荃灣，約2分鐘後到達，8節車廂，
東涌線開往東涌，約5分鐘後到達，8節車廂。
請注意月台間隙，讓路予下車乘客。
```

#### Train Announcements
When players are on/near trains, the system provides:
- Train identification
- Current speed
- Door status
- Next station information
- Safety reminders

**Example Output:**
```
歡迎乘搭001號列車。列車現正以時速80公里行駛。
下一站：銅鑼灣。列車行駛期間，請握好扶手，注意安全。
```

#### Contextual Announcements
The system adapts announcements based on:
- **Time of Day**: Different greetings for morning/afternoon/evening/night
- **Rush Hours**: Special messages during peak times (7-9am, 5-7pm)
- **Late Night**: Service adjustment notices after 10pm

## 🛠️ How to Use

### Basic Setup

1. **Install Dependencies**:
   - EasyAnnouncement mod (this mod)
   - MTR mod (for train data)

2. **Place Announcement Block**:
   - Place the Station Announcement Block near an MTR station or platform
   - The block will automatically detect nearby MTR infrastructure

3. **Configure Announcements**:
   - Right-click the block to open the configuration GUI
   - Select platforms/routes as usual
   - The system will automatically enhance announcements with MTR data

### Advanced Features

#### Platform Information Display System (PIDS)
The integration includes a PIDS-style information display:

```
=== 中環站 ===
第1區
現在時間：14:25

即將到達列車：
2分鐘  荃灣線 → 荃灣  (8節)
5分鐘  東涌線 → 東涌  (8節)
7分鐘  港島線 → 柴灣  (8節)

服務延誤：平均30秒
```

#### Multilingual Support
The integration supports all languages:
- **English**: Full MTR integration with localized announcements
- **繁體中文 (香港)**: Native Hong Kong MTR-style announcements  
- **日本語**: Japanese railway-style announcements

### Code Integration

#### Using the MTR Integration Service

```java
// Get MTR integration instance
MTRIntegrationService mtr = MTRIntegrationService.getInstance();

// Check if MTR mod is available
if (mtr.isMTRModLoaded()) {
    // Get station information
    MTRStationInfo station = mtr.getStationInfo(blockPos);
    
    // Get real-time train data
    MTRTrainInfo train = mtr.getNearbyTrainInfo(player);
    
    // Get platform schedule
    List<MTRScheduleInfo> schedule = mtr.getPlatformSchedule(blockPos, 5);
    
    // Get delay information
    MTRDelayInfo delays = mtr.getDelayInfo(blockPos);
}
```

#### Using the Enhanced Announcement Helper

```java
// Generate intelligent station announcement
String announcement = EnhancedAnnouncementHelper.generateStationAnnouncement(
    world, blockPos, customMessage);

// Generate train-specific announcement  
String trainAnnouncement = EnhancedAnnouncementHelper.generateTrainAnnouncement(
    player, customMessage);

// Generate contextual announcement based on time
String contextualAnnouncement = EnhancedAnnouncementHelper.getContextualAnnouncement(
    world, blockPos, customMessage);

// Generate PIDS display
List<String> display = EnhancedAnnouncementHelper.generatePlatformDisplay(
    world, blockPos);
```

## 🔧 Configuration

### Automatic Detection
The system automatically detects:
- MTR mod presence
- Station boundaries
- Platform locations
- Train positions
- Route information

### Fallback Mode
When MTR mod is not available or no MTR data is found:
- Falls back to basic announcements
- Still supports custom JSON messages
- Maintains full functionality

## 🚀 Technical Details

### Architecture
- **MTRIntegrationService**: Core service using reflection to access MTR data
- **EnhancedAnnouncementHelper**: High-level announcement generation
- **Localization System**: Full i18n support with context-aware translations

### Performance
- **Lazy Loading**: MTR integration only activates when needed
- **Caching**: Station and route data cached for performance
- **Error Handling**: Graceful fallback when MTR data unavailable

### Compatibility
- **Fabric/Forge**: Supports both mod loaders
- **Minecraft Versions**: Compatible with MTR mod versions
- **Reflection-based**: No hard dependency on MTR mod

## 🎯 Real-world Applications

### Hong Kong MTR Style
Perfect for recreating authentic Hong Kong MTR stations:
- Bilingual announcements (Chinese/English)
- Zone-based information
- Authentic announcement patterns
- Real-time arrival information

### Other Railway Systems
Adaptable for other railway networks:
- JR East/West style (Japan)
- London Underground style (UK)
- NYC Subway style (USA)
- Custom railway networks

## 🐛 Troubleshooting

### MTR Integration Not Working
1. Ensure MTR mod is installed and loaded
2. Check that announcement block is near MTR infrastructure
3. Verify station/platform boundaries are set up correctly
4. Check console for integration error messages

### Announcements Not Updating
1. Ensure trains are running with proper schedules
2. Check that depot settings are configured
3. Verify route and platform connections

### Performance Issues
1. Reduce number of announcement blocks
2. Increase announcement intervals
3. Check for conflicting mods

## 📝 Future Enhancements

Planned features for future versions:
- **Sound Integration**: Custom announcement sounds
- **Display Blocks**: Visual PIDS displays
- **Emergency Announcements**: Special announcements for delays/disruptions
- **Custom Route Mapping**: Advanced route configuration
- **API Extensions**: More detailed MTR data access

## 🤝 Contributing

To contribute to MTR integration:
1. Test with different MTR setups
2. Report bugs and compatibility issues
3. Suggest feature improvements
4. Submit localization improvements

---

**Note**: This integration requires the MTR mod to be installed for full functionality. Without MTR mod, the system falls back to basic announcement features. 