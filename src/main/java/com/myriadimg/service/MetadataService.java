package com.myriadimg.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

public class MetadataService {

    private static MetadataService instance;

    private MetadataService() {}

    public static synchronized MetadataService getInstance() {
        if (instance == null) {
            instance = new MetadataService();
        }
        return instance;
    }

    /**
     * Extracts the best available creation date for the given file.
     * Priority:
     * 1. EXIF "Date/Time Original"
     * 2. File System Creation Time
     * 3. File System Last Modified Time
     *
     * @param file The file to analyze
     * @return The extracted LocalDateTime
     */
    public LocalDateTime extractDate(File file) {
        // 1. Try EXIF
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);
            ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (directory != null) {
                Date date = directory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                if (date != null) {
                    return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
                }
            }
        } catch (Exception e) {
            // Ignore metadata read errors, fall back to file system
        }

        // 2. Fallback to File System
        try {
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            return LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            // Last resort
            return LocalDateTime.ofInstant(new Date(file.lastModified()).toInstant(), ZoneId.systemDefault());
        }
    }
}
