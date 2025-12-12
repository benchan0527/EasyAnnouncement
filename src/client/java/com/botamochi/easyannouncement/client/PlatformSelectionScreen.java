package com.botamochi.easyannouncement.client;

import com.botamochi.easyannouncement.network.AnnounceSendToClient;
import com.botamochi.easyannouncement.tile.AnnounceTile;
import com.botamochi.easyannouncement.data.AnnouncementEntry;
import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Station;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class PlatformSelectionScreen extends Screen {
    private final BlockPos blockPos;
    private List<Platform> platforms;
    private HashSet<Long> selectedPlatforms;
    private int scrollOffset = 0;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 5;
    private static final int VISIBLE_BUTTONS = 5;
    private static final int BUTTON_WIDTH = 200;
    private int maxScroll = 0;
    private int listX;
    private int listYStart;
    private int listYOffset;

    public PlatformSelectionScreen(BlockPos blockPos, List<Long> selectedPlatforms) {
        super(Text.translatable("gui.easyannouncement.platform_selection"));
        this.blockPos = blockPos;
        this.selectedPlatforms = new HashSet<>(selectedPlatforms); // 初期化
        this.platforms = getPlatforms();
    }

    private List<Platform> getPlatforms() {
        World world = MinecraftClient.getInstance().world;
        if (world == null) {
            return new ArrayList<>();  // 空のリストを返す
        }

        // 駅情報を取得する
        Station station = RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, blockPos);
        if (station == null) {
            return new ArrayList<>();  // 駅がnullの場合、空のリストを返す
        }

        // 駅のプラットフォームをリクエストして返す
        return new ArrayList<>(ClientData.DATA_CACHE.requestStationIdToPlatforms(station.id).values());
    }

    @Override
    protected void init() {
        super.init();

        // プラットフォームの選択ボタンを更新
        updateButtons();
    }

    private void updateButtons() {
        this.clearChildren();  // 既存のボタンをクリア

        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int yStart = this.height / 4;
        int yOffset = BUTTON_HEIGHT + BUTTON_SPACING;
        listX = x;
        listYStart = yStart;
        listYOffset = yOffset;
        maxScroll = Math.max(0, platforms.size() - VISIBLE_BUTTONS);

        for (int i = 0; i < VISIBLE_BUTTONS && i + scrollOffset < platforms.size(); i++) {
            Platform platform = platforms.get(i + scrollOffset);
            boolean isSelected = selectedPlatforms.contains(platform.id);
            this.addDrawableChild(new ButtonWidget(x, yStart + i * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT,
                    Text.translatable("gui.easyannouncement.platform").append(" ").append(Text.of(cleanMtrText(platform.name))).append(isSelected ? Text.translatable("gui.easyannouncement.selected") : Text.of("")), button -> {
                if (selectedPlatforms.contains(platform.id)) {
                    selectedPlatforms.remove(platform.id);
                } else {
                    selectedPlatforms.add(platform.id);
                }
                updateButtons();
            }));
        }

        // スクロールボタンの追加
        addScrollButtons(x, yStart, yOffset);

        // 保存ボタン
        this.addDrawableChild(new ButtonWidget(x, yStart + (VISIBLE_BUTTONS + 1) * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT, Text.translatable("gui.easyannouncement.save"), button -> {
            saveSelectionAndClose();
        }));
    }

    private void addScrollButtons(int x, int yStart, int yOffset) {
        // 上スクロールボタン
        if (scrollOffset > 0) {
            this.addDrawableChild(new ButtonWidget(x, yStart - yOffset, BUTTON_WIDTH, BUTTON_HEIGHT, Text.translatable("gui.easyannouncement.scroll_up"), button -> {
                scrollOffset = Math.max(0, scrollOffset - 1);
                updateButtons();
            }));
        }

        // 下スクロールボタン
        if (scrollOffset + VISIBLE_BUTTONS < platforms.size()) {
            this.addDrawableChild(new ButtonWidget(x, yStart + VISIBLE_BUTTONS * yOffset, BUTTON_WIDTH, BUTTON_HEIGHT, Text.translatable("gui.easyannouncement.scroll_down"), button -> {
                scrollOffset = Math.min(platforms.size() - VISIBLE_BUTTONS, scrollOffset + 1);
                updateButtons();
            }));
        }
    }

    private void saveSelectionAndClose() {
        World world = MinecraftClient.getInstance().world;
        if (world != null) {
            BlockEntity blockEntity = world.getBlockEntity(blockPos);
            if (blockEntity instanceof AnnounceTile announceTile) {
                try {
                    int seconds = announceTile.getSeconds(); // AnnounceTileから秒数を取得
                    announceTile.setSeconds(seconds); // 秒数をAnnounceTileに設定
                    announceTile.setSelectedPlatformIds(new ArrayList<>(selectedPlatforms)); // プラットフォームIDを設定
                    List<AnnouncementEntry> entries = announceTile.getAnnouncementEntries(); // 複数エントリを取得
                    sendUpdatePacket(blockPos, seconds, selectedPlatforms, entries); // サーバーに送信
                } catch (NumberFormatException e) {
                    sendUpdatePacket(announceTile.getPos(), 0, selectedPlatforms, announceTile.getAnnouncementEntries());
                }
            }
        }
        this.client.setScreen(null);  // Saveが完了したら画面を閉じる
    }

    private void sendUpdatePacket(BlockPos pos, int seconds, HashSet<Long> selectedPlatforms, List<AnnouncementEntry> entries) {
        if (MinecraftClient.getInstance().player == null) {
            return;
        }

        // サーバーにパケットを送信
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeInt(seconds);  // 秒数を送信
        buf.writeLongArray(selectedPlatforms.stream().mapToLong(Long::longValue).toArray());  // 選択したプラットフォームを送信
        
        // Write announcement entries in the expected format
        if (entries != null && !entries.isEmpty()) {
            buf.writeInt(entries.size());
            for (AnnouncementEntry entry : entries) {
                buf.writeString(entry.getJsonName());
                buf.writeInt(entry.getDelaySeconds());
            }
        } else {
            buf.writeInt(0); // No entries
        }
        
        // Write sound configuration (defaults)
        float volume = 2.0F;
        int range = 64;
        String attenuationType = "LINEAR";
        boolean boundingBoxEnabled = false;
        int startX = -100, startY = -64, startZ = -100, endX = 100, endY = 320, endZ = 100;

        // Use current tile configuration if available
        if (MinecraftClient.getInstance().world != null &&
            MinecraftClient.getInstance().world.getBlockEntity(pos) instanceof AnnounceTile tile) {
            volume = tile.getSoundVolume();
            range = tile.getSoundRange();
            attenuationType = tile.getAttenuationType();
            boundingBoxEnabled = tile.isBoundingBoxEnabled();
            startX = tile.getStartX();
            startY = tile.getStartY();
            startZ = tile.getStartZ();
            endX = tile.getEndX();
            endY = tile.getEndY();
            endZ = tile.getEndZ();
        }

        // Write sound configuration
        buf.writeFloat(volume);
        buf.writeInt(range);
        buf.writeString(attenuationType);

        // Write bounding box enabled flag and coordinates in expected order
        buf.writeBoolean(boundingBoxEnabled);
        buf.writeInt(startX);
        buf.writeInt(startY);
        buf.writeInt(startZ);
        buf.writeInt(endX);
        buf.writeInt(endY);
        buf.writeInt(endZ);
        
        // Write trigger mode last (optional older server compatibility)
        String triggerMode = "EXACT";
        if (MinecraftClient.getInstance().world != null &&
            MinecraftClient.getInstance().world.getBlockEntity(pos) instanceof AnnounceTile tile2) {
            triggerMode = tile2.getTriggerMode();
        }
        buf.writeString(triggerMode);

        // サーバーに送信
        ClientPlayNetworking.send(AnnounceSendToClient.ID, buf); // IDの変更
    }

    /**
     * Cleans MTR text by removing parentheses, periods, commas, and spaces,
     * then converts to lowercase
     */
    private String cleanMtrText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // Remove (), ., , and spaces, then convert to lowercase
        return text.replaceAll("[(),. ]", "").toLowerCase();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (platforms == null || platforms.isEmpty()) {
            return super.mouseScrolled(mouseX, mouseY, amount);
        }
        int listTop = listYStart - listYOffset;
        int listBottom = listYStart + VISIBLE_BUTTONS * listYOffset;
        int listLeft = listX;
        int listRight = listX + BUTTON_WIDTH;
        if (mouseY >= listTop && mouseY <= listBottom && mouseX >= listLeft && mouseX <= listRight) {
            if (amount < 0) {
                scrollOffset = Math.min(maxScroll, scrollOffset + 1);
            } else if (amount > 0) {
                scrollOffset = Math.max(0, scrollOffset - 1);
            }
            updateButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, this.height / 4 - 20, 0xFFFFFF);
        super.render(matrices, mouseX, mouseY, delta);

        // draw a simple scrollbar track and thumb to indicate position
        if (platforms != null && platforms.size() > VISIBLE_BUTTONS) {
            int trackX = listX + BUTTON_WIDTH + 6;
            int trackY = listYStart - 2;
            int trackH = VISIBLE_BUTTONS * listYOffset - (listYOffset - BUTTON_HEIGHT);
            fill(matrices, trackX, trackY, trackX + 4, trackY + trackH, 0x55000000);

            // thumb height proportional to visible fraction
            int thumbH = Math.max(12, (int) Math.round(trackH * (VISIBLE_BUTTONS / (double) platforms.size())));
            int thumbY = trackY + (int) Math.round((trackH - thumbH) * (scrollOffset / (double) Math.max(1, maxScroll)));
            fill(matrices, trackX, thumbY, trackX + 4, thumbY + thumbH, 0x99FFFFFF);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;  // この画面を開いている間、ゲームを一時停止させない
    }
}