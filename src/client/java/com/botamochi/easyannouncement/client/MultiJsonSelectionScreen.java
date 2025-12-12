package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.data.AnnouncementEntry;
import com.botamochi.easyannouncement.tile.AnnounceTile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;

// removed registry import; we only use sounds.json per requirement

public class MultiJsonSelectionScreen extends Screen {
    private final AnnounceTile announceTile;
    private final Screen parent;
    private List<AnnouncementEntry> workingEntries;
    private List<String> availableJsonFiles;
    private List<ButtonWidget> entryButtons;
    private List<TextFieldWidget> delayFields;
    private ButtonWidget addButton;
    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;
    private boolean draggingScrollbar = false;
    private int scrollOffset = 0;
    private final int maxVisibleEntries = 5; // Reduced for better spacing

    // Color scheme for modern look
    private static final int BACKGROUND_COLOR = 0x88000000; // Semi-transparent dark
    private static final int PANEL_COLOR = 0xAA1E1E1E; // Dark panel
    private static final int BORDER_COLOR = 0xFF3A3A3A; // Light border
    private static final int ACCENT_COLOR = 0xFF4A90E2; // Blue accent
    private static final int SUCCESS_COLOR = 0xFF5CB85C; // Green
    private static final int WARNING_COLOR = 0xFFD9534F; // Red
    private static final int TEXT_COLOR = 0xFFFFFFFF; // White text
    private static final int SUBTITLE_COLOR = 0xFFCCCCCC; // Light gray

    // Responsive layout helper
    private int[] calculatePanelDimensions() {
        int margin = 20;
        int availableWidth = Math.max(0, width - margin * 2);
        int availableHeight = Math.max(0, height - margin * 2);
        // Ensure minimum width for proper JSON button display
        int minPanelWidth = 320; // Reduced minimum to fit better on smaller screens
        int maxPanelWidth = 400; // Reasonable max width for better button display
        int panelWidth = Math.max(minPanelWidth, Math.min(maxPanelWidth, availableWidth));
        int panelHeight = Math.min(450, availableHeight); // Slightly increased height
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;
        return new int[]{panelX, panelY, panelWidth, panelHeight};
    }

    public MultiJsonSelectionScreen(AnnounceTile announceTile, Screen parent) {
        super(Text.translatable("gui.easyannouncement.multi_json_selection"));
        this.announceTile = announceTile;
        this.parent = parent;
        this.workingEntries = new ArrayList<>();
        
        // Copy current entries from tile
        for (AnnouncementEntry entry : announceTile.getAnnouncementEntries()) {
            this.workingEntries.add(entry.copy());
        }
        
        // Ensure at least one empty entry for adding new ones
        if (workingEntries.isEmpty()) {
            workingEntries.add(new AnnouncementEntry("", 0));
        }
        
        loadAvailableJsonFiles();
    }

    private void loadAvailableJsonFiles() {
        availableJsonFiles = new ArrayList<>();
        
        // Step 1: Load JSON sequence files ONLY from easyannouncement namespace
        try {
            ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
            for (Identifier id : rm.findResources("sounds", path -> {
                String pathStr = path.getPath();
                // Must end with .json but NOT be sounds.json itself
                return pathStr.endsWith(".json") && !pathStr.endsWith("sounds.json");
            }).keySet()) {
                if (!"easyannouncement".equals(id.getNamespace())) {
                    continue; // restrict JSON sequences to easyannouncement only
                }
                String jsonName = id.getPath().replace("sounds/", "").replace(".json", "");
                String namespaced = id.getNamespace() + ":" + jsonName;
                if (!availableJsonFiles.contains(namespaced)) {
                    availableJsonFiles.add(namespaced);
                }
            }
        } catch (Exception e) {
            System.err.println("[EasyAnnouncement] Failed to load JSON sequence files: " + e.getMessage());
        }
        
        // Step 2: Load individual sound events from ALL namespaces' sounds.json
        try {
            ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
            Set<String> namespaces = rm.getAllNamespaces();
            
            for (String ns : namespaces) {
                try {
                    Identifier soundsJsonId = new Identifier(ns, "sounds.json");
                    Optional<Resource> resOpt = rm.getResource(soundsJsonId);
                    if (resOpt.isPresent()) {
                        try (InputStream in = resOpt.get().getInputStream();
                             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                            for (String key : obj.keySet()) {
                                String namespaced = ns + ":" + key;
                                if (!availableJsonFiles.contains(namespaced)) {
                                    availableJsonFiles.add(namespaced);
                                }
                            }
                        } catch (Exception parseError) {
                            System.err.println("[EasyAnnouncement] Failed to parse sounds.json (" + ns + "): " + parseError.getMessage());
                        }
                    }
                } catch (Exception nsError) {
                    // Silently ignore errors for individual namespaces
                }
            }
        } catch (Exception e) {
            System.err.println("[EasyAnnouncement] Failed to load sound events: " + e.getMessage());
        }
        
        // Sort the list for better organization
        availableJsonFiles.sort(String::compareToIgnoreCase);
        
        System.out.println("[EasyAnnouncement] Loaded " + availableJsonFiles.size() + " available sounds/JSON files");
    }

    @Override
    protected void init() {
        // Clear all existing GUI components first
        this.clearChildren();
        
        entryButtons = new ArrayList<>();
        delayFields = new ArrayList<>();
        
        // Main panel dimensions - responsive
        int[] panelDims = calculatePanelDimensions();
        int panelX = panelDims[0];
        int panelY = panelDims[1];
        int panelWidth = panelDims[2];
        int panelHeight = panelDims[3];
        
        int startY = panelY + 55; // Adjusted to account for labels
        int spacing = 35;
        int buttonWidth = 200;
        int buttonHeight = 20;
        int centerX = this.width / 2 - buttonWidth / 2;
        
            // Create buttons and text fields for visible entries - responsive layout
    for (int i = 0; i < maxVisibleEntries && (i + scrollOffset) < workingEntries.size(); i++) {
        final int entryIndex = i + scrollOffset;
        int y = startY + i * spacing;
        
        // Calculate responsive button widths - ensure JSON button has minimum width
        int margin = 10;
        int removeButtonWidth = 25; // Fixed width for remove button
        int delayFieldWidth = 60; // Reduced width for delay field
        int elementSpacing = 8; // Spacing between elements
        // Calculate available width for JSON button
        int usedWidth = margin + delayFieldWidth + elementSpacing + removeButtonWidth + margin;
        int availableWidthForJson = panelWidth - usedWidth;
        int minJsonButtonWidth = 180; // Minimum width for JSON button
        int jsonButtonWidth = Math.max(minJsonButtonWidth, availableWidthForJson);
        
        // JSON selection button with proper width and text truncation
        String displayText = getEntryDisplayText(entryIndex);
        // Truncate text if too long to fit nicely in button
        if (textRenderer.getWidth(displayText) > jsonButtonWidth - 10) {
            while (textRenderer.getWidth(displayText + "...") > jsonButtonWidth - 10 && displayText.length() > 1) {
                displayText = displayText.substring(0, displayText.length() - 1);
            }
            if (displayText.length() > 1) {
                displayText += "...";
            }
        }
        
        ButtonWidget jsonButton = new ButtonWidget(panelX + margin, y, jsonButtonWidth, buttonHeight,
            Text.literal(displayText),
            button -> openJsonSelector(entryIndex));
        entryButtons.add(jsonButton);
        addDrawableChild(jsonButton);
        
        // Delay text field with responsive positioning
        int delayX = panelX + margin + jsonButtonWidth + elementSpacing;
        TextFieldWidget delayField = new TextFieldWidget(textRenderer, delayX, y, delayFieldWidth, buttonHeight, Text.literal(""));
        delayField.setText(String.valueOf(getEntryDelay(entryIndex)));
        delayField.setChangedListener(text -> updateEntryDelay(entryIndex, text));
        delayFields.add(delayField);
        addDrawableChild(delayField);
        
        // Remove button with responsive positioning
        int removeX = delayX + delayFieldWidth + elementSpacing;
        ButtonWidget removeButton = new ButtonWidget(removeX, y, removeButtonWidth, buttonHeight,
            Text.of("×"),
            button -> removeEntry(entryIndex));
        addDrawableChild(removeButton);
    }
        
        // Bottom controls centered to match MainScreen
        int bottomStartY = panelY + panelHeight - 80;
        addButton = new ButtonWidget(centerX, bottomStartY, buttonWidth, buttonHeight,
            Text.translatable("gui.easyannouncement.add_entry"),
            button -> addNewEntry());
        addDrawableChild(addButton);

        saveButton = new ButtonWidget(centerX, bottomStartY + 40, buttonWidth, buttonHeight,
            Text.translatable("gui.easyannouncement.save"),
            button -> saveAndClose());
        addDrawableChild(saveButton);

        cancelButton = new ButtonWidget(centerX, bottomStartY + 80, buttonWidth, buttonHeight,
            Text.translatable("gui.easyannouncement.cancel"),
            button -> close());
        addDrawableChild(cancelButton);
    }

    private String getEntryDisplayText(int index) {
        if (index >= workingEntries.size()) return "";
        String jsonName = workingEntries.get(index).getJsonName();
        if (jsonName.isEmpty()) {
            return Text.translatable("gui.easyannouncement.select_json").getString();
        }
        return jsonName;
    }
    
    private String getFullEntryDisplayText(int index) {
        if (index >= workingEntries.size()) return "";
        String jsonName = workingEntries.get(index).getJsonName();
        if (jsonName.isEmpty()) {
            return Text.translatable("gui.easyannouncement.select_json").getString();
        }
        return "JSON: " + jsonName;
    }
    
    private int getEntryDelay(int index) {
        if (index >= workingEntries.size()) return 0;
        return workingEntries.get(index).getDelaySeconds();
    }
    
    private void updateEntryDelay(int index, String text) {
        if (index >= workingEntries.size()) return;
        try {
            int delay = Integer.parseInt(text);
            workingEntries.get(index).setDelaySeconds(Math.max(0, delay));
        } catch (NumberFormatException e) {
            // Invalid input, ignore
        }
    }

    private void openJsonSelector(int entryIndex) {
        if (entryIndex >= workingEntries.size()) return;
        
        AnnouncementEntry entry = workingEntries.get(entryIndex);
        String currentJson = entry.getJsonName();
        
        // Open the new JSON selection screen with search functionality
        JsonSelectionScreen jsonSelectionScreen = new JsonSelectionScreen(
            this,
            availableJsonFiles,
            selectedJson -> {
                entry.setJsonName(selectedJson);
                init(); // Refresh the display when returning
            },
            currentJson
        );
        
        client.setScreen(jsonSelectionScreen);
    }

    private void addNewEntry() {
        workingEntries.add(new AnnouncementEntry("", 0));
        init(); // Reinitialize to update the display
    }

    private void removeEntry(int index) {
        if (index >= 0 && index < workingEntries.size()) {
            workingEntries.remove(index);
            // Adjust scroll offset if necessary
            if (scrollOffset > 0 && scrollOffset >= workingEntries.size() - maxVisibleEntries) {
                scrollOffset = Math.max(0, workingEntries.size() - maxVisibleEntries);
            }
            init(); // Reinitialize to update the display
        }
    }

    private void scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset--;
            init(); // Reinitialize to update the display
        }
    }

    private void scrollDown() {
        if (scrollOffset < workingEntries.size() - maxVisibleEntries) {
            scrollOffset++;
            init(); // Reinitialize to update the display
        }
    }

    private void saveAndClose() {
        // Filter out empty entries
        List<AnnouncementEntry> validEntries = workingEntries.stream()
            .filter(entry -> !entry.isEmpty())
            .collect(Collectors.toList());
        
        // Update the tile with the new entries
        announceTile.setAnnouncementEntries(validEntries);
        announceTile.markDirty();
        
        close();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        // Simple default background
        this.renderBackground(matrices);
        
        // Main panel dimensions - responsive
        int[] panelDims = calculatePanelDimensions();
        int panelX = panelDims[0];
        int panelY = panelDims[1];
        int panelWidth = panelDims[2];
        int panelHeight = panelDims[3];
        
        // Title with modern styling
        drawCenteredText(matrices, textRenderer, title, width / 2, panelY + 12, 0xFFFFFFFF);
        
        // Add labels for better understanding
        String jsonLabel = Text.translatable("gui.easyannouncement.json_files").getString();
        String delayLabel = Text.translatable("gui.easyannouncement.delay_short").getString();
        int labelY = panelY + 30;
        int margin = 20;
        textRenderer.draw(matrices, jsonLabel, panelX + margin, labelY, 0xFFCCCCCC);
        
        // Calculate label positions to match the button layout
        int removeButtonWidthRender = 25;
        int delayFieldWidthRender = 60;
        int elementSpacingRender = 8;
        int usedWidthRender = margin + delayFieldWidthRender + elementSpacingRender + removeButtonWidthRender + margin;
        int availableWidthForJsonRender = panelWidth - usedWidthRender;
        int minJsonButtonWidthRender = 180;
        int jsonButtonWidthRender = Math.max(minJsonButtonWidthRender, availableWidthForJsonRender);
        int delayX = panelX + margin + jsonButtonWidthRender + elementSpacingRender;
        textRenderer.draw(matrices, delayLabel, delayX, labelY, 0xFFCCCCCC);
        
        // Render simple scrollbar if needed
        if (workingEntries.size() > maxVisibleEntries) {
            int startY = panelY + 55; // Match the button layout
            int spacing = 35;
            int trackX = panelX + panelWidth - 10;
            int trackTop = startY;
            int trackHeight = maxVisibleEntries * spacing - 4;
            int totalItems = workingEntries.size();
            int maxOffset = Math.max(0, totalItems - maxVisibleEntries);
            int thumbHeight = Math.max(20, (int)Math.round((double)trackHeight * maxVisibleEntries / totalItems));
            int thumbY = trackTop;
            if (maxOffset > 0) {
                thumbY = trackTop + (int)Math.round((double)(trackHeight - thumbHeight) * scrollOffset / maxOffset);
            }
            // Track
            fill(matrices, trackX, trackTop, trackX + 4, trackTop + trackHeight, 0xFF444444);
            // Thumb
            int thumbColor = draggingScrollbar ? 0xFFAAAAAA : 0xFF888888;
            fill(matrices, trackX, thumbY, trackX + 4, thumbY + thumbHeight, thumbColor);
        }
        
        super.render(matrices, mouseX, mouseY, delta);
    }
    
    private void drawBorder(MatrixStack matrices, int x, int y, int width, int height, int color) {
        // Top
        fill(matrices, x, y, x + width, y + 1, color);
        // Bottom  
        fill(matrices, x, y + height - 1, x + width, y + height, color);
        // Left
        fill(matrices, x, y, x + 1, y + height, color);
        // Right
        fill(matrices, x + width - 1, y, x + width, y + height, color);
    }

    private void setScrollOffset(int newOffset) {
        int maxOffset = Math.max(0, workingEntries.size() - maxVisibleEntries);
        int clamped = Math.max(0, Math.min(maxOffset, newOffset));
        if (clamped != scrollOffset) {
            scrollOffset = clamped;
            init();
        }
    }

    private int computeOffsetFromMouseY(double mouseY) {
        int totalItems = workingEntries.size();
        int maxOffset = Math.max(0, totalItems - maxVisibleEntries);
        if (maxOffset == 0) return 0;
        int[] panelDims = calculatePanelDimensions();
        int panelY = panelDims[1];
        int startY = panelY + 55; // Match the button layout
        int spacing = 35;
        int trackTop = startY;
        int trackHeight = maxVisibleEntries * spacing - 4;
        int thumbHeight = Math.max(20, (int)Math.round((double)trackHeight * maxVisibleEntries / totalItems));
        double clampedY = Math.max(trackTop, Math.min(mouseY - thumbHeight / 2.0, trackTop + trackHeight - thumbHeight));
        double ratio = (clampedY - trackTop) / (double)(trackHeight - thumbHeight);
        int offset = (int)Math.round(ratio * maxOffset);
        return Math.max(0, Math.min(maxOffset, offset));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (workingEntries.size() > maxVisibleEntries) {
            int delta = amount > 0 ? -1 : 1;
            setScrollOffset(scrollOffset + delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (workingEntries.size() > maxVisibleEntries && button == 0) {
            int[] panelDims = calculatePanelDimensions();
            int panelX = panelDims[0];
            int panelY = panelDims[1];
            int panelWidth = panelDims[2];
            int startY = panelY + 55; // Match the button layout
            int spacing = 35;
            int trackX = panelX + panelWidth - 10;
            int trackTop = startY;
            int trackHeight = maxVisibleEntries * spacing - 4;
            if (mouseX >= trackX && mouseX <= trackX + 4 && mouseY >= trackTop && mouseY <= trackTop + trackHeight) {
                draggingScrollbar = true;
                setScrollOffset(computeOffsetFromMouseY(mouseY));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingScrollbar) {
            setScrollOffset(computeOffsetFromMouseY(mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar && button == 0) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
}