package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.Easyannouncement;
import com.botamochi.easyannouncement.screen.EAScreenHandlers;
import com.botamochi.easyannouncement.screen.MainScreenHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry;
import net.minecraft.text.Text;

public class EasyannouncementClient implements ClientModInitializer {
    
    // Client-side position storage (not persisted)
    private static int[] firstPosition = null;
    private static int[] secondPosition = null;
    
    @Override
    public void onInitializeClient() {
        BlockRenderLayerMap.INSTANCE.putBlock(Easyannouncement.EA_BLOCK, RenderLayer.getTranslucent());
        ScreenRegistry.register(EAScreenHandlers.MAIN_SCREEN_HANDLER, MainScreen::new);
        ClientNetworkHandler.register();
        AnnounceReceiveFromServer.register();
        
        // Register client commands
        registerCommands();
    }
    
    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /ea1 - Set first position (uses player current position)
            dispatcher.register(ClientCommandManager.literal("ea1")
                .executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player != null) {
                        int x = (int) client.player.getX();
                        int y = (int) client.player.getY();
                        int z = (int) client.player.getZ();
                        firstPosition = new int[]{x, y, z};
                        client.player.sendMessage(Text.literal("§aFirst position set: §f" + x + ", " + y + ", " + z), true);
                        
                        // Auto-paste if both positions are set
                        if (secondPosition != null) {
                            copyToClipboard(client, firstPosition, secondPosition);
                        }
                    }
                    return 1;
                })
            );
            
            // /ea2 - Set second position (uses player current position)
            dispatcher.register(ClientCommandManager.literal("ea2")
                .executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player != null) {
                        int x = (int) client.player.getX();
                        int y = (int) client.player.getY();
                        int z = (int) client.player.getZ();
                        secondPosition = new int[]{x, y, z};
                        client.player.sendMessage(Text.literal("§aSecond position set: §f" + x + ", " + y + ", " + z), true);
                        
                        // Auto-paste if both positions are set
                        if (firstPosition != null) {
                            copyToClipboard(client, firstPosition, secondPosition);
                        }
                    }
                    return 1;
                })
            );
            
            // /eaclip - Copy positions to clipboard
            dispatcher.register(ClientCommandManager.literal("eaclip")
                .executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (firstPosition != null && secondPosition != null) {
                        copyToClipboard(client, firstPosition, secondPosition);
                    } else if (client.player != null) {
                        client.player.sendMessage(Text.literal("§cSet both positions first with /ea1 and /ea2"), true);
                    }
                    return 1;
                })
            );
            
            // /eaclear - Clear stored positions
            dispatcher.register(ClientCommandManager.literal("eaclear")
                .executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    firstPosition = null;
                    secondPosition = null;
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("§aPositions cleared"), true);
                    }
                    return 1;
                })
            );
        });
    }
    
    private void copyToClipboard(MinecraftClient client, int[] pos1, int[] pos2) {
        if (client.player == null) return;
        
        // Calculate min/max for each axis
        int startX = Math.min(pos1[0], pos2[0]);
        int startY = Math.min(pos1[1], pos2[1]);
        int startZ = Math.min(pos1[2], pos2[2]);
        int endX = Math.max(pos1[0], pos2[0]);
        int endY = Math.max(pos1[1], pos2[1]);
        int endZ = Math.max(pos1[2], pos2[2]);
        
        // Format: startX, startY, startZ, endX, endY, endZ
        String clipboardText = startX + ", " + startY + ", " + startZ + ", " + endX + ", " + endY + ", " + endZ;
        
        // Copy to clipboard using GLFW
        long handle = client.getWindow().getHandle();
        org.lwjgl.glfw.GLFW.glfwSetClipboardString(handle, clipboardText);
        
        client.player.sendMessage(Text.literal("§aCopied to clipboard: §f" + clipboardText), true);
    }
    
    // Getters for other parts of the code if needed
    public static int[] getFirstPosition() {
        return firstPosition;
    }
    
    public static int[] getSecondPosition() {
        return secondPosition;
    }
}
