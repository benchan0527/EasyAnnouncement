package com.botamochi.easyannouncement.registry;

import com.botamochi.easyannouncement.Easyannouncement;
import com.botamochi.easyannouncement.tile.AnnounceTile;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class EATile {

    public static final BlockEntityType<AnnounceTile> EA_BLOCK_TILE = FabricBlockEntityTypeBuilder.create(
            AnnounceTile::new, Easyannouncement.EA_BLOCK
    ).build();

    public static void init() {
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Easyannouncement.id("announce_tile"), EA_BLOCK_TILE);
    }
}
