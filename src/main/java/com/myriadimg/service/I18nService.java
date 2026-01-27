package com.myriadimg.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myriadimg.util.SettingsManager;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for Internationalization (I18n).
 * Loads language files (JSON) and provides localized strings.
 * Implements the Singleton pattern.
 */
public class I18nService {

    private static I18nService instance;
    private JsonNode rootNode;
    private String currentLang;
    private final List<Runnable> languageChangeListeners = new ArrayList<>();

    private I18nService() {
        // Load language from settings
        String savedLang = SettingsManager.getInstance().getLanguage();
        loadLanguage(savedLang);
    }

    public static synchronized I18nService getInstance() {
        if (instance == null) {
            instance = new I18nService();
        }
        return instance;
    }

    /**
     * Loads the specified language file from resources.
     * If the file is not found, it attempts to fallback to 'fr'.
     * Notifies all listeners after a successful language change.
     *
     * @param lang The language code (e.g., "en", "fr").
     */
    public void loadLanguage(String lang) {
        try (InputStream is = getClass().getResourceAsStream("/i18n/" + lang + ".json")) {
            if (is == null) {
                System.err.println("Language file not found: " + lang);
                // Fallback to default if requested lang not found
                if (!lang.equals("fr")) {
                    loadLanguage("fr");
                }
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            rootNode = mapper.readTree(is);
            currentLang = lang;
            
            // Save preference
            SettingsManager.getInstance().setLanguage(lang);

            // Notify listeners
            for (Runnable listener : languageChangeListeners) {
                listener.run();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public String getCurrentLang() {
        return currentLang;
    }

    /**
     * Retrieves a localized string by its key.
     * Supports nested keys using dot notation (e.g., "menu.file.open").
     *
     * @param key The key of the string resource.
     * @return The localized string, or "!key!" if not found.
     */
    public String get(String key) {
        if (rootNode == null) return "!" + key + "!";
        
        String[] parts = key.split("\\.");
        JsonNode node = rootNode;
        
        for (String part : parts) {
            if (node.has(part)) {
                node = node.get(part);
            } else {
                return "!" + key + "!";
            }
        }
        
        return node.asText();
    }

    /**
     * Retrieves a localized string and formats it with arguments.
     * Uses MessageFormat for formatting.
     *
     * @param key The key of the string resource.
     * @param args Arguments to replace placeholders in the string.
     * @return The formatted localized string.
     */
    public String get(String key, Object... args) {
        String pattern = get(key);
        return MessageFormat.format(pattern, args);
    }

    public JsonNode getNode(String key) {
        if (rootNode == null) return null;

        String[] parts = key.split("\\.");
        JsonNode node = rootNode;

        for (String part : parts) {
            if (node.has(part)) {
                node = node.get(part);
            } else {
                return null;
            }
        }
        return node;
    }

    public void addLanguageChangeListener(Runnable listener) {
        languageChangeListeners.add(listener);
    }
}
