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
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataServiceTest {

    @TempDir
    Path tempDir;

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

    @Test
    void testExtractDate_NoExif_ShouldReturnFileSystemDate() throws IOException {
        File imageFile = tempDir.resolve("photo_no_exif.jpg").toFile();
        imageFile.createNewFile();

        // Set file creation time
        LocalDateTime fsDate = LocalDateTime.of(2023, 5, 20, 10, 30);
        FileTime fileTime = FileTime.from(fsDate.atZone(ZoneId.systemDefault()).toInstant());
        Files.setAttribute(imageFile.toPath(), "creationTime", fileTime);

        try (MockedStatic<ImageMetadataReader> mockedReader = mockStatic(ImageMetadataReader.class)) {
            // Mock Metadata throwing exception or returning null directory
            mockedReader.when(() -> ImageMetadataReader.readMetadata(any(File.class))).thenThrow(new IOException("No EXIF"));

            // Execute
            LocalDateTime result = MetadataService.getInstance().extractDate(imageFile);

            // Verify
            // Note: File system time precision might vary, so we check equality loosely or exact if FS supports it.
            // For test stability, we assume FS supports it or we check close enough.
            // But here we set it explicitly, so it should match.
            assertEquals(fsDate, result, "Should fallback to File System date");
        }
    }
}
