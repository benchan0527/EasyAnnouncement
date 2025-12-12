# EasyAnnouncement ダウンロード / Download / 下載
## [releases](https://github.com/botamochi129/EasyAnnouncement/releases)

# 🚄 MTR Integration / MTR集成功能
EasyAnnouncement now features intelligent MTR mod integration! See [MTR_INTEGRATION_GUIDE.md](MTR_INTEGRATION_GUIDE.md) for details.
EasyAnnouncement現已支援智能MTR模組集成！詳情請參閱 [MTR_INTEGRATION_GUIDE.md](MTR_INTEGRATION_GUIDE.md)。

---

# 使用方法 / Usage Guide / 使用指南

EasyAnnouncementでは、jsonも使い駅自動放送を流すことが出来ます。
EasyAnnouncement allows you to create custom station announcements using JSON files.
EasyAnnouncement 允許您使用 JSON 文件創建自定義車站廣播。

## 📝 JSON文件創建 / JSON File Creation / JSON文件創建

### 1. JSON文件格式 / JSON File Format / JSON文件格式

創建JSON文件並參考以下示例：
Create a JSON file and refer to the following example:
創建JSON文件並參考以下示例：

```json
    {
      "sounds": [
        {
          "soundPath": "mamonaku",
          "duration": 1
        },
        {
          "soundPath": "($track)",
          "duration": 2
        },
        {
          "soundPath": "($route)",
          "duration": 1.5
        },
        {
          "soundPath": "($routetype)",
          "duration": 1.7
        },
        {
          "soundPath": "($boundfor)",
          "duration": 2.5
        },
        {
          "soundPath": "mairimasu",
          "duration": 0
        }
      ]
    }
```

### 2. 專用於MTR的JSON示例 / MTR-specific JSON Example / MTR專用JSON示例

**For Hong Kong MTR-style announcements / 香港MTR風格廣播:**
```json
{
  "sounds": [
    {
      "soundPath": "mtr_chime",
      "duration": 1.5
    },
    {
      "soundPath": "attention_please_chi",
      "duration": 2
    },
    {
      "soundPath": "($track)",
      "duration": 1.5
    },
    {
      "soundPath": "($route)_line_chi",
      "duration": 2
    },
    {
      "soundPath": "to_($boundfor)_chi",
      "duration": 3
    },
    {
      "soundPath": "is_arriving_chi",
      "duration": 2
    },
    {
      "soundPath": "mind_gap_chi",
      "duration": 3
    }
  ]
}
```

**For English announcements / 英文廣播:**
```json
{
  "sounds": [
    {
      "soundPath": "attention_please_eng",
      "duration": 2
    },
    {
      "soundPath": "train_on_platform_($track)",
      "duration": 2.5
    },
    {
      "soundPath": "($route)_line_eng",
      "duration": 2
    },
    {
      "soundPath": "to_($boundfor)_eng",
      "duration": 3
    },
    {
      "soundPath": "is_arriving_eng",
      "duration": 2
    },
    {
      "soundPath": "please_stand_clear",
      "duration": 2.5
    }
  ]
}
```

### 3. パラメータ説明 / Parameter Explanation / 參數說明

| パラメータ | Parameter | 參數 | 説明 | Description | 說明 |
|-----------|-----------|------|------|-------------|------|
| `"sounds"` | `"sounds"` | `"sounds"` | 必須項目 | Required field | 必填欄位 |
| `"soundPath"` | `"soundPath"` | `"soundPath"` | 音声ファイルのパス | Audio file path | 音頻文件路徑 |
| `"duration"` | `"duration"` | `"duration"` | 音声の長さ＋間隔 | Audio length + interval | 音頻長度+間隔 |

### 4. 動的変数 / Dynamic Variables / 動態變數

| 変数 | Variable | 變數 | 内容 | Content | 內容 |
|------|----------|------|------|---------|------|
| `($track)` | `($track)` | `($track)` | ホーム名 | Platform name | 月台名稱 |
| `($route)` | `($route)` | `($route)` | 路線名 | Route name | 路線名稱 |
| `($routetype)` | `($routetype)` | `($routetype)` | 種別 | Service type | 服務類型 |
| `($boundfor)` | `($boundfor)` | `($boundfor)` | 行き先 | Destination | 目的地 |
| `($hh)` | `($hh)` | `($hh)` | 到着時刻（時・00-23） | Arrival hour (00-23) | 到達小時（00-23） |
| `($mm)` | `($mm)` | `($mm)` | 到着時刻（分・00-59） | Arrival minute (00-59) | 到達分鐘（00-59） |

## 📁 ファイル配置 / File Placement / 文件放置

### 1. リソースパック構造 / Resource Pack Structure / 資源包結構

```
resource_pack_name/
├── pack.mcmeta
├── assets/
│   └── easyannouncement/
│       ├── sounds/
│       │   ├── your_announcement.json
│       │   ├── mtr_announcements.json
│       │   └── custom_sounds.json
│       └── sounds.json
```

### 2. ファイル配置手順 / File Placement Steps / 文件放置步驟

**日本語：**
1. JSONファイルを `<リソースパック名>/assets/easyannouncement/sounds` に配置
2. 使用する音声の `sounds.json` への記入を忘れずに
3. リソースパックを適用

**English:**
1. Place JSON file in `<resource_pack_name>/assets/easyannouncement/sounds`
2. Don't forget to register audio files in `sounds.json`
3. Apply the resource pack

**中文：**
1. 將JSON文件放置在 `<資源包名>/assets/easyannouncement/sounds`
2. 別忘記在 `sounds.json` 中註冊音頻文件
3. 套用資源包

### 3. sounds.json 例 / sounds.json Example / sounds.json 示例

```json
{
  "mamonaku": {
    "category": "voice",
    "sounds": ["easyannouncement:sounds/mamonaku"]
  },
  "mairimasu": {
    "category": "voice", 
    "sounds": ["easyannouncement:sounds/mairimasu"]
  },
  "mtr_chime": {
    "category": "voice",
    "sounds": ["easyannouncement:sounds/mtr_chime"]
  },
  "attention_please_chi": {
    "category": "voice",
    "sounds": ["easyannouncement:sounds/attention_please_chi"]
  },
  "attention_please_eng": {
    "category": "voice",
    "sounds": ["easyannouncement:sounds/attention_please_eng"]
  }
}
```

## 🎮 ゲーム内設定 / In-Game Setup / 遊戲內設定

### 1. 設定手順 / Setup Steps / 設定步驟

**日本語：**
1. アナウンスブロックを右クリックしてGUIを開く
2. "Select JSON" をクリックして作成したJSONを選択
3. プラットフォーム名を入力（空だと動作しません）
4. 秒数を入力（10と入力すると10秒前に放送）
5. 保存して完了

**English:**
1. Right-click the announcement block to open GUI
2. Click "Select JSON" and choose your created JSON
3. Enter platform name (required, won't work if empty)
4. Enter seconds (entering 10 means announcement plays 10 seconds before)
5. Save and you're done

**中文：**
1. 右鍵點擊廣播方塊打開GUI
2. 點擊 "Select JSON" 選擇您創建的JSON
3. 輸入月台名稱（必填，空白則無法運作）
4. 輸入秒數（輸入10表示提前10秒播放廣播）
5. 保存即完成

### 2. トラブルシューティング / Troubleshooting / 故障排除

**放送が流れない場合 / If announcements don't play / 如果廣播不播放：**

| 問題 | Problem | 問題 | 解決方法 | Solution | 解決方案 |
|------|---------|------|----------|----------|----------|
| プラットフォーム空白 | Platform empty | 月台空白 | プラットフォーム名を入力 | Enter platform name | 輸入月台名稱 |
| JSON不正 | Invalid JSON | JSON無效 | JSON構文を確認 | Check JSON syntax | 檢查JSON語法 |
| 音声ファイル不明 | Audio file missing | 音頻文件遺失 | sounds.jsonを確認 | Check sounds.json | 檢查sounds.json |

**失敗時のデフォルト放送 / Default announcement on failure / 失敗時的預設廣播：**
- 日本語: 「まもなく まいります」
- English: Default announcement plays
- 中文: 播放預設廣播

## 🚄 MTR集成功能 / MTR Integration Features / MTR集成功能

新しいMTR集成機能により、以下が可能になりました：
With the new MTR integration features, you can now:
透過新的MTR集成功能，您現在可以：

### リアルタイム情報 / Real-time Information / 即時資訊
- ⏰ 現在時刻 / Current time / 現在時間
- 🚉 駅情報 / Station information / 車站資訊
- 🚆 列車状況 / Train status / 列車狀態
- 📅 時刻表情報 / Schedule information / 時刻表資訊

### スマート放送例 / Smart Announcement Examples / 智能廣播示例

**English Example:**
```
Current time is 14:25, welcome to Central Station, Zone 1.
Upcoming trains: Tsuen Wan line to Tsuen Wan, arriving in 2 minutes, 8 cars.
Please mind the platform gap and give way to alighting passengers.
```

**中文示例：**
```
現在時間14:25，歡迎來到中環站，第1區。
即將到達的列車：荃灣線開往荃灣，約2分鐘後到達，8節車廂。
請注意月台間隙，讓路予下車乘客。
```

詳細は [MTR_INTEGRATION_GUIDE.md](MTR_INTEGRATION_GUIDE.md) をご覧ください。
For details, see [MTR_INTEGRATION_GUIDE.md](MTR_INTEGRATION_GUIDE.md).
詳情請參閱 [MTR_INTEGRATION_GUIDE.md](MTR_INTEGRATION_GUIDE.md)。

---

以上が使い方です。質問があればお願いします。
That's how to use it. Please ask if you have any questions.
以上就是使用方法。如有疑問請隨時詢問。
