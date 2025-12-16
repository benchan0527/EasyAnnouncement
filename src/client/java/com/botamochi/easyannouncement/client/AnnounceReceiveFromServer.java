package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.Easyannouncement;
import com.botamochi.easyannouncement.data.AnnouncementEntry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.client.ClientCache;
import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.Route;
import mtr.data.ScheduleEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.resource.Resource;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AnnounceReceiveFromServer {
    public static final Identifier ID = new Identifier(Easyannouncement.MOD_ID, "announce_update");
    public static final Identifier ANNOUNCE_START_ID = new Identifier(Easyannouncement.MOD_ID, "announce_start");
    
    private static final long MIN_ANNOUNCE_INTERVAL = 500; // 例: 500 ミリ秒間隔
    // 使用 ConcurrentHashMap 以確保線程安全
    private static final Map<BlockPos, Long> lastAnnounceTime = new ConcurrentHashMap<>();

    

    public static void register() {
		// Removed announce_update receiver; handled by ClientNetworkHandler to use new entry-list protocol
		ClientPlayNetworking.registerGlobalReceiver(ANNOUNCE_START_ID, (client, handler, buf, responseSender) -> {
			try {
				final BlockPos pos = buf.readBlockPos();
				long[] platformIds = buf.readLongArray();
				final List<Long> selectedPlatforms = new ArrayList<>();
				for (long platformId : platformIds) {
					selectedPlatforms.add(platformId);
				}
				
				// Check buffer readability to detect format issues
				int remainingBytes = buf.readableBytes();
				
				List<AnnouncementEntry> announcementEntries = new ArrayList<>();
				String destinationLocal = "unknown";
				String routeTypeLocal = "unknown";
                String hhLocal = "00";
                String mmLocal = "00";
				// Local chosen identifiers for this packet
				long chosenPlatformIdLocal = -1L;
				long chosenRouteIdLocal = -1L;
				int chosenCurrentStationIndexLocal = -1;
				
				// Try new format first
				if (remainingBytes >= 4) { // At least an int for entry count
					int entryCount = buf.readInt();
					
					if (entryCount >= 0 && entryCount <= 100) { // Sanity check
						// Read announcement entries
						for (int i = 0; i < entryCount; i++) {
							if (buf.readableBytes() <= 0) { // No more data
								System.err.println("Not enough buffer for entry " + i);
								break;
							}
							String jsonName = buf.readString();
							if (buf.readableBytes() <= 0) { // Not enough for delay int (conservative)
								System.err.println("Not enough buffer for delay for entry " + i);
								break;
							}
							int delaySeconds = buf.readInt();
							announcementEntries.add(new AnnouncementEntry(jsonName, delaySeconds));
						}
						
						// Read destination and route type if buffer has enough data
						if (buf.readableBytes() > 0) {
							destinationLocal = buf.readString();
						}
						if (buf.readableBytes() > 0) {
							routeTypeLocal = buf.readString();
						}
                        if (buf.readableBytes() > 0) {
                            hhLocal = buf.readString();
                        }
                        if (buf.readableBytes() > 0) {
                            mmLocal = buf.readString();
                        }
                        						// New chosen identifiers (optional)
						if (buf.readableBytes() >= Long.BYTES) {
							chosenPlatformIdLocal = buf.readLong();
						}
						if (buf.readableBytes() >= Long.BYTES) {
							chosenRouteIdLocal = buf.readLong();
						}
						if (buf.readableBytes() >= Integer.BYTES) {
							chosenCurrentStationIndexLocal = buf.readInt();
						}
					} else {
						System.err.println("Invalid entry count: " + entryCount + ", trying legacy format (not resetting buffer)");
						
						// Legacy format: selectedJson, destination, routeType  
						// Don't reset buffer, just continue reading from current position
						if (buf.readableBytes() > 0) {
							String selectedJson = buf.readString();
							if (!selectedJson.trim().isEmpty()) {
								announcementEntries.add(new AnnouncementEntry(selectedJson, 0));
							}
						}
						if (buf.readableBytes() > 0) {
							destinationLocal = buf.readString();
						}
						if (buf.readableBytes() > 0) {
							routeTypeLocal = buf.readString();
						}
                        						// Legacy: no chosen identifiers
						chosenPlatformIdLocal = -1L;
						chosenRouteIdLocal = -1L;
						chosenCurrentStationIndexLocal = -1;
					}
				}
				
				// Build immutable context snapshot for this announcement
                				String routeTypeResolved = routeTypeLocal;
				if (routeTypeResolved == null || routeTypeResolved.isEmpty() || "unknown".equalsIgnoreCase(routeTypeResolved) || "route_type_unknown".equalsIgnoreCase(routeTypeResolved)) {
					String derived = deriveRouteTypeFromClientData(chosenRouteIdLocal, selectedPlatforms);
					if (derived != null) {
						routeTypeResolved = derived;
					}
				}
                				final AnnouncementContext context = new AnnouncementContext(
						selectedPlatforms,
						destinationLocal,
						routeTypeResolved,
						hhLocal,
						mmLocal,
						chosenPlatformIdLocal,
						chosenRouteIdLocal,
						chosenCurrentStationIndexLocal
				);
				final List<AnnouncementEntry> finalEntries = announcementEntries;
				client.execute(() -> {
					long currentTime = System.currentTimeMillis();
					if (!lastAnnounceTime.containsKey(pos) || currentTime - lastAnnounceTime.get(pos) > MIN_ANNOUNCE_INTERVAL) {
						lastAnnounceTime.put(pos, currentTime);
						playMultiJsonAnnouncements(client, pos, finalEntries, context);
					} else {
						System.err.println("[EasyAnnouncement] Skipping duplicate announcement at: " + pos);
					}
				});
			} catch (Exception e) {
				System.err.println("Error reading announce start packet: " + e.getMessage());
				e.printStackTrace();
			}
		});
	}

    

    // Client-side fallback: mimic server getRouteType logic
    	private static String deriveRouteTypeFromClientData(long chosenRouteId, List<Long> platforms) {
		try {
			// Prefer chosen route ID if provided
			if (chosenRouteId != -1L) {
				Route route = ClientData.DATA_CACHE.routeIdMap.get(chosenRouteId);
				String val = deriveFromRoute(route);
				if (val != null) return val;
			}
			// Otherwise, scan all selected platforms for the earliest upcoming schedule like getRouteName
			if (platforms != null && !platforms.isEmpty()) {
				final List<ScheduleEntry> all = new ArrayList<>();
				final long now = System.currentTimeMillis();
				for (long pid : platforms) {
					final Set<ScheduleEntry> schedules = ClientData.SCHEDULES_FOR_PLATFORM.get(pid);
					if (schedules != null) {
						for (ScheduleEntry se : schedules) {
							if (se.arrivalMillis >= now) {
								final Route r = ClientData.DATA_CACHE.routeIdMap.get(se.routeId);
								if (r != null && se.currentStationIndex < r.platformIds.size() - 1) {
									all.add(se);
								}
							}
						}
					}
				}
				if (!all.isEmpty()) {
					Collections.sort(all);
					final ScheduleEntry next = all.get(0);
					final Route route = ClientData.DATA_CACHE.routeIdMap.get(next.routeId);
					String val = deriveFromRoute(route);
					if (val != null) return val;
				}
			}
		} catch (Exception e) {
			System.err.println("deriveRouteTypeFromClientData failed: " + e.getMessage());
		}
		return null;
	}

    private static String deriveFromRoute(Route route) {
        if (route == null) return null;
        // 1) Prefer explicit light rail route number if present (old behavior)
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
        // 2) High speed explicit type
        if (route.routeType == mtr.data.RouteType.HIGH_SPEED) {
            return "high_speed";
        }
        // 3) If marked as light rail but number missing, return generic light_rail
        try {
            java.lang.reflect.Field f = route.getClass().getDeclaredField("isLightRailRoute");
            f.setAccessible(true);
            Object val = f.get(route);
            if (val instanceof Boolean && ((Boolean) val)) {
                return "light_rail";
            }
        } catch (Exception ignore) {
            // ignore, field may not exist in some builds
        }
        return null;
    }

    private static List<SoundData> loadAnnouncementSequence(String selectedJson, AnnouncementContext context) {
        
        List<SoundData> soundDataList = new ArrayList<>();
        // Support namespaced IDs in selectedJson (e.g., othermod:foo). If no namespace, default to easyannouncement
        String trimmed = selectedJson == null ? "" : selectedJson.trim();
        Identifier jsonId;
        String jsonNamespace; // Track the namespace for sound path inheritance
        
        if (trimmed.contains(":")) {
            // Provided as namespace:path (without folder/extension). Prepend sounds/ and append .json
            String[] parts = trimmed.split(":", 2);
            jsonNamespace = parts[0];
            jsonId = new Identifier(parts[0], "sounds/" + parts[1] + ".json");
        } else {
            jsonNamespace = "easyannouncement";
            jsonId = new Identifier("easyannouncement", "sounds/" + trimmed + ".json");
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        Optional<Resource> resourceOptional = client.getResourceManager().getResource(jsonId);

        if (resourceOptional.isPresent()) {
            try (InputStream inputStream = resourceOptional.get().getInputStream()) {
                Gson gson = new Gson();
                JsonObject jsonObject = gson.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), JsonObject.class);
                
                if (!jsonObject.has("sounds")) {
                    System.err.println("JSON file does not contain 'sounds' array: " + jsonId);
                    return soundDataList;
                }
                
                JsonArray announcementArray = jsonObject.getAsJsonArray("sounds");
                if (announcementArray == null || announcementArray.size() == 0) {
                    System.err.println("JSON file has empty or null 'sounds' array: " + jsonId);
                    return soundDataList;
                }
                
                for (int i = 0; i < announcementArray.size(); i++) {
                    try {
                        JsonObject announcementObject = announcementArray.get(i).getAsJsonObject();
                        if (!announcementObject.has("soundPath")) {
                            System.err.println("Sound object at index " + i + " missing 'soundPath' field");
                            continue;
                        }
                        
                        String rawSoundPath = announcementObject.get("soundPath").getAsString();
                        // If soundPath doesn't have namespace, inherit from JSON file's namespace
                        if (!rawSoundPath.contains(":")) {
                            rawSoundPath = jsonNamespace + ":" + rawSoundPath;
                        }
                        double duration = announcementObject.has("duration") ? announcementObject.get("duration").getAsDouble() : 0.0;
                        List<String> formattedSoundPaths = getFormattedAnnouncement(rawSoundPath, context);
                        for (String formattedSoundPath : formattedSoundPaths) {
                            soundDataList.add(new SoundData(formattedSoundPath, duration));
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing sound object at index " + i + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to load announcement sequence JSON: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // Fallback: treat selectedJson itself as a single sound event id
            if (trimmed.isEmpty()) {
                return soundDataList;
            }
            
            Identifier soundId;
            if (trimmed.contains(":")) {
                soundId = Identifier.tryParse(trimmed);
                if (soundId == null) {
                    System.err.println("[EasyAnnouncement] Invalid sound ID: " + trimmed);
                    return soundDataList;
                }
            } else {
                soundId = new Identifier(Easyannouncement.MOD_ID, trimmed);
            }
            
            // Try to get or create sound event
            SoundEvent soundEvent = Registry.SOUND_EVENT.getOrEmpty(soundId).orElse(null);
            if (soundEvent == null) {
                try {
                    soundEvent = new SoundEvent(soundId);
                } catch (Exception e) {
                    System.err.println("[EasyAnnouncement] Failed to create sound event: " + soundId);
                    return soundDataList;
                }
            }
            soundDataList.add(new SoundData(soundId.toString(), 0.0));
        }
        return soundDataList;
    }

    private static List<String> getFormattedAnnouncement(String soundPath, AnnouncementContext context) {
        List<String> formattedSoundPaths = new ArrayList<>();
        String platformName = getPlatformName(context);
        String routeName = getRouteName(context);
        String currentStationName = getCurrentStationName(context);
        String nextStationName = getNextStationName(context);

        String formattedSoundPath = soundPath
                .replace("($track)", platformName == null ? "" : platformName)
                .replace("($boundfor)", context.getDestination())
                .replace("($routetype)", cleanRouteText(context.getRouteType()))
                .replace("($route)", routeName == null ? "" : cleanRouteText(routeName))
                .replace("($hh)", context.getHh())
                .replace("($mm)", context.getMm())
                .replace("($currentstation)", currentStationName == null ? "" : currentStationName)
                .replace("($nextstation)", nextStationName == null ? "" : nextStationName);
        
        // Sanitize: remove spaces and the characters < > ? , . ( )
        // Preserve namespace separator ':' if present at the start of the identifier
        formattedSoundPath = formattedSoundPath.replaceAll("[ <>?,.()]", "");
        
        // If namespaced, ensure the ':' remains (it will unless it was surrounded by filtered chars)
        if (formattedSoundPath != null && !formattedSoundPath.isEmpty()) {
            formattedSoundPaths.add(formattedSoundPath);
        }
        return formattedSoundPaths;
    }

    private static void playAnnouncementSounds(MinecraftClient client, BlockPos pos, List<SoundData> soundDataList, float volume, SoundInstance.AttenuationType attenuationType) {
        if (soundDataList == null || soundDataList.isEmpty()) {
            return;
        }
        SoundManager soundManager = client.getSoundManager();
        Random random = Random.create();

        Thread playbackThread = new Thread(() -> {
            try {
                for (SoundData soundData : soundDataList) {
                    // 檢查是否被中斷
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    
                    String finalSoundPath = soundData.soundPath;
                    
                    // Parse sound ID
                    Identifier soundId;
                    if (finalSoundPath.contains(":")) {
                        soundId = Identifier.tryParse(finalSoundPath);
                        if (soundId == null) {
                            System.err.println("[EasyAnnouncement] Invalid sound ID: " + finalSoundPath);
                            continue;
                        }
                    } else {
                        soundId = new Identifier(Easyannouncement.MOD_ID, finalSoundPath);
                    }
                    
                    // Get or create sound event
                    SoundEvent soundEvent = Registry.SOUND_EVENT.getOrEmpty(soundId).orElse(null);
                    if (soundEvent == null) {
                        try {
                            soundEvent = new SoundEvent(soundId);
                        } catch (Exception e) {
                            System.err.println("[EasyAnnouncement] Failed to create sound event: " + soundId);
                            continue;
                        }
                    }
                    
                    // Create and play sound instance
                    PositionedSoundInstance instance = new PositionedSoundInstance(
                        soundEvent.getId(), 
                        net.minecraft.sound.SoundCategory.MASTER, 
                        volume, 1.0F, random, false, 0, 
                        attenuationType, 
                        pos.getX(), pos.getY(), pos.getZ(), 
                        false
                    );

                    boolean playSuccess = false;
                    try {
                        client.submit(() -> {
                            try {
                                soundManager.play(instance);
                            } catch (Exception e) {
                                System.err.println("[EasyAnnouncement] Playback failed: " + e.getMessage());
                            }
                        }).get(); // 等待提交完成
                        playSuccess = true;
                    } catch (Exception e) {
                        System.err.println("[EasyAnnouncement] Failed to submit sound playback: " + e.getMessage());
                    }

                    if (!playSuccess) {
                        continue;
                    }

                    // Wait for sound to start (最多等待 200ms)
                    long registerStart = System.currentTimeMillis();
                    boolean soundStarted = false;
                    while (!soundManager.isPlaying(instance) && System.currentTimeMillis() - registerStart < 200) {
                        Thread.sleep(10);
                    }
                    soundStarted = soundManager.isPlaying(instance);
                    
                    if (!soundStarted) {
                        System.err.println("[EasyAnnouncement] Sound failed to start: " + soundId);
                        continue;
                    }

                    // 等待聲音實際播放完成
                    long playStart = System.currentTimeMillis();
                    int safetyMillis = 30000; // 30秒安全超時
                    
                    while (soundManager.isPlaying(instance)) {
                        Thread.sleep(50);
                        if (System.currentTimeMillis() - playStart > safetyMillis) {
                            System.err.println("[EasyAnnouncement] Sound playback timeout: " + soundId);
                            break;
                        }
                        if (Thread.currentThread().isInterrupted()) {
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[EasyAnnouncement] Unexpected error in sound playback: " + e.getMessage());
                e.printStackTrace();
            }
        }, "EA-SeqSoundPlayer");
        playbackThread.setDaemon(true); // 設為 daemon 線程，不會阻止 JVM 關閉
        playbackThread.start();
    }
    
    	private static void playMultiJsonAnnouncements(MinecraftClient client, BlockPos pos, List<AnnouncementEntry> announcementEntries, AnnouncementContext context) {
        
        // Enable bounding box check for area-limited announcements
        // Get bounding box setting from the tile entity
        boolean ENABLE_BOUNDING_BOX_CHECK = false;
        if (client.player != null && client.world != null && client.world.getBlockEntity(pos) instanceof com.botamochi.easyannouncement.tile.AnnounceTile announceTile) {
            ENABLE_BOUNDING_BOX_CHECK = announceTile.isBoundingBoxEnabled();
        }
        
        // Check if player is within bounding box before playing sound
        if (ENABLE_BOUNDING_BOX_CHECK && client.player != null && client.world != null && client.world.getBlockEntity(pos) instanceof com.botamochi.easyannouncement.tile.AnnounceTile announceTile) {
            // Get player position
            double playerX = client.player.getX();
            double playerY = client.player.getY();
            double playerZ = client.player.getZ();
            
            // Removed bounding box verbose logs
            
            // Check if player is within the announcement bounding box
            int minX = Math.min(announceTile.getStartX(), announceTile.getEndX());
            int maxX = Math.max(announceTile.getStartX(), announceTile.getEndX());
            int minY = Math.min(announceTile.getStartY(), announceTile.getEndY());
            int maxY = Math.max(announceTile.getStartY(), announceTile.getEndY());
            int minZ = Math.min(announceTile.getStartZ(), announceTile.getEndZ());
            int maxZ = Math.max(announceTile.getStartZ(), announceTile.getEndZ());

            if (playerX < minX || playerX > maxX ||
                playerY < minY || playerY > maxY ||
                playerZ < minZ || playerZ > maxZ) {
                return; // Player is outside the bounding box, don't play sound
            }
        }
        
		// Get sound settings from the AnnounceTile
		float volume = 2.0F; // Default
		int range = 64; // Default sound range in blocks
		SoundInstance.AttenuationType attenuationType = SoundInstance.AttenuationType.LINEAR; // Default
        
		if (client.world != null && client.world.getBlockEntity(pos) instanceof com.botamochi.easyannouncement.tile.AnnounceTile announceTile) {
            volume = announceTile.getSoundVolume();
			range = announceTile.getSoundRange();
            String attenuationTypeString = announceTile.getAttenuationType();
            try {
                attenuationType = SoundInstance.AttenuationType.valueOf(attenuationTypeString);
            } catch (IllegalArgumentException e) {
                attenuationType = SoundInstance.AttenuationType.LINEAR; // Default fallback
            }
        }

		// Distance check: if player is farther than range, skip playback
		if (client.player != null) {
			double centerX = pos.getX() + 0.5D;
			double centerY = pos.getY() + 0.5D;
			double centerZ = pos.getZ() + 0.5D;
			double distSq = client.player.squaredDistanceTo(centerX, centerY, centerZ);
			if (distSq > (double) range * (double) range) {
				return;
			}
		}
        
		final float finalVolume = volume;
		final SoundInstance.AttenuationType finalAttenuationType = attenuationType;
        
        		// Schedule announcements using absolute time from broadcast start
		Thread schedulerThread = new Thread(() -> {
			try {
				long startTime = System.currentTimeMillis();
				
				for (int i = 0; i < announcementEntries.size(); i++) {
					// 檢查是否被中斷
					if (Thread.currentThread().isInterrupted()) {
						break;
					}
					
					AnnouncementEntry entry = announcementEntries.get(i);
					if (entry.isEmpty()) continue;
					
					// Calculate absolute target time for this entry
					long targetTime = startTime + (entry.getDelaySeconds() * 1000L);
					long currentTime = System.currentTimeMillis();
					long waitTime = targetTime - currentTime;
					
					if (waitTime > 0) {
						// 分段等待，以便能夠響應中斷
						long remainingWait = waitTime;
						while (remainingWait > 0 && !Thread.currentThread().isInterrupted()) {
							long sleepTime = Math.min(remainingWait, 100); // 每次最多等待 100ms
							Thread.sleep(sleepTime);
							remainingWait = targetTime - System.currentTimeMillis();
						}
					} else if (waitTime < -1000) {
						System.err.println("[EasyAnnouncement] Warning: Entry " + (i + 1) + " is " + 
						                   String.format("%.1f", Math.abs(waitTime) / 1000.0) + "s behind schedule");
					}
					
					// 再次檢查中斷狀態
					if (Thread.currentThread().isInterrupted()) {
						break;
					}
					
					List<SoundData> soundDataList = loadAnnouncementSequence(entry.getJsonName(), context);
					
					// 如果載入失敗，跳過這個 entry
					if (soundDataList == null || soundDataList.isEmpty()) {
						System.err.println("[EasyAnnouncement] Warning: Entry " + (i + 1) + " (" + entry.getJsonName() + ") has no sounds, skipping");
						continue;
					}
					
					// Play in a separate thread so it doesn't block the scheduler
					final List<SoundData> finalSoundDataList = new ArrayList<>(soundDataList);
					Thread entryThread = new Thread(() -> {
						playAnnouncementSounds(client, pos, finalSoundDataList, finalVolume, finalAttenuationType);
					}, "EA-Entry-" + (i + 1));
					entryThread.setDaemon(true); // 設為 daemon 線程
					entryThread.start();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				System.err.println("[EasyAnnouncement] Unexpected error in announcement scheduler: " + e.getMessage());
				e.printStackTrace();
			}
		}, "EA-MultiJsonScheduler");
		schedulerThread.setDaemon(true); // 設為 daemon 線程
		schedulerThread.start();
    }

    	private static String getPlatformName(AnnouncementContext context) {
        		if (context.getChosenPlatformId() != -1L) {
			Platform platform = ClientData.DATA_CACHE.platformIdMap.get(context.getChosenPlatformId());
			return platform != null ? cleanMtrText(platform.name) : "platform_name_unknown";
		}
        		List<Long> selectedPlatforms = context.getPlatformIds();
		if (selectedPlatforms.isEmpty()) {
			return "platform_name_not_found";
		}
		long platformId = selectedPlatforms.get(0);
        Platform platform = ClientData.DATA_CACHE.platformIdMap.get(platformId);
        return platform != null ? cleanMtrText(platform.name) : "platform_name_unknown";
    }

    	private static String getRouteName(AnnouncementContext context) {
        		if (context.getChosenRouteId() != -1L) {
			Route route = ClientData.DATA_CACHE.routeIdMap.get(context.getChosenRouteId());
			if (route == null || route.name == null || route.name.isEmpty()) {
				return "route_name_unknown";
			}
			final String routeName = route.name;
            int doublePipeIndex = routeName.indexOf("||");
            if (doublePipeIndex > 0) {
                return cleanRouteText(routeName.substring(0, doublePipeIndex));
            }
            return cleanRouteText(routeName);
        }
        		List<Long> selectedPlatforms = context.getPlatformIds();
		if (selectedPlatforms.isEmpty()) {
			return "route_name_not_found";
		}
		// Fallback to old multi-platform logic
        final List<ScheduleEntry> all = new ArrayList<>();
        final long now = System.currentTimeMillis();
        for (long platformId : selectedPlatforms) {
            final Set<ScheduleEntry> schedules = ClientData.SCHEDULES_FOR_PLATFORM.get(platformId);
            if (schedules != null) {
                for (ScheduleEntry se : schedules) {
                    if (se.arrivalMillis >= now) {
                        final Route r = ClientData.DATA_CACHE.routeIdMap.get(se.routeId);
                        if (r != null && se.currentStationIndex < r.platformIds.size() - 1) {
                            all.add(se);
                        }
                    }
                }
            }
        }
        if (all.isEmpty()) {
            return "route_name_unknown";
        }
        Collections.sort(all);
        final ScheduleEntry next = all.get(0);
        final Route route = ClientData.DATA_CACHE.routeIdMap.get(next.routeId);
        if (route == null || route.name == null || route.name.isEmpty()) {
            return "route_name_unknown";
        }
        final String routeName = route.name;
        int doublePipeIndex = routeName.indexOf("||");
        if (doublePipeIndex > 0) {
            return cleanRouteText(routeName.substring(0, doublePipeIndex));
        }
        return cleanRouteText(routeName);
    }

    	private static String getCurrentStationName(AnnouncementContext context) {
        		if (context.getChosenPlatformId() != -1L) {
			if (ClientData.DATA_CACHE.platformIdToStation.get(context.getChosenPlatformId()) == null) {
				return "station_name_unknown";
			}
			String rawName = ClientData.DATA_CACHE.platformIdToStation.get(context.getChosenPlatformId()).name;
            if (rawName == null || rawName.isEmpty()) {
                return "station_name_unknown";
            }
            int lastPipeIndex = rawName.lastIndexOf('|');
            String englishOnly = lastPipeIndex != -1 && lastPipeIndex < rawName.length() - 1
                    ? rawName.substring(lastPipeIndex + 1)
                    : rawName;
            return cleanMtrText(englishOnly);
        }
        		List<Long> selectedPlatforms = context.getPlatformIds();
		if (selectedPlatforms == null || selectedPlatforms.isEmpty()) {
			return "station_name_not_found";
		}
		long platformId = selectedPlatforms.get(0);
        if (ClientData.DATA_CACHE.platformIdToStation.get(platformId) == null) {
            return "station_name_unknown";
        }
        String rawName = ClientData.DATA_CACHE.platformIdToStation.get(platformId).name;
        if (rawName == null || rawName.isEmpty()) {
            return "station_name_unknown";
        }
        int lastPipeIndex = rawName.lastIndexOf('|');
        String englishOnly = lastPipeIndex != -1 && lastPipeIndex < rawName.length() - 1
                ? rawName.substring(lastPipeIndex + 1)
                : rawName;
        return cleanMtrText(englishOnly);
    }

    	private static String getNextStationName(AnnouncementContext context) {
        		if (context.getChosenRouteId() != -1L && context.getChosenCurrentStationIndex() != -1) {
			Route route = ClientData.DATA_CACHE.routeIdMap.get(context.getChosenRouteId());
			if (route != null && context.getChosenCurrentStationIndex() + 1 < route.platformIds.size()) {
				long nextPlatformId = route.platformIds.get(context.getChosenCurrentStationIndex() + 1).platformId;
                if (ClientData.DATA_CACHE.platformIdToStation.get(nextPlatformId) != null) {
                    String rawName = ClientData.DATA_CACHE.platformIdToStation.get(nextPlatformId).name;
                    int lastPipeIndex = rawName.lastIndexOf('|');
                    String englishOnly = lastPipeIndex != -1 && lastPipeIndex < rawName.length() - 1
                            ? rawName.substring(lastPipeIndex + 1)
                            : rawName;
                    return cleanMtrText(englishOnly);
                }
            }
        }
        		List<Long> selectedPlatforms = context.getPlatformIds();
		if (selectedPlatforms == null || selectedPlatforms.isEmpty()) {
			return "next_station_name_not_found";
		}
		long platformId = selectedPlatforms.get(0);
        List<ClientCache.PlatformRouteDetails> routeDetails = ClientData.DATA_CACHE.requestPlatformIdToRoutes(platformId);
        if (routeDetails == null || routeDetails.isEmpty()) {
            return "next_station_name_unknown";
        }
        ClientCache.PlatformRouteDetails details = routeDetails.get(0);
        int nextIndex = details.currentStationIndex + 1;
        if (details.stationDetails == null || nextIndex < 0 || nextIndex >= details.stationDetails.size()) {
            return "next_station_name_unknown";
        }
        String nextStationName = details.stationDetails.get(nextIndex).stationName;
        if (nextStationName == null || nextStationName.isEmpty()) {
            return "next_station_name_unknown";
        }
        int lastPipeIndex = nextStationName.lastIndexOf('|');
        String englishOnly = lastPipeIndex != -1 && lastPipeIndex < nextStationName.length() - 1
                ? nextStationName.substring(lastPipeIndex + 1)
                : nextStationName;
        return cleanMtrText(englishOnly);
    }

    /**
     * Cleans MTR text by removing parentheses, periods, commas, and spaces,
     * then converts to lowercase
     */
    private static String cleanMtrText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // Remove spaces and the characters , . ( ) & then convert to lowercase
        return text.replaceAll("[(),.& ]", "").toLowerCase();
    }

    // Route-specific cleaner: keep underscores by mapping spaces/hyphens to underscores,
    // strip other non [a-z0-9_], and lowercase.
    private static String cleanRouteText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Delete spaces and , . ( ) & first, then apply existing normalization
        String removed = text.replaceAll("[(),.& ]", "");
        String lowered = removed.toLowerCase(java.util.Locale.ENGLISH);
        String underscored = lowered.replaceAll("[\\s-]+", "_");
        String filtered = underscored.replaceAll("[^a-z0-9_/]", "");
        return filtered.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
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