package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.tile.AnnounceTile;
import mtr.client.ClientData;
import mtr.client.ClientCache;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Station;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

public class RouteSelectionScreen extends Screen {
	private final BlockPos blockPos;
	private final Set<Integer> availableRouteColors = new HashSet<>();
	private final Map<Integer, String> routeColorToName = new HashMap<>();
	private final Set<Integer> selectedRouteColors = new HashSet<>();
	private List<Long> preselectedPlatformIds;
	private List<Platform> platformsAtStation;

	private int scrollOffset = 0;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_SPACING = 5;
	private static final int VISIBLE_BUTTONS = 6;
	private static final int BUTTON_WIDTH = 220;

	public RouteSelectionScreen(BlockPos blockPos, List<Long> preselectedPlatformIds) {
		super(Text.translatable("gui.easyannouncement.route_selection"));
		this.blockPos = blockPos;
		this.preselectedPlatformIds = preselectedPlatformIds != null ? new ArrayList<>(preselectedPlatformIds) : new ArrayList<>();
		loadStationData();
		preselectRoutesFromPlatforms();
	}

	private void loadStationData() {
		World world = MinecraftClient.getInstance().world;
		if (world == null) {
			platformsAtStation = new ArrayList<>();
			return;
		}
		Station station = RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, blockPos);
		if (station == null) {
			platformsAtStation = new ArrayList<>();
			return;
		}
		platformsAtStation = new ArrayList<>(ClientData.DATA_CACHE.requestStationIdToPlatforms(station.id).values());
		Map<Integer, ClientCache.ColorNameTuple> stationRoutes = ClientData.DATA_CACHE.stationIdToRoutes.getOrDefault(station.id, Collections.emptyMap());
		for (Map.Entry<Integer, ClientCache.ColorNameTuple> e : stationRoutes.entrySet()) {
			availableRouteColors.add(e.getValue().color);
			routeColorToName.put(e.getValue().color, e.getValue().name);
		}

		// If platforms are preselected, restrict available routes to only those serving the chosen platforms
		if (preselectedPlatformIds != null && !preselectedPlatformIds.isEmpty()) {
			Set<Integer> routesServingChosenPlatforms = new HashSet<>();
			for (long pid : preselectedPlatformIds) {
				List<ClientCache.PlatformRouteDetails> details = ClientData.DATA_CACHE.requestPlatformIdToRoutes(pid);
				for (ClientCache.PlatformRouteDetails prd : details) {
					routesServingChosenPlatforms.add(prd.routeColor);
				}
			}
			availableRouteColors.retainAll(routesServingChosenPlatforms);
		}
	}

	private void preselectRoutesFromPlatforms() {
		if (preselectedPlatformIds == null || preselectedPlatformIds.isEmpty()) return;
		for (long pid : preselectedPlatformIds) {
			List<ClientCache.PlatformRouteDetails> details = ClientData.DATA_CACHE.requestPlatformIdToRoutes(pid);
			for (ClientCache.PlatformRouteDetails prd : details) {
				selectedRouteColors.add(prd.routeColor);
			}
		}
	}

	@Override
	protected void init() {
		super.init();
		updateButtons();
	}

	private void updateButtons() {
		this.clearChildren();
		int x = this.width / 2 - BUTTON_WIDTH / 2;
		int yStart = this.height / 4;
		int yOffset = BUTTON_HEIGHT + BUTTON_SPACING;

		List<Integer> colorList = new ArrayList<>(availableRouteColors);
		colorList.sort(Comparator.comparing(c -> routeColorToName.getOrDefault(c, "")));
		for (int i = 0; i < VISIBLE_BUTTONS && i + scrollOffset < colorList.size(); i++) {
			int color = colorList.get(i + scrollOffset);
			String name = routeColorToName.getOrDefault(color, "");
			boolean isSelected = selectedRouteColors.contains(color);
			this.addDrawableChild(new ButtonWidget(x, yStart + i * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT,
					Text.of(name + (isSelected ? "  ✔" : "")), button -> {
				if (isSelected) {
					selectedRouteColors.remove(color);
				} else {
					selectedRouteColors.add(color);
				}
				updateButtons();
			}));
		}

		addScrollButtons(x, yStart, yOffset, colorList.size());

		this.addDrawableChild(new ButtonWidget(x, yStart + (VISIBLE_BUTTONS + 1) * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT, Text.translatable("gui.easyannouncement.save"), button -> {
			saveSelectionAndClose();
		}));
	}

	private void addScrollButtons(int x, int yStart, int yOffset, int count) {
		if (scrollOffset > 0) {
			this.addDrawableChild(new ButtonWidget(x, yStart - yOffset, BUTTON_WIDTH, BUTTON_HEIGHT, Text.translatable("gui.easyannouncement.scroll_up"), button -> {
				scrollOffset = Math.max(0, scrollOffset - 1);
				updateButtons();
			}));
		}
		if (scrollOffset + VISIBLE_BUTTONS < count) {
			this.addDrawableChild(new ButtonWidget(x, yStart + VISIBLE_BUTTONS * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT, Text.translatable("gui.easyannouncement.scroll_down"), button -> {
				scrollOffset = Math.min(count - VISIBLE_BUTTONS, scrollOffset + 1);
				updateButtons();
			}));
		}
	}

	private void saveSelectionAndClose() {
		World world = MinecraftClient.getInstance().world;
		if (world != null) {
			BlockEntity blockEntity = world.getBlockEntity(blockPos);
			if (blockEntity instanceof AnnounceTile announceTile) {
				Set<Long> platformIds = new HashSet<>();
				for (Platform platform : platformsAtStation) {
					List<ClientCache.PlatformRouteDetails> prds = ClientData.DATA_CACHE.requestPlatformIdToRoutes(platform.id);
					boolean servedBySelectedRoute = prds.stream().anyMatch(prd -> selectedRouteColors.contains(prd.routeColor));
					if (servedBySelectedRoute) {
						platformIds.add(platform.id);
					}
				}
				announceTile.setSelectedPlatformIds(new ArrayList<>(platformIds));
				int seconds = announceTile.getSeconds();
				var entries = announceTile.getAnnouncementEntries();
				PacketByteBuf buf = PacketByteBufs.create();
				buf.writeBlockPos(blockPos);
				buf.writeInt(seconds);
				buf.writeLongArray(platformIds.stream().mapToLong(Long::longValue).toArray());
				buf.writeInt(entries.size());
				entries.forEach(e -> { buf.writeString(e.getJsonName()); buf.writeInt(e.getDelaySeconds()); });
				buf.writeFloat(announceTile.getSoundVolume());
				buf.writeInt(announceTile.getSoundRange());
				buf.writeString(announceTile.getAttenuationType());
				buf.writeBoolean(announceTile.isBoundingBoxEnabled());
				buf.writeInt(announceTile.getStartX());
				buf.writeInt(announceTile.getStartY());
				buf.writeInt(announceTile.getStartZ());
				buf.writeInt(announceTile.getEndX());
				buf.writeInt(announceTile.getEndY());
				buf.writeInt(announceTile.getEndZ());
				ClientPlayNetworking.send(com.botamochi.easyannouncement.network.AnnounceSendToClient.ID, buf);
			}
		}
		this.client.setScreen(null);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
} 