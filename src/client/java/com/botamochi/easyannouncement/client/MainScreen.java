package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.Easyannouncement;
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
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MainScreen extends Screen implements ScreenHandlerProvider<MainScreenHandler> {
    private final MainScreenHandler handler;
    private List<Long> selectedPlatforms = new ArrayList<>();
    public TextFieldWidget secondsField;
    public TextFieldWidget repeatIntervalField;
    
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
    private static final int MIN_SCREEN_HEIGHT = 240; // Lower threshold to enable scrolling
    private static final int CONTENT_HEIGHT = 520; // Total height needed for all elements

    // Repeat entries UI
    private List<AnnouncementEntry> workingTriggerEntries = new ArrayList<>();
    private List<AnnouncementEntry> workingRepeatEntries = new ArrayList<>();

    // Exclude players above the block
    private CheckboxWidget excludePlayersAboveCheckbox;
    
    // Legacy migration flag - if true, auto-select all platforms when opening PlatformSelectionScreen
    private boolean needsLegacyMigration = false;

    public MainScreen(MainScreenHandler handler, PlayerInventory inventory, Text title) {
        super(title);
        this.handler = handler;
    }

    // Refresh data from AnnounceTile when screen is opened/reshown
    private void refreshFromTile() {
        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile != null) {
            selectedPlatforms = announceTile.getSelectedPlatformIds();
        }
    }

    @Override
    protected void init() {
        // Refresh data from tile when screen initializes
        refreshFromTile();

        this.clearChildren();
        
        // Calculate dynamic button width and spacing based on screen size
        int buttonWidth = Math.min(200, this.width - 40);
        int buttonHeight = 20;
        int x = this.width / 2 - buttonWidth / 2;
        
        // Adjust spacing based on screen height
        int totalContentHeight = 14 * 30 + 80; // Approximate height of all elements
        int yOffset = (this.height - 60) / 14; // Distribute elements evenly
        yOffset = Math.max(22, Math.min(yOffset, 30)); // Clamp between 22-30
        int yStart = 20;

        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile != null) {
            selectedPlatforms = announceTile.getSelectedPlatformIds();
            needsLegacyMigration = announceTile.needsLegacyMigration();
            // Load entries - first list is trigger, second is repeat
            workingTriggerEntries = new ArrayList<>();
            for (AnnouncementEntry entry : announceTile.getAnnouncementEntries()) {
                workingTriggerEntries.add(entry.copy());
            }
            workingRepeatEntries = new ArrayList<>();
            for (AnnouncementEntry entry : announceTile.getRepeatEntries()) {
                workingRepeatEntries.add(entry.copy());
            }
            secondsField = new TextFieldWidget(textRenderer, x, yStart + 2 * yOffset - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.seconds_input"));
            secondsField.setMaxLength(3);
            secondsField.setText(String.valueOf(announceTile.getSeconds()));
            secondsField.setChangedListener(text -> autoSave());
            if (isElementVisible(yStart + 2 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(secondsField);
            }
        }

        // Platform Selection Button
        ButtonWidget platformButton = ButtonWidget.builder(Text.translatable("gui.easyannouncement.platform_selection"), button -> {
            if (announceTile != null) {
                autoSave(); // Save before opening child screen
                this.client.setScreen(new PlatformSelectionScreen(announceTile.getPos(), announceTile.getSelectedPlatformIds(), announceTile.needsLegacyMigration()));
            }
        }).dimensions(x, yStart - scrollOffset, buttonWidth, buttonHeight).build();
        if (isElementVisible(yStart - scrollOffset, buttonHeight)) {
            this.addDrawableChild(platformButton);
        }

        // Check if scrolling is needed based on content height vs screen height
        boolean needsScrolling = this.height < CONTENT_HEIGHT;

        // Route Selection Button
        ButtonWidget routeButton = ButtonWidget.builder(Text.translatable("gui.easyannouncement.route_selection"), button -> {
            if (announceTile != null) {
                autoSave(); // Save before opening child screen
                this.client.setScreen(new RouteSelectionScreen(announceTile.getPos(), announceTile.getSelectedPlatformIds()));
            }
        }).dimensions(x, yStart + 1 * yOffset - scrollOffset, buttonWidth, buttonHeight).build();
        if (isElementVisible(yStart + 1 * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(routeButton);
        }

        // Trigger JSON Selection Button
        ButtonWidget triggerJsonButton = ButtonWidget.builder(Text.translatable("gui.easyannouncement.trigger_json_selection"), button -> {
            if (announceTile != null) {
                autoSave();
                this.client.setScreen(new MultiJsonSelectionScreen(announceTile, this, workingTriggerEntries, Text.translatable("gui.easyannouncement.trigger_json_selection"), entries -> {
                    workingTriggerEntries = entries;
                    announceTile.setAnnouncementEntries(entries);
                    announceTile.markDirty();
                    client.setScreen(this);
                    // Save to server AFTER returning from selection screen
                    autoSave();
                }));
            }
        }).dimensions(x, yStart + 3 * yOffset - scrollOffset, buttonWidth, buttonHeight).build();
        if (isElementVisible(yStart + 3 * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(triggerJsonButton);
        }

        // Repeat JSON Selection Button
        ButtonWidget repeatJsonButton = ButtonWidget.builder(Text.translatable("gui.easyannouncement.repeat_json_selection"), button -> {
            if (announceTile != null) {
                autoSave();
                this.client.setScreen(new MultiJsonSelectionScreen(announceTile, this, workingRepeatEntries, Text.translatable("gui.easyannouncement.repeat_json_selection"), entries -> {
                    workingRepeatEntries = entries;
                    announceTile.setRepeatEntries(entries);
                    // Only auto-fill interval if user hasn't manually set it
                    if (repeatIntervalField != null && repeatIntervalField.getText().isEmpty()) {
                        int repeatIntervalSeconds = AnnounceReceiveFromServer.calculateRepeatIntervalSeconds(entries);
                        announceTile.setRepeatIntervalSeconds(repeatIntervalSeconds);
                        repeatIntervalField.setText(String.valueOf(repeatIntervalSeconds));
                    }
                    announceTile.markDirty();
                    client.setScreen(this);
                    // Save to server AFTER returning from selection screen
                    autoSave();
                }));
            }
        }).dimensions(x, yStart + 4 * yOffset - scrollOffset, buttonWidth, buttonHeight).build();
        if (isElementVisible(yStart + 4 * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(repeatJsonButton);
        }

        // Repeat Interval Input Field
        if (announceTile != null) {
            repeatIntervalField = new TextFieldWidget(textRenderer, x, yStart + 5 * yOffset - scrollOffset, buttonWidth, buttonHeight, Text.translatable("gui.easyannouncement.repeat_interval_input"));
            repeatIntervalField.setMaxLength(4);
            repeatIntervalField.setText(String.valueOf(announceTile.getRepeatIntervalSeconds()));
            repeatIntervalField.setChangedListener(text -> autoSave());
            if (isElementVisible(yStart + 5 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(repeatIntervalField);
            }
        }

        // Sound Configuration Section
        if (announceTile != null) {
            // Initialize current values from tile
            currentAttenuationType = announceTile.getAttenuationType();
            currentVolume = announceTile.getSoundVolume();
            currentRange = announceTile.getSoundRange();
            
            // Volume Slider (0.1 - 3.0)
            volumeSlider = new SliderWidget(x, yStart + 6 * yOffset - scrollOffset, buttonWidth, buttonHeight, 
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
            if (isElementVisible(yStart + 6 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(volumeSlider);
            }
            
            // Range Slider (16 - 128 blocks)
            rangeSlider = new SliderWidget(x, yStart + 7 * yOffset - scrollOffset, buttonWidth, buttonHeight, 
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
            if (isElementVisible(yStart + 7 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(rangeSlider);
            }
            
            // Bounding Box Checkbox
            boundingBoxCheckbox = new CheckboxWidget(x, yStart + 8 * yOffset - scrollOffset, buttonWidth, buttonHeight,
                Text.translatable("gui.easyannouncement.enable_area_limit"), announceTile.isBoundingBoxEnabled());
            if (isElementVisible(yStart + 8 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(boundingBoxCheckbox);
            }

            // Exclude Players Above Checkbox (placed after repeat mode)
            excludePlayersAboveCheckbox = new CheckboxWidget(x, yStart + 10 * yOffset - scrollOffset, buttonWidth, buttonHeight,
                Text.translatable("gui.easyannouncement.exclude_players_above"), announceTile.isExcludePlayersAbove());
            if (isElementVisible(yStart + 10 * yOffset - scrollOffset, buttonHeight)) {
                this.addDrawableChild(excludePlayersAboveCheckbox);
            }

            // XYZ Coordinate Input Fields
            int fieldWidth = 60;
            int fieldHeight = 20;
            int startXPos = x;
            int labelOffset = 10;

            // Start coordinates (row 1)
            startXField = new TextFieldWidget(this.textRenderer, startXPos, yStart + 12 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.start_x"));
            startXField.setText(String.valueOf(announceTile.getStartX()));
            startXField.setMaxLength(10);
            if (isElementVisible(yStart + 12 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(startXField);
            }

            startYField = new TextFieldWidget(this.textRenderer, startXPos + fieldWidth + labelOffset, yStart + 12 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.start_y"));
            startYField.setText(String.valueOf(announceTile.getStartY()));
            startYField.setMaxLength(10);
            if (isElementVisible(yStart + 12 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(startYField);
            }

            startZField = new TextFieldWidget(this.textRenderer, startXPos + 2 * (fieldWidth + labelOffset), yStart + 12 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.start_z"));
            startZField.setText(String.valueOf(announceTile.getStartZ()));
            startZField.setMaxLength(10);
            if (isElementVisible(yStart + 12 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(startZField);
            }

            // End coordinates (row 2)
            endXField = new TextFieldWidget(this.textRenderer, startXPos, yStart + 13 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.end_x"));
            endXField.setText(String.valueOf(announceTile.getEndX()));
            endXField.setMaxLength(10);
            if (isElementVisible(yStart + 13 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(endXField);
            }

            endYField = new TextFieldWidget(this.textRenderer, startXPos + fieldWidth + labelOffset, yStart + 13 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.end_y"));
            endYField.setText(String.valueOf(announceTile.getEndY()));
            endYField.setMaxLength(10);
            if (isElementVisible(yStart + 13 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(endYField);
            }

            endZField = new TextFieldWidget(this.textRenderer, startXPos + 2 * (fieldWidth + labelOffset), yStart + 13 * yOffset - scrollOffset, fieldWidth, fieldHeight, Text.translatable("gui.easyannouncement.end_z"));
            endZField.setText(String.valueOf(announceTile.getEndZ()));
            endZField.setMaxLength(10);
            if (isElementVisible(yStart + 13 * yOffset - scrollOffset, fieldHeight)) {
                this.addDrawableChild(endZField);
            }

            // Copy/Paste All Positions Buttons
            int buttonSmallWidth = 100;
            int buttonSmallHeight = 20;
            int buttonX = startXPos + 3 * (fieldWidth + labelOffset) + 10;

            // Copy All Positions Button
            ButtonWidget copyAllButton = ButtonWidget.builder(Text.translatable("gui.easyannouncement.copy_all_positions"), button -> {
                copyAllPositions();
            }).dimensions(buttonX, yStart + 12 * yOffset - scrollOffset, buttonSmallWidth, buttonSmallHeight).build();
            if (isElementVisible(yStart + 12 * yOffset - scrollOffset, buttonSmallHeight)) {
                this.addDrawableChild(copyAllButton);
            }

            // Paste All Positions Button
            ButtonWidget pasteAllButton = ButtonWidget.builder(Text.translatable("gui.easyannouncement.paste_all_positions"), button -> {
                pasteAllPositions();
            }).dimensions(buttonX, yStart + 13 * yOffset - scrollOffset, buttonSmallWidth, buttonSmallHeight).build();
            if (isElementVisible(yStart + 13 * yOffset - scrollOffset, buttonSmallHeight)) {
                this.addDrawableChild(pasteAllButton);
            }
        }

        // Done/Close Button - saves automatically when closed
        ButtonWidget doneButton = ButtonWidget.builder(Text.translatable("gui.easyannouncement.done"), button -> {
            saveAndClose();
        }).dimensions(x, yStart + 14 * yOffset - scrollOffset, buttonWidth, buttonHeight).build();
        if (isElementVisible(yStart + 14 * yOffset - scrollOffset, buttonHeight)) {
            this.addDrawableChild(doneButton);
        }
        // Scroll functionality is handled by mouse wheel (mouseScrolled method)
        // No scroll buttons needed - user can use mouse wheel or keyboard arrows
    }
    
    // Reset scroll offset when screen is resized or initialized
    @Override
    public void resize(MinecraftClient client, int width, int height) {
        this.scrollOffset = 0; // Reset scroll on resize
        super.resize(client, width, height);
    }
    
    private boolean isElementVisible(int elementY, int elementHeight) {
        return elementY + elementHeight >= 0 && elementY <= this.height;
    }
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int maxScrollOffset = Math.max(0, CONTENT_HEIGHT - this.height + 40);
        
        if (maxScrollOffset > 0) {
            int oldScrollOffset = scrollOffset;
            
            if (amount > 0) {
                scrollOffset = Math.max(0, scrollOffset - 30);
            } else {
                scrollOffset = Math.min(maxScrollOffset, scrollOffset + 30);
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
                float volume = currentVolume;
                int range = currentRange;
                if (volume < 0.1f || volume > 3.0f) volume = announceTile.getSoundVolume();
                if (range < 16 || range > 128) range = announceTile.getSoundRange();

                int startX = announceTile.getStartX();
                int startY = announceTile.getStartY();
                int startZ = announceTile.getStartZ();
                int endX = announceTile.getEndX();
                int endY = announceTile.getEndY();
                int endZ = announceTile.getEndZ();

                try {
                    if (startXField != null && !startXField.getText().isEmpty()) startX = Integer.parseInt(startXField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (startYField != null && !startYField.getText().isEmpty()) startY = Integer.parseInt(startYField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (startZField != null && !startZField.getText().isEmpty()) startZ = Integer.parseInt(startZField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (endXField != null && !endXField.getText().isEmpty()) endX = Integer.parseInt(endXField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (endYField != null && !endYField.getText().isEmpty()) endY = Integer.parseInt(endYField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (endZField != null && !endZField.getText().isEmpty()) endZ = Integer.parseInt(endZField.getText());
                } catch (NumberFormatException ignored) {}

                boolean boundingBoxEnabled = boundingBoxCheckbox != null ? boundingBoxCheckbox.isChecked() : announceTile.isBoundingBoxEnabled();
                boolean excludePlayersAbove = excludePlayersAboveCheckbox != null ? excludePlayersAboveCheckbox.isChecked() : announceTile.isExcludePlayersAbove();
                String triggerMode = announceTile.getTriggerMode();

                List<AnnouncementEntry> triggerEntries = workingTriggerEntries.stream()
                    .filter(entry -> !entry.isEmpty())
                    .collect(Collectors.toList());
                List<AnnouncementEntry> repeatEntries = workingRepeatEntries.stream()
                    .filter(entry -> !entry.isEmpty())
                    .collect(Collectors.toList());
                int repeatIntervalSeconds;
                if (repeatIntervalField != null && !repeatIntervalField.getText().trim().isEmpty()) {
                    repeatIntervalSeconds = Math.max(1, Integer.parseInt(repeatIntervalField.getText().trim()));
                } else {
                    repeatIntervalSeconds = AnnounceReceiveFromServer.calculateRepeatIntervalSeconds(repeatEntries);
                }

                sendUpdatePacket(announceTile.getPos(), seconds, selectedPlatforms, triggerEntries, repeatEntries, volume, range, currentAttenuationType, boundingBoxEnabled, startX, startY, startZ, endX, endY, endZ, triggerMode, excludePlayersAbove, repeatIntervalSeconds);

                announceTile.setSeconds(seconds);
                announceTile.setSelectedPlatformIds(selectedPlatforms);
                announceTile.setAnnouncementEntries(triggerEntries);
                announceTile.setRepeatEntries(repeatEntries);
                announceTile.setRepeatIntervalSeconds(repeatIntervalSeconds);
                announceTile.setSoundVolume(volume);
                announceTile.setSoundRange(range);
                announceTile.setAttenuationType(currentAttenuationType);
                announceTile.setBoundingBoxEnabled(boundingBoxEnabled);
                announceTile.setExcludePlayersAbove(excludePlayersAbove);
                announceTile.setStartX(startX);
                announceTile.setStartY(startY);
                announceTile.setStartZ(startZ);
                announceTile.setEndX(endX);
                announceTile.setEndY(endY);
                announceTile.setEndZ(endZ);
                this.close();
            } catch (NumberFormatException e) {
                this.close();
            }
        } else {
            this.close();
        }
    }

    // Auto-save current settings to server (without closing)
    private void autoSave() {
        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile != null && secondsField != null) {
            try {
                int seconds = Integer.parseInt(secondsField.getText());
                float volume = currentVolume;
                int range = currentRange;
                if (volume < 0.1f || volume > 3.0f) volume = announceTile.getSoundVolume();
                if (range < 16 || range > 128) range = announceTile.getSoundRange();

                int startX = announceTile.getStartX();
                int startY = announceTile.getStartY();
                int startZ = announceTile.getStartZ();
                int endX = announceTile.getEndX();
                int endY = announceTile.getEndY();
                int endZ = announceTile.getEndZ();

                try {
                    if (startXField != null && !startXField.getText().isEmpty()) startX = Integer.parseInt(startXField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (startYField != null && !startYField.getText().isEmpty()) startY = Integer.parseInt(startYField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (startZField != null && !startZField.getText().isEmpty()) startZ = Integer.parseInt(startZField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (endXField != null && !endXField.getText().isEmpty()) endX = Integer.parseInt(endXField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (endYField != null && !endYField.getText().isEmpty()) endY = Integer.parseInt(endYField.getText());
                } catch (NumberFormatException ignored) {}
                try {
                    if (endZField != null && !endZField.getText().isEmpty()) endZ = Integer.parseInt(endZField.getText());
                } catch (NumberFormatException ignored) {}

                boolean boundingBoxEnabled = boundingBoxCheckbox != null ? boundingBoxCheckbox.isChecked() : announceTile.isBoundingBoxEnabled();
                boolean excludePlayersAbove = excludePlayersAboveCheckbox != null ? excludePlayersAboveCheckbox.isChecked() : announceTile.isExcludePlayersAbove();
                String triggerMode = announceTile.getTriggerMode();

                List<AnnouncementEntry> triggerEntries = workingTriggerEntries.stream()
                    .filter(entry -> !entry.isEmpty())
                    .collect(Collectors.toList());
                List<AnnouncementEntry> repeatEntries = workingRepeatEntries.stream()
                    .filter(entry -> !entry.isEmpty())
                    .collect(Collectors.toList());
                int repeatIntervalSeconds;
                if (repeatIntervalField != null && !repeatIntervalField.getText().trim().isEmpty()) {
                    repeatIntervalSeconds = Math.max(1, Integer.parseInt(repeatIntervalField.getText().trim()));
                } else {
                    repeatIntervalSeconds = AnnounceReceiveFromServer.calculateRepeatIntervalSeconds(repeatEntries);
                }

                sendUpdatePacket(announceTile.getPos(), seconds, selectedPlatforms, triggerEntries, repeatEntries, volume, range, currentAttenuationType, boundingBoxEnabled, startX, startY, startZ, endX, endY, endZ, triggerMode, excludePlayersAbove, repeatIntervalSeconds);

                announceTile.setSeconds(seconds);
                announceTile.setSelectedPlatformIds(selectedPlatforms);
                announceTile.setAnnouncementEntries(triggerEntries);
                announceTile.setRepeatEntries(repeatEntries);
                announceTile.setRepeatIntervalSeconds(repeatIntervalSeconds);
                announceTile.setSoundVolume(volume);
                announceTile.setSoundRange(range);
                announceTile.setAttenuationType(currentAttenuationType);
                announceTile.setBoundingBoxEnabled(boundingBoxEnabled);
                announceTile.setExcludePlayersAbove(excludePlayersAbove);
                announceTile.setStartX(startX);
                announceTile.setStartY(startY);
                announceTile.setStartZ(startZ);
                announceTile.setEndX(endX);
                announceTile.setEndY(endY);
                announceTile.setEndZ(endZ);
            } catch (NumberFormatException e) {
            }
        }
    }

    private void sendUpdatePacket(BlockPos pos, int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries, List<AnnouncementEntry> repeatEntries, float volume, int range, String attenuationType, boolean boundingBoxEnabled, int startX, int startY, int startZ, int endX, int endY, int endZ, String triggerMode, boolean excludePlayersAbove, int repeatIntervalSeconds) {
        if (MinecraftClient.getInstance().player == null) {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeInt(seconds);
        buf.writeLongArray(selectedPlatforms.stream().mapToLong(Long::longValue).toArray());

        // Write announcement entries (trigger) - MUST use custom writeString to match server
        buf.writeInt(announcementEntries.size());
        for (AnnouncementEntry entry : announcementEntries) {
            writeString(buf, entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }

        // Write repeat entries - MUST use custom writeString to match server
        buf.writeInt(repeatEntries.size());
        for (AnnouncementEntry entry : repeatEntries) {
            writeString(buf, entry.getJsonName());
            buf.writeInt(entry.getDelaySeconds());
        }

        // Write sound configuration
        buf.writeFloat(volume);
        buf.writeInt(range);
        writeString(buf, attenuationType);

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
        writeString(buf, triggerMode);

        // Write exclude players above
        buf.writeBoolean(excludePlayersAbove);

        // Write repeat interval seconds
        buf.writeInt(repeatIntervalSeconds);

        ClientPlayNetworking.send(AnnounceSendToClient.ID, buf);
    }

    // Custom writeString that matches server's writeString format
    private static void writeString(PacketByteBuf buf, String str) {
        if (str == null) str = "";
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }

    // Legacy support method
    private void sendUpdatePacket(BlockPos pos, int seconds, List<Long> selectedPlatforms, String selectedJson) {
        List<AnnouncementEntry> entries = new ArrayList<>();
        if (selectedJson != null && !selectedJson.trim().isEmpty()) {
            entries.add(new AnnouncementEntry(selectedJson, 0));
        }
        int repeatIntervalSeconds = AnnounceReceiveFromServer.calculateRepeatIntervalSeconds(new ArrayList<>());
        sendUpdatePacket(pos, seconds, selectedPlatforms, entries, new ArrayList<>(), 2.0F, 64, "LINEAR", false, -100, -64, -100, 100, 320, 100, "EXACT", false, repeatIntervalSeconds);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Save on ESC key press
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            autoSave();
            return true;
        }
        
        // Arrow key scrolling
        int maxScrollOffset = Math.max(0, CONTENT_HEIGHT - this.height + 40);
        if (maxScrollOffset > 0) {
            if (keyCode == GLFW.GLFW_KEY_UP) {
                scrollOffset = Math.max(0, scrollOffset - 30);
                this.init();
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_DOWN) {
                scrollOffset = Math.min(maxScrollOffset, scrollOffset + 30);
                this.init();
                return true;
            }
        }
        
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        
        // Draw labels for coordinate fields if they're visible and bounding box is enabled
        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile != null && boundingBoxCheckbox != null) {
            int buttonWidth = Math.min(200, this.width - 40);
            int x = this.width / 2 - buttonWidth / 2;
            
            // Use same dynamic calculation as init()
            int yOffset = (this.height - 60) / 14;
            yOffset = Math.max(22, Math.min(yOffset, 30));
            int yStart = 20;
            
            // Draw "Start Coordinates" label above start coordinate fields
            if (startXField != null && isElementVisible(yStart + 11 * yOffset - scrollOffset - 15, 10)) {
                context.drawText(textRenderer, Text.translatable("gui.easyannouncement.start_coordinates"), x, yStart + 11 * yOffset - scrollOffset - 15, 0xFFFFFF, true);
            }

            // Draw "End Coordinates" label above end coordinate fields
            if (endXField != null && isElementVisible(yStart + 12 * yOffset - scrollOffset - 15, 10)) {
                context.drawText(textRenderer, Text.translatable("gui.easyannouncement.end_coordinates"), x, yStart + 12 * yOffset - scrollOffset - 15, 0xFFFFFF, true);
            }
        }
    }

    @Override
    public MainScreenHandler getScreenHandler() {
        return handler;
    }

    public void updateData(int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries) {
        updateData(seconds, selectedPlatforms, announcementEntries, new ArrayList<>());
    }

    public void updateData(int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries, List<AnnouncementEntry> repeatEntries) {
        this.secondsField.setText(String.valueOf(seconds));
        this.selectedPlatforms = new ArrayList<>(selectedPlatforms);
        this.workingTriggerEntries = new ArrayList<>(announcementEntries);
        this.workingRepeatEntries = new ArrayList<>(repeatEntries);


        AnnounceTile announceTile = getAnnounceTile();
        if (announceTile != null) {
            announceTile.setSelectedPlatformIds(selectedPlatforms);
            announceTile.setAnnouncementEntries(announcementEntries);
            announceTile.setRepeatEntries(repeatEntries);
            announceTile.setRepeatIntervalSeconds(announceTile.getRepeatIntervalSeconds()); // Already synced via packet, just refresh
            announceTile.markDirty();

            if (startXField != null) startXField.setText(String.valueOf(announceTile.getStartX()));
            if (startYField != null) startYField.setText(String.valueOf(announceTile.getStartY()));
            if (startZField != null) startZField.setText(String.valueOf(announceTile.getStartZ()));
            if (endXField != null) endXField.setText(String.valueOf(announceTile.getEndX()));
            if (endYField != null) endYField.setText(String.valueOf(announceTile.getEndY()));
            if (endZField != null) endZField.setText(String.valueOf(announceTile.getEndZ()));
        }
    }
    
    // Overloaded method with legacy migration flag
    public void updateData(int seconds, List<Long> selectedPlatforms, List<AnnouncementEntry> announcementEntries, List<AnnouncementEntry> repeatEntries, boolean needsLegacyMigration) {
        this.needsLegacyMigration = needsLegacyMigration;
        updateData(seconds, selectedPlatforms, announcementEntries, repeatEntries);
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

                // Step 3: Load JSON sequence files from sounds folder
                // These are custom announcement JSON files like normalsoon.json, nextstop.json, etc.
                if (ns.equals("easyannouncement") || ns.equals("mtr")) {
                    try {
                        for (Identifier id : rm.findResources("sounds", path -> {
                            String pathStr = path.getPath().toLowerCase();
                            // Only match .json files that are NOT sounds.json (the main sounds manifest)
                            return pathStr.endsWith(".json") && !pathStr.equals("sounds.json");
                        }).keySet()) {
                            if (ns.equals(id.getNamespace())) {
                                String path = id.getPath();
                                // Extract just the filename without extension and without "sounds/" prefix
                                String jsonName = path.replace("sounds/", "").replace(".json", "");
                                String namespaced = ns + ":" + jsonName;

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

    @Override
    public void tick() {
        // Refresh data from AnnounceTile every tick to catch updates from child screens
        refreshFromTile();
        super.tick();
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
            
            // Also scan for JSON sequence files (custom announcement JSONs like normalsoon.json)
            for (Identifier id : rm.findResources("sounds", path -> {
                String pathStr = path.getPath().toLowerCase();
                // Only match .json files that are NOT sounds.json
                return pathStr.endsWith(".json") && !pathStr.equals("sounds.json");
            }).keySet()) {
                if (namespace.equals(id.getNamespace())) {
                    String path = id.getPath();
                    String jsonName = path.replace("sounds/", "").replace(".json", "");
                    String namespaced = namespace + ":" + jsonName;
                    
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