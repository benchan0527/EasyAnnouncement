# MTR 4.0 移植進度記錄

## 狀態：修復網絡同步問題中 🔧

最後更新：2026-05-05

---

## Bug 修復（第五次）- 1.3.1a

### 問題
`IndexOutOfBoundsException: readerIndex(70) + length(4) exceeds writerIndex(72)`
點擊 Announce 方塊開啟 GUI 時崩潰

### 原因
服務端和客戶端的封包讀寫順序**完全不一致**：
- 服務端寫入順序：BlockPos → seconds → platforms → **entries → repeatEntries** → volume → range → attenuationType → ...
- 客戶端讀取順序：BlockPos → seconds → platforms → **volume → range** → attenuationType → ...（漏掉了 entries 和 repeatEntries）

### 解決方案
完全重寫 `AnnounceSendToClient.java` 和 `AnnounceReceiveFromServer.java`，確保兩端完全一致：

#### 服務端 `sendToClient` 寫入順序：
```java
writeBlockPos(pos);
writeInt(seconds);
writeLongArray(platformIds);
writeInt(entryCount);
for each entry: writeString(jsonName) + writeInt(delay);
writeInt(repeatCount);
for each repeat: writeString(jsonName) + writeInt(delay);
writeFloat(volume);
writeInt(range);
writeString(attenuationType);
writeBoolean(boundingBox);
writeInt(startX/Y/Z), writeInt(endX/Y/Z);
writeString(triggerMode);
writeBoolean(excludePlayersAbove);
writeInt(repeatIntervalSeconds);
```

#### 客戶端接收讀取順序（完全匹配）：
```java
readBlockPos();
readInt();
readLongArray();
readInt(entryCount);
for each: readString() + readInt();
readInt(repeatCount);
for each: readString() + readInt();
readFloat();
readInt();
readString();
readBoolean();
readInt() x6;
readString();
readBoolean();
readInt();
```

### Build 結果
```
BUILD SUCCESSFUL in 9s
```

### 版本
- mod_version=1.3.1a

### 測試步驟
1. 複製 `easyannouncement-1.3.1a.jar` 到 mods 資料夾
2. 啟動 Minecraft
3. 放置 Announce 方塊
4. 右鍵點擊方塊開啟 GUI
5. 確認是否正常運作，不再崩潰

---

## Bug 修復（第四次）

### 問題
1. `IndexOutOfBoundsException` - 網絡同步時字節偏移不匹配
2. GUI 顯示佔位符數據而非 MTR 真實線路/月台數據

### 解決方案

#### 1. 網絡同步修復
- 客戶端讀取順序與服務端寫入順序不一致
- 修復後：兩端都按相同順序讀寫：BlockPos → seconds → platforms → entries → repeatEntries → volume/range → attenuationType → boundingBox → triggerMode...

#### 2. MTR 4.0 API 集成
- 在 `RouteSelectionScreen` 中添加 `loadMTRRoutes()` 方法
- 在 `PlatformSelectionScreen` 中添加 `loadMTRPlatforms()` 方法
- 使用反射調用 `MinecraftClientData.getInstance()` 和 `stations` 字段
- 遍歷 `Station.getOneInterchangeRouteFromEachColor()` 獲取線路
- 解析 `Route.getRouteTypeKey()` 獲取顏色
- 如果 MTR API 不可用，回退到示範數據

### Build 結果
```
BUILD SUCCESSFUL in 22s
```

### 版本
- mod_version=1.3.4a

### 測試步驟
1. 複製 `easyannouncement-1.3.4a.jar` 到 mods 資料夾
2. 啟動 Minecraft
3. 放置 Announce 方塊
4. 檢查 GUI 是否顯示 MTR 真實線路/月台數據
5. 如果仍顯示 "Red Line", "Green Line" 等示範數據，日誌會顯示 `[EA] Using fallback demo routes`

| # | 任務 | 狀態 |
|---|------|------|
| 1 | 複製項目資料夾 | ✅ 完成 |
| 2 | 更新 build.gradle / gradle.properties | ✅ 完成 |
| 3 | 更新 fabric.mod.json | ✅ 完成 |
| 4 | 重寫 Easyannouncement.java | ✅ 完成 |
| 5 | 重寫 AnnounceTile.java | ✅ 完成 |
| 6 | 建立 PacketHandler Class | ⚠️ 已改用 Fabric 原生網絡 |
| 7 | 重寫 AnnounceReceiveFromServer.java | ✅ 完成 |
| 8 | 重寫 EasyannouncementClient.java | ✅ 完成 |
| 9 | 重寫 AnnounceBlock.java + EATile.java | ✅ 完成 |
| 10 | 更新 GUI Screens | ✅ 無需改動 |
| 11 | 檢查 PlatformSelectionEvent.java | ✅ 完成 |
| 12 | 迭代替換 import + Build | ✅ 完成 |

---

## 完成的工作細節

### 1. 專案複製 ✅
- 將 `C:\easy\EasyAnnouncement-main old for 3.0_backup` 複製到 `C:\easy\EasyAnnouncement-main new for 4.0`

### 2. Build 設定更新 ✅

**gradle.properties**:
```properties
mod_version=1.3.0
mtr_version=4.0.4
```

**build.gradle**:
```groovy
repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = "https://api.modrinth.com/maven"
            }
        }
        filter {
            includeGroup "maven.modrinth"
        }
    }
}

dependencies {
    modImplementation "maven.modrinth:minecraft-transit-railway:FABRIC-4.0.4+1.19.2"
}
```

### 3. fabric.mod.json 更新 ✅
```json
"dependencies": {
    "mtr": ">=4.0.0"
}
```

### 4. 重寫 Easyannouncement.java (服務端入口) ✅
- 使用原生 Fabric Registry (`Registry.register`)
- 使用 Fabric Event (`ServerLifecycleEvents`, `ServerWorldEvents`, `ServerTickEvents`)
- 移除 MTR 3.0 API import
- 修正 `EASounds.init()` → `EASounds.register()`

### 5. 重寫 AnnounceTile.java (核心) ✅
- 移除所有 `mtr.data.*` import
- 引入 `CachedArrival` 和 `refreshArrivalCache()` 機制
- 使用 try-catch 包裹 MTR 4.0 API 調用
- 由於 `ServerWorld` 類型不相容，提供佔位符數據作為回退

### 6. 封包系統 ⚠️ 策略調整
**原計劃**: 使用 MTR 4.0 PacketHandler 系統
**實際**: 維持使用 Fabric 原生 `ServerPlayNetworking` / `ClientPlayNetworking`

原因：MTR 4.0 的 mapping 層無法直接訪問，保持 Fabric 原生 API 更穩定

### 7. 重寫 AnnounceReceiveFromServer.java ✅
- 修正 `ClientPlayNetworking.registerGlobalReceiver` lambda 簽名 (4 個參數)
- 正確處理封包讀取
- 嘗試使用 `MinecraftClientData.getInstance()` 獲取 MTR 數據

### 8. 重寫 EasyannouncementClient.java ✅
- 正確的 ScreenRegistry 註冊
- 正確引入 MainScreen

### 9. 重寫 AnnounceBlock.java + EATile.java ✅
- `AnnounceBlock`: 使用原生 `BlockWithEntity` + `FabricBlockSettings`
- `EATile`: 使用 `FabricBlockEntityTypeBuilder.create`

### 10. GUI Screens ✅
**結論**: 無需改動
- GUI Screens 位於 `src/main` (共用代碼)，使用原生 Fabric API
- `MainScreen.java` 無需修改

### 11. PlatformSelectionEvent.java ✅
- 將 `Platform` 類型改為 `Long`
- 保持功能不變

### 12. 清理不需要的檔案 ✅
| 刪除的檔案 | 原因 |
|------------|------|
| `ClientNetworkHandler.java` | 與 AnnounceReceiveFromServer 功能重複 |
| `MTRIntegrationService.java` | MTR 3.0 整合，已過時 |
| `EnhancedAnnouncementHelper.java` | 依賴已刪除的服務 |
| `AnnouncementContext.java` | 重複的類別 |
| `PacketEAConfigS2C.java` | 改用 Fabric 原生網絡 |
| `PacketEAStartS2C.java` | 改用 Fabric 原生網絡 |
| `PacketEAFinishedC2S.java` | 改用 Fabric 原生網絡 |

---

## 技術決策

### 採用策略: 原生 Fabric API + MTR 數據橋接

| 組件 | 策略 |
|------|------|
| 方塊/物品/方塊實體註冊 | 原生 Fabric API |
| 網絡通信 | Fabric 原生 `ServerPlayNetworking` / `ClientPlayNetworking` |
| MTR 數據訪問 | 嘗試使用 `ArrivalsCacheServer`，失敗時使用佔位符 |

### 不使用 MTR 4.0 PacketHandler 系統
- 維持使用 Fabric 原生網絡 API
- 簡化整合複雜度
- 避免 mapping 層訪問問題

---

## 已知限制

### MTR 4.0 API 兼容性問題
- **問題**: `ArrivalsCacheServer.getInstance()` 需要 `org.mtr.mapping.holder.ServerWorld`，但 Fabric 使用 `net.minecraft.server.world.ServerWorld`，兩者不相容
- **解決方案**: try-catch 包裹調用，失敗時提供佔位符數據
- **影響**: Mod 可編譯運行，但班次數據使用佔位符

### 佔位符數據行為
- 每 5 秒刷新一次緩存
- 為每個選擇的月台生成 4 個「下一班列車」（5 分鐘間隔）
- 目的地為 "next_train"

---

## 測試結果

```
BUILD SUCCESSFUL in 21s
9 actionable tasks: 4 executed, 5 up-to-date
```

- `compileJava` (服務端): ✅
- `compileClientJava` (客戶端): ✅
- `build` (完整): ✅

---

## 最終檔案結構

```
src/main/java/com/botamochi/easyannouncement/
├── Easyannouncement.java              # 服務端入口
├── block/
│   └── AnnounceBlock.java             # 方塊
├── data/
│   └── AnnouncementEntry.java
├── event/
│   └── PlatformSelectionEvent.java
├── network/
│   └── AnnounceSendToClient.java      # 網絡發送
├── registry/
│   ├── EASounds.java
│   ├── EATab.java
│   └── EATile.java                    # 方塊實體類型
├── screen/
│   ├── EAScreenHandlers.java
│   └── MainScreenHandler.java
├── tile/
│   └── AnnounceTile.java              # 核心邏輯
└── world/
    └── AnnounceTilePositionsSavedData.java

src/client/java/com/botamochi/easyannouncement/client/
├── EasyannouncementClient.java         # 客戶端入口
├── AnnounceReceiveFromServer.java     # 封包接收
├── MainScreen.java                   # GUI
├── PlatformSelectionScreen.java
├── RouteSelectionScreen.java
├── JsonSelectionScreen.java
└── MultiJsonSelectionScreen.java
```

---

## 版本信息

| 項目 | 版本 |
|------|------|
| Mod Version | 1.3.0 |
| Minecraft | 1.19.2 |
| Fabric Loader | 0.16.10 |
| Fabric API | 0.77.0+ |
| MTR Version | 4.0.4 |
| Build Tool | Gradle 8.12 / Fabric Loom 1.10.5 |

---

## 產出位置

- **Mod JAR**: `C:\easy\EasyAnnouncement-main new for 4.0\build\libs\`
- **進度記錄**: `C:\easy\EasyAnnouncement-main new for 4.0\MTR_4.0_PORT_PROGRESS.md`

---

## 下一步建議

1. **完整功能測試**: 在遊戲中放置 Announce 方塊，測試 GUI 和觸發邏輯
2. **MTR 數據整合優化**: 若需要真實 MTR 數據，需研究如何橋接 MTR mapping 層
3. **音效系統測試**: 測試 JSON 音效序列播放
4. **重複播放功能**: 測試 repeat entries 是否正常運作

---

*移植完成於 2026-05-04*
