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

import org.lwjgl.glfw.GLFW;
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
    
    // XYZ coordinate input fields
    private int scrollOffset = 0;
    private static final int MIN_SCREEN_HEIGHT = 400;
    private static final int CONTENT_HEIGHT = 480; // Total height needed for all elements (increased for repeat mode)
    private ButtonWidget scrollUpButton;
    private ButtonWidget scrollDownButton;

    // Repeat mode
    private CheckboxWidget repeatModeCheckbox;
    
    // Exclude players above the block
    private CheckboxWidget excludePlayersAboveCheckbox;

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
            secondsField.setChangedListener(text -> autoSave()); // Auto-save on change
            if (isElementVisible(yStart + 2 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(secondsField);
            }
        }

        // Platform Selection Button
        ButtonWidget platformButton = new ButtonWidget(x, yStart - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.platform_selection"), button -> {
            if (announceTile != null) {
                autoSave(); // Save before opening child screen
                this.client.setScreen(new PlatformSelectionScreen(announceTile.getPos(), announceTile.getSelectedPlatformIds()));
            }
        });
        if (isElementVisible(yStart - scrollOffset, buttonHeight)) {
            this.addDrawableChild(platformButton);
        }

        // Route Selection Button
        ButtonWidget routeButton = new ButtonWidget(x, yStart + (needsScrolling ? 1 : 1) * yOffset - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.route_selection"), button -> {
            if (announceTile != null) {
                autoSave(); // Save before opening child screen
                this.client.setScreen(new RouteSelectionScreen(announceTile.getPos(), announceTile.getSelectedPlatformIds()));
            }
        });
        if (isElementVisible(yStart + (needsScrolling ? 1 : 1) * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(routeButton);
        }

        // Multi-JSON Selection Button
        ButtonWidget multiJsonButton = new ButtonWidget(x, yStart + 3 * yOffset - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.multi_json_selection"), button -> {
            if (announceTile != null) {
                autoSave(); // Save before opening child screen
                this.client.setScreen(new MultiJsonSelectionScreen(announceTile, this));
            }
        });
        if (isElementVisible(yStart + 3 * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(multiJsonButton);
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

            // Repeat Mode Checkbox (placed after bounding box)
            repeatModeCheckbox = new CheckboxWidget(x, yStart + 8 * yOffset - scrollOffset, buttonWidth, buttonHeight,
                Text.translatable("gui.easyannouncement.repeat_mode"), announceTile.isRepeatMode());
            if (isElementVisible(yStart + 8 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(repeatModeCheckbox);
            }

            // Exclude Players Above Checkbox (placed after repeat mode)
            excludePlayersAboveCheckbox = new CheckboxWidget(x, yStart + 9 * yOffset - scrollOffset, buttonWidth, buttonHeight,
                Text.translatable("gui.easyannouncement.exclude_players_above"), announceTile.isExcludePlayersAbove());
            if (isElementVisible(yStart + 9 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(excludePlayersAboveCheckbox);
            }

            // XYZ Coordinate Input Fields
            int fieldWidth = 60;
            int fieldHeight = 20;
            int startXPos = x;
            int labelOffset = 10;

            // Start coordinates (row 1) - Always create fields, conditionally add to screen
            startXField = new TextFieldWidget(this.textRenderer, startXPos, yStart + 11 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.start_x"));
            startXField.setText(String.valueOf(announceTile.getStartX()));
            startXField.setMaxLength(10);
            if (isElementVisible(yStart + 11 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(startXField);
            }

            startYField = new TextFieldWidget(this.textRenderer, startXPos + fieldWidth + labelOffset, yStart + 11 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.start_y"));
            startYField.setText(String.valueOf(announceTile.getStartY()));
            startYField.setMaxLength(10);
            if (isElementVisible(yStart + 11 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(startYField);
            }

            startZField = new TextFieldWidget(this.textRenderer, startXPos + 2 * (fieldWidth + labelOffset), yStart + 11 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.start_z"));
            startZField.setText(String.valueOf(announceTile.getStartZ()));
            startZField.setMaxLength(10);
            if (isElementVisible(yStart + 11 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(startZField);
            }

            // End coordinates (row 2) - Always create fields, conditionally add to screen
            endXField = new TextFieldWidget(this.textRenderer, startXPos, yStart + 12 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.end_x"));
            endXField.setText(String.valueOf(announceTile.getEndX()));
            endXField.setMaxLength(10);
            if (isElementVisible(yStart + 12 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(endXField);
            }

            endYField = new TextFieldWidget(this.textRenderer, startXPos + fieldWidth + labelOffset, yStart + 12 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.end_y"));
            endYField.setText(String.valueOf(announceTile.getEndY()));
            endYField.setMaxLength(10);
            if (isElementVisible(yStart + 12 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(endYField);
            }

            endZField = new TextFieldWidget(this.textRenderer, startXPos + 2 * (fieldWidth + labelOffset), yStart + 12 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.end_z"));
            endZField.setText(String.valueOf(announceTile.getEndZ()));
            endZField.setMaxLength(10);
            if (isElementVisible(yStart + 12 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(endZField);
            }

            // Copy/Paste All Positions Buttons
            int buttonSmallWidth = 100;
            int buttonSmallHeight = 20;
            int buttonX = startXPos + 3 * (fieldWidth + labelOffset) + 10;

            // Copy All Positions Button
            ButtonWidget copyAllButton = new ButtonWidget(buttonX, yStart + 11 * yOffset - scrollOffset, buttonSmallWidth, buttonSmallHeight,
                Text.translatable("gui.easyannouncement.copy_all_positions"), button -> {
                    copyAllPositions();
                });
            if (isElementVisible(yStart + 11 * yOffset - scrollOffset, buttonSmallHeight)) {
                this.addDrawableChild(copyAllButton);
            }

            // Paste All Positions Button
            ButtonWidget pasteAllButton = new ButtonWidget(buttonX, yStart + 12 * yOffset - scrollOffset, buttonSmallWidth, buttonSmallHeight,
                Text.translatable("gui.easyannouncement.paste_all_positions"), button -> {
                    pasteAllPositions();
                });
            if (isElementVisible(yStart + 12 * yOffset - scrollOffset, buttonSmallHeight)) {
                this.addDrawableChild(pasteAllButton);
            }
        }

        // Done/Close Button - saves automatically when closed
        ButtonWidget doneButton = new ButtonWidget(x, yStart + 13 * yOffset - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.done"), button -> {
            saveAndClose();
        });
        if (isElementVisible(yStart + 13 * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(doneButton);
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

    // Auto-save when closing the screen
    private void saveAndClose() {
        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile != null && secondsField != null) {
            try {
                int seconds = Integer.parseInt(secondsField.getText());
                List<AnnouncementEntry> entries = announceTile.getAnnouncementEntries();

                // Read slider values
                float volume = currentVolume;
                int range = currentRange;

                // Ensure we have valid values
                if (volume < 0.1f || volume > 3.0f) {
                    volume = announceTile.getSoundVolume();
                }
                if (range < 16 || range > 128) {
                    range = announceTile.getSoundRange();
                }

                // Parse XYZ coordinates
                int startX = announceTile.getStartX();
                int startY = announceTile.getStartY();
                int startZ = announceTile.getStartZ();
                int endX = announceTile.getEndX();
                int endY = announceTile.getEndY();
                int endZ = announceTile.getEndZ();

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
                boolean repeatMode = repeatModeCheckbox != null ? repeatModeCheckbox.isChecked() : announceTile.isRepeatMode();
                boolean excludePlayersAbove = excludePlayersAboveCheckbox != null ? excludePlayersAboveCheckbox.isChecked() : announceTile.isExcludePlayersAbove();
                String triggerMode = announceTile.getTriggerMode(); // Always EXACT

                // Send update packet with all current values
                sendUpdatePacket(announceTile.getPos(), seconds, selectedPlatforms, entries, volume, range, currentAttenuationType, boundingBoxEnabled, startX, startY, startZ, endX, endY, endZ, triggerMode, repeatMode, excludePlayersAbove);

                // Also update local tile immediately for responsive UI
                announceTile.setSeconds(seconds);
                announceTile.setSelectedPlatformIds(selectedPlatforms);
                announceTile.setAnnouncementEntries(entries);
                announceTile.setSoundVolume(volume);
                announceTile.setSoundRange(range);
                announceTile.setAttenuationType(currentAttenuationType);
                announceTile.setBoundingBoxEnabled(boundingBoxEnabled);
                announceTile.setRepeatMode(repeatMode);
                announceTile.setExcludePlayersAbove(excludePlayersAbove);
                announceTile.setStartX(startX);
                announceTile.setStartY(startY);
                announceTile.setStartZ(startZ);
                announceTile.setEndX(endX);
                announceTile.setEndY(endY);
                announceTile.setEndZ(endZ);
                this.close();
            } catch (NumberFormatException e) {
                // Invalid seconds value - don't save
                this.close();
            }
        } else {
            this.close();
        }
    }

    // Auto-save current settings to server
    private void autoSave() {
        saveAndClose();
    }

    private void sendUpdatePacket(BlockPos pos, int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries, float volume, int range, String attenuationType, boolean boundingBoxEnabled, int startX, int startY, int startZ, int endX, int endY, int endZ, boolean repeatMode) {
        sendUpdatePacket(pos, seconds, selectedPlatforms, announcementEntries, volume, range, attenuationType, boundingBoxEnabled, startX, startY, startZ, endX, endY, endZ, "EXACT", repeatMode, false);
    }

    private void sendUpdatePacket(BlockPos pos, int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries, float volume, int range, String attenuationType, boolean boundingBoxEnabled, int startX, int startY, int startZ, int endX, int endY, int endZ, boolean repeatMode, boolean excludePlayersAbove) {
        sendUpdatePacket(pos, seconds, selectedPlatforms, announcementEntries, volume, range, attenuationType, boundingBoxEnabled, startX, startY, startZ, endX, endY, endZ, "EXACT", repeatMode, excludePlayersAbove);
    }

    private void sendUpdatePacket(BlockPos pos, int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries, float volume, int range, String attenuationType, boolean boundingBoxEnabled, int startX, int startY, int startZ, int endX, int endY, int endZ, String triggerMode, boolean repeatMode) {
        sendUpdatePacket(pos, seconds, selectedPlatforms, announcementEntries, volume, range, attenuationType, boundingBoxEnabled, startX, startY, startZ, endX, endY, endZ, triggerMode, repeatMode, false);
    }

    private void sendUpdatePacket(BlockPos pos, int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries, float volume, int range, String attenuationType, boolean boundingBoxEnabled, int startX, int startY, int startZ, int endX, int endY, int endZ, String triggerMode, boolean repeatMode, boolean excludePlayersAbove) {
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

        // Write repeat mode
        buf.writeBoolean(repeatMode);
        
        // Write exclude players above
        buf.writeBoolean(excludePlayersAbove);

        ClientPlayNetworking.send(AnnounceSendToClient.ID, buf);
    }
    
    // Legacy support method
    private void sendUpdatePacket(BlockPos pos, int seconds, List<Long> selectedPlatforms, String selectedJson) {
        List<AnnouncementEntry> entries = new ArrayList<>();
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            entries.add(new AnnouncementEntry(selectedJson, 0));
        }
        // Use default sound settings and coordinates for legacy calls
        sendUpdatePacket(pos, seconds, selectedPlatforms, entries, 2.0F, 64, "LINEAR", false, -100, -64, -100, 100, 320, 100, "EXACT", false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Save on ESC key press
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            autoSave();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
            if (startXField != null && isElementVisible(yStart + 11 * yOffset - scrollOffset - 15, 10)) {
                this.textRenderer.draw(matrices, Text.translatable("gui.easyannouncement.start_coordinates"), x, yStart + 11 * yOffset - scrollOffset - 15, 0xFFFFFF);
            }

            // Draw "End Coordinates" label above end coordinate fields
            if (endXField != null && isElementVisible(yStart + 12 * yOffset - scrollOffset - 15, 10)) {
                this.textRenderer.draw(matrices, Text.translatable("gui.easyannouncement.end_coordinates"), x, yStart + 12 * yOffset - scrollOffset - 15, 0xFFFFFF);
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
        
        // Load sounds efficiently - only sounds.json and key sound folders
        // This is much faster than scanning all sound files
        
        try {
            net.minecraft.resource.ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
            java.util.Set<String> namespaces = rm.getAllNamespaces();
            
            for (String ns : namespaces) {
                // Step 1: Load from sounds.json (most important)
                Identifier soundsJsonId = new Identifier(ns, "sounds.json");
                java.util.Optional<net.minecraft.resource.Resource> resOpt = rm.getResource(soundsJsonId);
                if (resOpt.isPresent()) {
                    try (java.io.InputStream in = resOpt.get().getInputStream();
                         java.io.InputStreamReader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                        parseSoundEventsRecursive(ns, obj, "", jsonFiles);
                    } catch (Exception ignored) {}
                }
                
                // Step 2: Only scan sounds folder for common namespaces
                // Skip scanning all sound files as it's very slow
                if (ns.equals("minecraft") || ns.equals("mtr")) {
                    try {
                        for (Identifier id : rm.findResources("sounds", path -> {
                            String pathStr = path.getPath().toLowerCase();
                            return pathStr.endsWith(".ogg") || pathStr.endsWith(".mp3") || 
                                   pathStr.endsWith(".wav") || pathStr.endsWith(".flac");
                        }).keySet()) {
                            if (ns.equals(id.getNamespace())) {
                                String path = id.getPath();
                                String soundName = path.replace("sounds/", "").replaceAll("\\.(ogg|mp3|wav|flac)$", "");
                                String namespaced = ns + ":" + soundName;
                                
                                if (!jsonFiles.contains(namespaced)) {
                                    jsonFiles.add(namespaced);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        
        return jsonFiles;
    }
    
    /**
     * Load all sounds from a specific namespace's sounds folder
     * Use this when user wants to load sounds from their resource pack
     */
    public static List<String> getSoundsFromNamespace(String namespace) {
        List<String> sounds = new ArrayList<>();
        
        try {
            net.minecraft.resource.ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
            
            // First check sounds.json
            Identifier soundsJsonId = new Identifier(namespace, "sounds.json");
            java.util.Optional<net.minecraft.resource.Resource> resOpt = rm.getResource(soundsJsonId);
            if (resOpt.isPresent()) {
                try (java.io.InputStream in = resOpt.get().getInputStream();
                     java.io.InputStreamReader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    parseSoundEventsRecursive(namespace, obj, "", sounds);
                } catch (Exception ignored) {}
            }
            
            // Then scan sounds folder for actual audio files
            for (Identifier id : rm.findResources("sounds", path -> {
                String pathStr = path.getPath().toLowerCase();
                return pathStr.endsWith(".ogg") || pathStr.endsWith(".mp3") || 
                       pathStr.endsWith(".wav") || pathStr.endsWith(".flac");
            }).keySet()) {
                if (namespace.equals(id.getNamespace())) {
                    String path = id.getPath();
                    String soundName = path.replace("sounds/", "").replaceAll("\\.(ogg|mp3|wav|flac)$", "");
                    String namespaced = namespace + ":" + soundName;
                    
                    if (!sounds.contains(namespaced)) {
                        sounds.add(namespaced);
                    }
                }
            }
        } catch (Exception ignored) {}
        
        sounds.sort(String::compareToIgnoreCase);
        return sounds;
    }
    
    /**
     * Recursively parse sounds.json to find all sound event names
     * Handles nested structures in Minecraft's sounds.json format
     */
    private static void parseSoundEventsRecursive(String namespace, com.google.gson.JsonObject obj, String prefix, List<String> jsonFiles) {
        for (String key : obj.keySet()) {
            com.google.gson.JsonElement element = obj.get(key);
            
            if (element.isJsonPrimitive()) {
                // Direct sound reference like "sounds": "path/to/sound"
                String soundPath = element.getAsString();
                // Extract just the sound name without extension
                String soundName = soundPath;
                if (soundName.contains("/")) {
                    soundName = soundName.substring(soundName.lastIndexOf("/") + 1);
                }
                if (soundName.endsWith(".ogg")) {
                    soundName = soundName.replace(".ogg", "");
                }
                String namespaced = namespace + ":" + prefix + key;
                if (!jsonFiles.contains(namespaced)) {
                    jsonFiles.add(namespaced);
                }
            } else if (element.isJsonArray()) {
                // Array of sounds like "sounds": ["sound1", "sound2"]
                com.google.gson.JsonArray arr = element.getAsJsonArray();
                for (com.google.gson.JsonElement arrElement : arr) {
                    if (arrElement.isJsonPrimitive()) {
                        String soundPath = arrElement.getAsString();
                        String soundName = soundPath;
                        if (soundName.contains("/")) {
                            soundName = soundName.substring(soundName.lastIndexOf("/") + 1);
                        }
                        if (soundName.endsWith(".ogg")) {
                            soundName = soundName.replace(".ogg", "");
                        }
                        String namespaced = namespace + ":" + prefix + key;
                        if (!jsonFiles.contains(namespaced)) {
                            jsonFiles.add(namespaced);
                        }
                    }
                }
            } else if (element.isJsonObject()) {
                // Nested object like "music": { "sounds": [...] }
                com.google.gson.JsonObject nestedObj = element.getAsJsonObject();
                if (nestedObj.has("sounds")) {
                    // This is a sound event definition with a "sounds" array
                    com.google.gson.JsonElement soundsElement = nestedObj.get("sounds");
                    if (soundsElement.isJsonArray()) {
                        com.google.gson.JsonArray soundsArr = soundsElement.getAsJsonArray();
                        for (com.google.gson.JsonElement soundElem : soundsArr) {
                            if (soundElem.isJsonPrimitive()) {
                                String soundPath = soundElem.getAsString();
                                String soundName = soundPath;
                                if (soundName.contains("/")) {
                                    soundName = soundName.substring(soundName.lastIndexOf("/") + 1);
                                }
                                if (soundName.endsWith(".ogg")) {
                                    soundName = soundName.replace(".ogg", "");
                                }
                                String namespaced = namespace + ":" + prefix + key;
                                if (!jsonFiles.contains(namespaced)) {
                                    jsonFiles.add(namespaced);
                                }
                            } else if (soundElem.isJsonObject()) {
                                // Object with "name" field like { "name": "path/to/sound", "volume": 1.0 }
                                com.google.gson.JsonObject soundObj = soundElem.getAsJsonObject();
                                if (soundObj.has("name")) {
                                    String soundPath = soundObj.get("name").getAsString();
                                    String soundName = soundPath;
                                    if (soundName.contains("/")) {
                                        soundName = soundName.substring(soundName.lastIndexOf("/") + 1);
                                    }
                                    if (soundName.endsWith(".ogg")) {
                                        soundName = soundName.replace(".ogg", "");
                                    }
                                    String namespaced = namespace + ":" + prefix + key;
                                    if (!jsonFiles.contains(namespaced)) {
                                        jsonFiles.add(namespaced);
                                    }
                                }
                            }
                        }
                    } else if (soundsElement.isJsonPrimitive()) {
                        // Single sound string
                        String soundPath = soundsElement.getAsString();
                        String soundName = soundPath;
                        if (soundName.contains("/")) {
                            soundName = soundName.substring(soundName.lastIndexOf("/") + 1);
                        }
                        if (soundName.endsWith(".ogg")) {
                            soundName = soundName.replace(".ogg", "");
                        }
                        String namespaced = namespace + ":" + prefix + key;
                        if (!jsonFiles.contains(namespaced)) {
                            jsonFiles.add(namespaced);
                        }
                    }
                } else {
                    // Other nested object, recurse with prefix
                    parseSoundEventsRecursive(namespace, nestedObj, prefix + key + ".", jsonFiles);
                }
            }
        }
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
     * Copy all positions (start and end coordinates) to clipboard
     * Format: "startX startY startZ endX endY endZ"
     */
    private void copyAllPositions() {
        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile == null) {
            return;
        }
        
        int startX = announceTile.getStartX();
        int startY = announceTile.getStartY();
        int startZ = announceTile.getStartZ();
        int endX = announceTile.getEndX();
        int endY = announceTile.getEndY();
        int endZ = announceTile.getEndZ();
        
        String positionStr = String.format("%d %d %d %d %d %d", startX, startY, startZ, endX, endY, endZ);
        
        // Use GLFW to set clipboard (works in Minecraft)
        long windowHandle = client.getWindow().getHandle();
        GLFW.glfwSetClipboardString(windowHandle, positionStr);
        
        // Show feedback message
        if (client.player != null) {
            client.player.sendMessage(Text.translatable("gui.easyannouncement.all_positions_copied"), false);
        }
    }
    
    /**
     * Parse all positions from clipboard and fill both start and end coordinates
     * Supports formats: "startX startY startZ endX endY endZ" (6 numbers)
     * Also supports: "x y z" (3 numbers - fills start, copies to end)
     */
    private void pasteAllPositions() {
        try {
            // Use GLFW to get clipboard (works in Minecraft)
            long windowHandle = client.getWindow().getHandle();
            String clipboardText = GLFW.glfwGetClipboardString(windowHandle);
            
            if (clipboardText == null || clipboardText.trim().isEmpty()) {
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("gui.easyannouncement.clipboard_empty"), false);
                }
                return;
            }
            
            int[] coords = parseAllPositions(clipboardText);
            if (coords != null) {
                // Fill all fields
                if (startXField != null) startXField.setText(String.valueOf(coords[0]));
                if (startYField != null) startYField.setText(String.valueOf(coords[1]));
                if (startZField != null) startZField.setText(String.valueOf(coords[2]));
                if (endXField != null) endXField.setText(String.valueOf(coords[3]));
                if (endYField != null) endYField.setText(String.valueOf(coords[4]));
                if (endZField != null) endZField.setText(String.valueOf(coords[5]));
                
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("gui.easyannouncement.all_positions_pasted"), false);
                }
            } else {
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("gui.easyannouncement.invalid_position_format"), false);
                }
            }
        } catch (Exception e) {
            System.err.println("[EasyAnnouncement] Failed to paste positions: " + e.getMessage());
            e.printStackTrace();
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("gui.easyannouncement.paste_failed"), false);
            }
        }
    }
    
    /**
     * Parse position string into coordinates array [startX, startY, startZ, endX, endY, endZ]
     * Supports formats: 
     * - "startX startY startZ endX endY endZ" (6 numbers, space or comma separated)
     * - "x y z" (3 numbers - fills start, copies same to end)
     */
    private int[] parseAllPositions(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        
        text = text.trim();
        
        // Try space-separated format first
        String[] parts = text.split("\\s+");
        
        // If 6 numbers: startX startY startZ endX endY endZ
        if (parts.length == 6) {
            try {
                return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5])
                };
            } catch (NumberFormatException e) {
                // Fall through to try comma-separated
            }
        }
        
        // If 3 numbers: x y z (fill start, copy to end)
        if (parts.length == 3) {
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                return new int[]{x, y, z, x, y, z};
            } catch (NumberFormatException e) {
                // Fall through to try comma-separated
            }
        }
        
        // Try comma-separated format: "x,y,z" or "x, y, z"
        parts = text.split("\\s*,\\s*");
        
        // If 6 numbers: startX,startY,startZ,endX,endY,endZ
        if (parts.length == 6) {
            try {
                return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5])
                };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        // If 3 numbers: x,y,z (fill start, copy to end)
        if (parts.length == 3) {
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                return new int[]{x, y, z, x, y, z};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
    }
}