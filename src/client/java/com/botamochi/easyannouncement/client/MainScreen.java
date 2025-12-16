package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.data.AnnouncementEntry;
import com.botamochi.easyannouncement.network.AnnounceSendToClient;
import com.botamochi.easyannouncement.screen.MainScreenHandler;
import com.botamochi.easyannouncement.tile.AnnounceTile;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ScreenHandlerProvider;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.DataFlavor;
import java.util.ArrayList;
import java.util.List;

public class MainScreen extends Screen implements ScreenHandlerProvider<MainScreenHandler> {
    private final MainScreenHandler handler;
    private List<Long> selectedPlatforms = new ArrayList<>();
    public TextFieldWidget secondsField;
    
    // Sound configuration widgets
    private SliderWidget volumeSlider;
    private SliderWidget rangeSlider;
    private String currentAttenuationType = "LINEAR";
    private float currentVolume = 2.0F;
    private int currentRange = 64;
    
    // XYZ coordinate input fields
    private CheckboxWidget boundingBoxCheckbox;
    private TextFieldWidget startXField;
    private TextFieldWidget startYField;
    private TextFieldWidget startZField;
    private TextFieldWidget endXField;
    private TextFieldWidget endYField;
    private TextFieldWidget endZField;
    
    // Trigger mode UI
    private ButtonWidget triggerModeButton;
    private String currentTriggerMode = "EXACT";
    
    // Scrolling support
    private int scrollOffset = 0;
    private static final int MIN_SCREEN_HEIGHT = 400;
    private static final int CONTENT_HEIGHT = 380; // Total height needed for all elements
    private ButtonWidget scrollUpButton;
    private ButtonWidget scrollDownButton;

    public MainScreen(MainScreenHandler handler, PlayerInventory inventory, Text title) {
        super(title);
        this.handler = handler;
    }

    @Override
    protected void init() {
        this.clearChildren();
        int buttonWidth = 200;
        int buttonHeight = 20;
        int x = this.width / 2 - buttonWidth / 2;
        int yStart = 15; // Start much higher on screen
        int yOffset = 40;
        
        // Check if scrolling is needed
        boolean needsScrolling = this.height < MIN_SCREEN_HEIGHT;
        if (needsScrolling) {
            yStart = 10; // Even smaller top margin when scrolling
            yOffset = 35; // Compact spacing when scrolling
        }

        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile != null) {
            selectedPlatforms = announceTile.getSelectedPlatformIds();
            secondsField = new TextFieldWidget(textRenderer, x, yStart + 2 * yOffset - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.seconds_input"));
            secondsField.setMaxLength(3);
            secondsField.setText(String.valueOf(announceTile.getSeconds()));
            if (isElementVisible(yStart + 2 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(secondsField);
            }
            // Initialize trigger mode
            currentTriggerMode = announceTile.getTriggerMode();
        }

        // Platform Selection Button
        ButtonWidget platformButton = new ButtonWidget(x, yStart - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.platform_selection"), button -> {
            if (announceTile != null) {
                this.client.setScreen(new PlatformSelectionScreen(announceTile.getPos(), announceTile.getSelectedPlatformIds()));
            }
        });
        if (isElementVisible(yStart - scrollOffset, buttonHeight)) {
            this.addDrawableChild(platformButton);
        }

        // Route Selection Button
        ButtonWidget routeButton = new ButtonWidget(x, yStart + (needsScrolling ? 1 : 1) * yOffset - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.route_selection"), button -> {
            if (announceTile != null) {
                this.client.setScreen(new RouteSelectionScreen(announceTile.getPos(), announceTile.getSelectedPlatformIds()));
            }
        });
        if (isElementVisible(yStart + (needsScrolling ? 1 : 1) * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(routeButton);
        }

        // Multi-JSON Selection Button
        ButtonWidget multiJsonButton = new ButtonWidget(x, yStart + 3 * yOffset - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.multi_json_selection"), button -> {
            if (announceTile != null) {
                this.client.setScreen(new MultiJsonSelectionScreen(announceTile, this));
            }
        });
        if (isElementVisible(yStart + 3 * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(multiJsonButton);
        }

        // Trigger Mode Button
        triggerModeButton = new ButtonWidget(x, yStart + 4 * yOffset - scrollOffset, buttonWidth, buttonHeight,
            Text.translatable("gui.easyannouncement.trigger_mode", currentTriggerMode), btn -> {
                // Cycle through modes
                if ("EXACT".equals(currentTriggerMode)) currentTriggerMode = "AT_OR_BEFORE";
                else if ("AT_OR_BEFORE".equals(currentTriggerMode)) currentTriggerMode = "AT_OR_AFTER";
                else currentTriggerMode = "EXACT";
                btn.setMessage(Text.translatable("gui.easyannouncement.trigger_mode", currentTriggerMode));
            }
        );
        if (isElementVisible(yStart + 4 * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(triggerModeButton);
        }

        // Sound Configuration Section
        if (announceTile != null) {
            // Initialize current values from tile
            currentAttenuationType = announceTile.getAttenuationType();
            currentVolume = announceTile.getSoundVolume();
            currentRange = announceTile.getSoundRange();
            
            // Volume Slider (0.1 - 3.0)
            volumeSlider = new SliderWidget(x, yStart + 5 * yOffset - scrollOffset, buttonWidth, buttonHeight, 
                Text.translatable("gui.easyannouncement.volume_label", String.format("%.1f", announceTile.getSoundVolume())), 
                (announceTile.getSoundVolume() - 0.1) / 2.9) {
                @Override
                protected void updateMessage() {
                    currentVolume = (float) (0.1 + this.value * 2.9);
                    this.setMessage(Text.translatable("gui.easyannouncement.volume_label", String.format("%.1f", currentVolume)));
                }
                
                @Override
                protected void applyValue() {
                    currentVolume = (float) (0.1 + this.value * 2.9);
                }
            };
            if (isElementVisible(yStart + 5 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(volumeSlider);
            }
            
            // Range Slider (16 - 128 blocks)
            rangeSlider = new SliderWidget(x, yStart + 6 * yOffset - scrollOffset, buttonWidth, buttonHeight, 
                Text.translatable("gui.easyannouncement.range_label", announceTile.getSoundRange()), 
                (announceTile.getSoundRange() - 16.0) / 112.0) {
                @Override
                protected void updateMessage() {
                    int range = (int) (16 + this.value * 112);
                    currentRange = range;
                    this.setMessage(Text.translatable("gui.easyannouncement.range_label", range));
                }
                
                @Override
                protected void applyValue() {
                    int range = (int) (16 + this.value * 112);
                    currentRange = range;
                }
            };
            if (isElementVisible(yStart + 6 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(rangeSlider);
            }
            
            // Bounding Box Checkbox
            boundingBoxCheckbox = new CheckboxWidget(x, yStart + 7 * yOffset - scrollOffset, buttonWidth, buttonHeight, 
                Text.translatable("gui.easyannouncement.enable_area_limit"), announceTile.isBoundingBoxEnabled());
            if (isElementVisible(yStart + 7 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(boundingBoxCheckbox);
            }
            
            // XYZ Coordinate Input Fields
            int fieldWidth = 60;
            int fieldHeight = 20;
            int startXPos = x;
            int labelOffset = 10;
            
            // Start coordinates (row 1) - Always create fields, conditionally add to screen
            startXField = new TextFieldWidget(this.textRenderer, startXPos, yStart + 8 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.start_x"));
            startXField.setText(String.valueOf(announceTile.getStartX()));
            startXField.setMaxLength(10);
            if (isElementVisible(yStart + 8 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(startXField);
            }
            
            startYField = new TextFieldWidget(this.textRenderer, startXPos + fieldWidth + labelOffset, yStart + 8 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.start_y"));
            startYField.setText(String.valueOf(announceTile.getStartY()));
            startYField.setMaxLength(10);
            if (isElementVisible(yStart + 8 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(startYField);
            }
            
            startZField = new TextFieldWidget(this.textRenderer, startXPos + 2 * (fieldWidth + labelOffset), yStart + 8 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.start_z"));
            startZField.setText(String.valueOf(announceTile.getStartZ()));
            startZField.setMaxLength(10);
            if (isElementVisible(yStart + 8 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(startZField);
            }
            
            // End coordinates (row 2) - Always create fields, conditionally add to screen
            endXField = new TextFieldWidget(this.textRenderer, startXPos, yStart + 9 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.end_x"));
            endXField.setText(String.valueOf(announceTile.getEndX()));
            endXField.setMaxLength(10);
            if (isElementVisible(yStart + 9 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(endXField);
            }
            
            endYField = new TextFieldWidget(this.textRenderer, startXPos + fieldWidth + labelOffset, yStart + 9 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.end_y"));
            endYField.setText(String.valueOf(announceTile.getEndY()));
            endYField.setMaxLength(10);
            if (isElementVisible(yStart + 9 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(endYField);
            }
            
            endZField = new TextFieldWidget(this.textRenderer, startXPos + 2 * (fieldWidth + labelOffset), yStart + 9 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.end_z"));
            endZField.setText(String.valueOf(announceTile.getEndZ()));
            endZField.setMaxLength(10);
            if (isElementVisible(yStart + 9 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(endZField);
            }
            
            // Copy/Paste Position Buttons
            int buttonSmallWidth = 80;
            int buttonSmallHeight = 20;
            int buttonX = startXPos + 3 * (fieldWidth + labelOffset) + 10;
            
            // Copy Position Button (next to start coordinates)
            ButtonWidget copyPosButton = new ButtonWidget(buttonX, yStart + 8 * yOffset - scrollOffset, buttonSmallWidth, buttonSmallHeight, 
                Text.translatable("gui.easyannouncement.copy_position"), button -> {
                    copyCurrentPosition();
                });
            if (isElementVisible(yStart + 8 * yOffset - scrollOffset, buttonSmallHeight)) {
                this.addDrawableChild(copyPosButton);
            }
            
            // Paste to Start Button
            ButtonWidget pasteStartButton = new ButtonWidget(buttonX, yStart + 8 * yOffset - scrollOffset + buttonSmallHeight + 5, buttonSmallWidth, buttonSmallHeight, 
                Text.translatable("gui.easyannouncement.paste_to_start"), button -> {
                    pasteToStartCoordinates();
                });
            if (isElementVisible(yStart + 8 * yOffset - scrollOffset + buttonSmallHeight + 5, buttonSmallHeight)) {
                this.addDrawableChild(pasteStartButton);
            }
            
            // Paste to End Button
            ButtonWidget pasteEndButton = new ButtonWidget(buttonX, yStart + 9 * yOffset - scrollOffset, buttonSmallWidth, buttonSmallHeight, 
                Text.translatable("gui.easyannouncement.paste_to_end"), button -> {
                    pasteToEndCoordinates();
                });
            if (isElementVisible(yStart + 9 * yOffset - scrollOffset, buttonSmallHeight)) {
                this.addDrawableChild(pasteEndButton);
            }
        }

        // Save Button
        ButtonWidget saveButton = new ButtonWidget(x, yStart + 10 * yOffset - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.save"), button -> {
            if (announceTile != null && secondsField != null) {
                try {
                    int seconds = Integer.parseInt(secondsField.getText());
                    List<AnnouncementEntry> entries = announceTile.getAnnouncementEntries();
                    
                    // Read slider values - use cached values which are updated by sliders' applyValue() methods
                    // If sliders exist but haven't been interacted with, cached values match tile values (initialized correctly)
                    // If sliders have been interacted with, applyValue() has updated the cached values
                    float volume = currentVolume;
                    int range = currentRange;
                    
                    // Ensure we have valid values (fallback to tile if somehow cache is wrong)
                    if (volume < 0.1f || volume > 3.0f) {
                        volume = announceTile.getSoundVolume();
                    }
                    if (range < 16 || range > 128) {
                        range = announceTile.getSoundRange();
                    }
                    
                    // Parse XYZ coordinates - always read from fields if they exist, otherwise use tile values
                    int startX = announceTile.getStartX();
                    int startY = announceTile.getStartY();
                    int startZ = announceTile.getStartZ();
                    int endX = announceTile.getEndX();
                    int endY = announceTile.getEndY();
                    int endZ = announceTile.getEndZ();
                    
                    // Try to parse coordinate fields, use defaults if parsing fails
                    try {
                        if (startXField != null && !startXField.getText().isEmpty()) {
                            startX = Integer.parseInt(startXField.getText());
                        }
                    } catch (NumberFormatException ignored) {}
                    try {
                        if (startYField != null && !startYField.getText().isEmpty()) {
                            startY = Integer.parseInt(startYField.getText());
                        }
                    } catch (NumberFormatException ignored) {}
                    try {
                        if (startZField != null && !startZField.getText().isEmpty()) {
                            startZ = Integer.parseInt(startZField.getText());
                        }
                    } catch (NumberFormatException ignored) {}
                    try {
                        if (endXField != null && !endXField.getText().isEmpty()) {
                            endX = Integer.parseInt(endXField.getText());
                        }
                    } catch (NumberFormatException ignored) {}
                    try {
                        if (endYField != null && !endYField.getText().isEmpty()) {
                            endY = Integer.parseInt(endYField.getText());
                        }
                    } catch (NumberFormatException ignored) {}
                    try {
                        if (endZField != null && !endZField.getText().isEmpty()) {
                            endZ = Integer.parseInt(endZField.getText());
                        }
                    } catch (NumberFormatException ignored) {}
                    
                    boolean boundingBoxEnabled = boundingBoxCheckbox != null ? boundingBoxCheckbox.isChecked() : announceTile.isBoundingBoxEnabled();
                    
                    // Send update packet with all current values
                    sendUpdatePacket(announceTile.getPos(), seconds, selectedPlatforms, entries, volume, range, currentAttenuationType, boundingBoxEnabled, startX, startY, startZ, endX, endY, endZ, currentTriggerMode);
                    
                    // Also update local tile immediately for responsive UI
                    announceTile.setSeconds(seconds);
                    announceTile.setSelectedPlatformIds(selectedPlatforms);
                    announceTile.setAnnouncementEntries(entries);
                    announceTile.setSoundVolume(volume);
                    announceTile.setSoundRange(range);
                    announceTile.setAttenuationType(currentAttenuationType);
                    announceTile.setBoundingBoxEnabled(boundingBoxEnabled);
                    announceTile.setStartX(startX);
                    announceTile.setStartY(startY);
                    announceTile.setStartZ(startZ);
                    announceTile.setEndX(endX);
                    announceTile.setEndY(endY);
                    announceTile.setEndZ(endZ);
                    announceTile.setTriggerMode(currentTriggerMode);
                    this.close();
                } catch (NumberFormatException e) {
                    // Invalid seconds value - don't save
                }
            }
        });
        if (isElementVisible(yStart + 10 * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(saveButton);
        }
        
        // Add scroll buttons if needed
        if (needsScrolling) {
            addScrollButtons(x, buttonWidth, buttonHeight);
        }
    }
    
    private boolean isElementVisible(int elementY, int elementHeight) {
        return elementY + elementHeight >= 0 && elementY <= this.height;
    }
    
    private void addScrollButtons(int x, int buttonWidth, int buttonHeight) {
        // Scroll Up Button
        if (scrollOffset > 0) {
            scrollUpButton = new ButtonWidget(x + buttonWidth + 10, 20, 20, buttonHeight, Text.of("↑"), button -> {
                scrollOffset = Math.max(0, scrollOffset - 30);
                this.init(); // Refresh the UI
            });
            this.addDrawableChild(scrollUpButton);
        }
        
        // Scroll Down Button
        int maxScrollOffset = Math.max(0, (CONTENT_HEIGHT + 40) - this.height + 40);
        if (scrollOffset < maxScrollOffset) {
            scrollDownButton = new ButtonWidget(x + buttonWidth + 10, 50, 20, buttonHeight, Text.of("↓"), button -> {
                scrollOffset = Math.min(maxScrollOffset, scrollOffset + 30);
                this.init(); // Refresh the UI
            });
            this.addDrawableChild(scrollDownButton);
        }
    }
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (this.height < MIN_SCREEN_HEIGHT) {
            int maxScrollOffset = Math.max(0, (CONTENT_HEIGHT + 40) - this.height + 40);
            int oldScrollOffset = scrollOffset;
            
            if (amount > 0) {
                scrollOffset = Math.max(0, scrollOffset - 20);
            } else {
                scrollOffset = Math.min(maxScrollOffset, scrollOffset + 20);
            }
            
            if (oldScrollOffset != scrollOffset) {
                this.init(); // Refresh the UI
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    private AnnounceTile getAnnounceTile() {
        if (handler.getBlockEntity() instanceof AnnounceTile) {
            return (AnnounceTile) handler.getBlockEntity();
        }
        return null;
    }

    private void sendUpdatePacket(BlockPos pos, int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries, float volume, int range, String attenuationType, boolean boundingBoxEnabled, int startX, int startY, int startZ, int endX, int endY, int endZ, String triggerMode) {
        if (MinecraftClient.getInstance().player == null) {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeInt(seconds);
        buf.writeLongArray(selectedPlatforms.stream().mapToLong(Long::longValue).toArray());
        
        // Write announcement entries
        buf.writeInt(announcementEntries.size());
        for (AnnouncementEntry entry : announcementEntries) {
            buf.writeString(entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }
        
        // Write sound configuration
        buf.writeFloat(volume);
        buf.writeInt(range);
        buf.writeString(attenuationType);
        
        // Write bounding box enabled
        buf.writeBoolean(boundingBoxEnabled);
        
        // Write XYZ coordinates
        buf.writeInt(startX);
        buf.writeInt(startY);
        buf.writeInt(startZ);
        buf.writeInt(endX);
        buf.writeInt(endY);
        buf.writeInt(endZ);
        
        // Write trigger mode
        buf.writeString(triggerMode);
        
        ClientPlayNetworking.send(AnnounceSendToClient.ID, buf);
    }
    
    // Legacy support method
    private void sendUpdatePacket(BlockPos pos, int seconds, List<Long> selectedPlatforms, String selectedJson) {
        List<AnnouncementEntry> entries = new ArrayList<>();
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            entries.add(new AnnouncementEntry(selectedJson, 0));
        }
        // Use default sound settings and coordinates for legacy calls
        sendUpdatePacket(pos, seconds, selectedPlatforms, entries, 2.0F, 64, "LINEAR", false, -100, -64, -100, 100, 320, 100, "EXACT");
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        super.render(matrices, mouseX, mouseY, delta);
        
        // Draw labels for coordinate fields if they're visible and bounding box is enabled
        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile != null && boundingBoxCheckbox != null) {
            int buttonWidth = 200;
            int x = this.width / 2 - buttonWidth / 2;
            int yStart = 20; // Start much higher on screen
            int yOffset = 40;
            
            if (this.height < MIN_SCREEN_HEIGHT) {
                yStart = 10; // Even smaller top margin when scrolling
                yOffset = 35;
            }
            
            // Draw "Start Coordinates" label above start coordinate fields
            if (startXField != null && isElementVisible(yStart + 8 * yOffset - scrollOffset - 15, 10)) {
                this.textRenderer.draw(matrices, Text.translatable("gui.easyannouncement.start_coordinates"), x, yStart + 8 * yOffset - scrollOffset - 15, 0xFFFFFF);
            }
            
            // Draw "End Coordinates" label above end coordinate fields  
            if (endXField != null && isElementVisible(yStart + 9 * yOffset - scrollOffset - 15, 10)) {
                this.textRenderer.draw(matrices, Text.translatable("gui.easyannouncement.end_coordinates"), x, yStart + 9 * yOffset - scrollOffset - 15, 0xFFFFFF);
            }
        }
    }

    @Override
    public MainScreenHandler getScreenHandler() {
        return handler;
    }

    public void updateData(int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries) {
        this.secondsField.setText(String.valueOf(seconds));
        this.selectedPlatforms = new ArrayList<>(selectedPlatforms); // コピーを作成

        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile != null) {
            announceTile.setAnnouncementEntries(announcementEntries); // Set entries in tile
            announceTile.markDirty(); // 追加
            
            // Update coordinate fields if they exist
            if (startXField != null) startXField.setText(String.valueOf(announceTile.getStartX()));
            if (startYField != null) startYField.setText(String.valueOf(announceTile.getStartY()));
            if (startZField != null) startZField.setText(String.valueOf(announceTile.getStartZ()));
            if (endXField != null) endXField.setText(String.valueOf(announceTile.getEndX()));
            if (endYField != null) endYField.setText(String.valueOf(announceTile.getEndY()));
            if (endZField != null) endZField.setText(String.valueOf(announceTile.getEndZ()));
            
            currentTriggerMode = announceTile.getTriggerMode();
            if (triggerModeButton != null) {
                triggerModeButton.setMessage(Text.translatable("gui.easyannouncement.trigger_mode", currentTriggerMode));
            }
        }
    }
    
    // Legacy support method
    public void updateData(int seconds, List<Long> selectedPlatforms, String selectedJson) {
        List<AnnouncementEntry> entries = new ArrayList<>();
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            entries.add(new AnnouncementEntry(selectedJson, 0));
        }
        updateData(seconds, selectedPlatforms, entries);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public static List<String> getAvailableJsonFiles() {
        List<String> jsonFiles = new ArrayList<>();
        // Include namespace for resource packs, so users can select othermod:sequence
        for (Identifier id : MinecraftClient.getInstance().getResourceManager().findResources("sounds", path -> path.getPath().endsWith(".json")).keySet()) {
            String name = id.getPath().replace("sounds/", "").replace(".json", "");
            String namespaced = id.getNamespace() + ":" + name;
            jsonFiles.add(namespaced);
        }
        
        // Also include sound events declared in all namespaces' sounds.json for convenience
        try {
            net.minecraft.resource.ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
            java.util.Set<String> namespaces = rm.getAllNamespaces();
            for (String ns : namespaces) {
                Identifier soundsJsonId = new Identifier(ns, "sounds.json");
                java.util.Optional<net.minecraft.resource.Resource> resOpt = rm.getResource(soundsJsonId);
                if (resOpt.isPresent()) {
                    try (java.io.InputStream in = resOpt.get().getInputStream();
                         java.io.InputStreamReader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                        for (String key : obj.keySet()) {
                            String namespaced = ns + ":" + key;
                            if (!jsonFiles.contains(namespaced)) {
                                jsonFiles.add(namespaced);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        
        return jsonFiles;
    }
    
    private String getAttenuationDisplayName(String type) {
        switch (type) {
            case "NONE":
                return "Global";
            case "LINEAR":
                return "Linear";
            default:
                return type;
        }
    }
    
    /**
     * Copy current player position to clipboard
     */
    private void copyCurrentPosition() {
        if (client.player == null) {
            return;
        }
        
        BlockPos pos = client.player.getBlockPos();
        String positionStr = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            StringSelection selection = new StringSelection(positionStr);
            clipboard.setContents(selection, null);
            
            // Show feedback message (you can customize this)
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("gui.easyannouncement.position_copied", positionStr), false);
            }
        } catch (Exception e) {
            System.err.println("[EasyAnnouncement] Failed to copy position to clipboard: " + e.getMessage());
        }
    }
    
    /**
     * Parse position string from clipboard and fill start coordinates
     * Supports formats: "x y z", "x,y,z", "x, y, z"
     */
    private void pasteToStartCoordinates() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            String clipboardText = (String) clipboard.getData(DataFlavor.stringFlavor);
            
            int[] coords = parsePosition(clipboardText);
            if (coords != null) {
                if (startXField != null) startXField.setText(String.valueOf(coords[0]));
                if (startYField != null) startYField.setText(String.valueOf(coords[1]));
                if (startZField != null) startZField.setText(String.valueOf(coords[2]));
            } else {
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("gui.easyannouncement.invalid_position_format"), false);
                }
            }
        } catch (Exception e) {
            System.err.println("[EasyAnnouncement] Failed to paste position: " + e.getMessage());
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("gui.easyannouncement.paste_failed"), false);
            }
        }
    }
    
    /**
     * Parse position string from clipboard and fill end coordinates
     * Supports formats: "x y z", "x,y,z", "x, y, z"
     */
    private void pasteToEndCoordinates() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            String clipboardText = (String) clipboard.getData(DataFlavor.stringFlavor);
            
            int[] coords = parsePosition(clipboardText);
            if (coords != null) {
                if (endXField != null) endXField.setText(String.valueOf(coords[0]));
                if (endYField != null) endYField.setText(String.valueOf(coords[1]));
                if (endZField != null) endZField.setText(String.valueOf(coords[2]));
            } else {
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("gui.easyannouncement.invalid_position_format"), false);
                }
            }
        } catch (Exception e) {
            System.err.println("[EasyAnnouncement] Failed to paste position: " + e.getMessage());
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("gui.easyannouncement.paste_failed"), false);
            }
        }
    }
    
    /**
     * Parse position string into coordinates array [x, y, z]
     * Supports formats: "x y z", "x,y,z", "x, y, z"
     */
    private int[] parsePosition(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        
        // Try space-separated format: "x y z"
        String[] parts = text.trim().split("\\s+");
        if (parts.length == 3) {
            try {
                return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
                };
            } catch (NumberFormatException e) {
                // Fall through to try comma-separated
            }
        }
        
        // Try comma-separated format: "x,y,z" or "x, y, z"
        parts = text.trim().split("\\s*,\\s*");
        if (parts.length == 3) {
            try {
                return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
                };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
    }
}