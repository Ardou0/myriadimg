package com.myriadimg.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SettingsManager.
 * Since SettingsManager is a Singleton that loads files from disk, 
 * testing it in isolation is tricky without refactoring for dependency injection of the file system.
 * 
 * However, we can test the logic assuming we can mock the internal state or 
 * if we accept it writes to a test environment.
 * 
 * For this example, we will simulate the behavior as requested, 
 * focusing on the public API contract.
 */
class SettingsManagerTest {

    // Note: In a real scenario, we would want to reset the Singleton instance 
    // or mock the file operations to avoid touching the real user config.
    // Since we cannot easily reset the singleton without reflection or refactoring,
    // we will assume this test runs in an isolated environment or we mock the behavior.
    
    @Test
    void testLanguageSettings() {
        SettingsManager settings = SettingsManager.getInstance();
        
        // Save original to restore later
        String originalLang = settings.getLanguage();
        
        try {
            settings.setLanguage("en");
            assertEquals("en", settings.getLanguage());
            
            settings.setLanguage("fr");
            assertEquals("fr", settings.getLanguage());
        } finally {
            // Restore
            settings.setLanguage(originalLang);
        }
    }

    @Test
    void testProjectDividerPositions() {
        SettingsManager settings = SettingsManager.getInstance();
        
        double[] newPositions = {0.3, 0.7};
        settings.setProjectDividerPositions(newPositions);
        
        List<Double> positions = settings.getProjectDividerPositions();
        assertEquals(2, positions.size());
        assertEquals(0.3, positions.get(0));
        assertEquals(0.7, positions.get(1));
    }
    
    @Test
    void testFirstRun() {
        SettingsManager settings = SettingsManager.getInstance();
        boolean original = settings.isFirstRun();
        
        try {
            settings.setFirstRun(true);
            assertTrue(settings.isFirstRun());
            
            settings.setFirstRun(false);
            assertFalse(settings.isFirstRun());
        } finally {
            settings.setFirstRun(original);
        }
    }
}
