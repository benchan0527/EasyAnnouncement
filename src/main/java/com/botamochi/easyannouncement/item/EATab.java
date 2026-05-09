package com.botamochi.easyannouncement.item;

import com.botamochi.easyannouncement.Easyannouncement;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;

public class EATab {

    public static final RegistryKey<ItemGroup> EA_KEY = RegistryKey.of(Registries.ITEM_GROUP.getKey(), Easyannouncement.id("ea_tab"));
    public static ItemGroup EA;

    public static void init() {
        EA = Registry.register(Registries.ITEM_GROUP, EA_KEY,
            FabricItemGroup.builder()
                .displayName(Text.literal("EasyAnnouncement"))
                .icon(() -> new ItemStack(Easyannouncement.EA_BLOCKITEM)).build());
    }
}
