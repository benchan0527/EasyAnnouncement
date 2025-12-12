package com.botamochi.easyannouncement.data;

import net.minecraft.nbt.NbtCompound;

/**
 * Represents a single JSON announcement entry with timing
 * Used for multi-JSON announcement sequences
 */
public class AnnouncementEntry {
    private String jsonName;
    private int delaySeconds;
    
    public AnnouncementEntry() {
        this("", 0);
    }
    
    public AnnouncementEntry(String jsonName, int delaySeconds) {
        this.jsonName = jsonName;
        this.delaySeconds = delaySeconds;
    }
    
    public String getJsonName() {
        return jsonName;
    }
    
    public void setJsonName(String jsonName) {
        this.jsonName = jsonName;
    }
    
    public int getDelaySeconds() {
        return delaySeconds;
    }
    
    public void setDelaySeconds(int delaySeconds) {
        this.delaySeconds = delaySeconds;
    }
    
    public boolean isEmpty() {
        return jsonName == null || jsonName.trim().isEmpty();
    }
    
    public void writeNbt(NbtCompound nbt) {
        nbt.putString("JsonName", jsonName);
        nbt.putInt("DelaySeconds", delaySeconds);
    }
    
    public void readNbt(NbtCompound nbt) {
        this.jsonName = nbt.getString("JsonName");
        this.delaySeconds = nbt.getInt("DelaySeconds");
    }
    
    public AnnouncementEntry copy() {
        return new AnnouncementEntry(this.jsonName, this.delaySeconds);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AnnouncementEntry that = (AnnouncementEntry) obj;
        return delaySeconds == that.delaySeconds && 
               (jsonName != null ? jsonName.equals(that.jsonName) : that.jsonName == null);
    }
    
    @Override
    public int hashCode() {
        int result = jsonName != null ? jsonName.hashCode() : 0;
        result = 31 * result + delaySeconds;
        return result;
    }
    
    @Override
    public String toString() {
        return "AnnouncementEntry{" +
                "jsonName='" + jsonName + '\'' +
                ", delaySeconds=" + delaySeconds +
                '}';
    }
} 