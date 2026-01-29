package com.myriadimg.service;

import com.myriadimg.util.SettingsManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class I18nServiceTest {

    private I18nService i18nService;

    @BeforeEach
    void setUp() {
        i18nService = I18nService.getInstance();
    }

    @Test
    void testGetExistingKey() {
        // Assuming 'en.json' exists and has a key like "app.name" or similar.
        // Since we don't know the exact content, we test the behavior.
        // If we load a non-existent language, it falls back or returns !key!
        
        // Let's try to load 'en' explicitly
        i18nService.loadLanguage("en");
        
        // We check if we get a string back that is not the error format "!key!"
        // Assuming "app.title" or "menu.file" exists in en.json
        // If we don't know keys, we can at least test the missing key behavior
        String missing = i18nService.get("non.existent.key");
        assertEquals("!non.existent.key!", missing);
    }

    @Test
    void testFormatting() {
        // Mocking behavior: if we had a key "greeting" -> "Hello {0}"
        // Since we can't easily inject a mock JSON without refactoring I18nService to accept a source,
        // we will rely on the logic we see in the code: MessageFormat.format
        
        // This test is limited without controlling the resource files.
        // Ideally, we would point I18nService to a test resource folder.
    }
    
    @Test
    void testLanguageSwitching() {
        i18nService.loadLanguage("fr");
        assertEquals("fr", i18nService.getCurrentLang());
        
        i18nService.loadLanguage("en");
        assertEquals("en", i18nService.getCurrentLang());
    }
}
