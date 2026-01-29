package com.myriadimg.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetadataService}.
 * Tests the extraction of creation dates from image files, prioritizing EXIF metadata
 * and falling back to file system attributes when necessary.
 */
@ExtendWith(MockitoExtension.class)
class MetadataServiceTest {

    @TempDir
    Path tempDir;

    /**
     * Verifies that if an image contains EXIF "Date/Time Original" metadata,
     * the service returns that date instead of the file system creation date.
     */
    @Test
    void testExtractDate_WithExif_ShouldReturnExifDate() throws IOException {
        File imageFile = tempDir.resolve("photo_with_exif.jpg").toFile();
        imageFile.createNewFile();

        // Prepare expected EXIF date
        LocalDateTime exifDate = LocalDateTime.of(2020, 1, 1, 12, 0);
        Date date = Date.from(exifDate.atZone(ZoneId.systemDefault()).toInstant());

        try (MockedStatic<ImageMetadataReader> mockedReader = mockStatic(ImageMetadataReader.class)) {
            // Mock Metadata structure
            Metadata metadata = mock(Metadata.class);
            ExifSubIFDDirectory directory = mock(ExifSubIFDDirectory.class);
            
            mockedReader.when(() -> ImageMetadataReader.readMetadata(any(File.class))).thenReturn(metadata);
            when(metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class)).thenReturn(directory);
            when(directory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)).thenReturn(date);

            // Execute
            LocalDateTime result = MetadataService.getInstance().extractDate(imageFile);

            // Verify
            assertEquals(exifDate, result, "Should prioritize EXIF date");
        }
    }

    /**
     * Verifies that if an image lacks EXIF metadata, the service falls back to the
     * file system's creation time.
     */
    @Test
    void testExtractDate_NoExif_ShouldReturnFileSystemDate() throws IOException {
        File imageFile = tempDir.resolve("photo_no_exif.jpg").toFile();
        imageFile.createNewFile();

        // Set file creation time
        LocalDateTime fsDate = LocalDateTime.of(2026, 5, 20, 10, 30);
        FileTime fileTime = FileTime.from(fsDate.atZone(ZoneId.systemDefault()).toInstant());
        
        try (MockedStatic<ImageMetadataReader> mockedReader = mockStatic(ImageMetadataReader.class);
             MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
            
            // Mock Metadata throwing exception
            mockedReader.when(() -> ImageMetadataReader.readMetadata(any(File.class))).thenThrow(new IOException("No EXIF"));

            // Mock Files.readAttributes
            BasicFileAttributes attrs = mock(BasicFileAttributes.class);
            when(attrs.creationTime()).thenReturn(fileTime);
            
            mockedFiles.when(() -> Files.readAttributes(any(Path.class), eq(BasicFileAttributes.class))).thenReturn(attrs);

            // Execute
            LocalDateTime result = MetadataService.getInstance().extractDate(imageFile);

            // Verify
            assertEquals(fsDate, result, "Should fallback to File System date");
        }
    }
}
