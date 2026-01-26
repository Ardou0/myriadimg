package com.myriadimg.model;

import java.time.LocalDateTime;

public class Project {
    private int id;
    private String name;
    private String path;
    private LocalDateTime lastOpened;
    private int displayOrder;

    public Project(int id, String name, String path, LocalDateTime lastOpened, int displayOrder) {
        this.id = id;
        this.name = name;
        this.path = path;
        this.lastOpened = lastOpened;
        this.displayOrder = displayOrder;
    }

    public Project(String name, String path) {
        this.name = name;
        this.path = path;
        this.lastOpened = LocalDateTime.now();
        this.displayOrder = 0;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getPath() { return path; }
    public LocalDateTime getLastOpened() { return lastOpened; }
    public int getDisplayOrder() { return displayOrder; }

    public void setLastOpened(LocalDateTime lastOpened) {
        this.lastOpened = lastOpened;
    }
    
    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
