# releases EasyAnnouncement Download
## [prereleases](https://github.com/benchan0527/EasyAnnouncement/releases/tag/beta)

# Usage Guide

EasyAnnouncement allows you to create custom station announcements using JSON files.

## 📝 JSON File Creation

### 1. JSON File Format

Create a JSON file and refer to the following example:

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

### 2. MTR-specific JSON Example

**added announcements:**
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

**For English announcements:**
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

### 3. Parameter Explanation

| Parameter | Description |
|-----------|-------------|
| `"sounds"` | Required field |
| `"soundPath"` | Audio file path |
| `"duration"` | Audio length + interval |

### 4. Dynamic Variables

| Variable | Content |
|----------|---------|
| `($track)` | Platform name |
| `($route)` | Route name |
| `($routetype)` | Service type |
| `($boundfor)` | Destination |
| `($hh)` | Arrival hour (00-23) |
| `($mm)` | Arrival minute (00-59) |

## 📁 File Placement

### 1. Resource Pack Structure

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

### 2. File Placement Steps

1. Place JSON file in `<resource_pack_name>/assets/easyannouncement/sounds`
2. Don't forget to register audio files in `sounds.json`
3. Apply the resource pack

### 3. sounds.json Example

`

## 🎮 In-Game Setup

### 1. Setup Steps

1. Right-click the announcement block to open GUI
2. Click "Select JSON" and choose your created JSON
3. Enter platform name (required, won't work if empty)
4. Enter seconds (entering 10 means announcement plays 10 seconds before)
5. Save and you're done

### 2. Troubleshooting

**If announcements don't play:**

| Problem | Solution |
|---------|----------|
| Platform empty | Enter platform name |
| Invalid JSON | Check JSON syntax |
| Audio file missing | Check sounds.json |

**Default announcement on failure:**
- Default announcement plays


For details, see [MTR_INTEGRATION_GUIDE.md](MTR_INTEGRATION_GUIDE.md).

---

That's how to use it. Please ask if you have any questions.
