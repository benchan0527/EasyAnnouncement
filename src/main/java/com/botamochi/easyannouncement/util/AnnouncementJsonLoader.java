package com.botamochi.easyannouncement.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public class AnnouncementJsonLoader {
    // [UNUSED] soundDataMap and related methods are not used - kept for potential future use
    private static final Map<String, Map<String, Double>> soundDataMap = new LinkedHashMap<>();

    // JSON ファイル名を引数として受け取るように修正
    public static void loadJson(InputStream inputStream, String jsonFileName) {
        try (InputStreamReader reader = new InputStreamReader(inputStream)) {
            Gson gson = new Gson();
            JsonObject jsonObject = gson.fromJson(reader, JsonObject.class);
            JsonArray soundsArray = jsonObject.getAsJsonArray("sounds");

            Map<String, Double> soundDurationMap = new LinkedHashMap<>();
            for (JsonElement element : soundsArray) {
                JsonObject soundObject = element.getAsJsonObject();
                String soundId = soundObject.get("soundId").getAsString();
                // [UNUSED] duration is read but not used anywhere
                double duration = soundObject.get("duration").getAsDouble();
                soundDurationMap.put(soundId, duration);
            }

            // 引数として受け取った JSON ファイル名を使用
            soundDataMap.put(jsonFileName, soundDurationMap);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // [UNUSED] method not used
    public static List<String> getSoundsForJson(String jsonName) {
        Map<String, Double> soundDurationMap = soundDataMap.get(jsonName);
        if (soundDurationMap == null) {
            return null;
        }

        return new ArrayList<>(soundDurationMap.keySet());
    }

    // [UNUSED] method not used
    public static double getDurationForSound(String jsonName, String soundId) {
        Map<String, Double> soundDurationMap = soundDataMap.get(jsonName);
        if (soundDurationMap == null) {
            return 1.0;
        }


        return soundDurationMap.getOrDefault(soundId, 1.0);
    }

    // [UNUSED] method not used
    public static void addDurationForSound(String jsonName, String soundId, double duration) {
        Map<String, Double> soundDurationMap = soundDataMap.get(jsonName);
        if (soundDurationMap != null) {
            soundDurationMap.put(soundId, duration);
        } else {
            Map<String, Double> newSoundDurationMap = new LinkedHashMap<>();
            newSoundDurationMap.put(soundId, duration);
            soundDataMap.put(jsonName, newSoundDurationMap);
        }
    }
}
