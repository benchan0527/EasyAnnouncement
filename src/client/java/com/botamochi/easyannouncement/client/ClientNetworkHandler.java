package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.data.AnnouncementEntry;
import com.botamochi.easyannouncement.network.AnnounceSendToClient;
import com.botamochi.easyannouncement.tile.AnnounceTile;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class ClientNetworkHandler {
	public static final Identifier ID = AnnounceSendToClient.ID;

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ID, (client, handler, buf, responseSender) -> {
			BlockPos pos = buf.readBlockPos();
			int seconds = buf.readInt();
			long[] platformIds = buf.readLongArray();
			List<Long> selectedPlatforms = new ArrayList<>();
			for (long platformId : platformIds) {
				selectedPlatforms.add(platformId);
			}
			
			// Read announcement entries
			List<AnnouncementEntry> announcementEntries = new ArrayList<>();
			float volume = 2.0F;
			int range = 64;
			String attenuationType = "LINEAR";
			boolean boundingBoxEnabled = false;
			int startX = -100, startY = -64, startZ = -100, endX = 100, endY = 320, endZ = 100;
			String triggerMode = "EXACT";
			
			// Backward-compatible parsing (same as server-side)
			if (buf.readableBytes() >= 4) {
				int entryCount = buf.readInt();
				if (entryCount >= 0 && entryCount <= 100) {
					for (int i = 0; i < entryCount; i++) {
						if (buf.readableBytes() < 4) break;
						String jsonName = buf.readString();
						if (buf.readableBytes() < 4) break;
						int delaySeconds = buf.readInt();
						announcementEntries.add(new AnnouncementEntry(jsonName, delaySeconds));
					}
				} else {
					announcementEntries.clear();
				}
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
			
			// XYZ coordinates (optional in older clients)
			if (buf.readableBytes() >= 24) { // 6 ints
				startX = buf.readInt();
				startY = buf.readInt();
				startZ = buf.readInt();
				endX = buf.readInt();
				endY = buf.readInt();
				endZ = buf.readInt();
			}
			
			// Trigger mode (optional in older servers)
			if (buf.readableBytes() >= 1) {
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

			// Removed normal log: Received packet details

			client.execute(() -> {
				ClientPlayerEntity player = client.player;
				if (player != null && player.world.getBlockEntity(pos) instanceof AnnounceTile announceTile) {
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

					if (client.currentScreen instanceof MainScreen mainScreen) {
						mainScreen.updateData(seconds, selectedPlatforms, announcementEntries);
					}
				}
			});
		});
	}
}