package com.myriadimg.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileUtilsTest {

    @Test
    void testGetExtensionNormalFile() {
        assertEquals("jpg", FileUtils.getExtension("image.jpg"));
        assertEquals("txt", FileUtils.getExtension("document.txt"));
    }

    @Test
    void testGetExtensionNoExtension() {
        assertEquals("", FileUtils.getExtension("README"));
        assertEquals("", FileUtils.getExtension("makefile"));
    }

    @Test
    void testGetExtensionHiddenFile() {
        // Based on current implementation: lastIndexOf('.') > 0
        // ".config" -> index is 0, so returns ""
        assertEquals("", FileUtils.getExtension(".config"));
        assertEquals("", FileUtils.getExtension(".gitignore"));
    }

    @Test
    void testGetExtensionMixedCase() {
        assertEquals("png", FileUtils.getExtension("Image.PNG"));
        assertEquals("jpeg", FileUtils.getExtension("Photo.JPEG"));
    }
    
    @Test
    void testGetExtensionMultipleDots() {
        assertEquals("gz", FileUtils.getExtension("archive.tar.gz"));
    }
    
    @Test
    void testGetExtensionNullOrEmpty() {
        assertEquals("", FileUtils.getExtension(null));
        assertEquals("", FileUtils.getExtension(""));
    }
}
