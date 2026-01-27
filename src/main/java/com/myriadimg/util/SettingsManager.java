package com.myriadimg.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages application settings and preferences.
 * Handles both global settings (stored in user home) and project-specific settings.
 * Uses Jackson for JSON serialization/deserialization.
 */
public class SettingsManager {

    private static final String APP_DIR = ".MyriadImgGlobal";
    private static final String SETTINGS_FILE = "settings.json";
    private static final String PROJECT_DIR = ".MyriadImg";
    
    private static SettingsManager instance;
    private final File settingsFile;
    private final ObjectMapper mapper;
    private JsonNode rootNode;

    private SettingsManager() {
        String userHome = System.getProperty("user.home");
        File appDir = new File(userHome, APP_DIR);
        if (!appDir.exists()) {
            appDir.mkdirs();
        }
        settingsFile = new File(appDir, SETTINGS_FILE);
        mapper = new ObjectMapper();
        loadSettings();
    }

    public static synchronized SettingsManager getInstance() {
        if (instance == null) {
            instance = new SettingsManager();
        }
        return instance;
    }

    private void loadSettings() {
        if (settingsFile.exists()) {
            try {
                rootNode = mapper.readTree(settingsFile);
            } catch (IOException e) {
                e.printStackTrace();
                rootNode = mapper.createObjectNode();
            }
        } else {
            rootNode = mapper.createObjectNode();
        }
    }

    public String getLanguage() {
        if (rootNode.has("language")) {
            return rootNode.get("language").asText();
        }
        return "fr"; // Default
    }

    public void setLanguage(String lang) {
        ((ObjectNode) rootNode).put("language", lang);
        saveSettings();
    }
    
    public List<Double> getProjectDividerPositions() {
        List<Double> positions = new ArrayList<>();
        if (rootNode.has("project_split_positions")) {
            JsonNode array = rootNode.get("project_split_positions");
            if (array.isArray()) {
                for (JsonNode node : array) {
                    positions.add(node.asDouble());
                }
            }
        }
        // Defaults if not found
        if (positions.isEmpty()) {
            positions.add(0.2);
            positions.add(0.8);
        }
        return positions;
    }

    public void setProjectDividerPositions(double[] positions) {
        ArrayNode array = mapper.createArrayNode();
        for (double pos : positions) {
            array.add(pos);
        }
        ((ObjectNode) rootNode).set("project_split_positions", array);
        saveSettings();
    }
    
    public double getDashboardScrollPosition() {
        if (rootNode.has("dashboard_scroll_pos")) {
            return rootNode.get("dashboard_scroll_pos").asDouble();
        }
        return 0.0;
    }

    public void setDashboardScrollPosition(double pos) {
        ((ObjectNode) rootNode).put("dashboard_scroll_pos", pos);
        saveSettings();
    }

    public boolean isFirstRun() {
        if (rootNode.has("is_first_run")) {
            return rootNode.get("is_first_run").asBoolean();
        }
        return true; // Default to true if not found
    }

    public void setFirstRun(boolean firstRun) {
        ((ObjectNode) rootNode).put("is_first_run", firstRun);
        saveSettings();
    }

    private void saveSettings() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile, rootNode);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- Project Specific Settings ---

    private File getProjectSettingsFile(String projectPath) {
        return new File(projectPath, PROJECT_DIR + File.separator + SETTINGS_FILE);
    }

    private JsonNode loadProjectSettingsNode(File file) {
        if (file.exists()) {
            try {
                return mapper.readTree(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return mapper.createObjectNode();
    }

    private void saveProjectSettingsNode(File file, JsonNode node) {
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, node);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getProjectViewMode(String projectPath) {
        File file = getProjectSettingsFile(projectPath);
        JsonNode node = loadProjectSettingsNode(file);
        if (node.has("view_mode")) {
            return node.get("view_mode").asText();
        }
        return "grid"; // Default
    }

    public void setProjectViewMode(String projectPath, String mode) {
        File file = getProjectSettingsFile(projectPath);
        ObjectNode node = (ObjectNode) loadProjectSettingsNode(file);
        node.put("view_mode", mode);
        saveProjectSettingsNode(file, node);
    }
}
