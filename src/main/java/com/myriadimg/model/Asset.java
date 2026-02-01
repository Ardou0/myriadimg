package com.myriadimg.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a digital asset (image or video) managed by the application.
 * This is a lightweight POJO used to transfer data between layers.
 */
public class Asset {
    private String path; // Relative path from project root or absolute path
    private String hash; // Partial MD5 for deduplication
    private long size;
    private LocalDateTime creationDate;
    private AssetType type;
    private List<Tag> tags = new ArrayList<>();

    public enum AssetType {
        IMAGE, VIDEO, ICON, UNKNOWN
    }

    public Asset(String path, String hash, long size, LocalDateTime creationDate, AssetType type) {
        this.path = path;
        this.hash = hash;
        this.size = size;
        this.creationDate = creationDate;
        this.type = type;
    }

    public String getPath() { return path; }
    public String getHash() { return hash; }
    public long getSize() { return size; }
    public LocalDateTime getCreationDate() { return creationDate; }
    public AssetType getType() { return type; }

    public void setCreationDate(LocalDateTime creationDate) {
        this.creationDate = creationDate;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public void addTag(Tag tag) {
        this.tags.add(tag);
    }
}
