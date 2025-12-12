package com.botamochi.easyannouncement.client;

import java.util.Collections;
import java.util.List;

/**
 * Immutable per-announcement context to eliminate shared static state.
 */
public final class AnnouncementContext {
	private final List<Long> platformIds;
	private final String destination;
	private final String routeType;
	private final String hh;
	private final String mm;
	private final long chosenPlatformId;
	private final long chosenRouteId;
	private final int chosenCurrentStationIndex;

	public AnnouncementContext(
			List<Long> platformIds,
			String destination,
			String routeType,
			String hh,
			String mm,
			long chosenPlatformId,
			long chosenRouteId,
			int chosenCurrentStationIndex
	) {
		this.platformIds = platformIds == null ? List.of() : Collections.unmodifiableList(platformIds);
		this.destination = destination == null ? "" : destination;
		this.routeType = routeType == null ? "" : routeType;
		this.hh = hh == null ? "00" : hh;
		this.mm = mm == null ? "00" : mm;
		this.chosenPlatformId = chosenPlatformId;
		this.chosenRouteId = chosenRouteId;
		this.chosenCurrentStationIndex = chosenCurrentStationIndex;
	}

	public List<Long> getPlatformIds() {
		return platformIds;
	}

	public String getDestination() {
		return destination;
	}

	public String getRouteType() {
		return routeType;
	}

	public String getHh() {
		return hh;
	}

	public String getMm() {
		return mm;
	}

	public long getChosenPlatformId() {
		return chosenPlatformId;
	}

	public long getChosenRouteId() {
		return chosenRouteId;
	}

	public int getChosenCurrentStationIndex() {
		return chosenCurrentStationIndex;
	}
} 