package com.myriadimg.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents a project (an isolated archive of photos/videos).
 * Projects are stored in the global database to keep track of their locations.
 */
public class Project {
    private int id;
    private String name;
    private String path;
    private LocalDateTime lastOpened;
    private int displayOrder;

    public Project(int id, String name, String path, LocalDateTime lastOpened, int displayOrder) {
        this.id = id;
        this.name = Objects.requireNonNull(name, "Project name cannot be null");
        this.path = Objects.requireNonNull(path, "Project path cannot be null");
        this.lastOpened = lastOpened;
        this.displayOrder = displayOrder;
    }

    public Project(String name, String path) {
        this.name = Objects.requireNonNull(name, "Project name cannot be null");
        this.path = Objects.requireNonNull(path, "Project path cannot be null");
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Project project = (Project) o;
        return id == project.id &&
                displayOrder == project.displayOrder &&
                Objects.equals(name, project.name) &&
                Objects.equals(path, project.path) &&
                Objects.equals(lastOpened, project.lastOpened);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, path, lastOpened, displayOrder);
    }

    @Override
    public String toString() {
        return "Project{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", path='" + path + '\'' +
                '}';
    }
}
