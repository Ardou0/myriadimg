package com.myriadimg.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link I18nService}.
 * Tests the internationalization service capabilities including language loading,
 * key retrieval, and fallback mechanisms.
 */
class I18nServiceTest {

    private I18nService i18nService;

    @BeforeEach
    void setUp() {
        i18nService = I18nService.getInstance();
    }

    /**
     * Verifies that retrieving a non-existent key returns the key wrapped in exclamation marks.
     * This ensures the UI displays a visible error placeholder instead of crashing or showing nothing.
     */
    @Test
    void testGetExistingKey() {
        // Load a default language (e.g., English)
        i18nService.loadLanguage("en");
        
        // Attempt to retrieve a key that definitely does not exist
        String missing = i18nService.get("non.existent.key");
        
        // Assert that the service returns the fallback format "!key!"
        assertEquals("!non.existent.key!", missing, "Should return the key wrapped in '!' when not found");
    }

    /**
     * Placeholder for testing message formatting.
     * Ideally, this would test parameter substitution (e.g., "Hello {0}").
     * Currently limited by the inability to inject custom resource files easily.
     */
    @Test
    void testFormatting() {
        // Future improvement: Inject a mock resource bundle or JSON source to test MessageFormat.format behavior.
    }
    
    /**
     * Verifies that the service correctly updates the current language state when requested.
     */
    @Test
    void testLanguageSwitching() {
        // Switch to French
        i18nService.loadLanguage("fr");
        assertEquals("fr", i18nService.getCurrentLang(), "Current language should be 'fr'");
        
        // Switch to English
        i18nService.loadLanguage("en");
        assertEquals("en", i18nService.getCurrentLang(), "Current language should be 'en'");
    }
}
