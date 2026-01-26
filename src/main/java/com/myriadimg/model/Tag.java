package com.myriadimg.model;

public class Tag {
    private int id;
    private String value;   // The normalized unique value (e.g., "vacation", "person_123")
    private String pseudo;  // The display name (e.g., "Vacances", "Maman")
    private TagType type;

    public enum TagType {
        MANUAL,     // Added by user
        AI_SCENE,   // Detected scene (Beach, Mountain)
        AI_OBJECT,  // Detected object (Car, Dog)
        AI_PERSON   // Detected face/person
    }

    public Tag(int id, String value, String pseudo, TagType type) {
        this.id = id;
        this.value = value;
        this.pseudo = pseudo;
        this.type = type;
    }

    public Tag(String value, String pseudo, TagType type) {
        this.value = value;
        this.pseudo = pseudo;
        this.type = type;
    }

    public int getId() { return id; }
    public String getValue() { return value; }
    public String getPseudo() { return pseudo; }
    public TagType getType() { return type; }

    public void setPseudo(String pseudo) {
        this.pseudo = pseudo;
    }
}
