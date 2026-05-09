package com.botamochi.easyannouncement;

import com.botamochi.easyannouncement.block.AnnounceBlock;
import com.botamochi.easyannouncement.item.EATab;
import com.botamochi.easyannouncement.network.AnnounceSendToClient;
import com.botamochi.easyannouncement.registry.EASounds;
import com.botamochi.easyannouncement.registry.EATile;
import com.botamochi.easyannouncement.screen.EAScreenHandlers;
import com.botamochi.easyannouncement.tile.AnnounceTile;
import com.botamochi.easyannouncement.world.AnnounceTilePositionsSavedData;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Easyannouncement implements ModInitializer {

    public static String MOD_ID = "easyannouncement";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Tile positions stored per-world in SavedData, keyed by world
    private static final Map<World, AnnounceTilePositionsSavedData> savedDataCache = new ConcurrentHashMap<>();

    // Block and Item
    public static Block EA_BLOCK = new AnnounceBlock(FabricBlockSettings.create().mapColor(MapColor.WHITE_GRAY).strength(6.0f, 6.0f).nonOpaque());
    public static BlockItem EA_BLOCKITEM = new BlockItem(EA_BLOCK, new Item.Settings());

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    /**
     * Register a tile position.
     */
    public static void registerAnnounceTilePosition(AnnounceTile tile) {
        World world = tile.getWorld();
        if (world == null || world.isClient()) return;
        if (!(world instanceof ServerWorld)) return;
        AnnounceTilePositionsSavedData data = savedDataCache.computeIfAbsent(world,
            w -> AnnounceTilePositionsSavedData.createAndLoad((ServerWorld) w));
        data.addPosition(tile.getPos());
    }

    public static void unregisterAnnounceTilePosition(World world, BlockPos pos) {
        if (world == null || world.isClient()) return;
        AnnounceTilePositionsSavedData data = savedDataCache.get(world);
        if (data != null) {
            data.removePosition(pos);
        }
    }

    @Override
    public void onInitialize() {
        // Item group registration
        EATab.init();

        // Block and block item registration
        Registry.register(Registries.BLOCK, id("announce_block"), EA_BLOCK);
        Registry.register(Registries.ITEM, id("announce_block"), EA_BLOCKITEM);

        // Block entity registration
        EATile.init();

        // Screen handler registration
        EAScreenHandlers.register();

        // Sound registration
        EASounds.register();

        // Register server-side packet handlers
        AnnounceSendToClient.register();
        AnnounceSendToClient.registerAnnouncementFinishedHandler();
        AnnounceSendToClient.registerPlatformSelection();
        AnnounceSendToClient.registerClientTriggerRequest();
        AnnounceSendToClient.registerMTRDataResponse();
        AnnounceSendToClient.registerAutoDetectResponse();

        // World load - pre-populate cache
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (!world.isClient()) {
                savedDataCache.computeIfAbsent(world,
                    w -> AnnounceTilePositionsSavedData.createAndLoad((ServerWorld) w));
            }
        });

        // Drive AnnounceTile ticking via global server tick events
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.isStopping()) return;
            for (ServerWorld world : server.getWorlds()) {
                if (world.isClient()) continue;
                AnnounceTilePositionsSavedData data = savedDataCache.get(world);
                if (data == null) continue;
                for (BlockPos pos : data.getPositions()) {
                    var be = world.getBlockEntity(pos);
                    if (be instanceof AnnounceTile tile) {
                        tile.tick(world, pos, world.getBlockState(pos));
                    }
                }
            }
        });

    }

    // Legacy: register without world context
    public static void registerAnnounceTilePosition(BlockPos pos) {
        // No longer functional - use registerAnnounceTilePosition(AnnounceTile) instead
    }
}
