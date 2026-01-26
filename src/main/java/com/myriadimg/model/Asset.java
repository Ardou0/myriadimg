package com.myriadimg.model;

import java.time.LocalDateTime;

public class Asset {
    private String path; // Relative path from project root or absolute path
    private String hash; // Partial MD5
    private long size;
    private LocalDateTime creationDate;
    private AssetType type;

    public enum AssetType {
        IMAGE, VIDEO, UNKNOWN
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
}
