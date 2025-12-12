package com.botamochi.easyannouncement.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class JsonSelectionScreen extends Screen {
    private final Screen parent;
    private final List<String> allJsonFiles;
    private final Consumer<String> onJsonSelected;
    private final String currentSelection;
    
    private List<String> filteredJsonFiles;
    private TextFieldWidget searchField;
    private List<ButtonWidget> jsonButtons;
    private ButtonWidget cancelButton;
    private boolean draggingScrollbar = false;
    private String searchText = "";
    
    private int scrollOffset = 0;
    private final int maxVisibleItems = 8;
    
    // Color scheme
    private static final int BACKGROUND_COLOR = 0x88000000;
    private static final int PANEL_COLOR = 0xAA1E1E1E;
    private static final int BORDER_COLOR = 0xFF3A3A3A;
    private static final int ACCENT_COLOR = 0xFF4A90E2;
    private static final int SUCCESS_COLOR = 0xFF5CB85C;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_COLOR = 0xFFCCCCCC;
    private static final int SELECTED_COLOR = 0xFF2A5A2A;
    private static final int HOVER_COLOR = 0xFF3A3A3A;
    
    // Responsive layout helper
    private int[] calculatePanelDimensions() {
        int minPanelWidth = 300;
        int maxPanelWidth = 400;
        int minPanelHeight = 350;
        int maxPanelHeight = 420;
        
        // Calculate responsive dimensions with margins
        int margin = 40;
        int panelWidth = Math.max(minPanelWidth, Math.min(maxPanelWidth, width - margin * 2));
        int panelHeight = Math.max(minPanelHeight, Math.min(maxPanelHeight, height - margin * 2));
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;
        
        return new int[]{panelX, panelY, panelWidth, panelHeight};
    }
    
    public JsonSelectionScreen(Screen parent, List<String> availableJsonFiles, 
                              Consumer<String> onJsonSelected, String currentSelection) {
        super(Text.translatable("gui.easyannouncement.select_json"));
        this.parent = parent;
        this.allJsonFiles = new ArrayList<>(availableJsonFiles);
        this.onJsonSelected = onJsonSelected;
        this.currentSelection = currentSelection;
        this.filteredJsonFiles = new ArrayList<>(allJsonFiles);
    }

    @Override
    protected void init() {
        this.clearChildren();
        jsonButtons = new ArrayList<>();
        
        int[] panelDims = calculatePanelDimensions();
        int panelX = panelDims[0];
        int panelY = panelDims[1];
        int panelWidth = panelDims[2];
        int panelHeight = panelDims[3];
        
        // Match main screen: centered controls, 200x20 buttons, 40px spacing
        int buttonWidth = 200;
        int buttonHeight = 20;
        int x = this.width / 2 - buttonWidth / 2;
        int yStart = Math.max(20, this.height / 4);
        int yOffset = 40;

        searchField = new TextFieldWidget(textRenderer, x, yStart, buttonWidth, buttonHeight, 
                                         Text.translatable("gui.easyannouncement.search"));
        searchField.setText(searchText);
        searchField.setMaxLength(256);
        searchField.setChangedListener(this::onSearchChanged);
        addDrawableChild(searchField);
        
        int listStartY = yStart + yOffset;
        int spacing = 35;
        
        for (int i = 0; i < maxVisibleItems && (i + scrollOffset) < filteredJsonFiles.size(); i++) {
            final int fileIndex = i + scrollOffset;
            String jsonName = filteredJsonFiles.get(fileIndex);
            int y = listStartY + i * spacing;
            String displayText = jsonName.isEmpty() ? Text.translatable("gui.easyannouncement.empty").getString() : jsonName;
            ButtonWidget jsonButton = new ButtonWidget(x, y, buttonWidth, buttonHeight,
                Text.literal(displayText),
                button -> selectJson(jsonName));
            
            jsonButtons.add(jsonButton);
            addDrawableChild(jsonButton);
        }
        
        int bottomY = Math.min(this.height - 30, listStartY + maxVisibleItems * spacing + 20);
        
        ButtonWidget clearButton = new ButtonWidget(x, bottomY, buttonWidth, buttonHeight,
            Text.translatable("gui.easyannouncement.clear"),
            button -> clearSearch());
        addDrawableChild(clearButton);
        
        cancelButton = new ButtonWidget(x, bottomY + yOffset, buttonWidth, buttonHeight,
            Text.translatable("gui.easyannouncement.cancel"),
            button -> close());
        addDrawableChild(cancelButton);
        
        setFocused(searchField);
    }
    
    // Rebuild only the JSON list buttons to preserve focus on the search field
    private void refreshJsonButtons() {
        if (searchField == null) return;
        
        // Remove existing json buttons from the screen and list
        if (jsonButtons != null) {
            for (ButtonWidget btn : jsonButtons) {
                this.remove(btn);
            }
            jsonButtons.clear();
        } else {
            jsonButtons = new ArrayList<>();
        }
        
        int buttonWidth = 200;
        int buttonHeight = 20;
        int x = this.width / 2 - buttonWidth / 2;
        int yStart = Math.max(20, this.height / 4);
        int yOffset = 40;
        int listStartY = yStart + yOffset;
        int spacing = 35;
        
        for (int i = 0; i < maxVisibleItems && (i + scrollOffset) < filteredJsonFiles.size(); i++) {
            final int fileIndex = i + scrollOffset;
            String jsonName = filteredJsonFiles.get(fileIndex);
            int y = listStartY + i * spacing;
            String displayText = jsonName.isEmpty() ? Text.translatable("gui.easyannouncement.empty").getString() : jsonName;
            ButtonWidget jsonButton = new ButtonWidget(x, y, buttonWidth, buttonHeight,
                Text.literal(displayText),
                button -> selectJson(jsonName));
            jsonButtons.add(jsonButton);
            addDrawableChild(jsonButton);
        }
        
        // Ensure the search field remains focused for continuous typing
        setFocused(searchField);
    }
    
    private void onSearchChanged(String searchText) {
        this.searchText = searchText;
        // Filter the JSON files based on search text
        if (searchText.trim().isEmpty()) {
            filteredJsonFiles = new ArrayList<>(allJsonFiles);
        } else {
            String lowerSearch = searchText.toLowerCase().trim();
            filteredJsonFiles = allJsonFiles.stream()
                .filter(jsonName -> jsonName.toLowerCase().contains(lowerSearch))
                .collect(Collectors.toList());
        }
        
        // Reset scroll offset and only rebuild the list buttons
        scrollOffset = 0;
        
        // Refresh only the JSON list to preserve focus
        refreshJsonButtons();
    }
    
    private void clearSearch() {
        searchText = "";
        searchField.setText("");
        onSearchChanged("");
    }
    
    private void selectJson(String jsonName) {
        onJsonSelected.accept(jsonName);
        close();
    }
    
    private void scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset--;
            refreshJsonButtons();
        }
    }
    
    private void scrollDown() {
        if (scrollOffset < filteredJsonFiles.size() - maxVisibleItems) {
            scrollOffset++;
            refreshJsonButtons();
        }
    }
    
    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, textRenderer, title, width / 2, 15, 0xFFFFFF);
        
        // Draw a minimal scrollbar if needed
        int totalItems = filteredJsonFiles.size();
        if (totalItems > maxVisibleItems) {
            int[] panelDims = calculatePanelDimensions();
            int buttonWidth = 200;
            int x = this.width / 2 - buttonWidth / 2;
            int yStart = Math.max(20, this.height / 4);
            int listStartY = yStart + 40;
            int spacing = 35;
            int trackX = x + buttonWidth + 6; // small scrollbar at right edge of list
            int trackTop = listStartY;
            int trackHeight = maxVisibleItems * spacing - 4;
            int maxOffset = Math.max(0, totalItems - maxVisibleItems);
            int thumbHeight = Math.max(20, (int)Math.round((double)trackHeight * maxVisibleItems / totalItems));
            int thumbY = trackTop;
            if (maxOffset > 0) {
                thumbY = trackTop + (int)Math.round((double)(trackHeight - thumbHeight) * scrollOffset / maxOffset);
            }
            // Track
            fill(matrices, trackX, trackTop, trackX + 4, trackTop + trackHeight, 0xFF666666);
            // Thumb
            int thumbColor = draggingScrollbar ? 0xFFAAAAAA : 0xFF888888;
            fill(matrices, trackX, thumbY, trackX + 4, thumbY + thumbHeight, thumbColor);
        }
        
        super.render(matrices, mouseX, mouseY, delta);
    }

    private void setScrollOffset(int newOffset) {
        int maxOffset = Math.max(0, filteredJsonFiles.size() - maxVisibleItems);
        int clamped = Math.max(0, Math.min(maxOffset, newOffset));
        if (clamped != scrollOffset) {
            scrollOffset = clamped;
            refreshJsonButtons();
        }
    }

    private int computeOffsetFromMouseY(double mouseY) {
        int totalItems = filteredJsonFiles.size();
        int maxOffset = Math.max(0, totalItems - maxVisibleItems);
        if (maxOffset == 0) return 0;
        int buttonWidth = 200;
        int yStart = Math.max(20, this.height / 4);
        int listStartY = yStart + 40;
        int spacing = 35;
        int trackTop = listStartY;
        int trackHeight = maxVisibleItems * spacing - 4;
        int thumbHeight = Math.max(20, (int)Math.round((double)trackHeight * maxVisibleItems / totalItems));
        double clampedY = Math.max(trackTop, Math.min(mouseY - thumbHeight / 2.0, trackTop + trackHeight - thumbHeight));
        double ratio = (clampedY - trackTop) / (double)(trackHeight - thumbHeight);
        int offset = (int)Math.round(ratio * maxOffset);
        return Math.max(0, Math.min(maxOffset, offset));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (filteredJsonFiles.size() > maxVisibleItems) {
            int delta = amount > 0 ? -1 : 1;
            setScrollOffset(scrollOffset + delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (filteredJsonFiles.size() > maxVisibleItems && button == 0) {
            int[] panelDims = calculatePanelDimensions();
            int panelX = panelDims[0];
            int panelY = panelDims[1];
            int panelWidth = panelDims[2];
            int listStartY = panelY + 100;
            int spacing = 28;
            int trackX = panelX + panelWidth - 10;
            int trackTop = listStartY;
            int trackHeight = maxVisibleItems * spacing - 4;
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
    
    private void drawBorder(MatrixStack matrices, int x, int y, int width, int height, int color) {
        fill(matrices, x, y, x + width, y + 1, color);
        fill(matrices, x, y + height - 1, x + width, y + height, color);
        fill(matrices, x, y, x + 1, y + height, color);
        fill(matrices, x + width - 1, y, x + width, y + height, color);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC key to close
        if (keyCode == 256) { // GLFW_KEY_ESCAPE
            close();
            return true;
        }
        
        // Arrow keys for navigation when not in search field
        if (searchField != null && !searchField.isFocused()) {
            if (keyCode == 265) { // GLFW_KEY_UP
                scrollUp();
                return true;
            }
            if (keyCode == 264) { // GLFW_KEY_DOWN
                scrollDown();
                return true;
            }
        }
        
        // Let search field handle key presses first
        if (searchField != null && searchField.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
} 