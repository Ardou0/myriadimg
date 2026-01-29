package com.myriadimg.util;

/**
 * Utility class for file manipulation.
 */
public class FileUtils {

    /**
     * Extracts the file extension from a file name.
     *
     * @param fileName The file name (e.g., "image.jpg").
     * @return The extension (lowercase, without dot), or an empty string if none found.
     */
    public static String getExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int i = fileName.lastIndexOf('.');
        // Handle hidden files (starting with dot) correctly if they don't have an extension
        // e.g. ".config" -> "config" or ""?
        // Standard behavior usually:
        // "image.jpg" -> "jpg"
        // "README" -> ""
        // ".gitignore" -> "gitignore" (if we consider it an extension) or "" (if we consider it a hidden file with no name)
        // The implementation in IndexingService was:
        // int i = fileName.lastIndexOf('.');
        // if (i > 0) { return fileName.substring(i + 1); }
        // return "";
        // This means ".gitignore" (i=0) returns "". "image.jpg" (i=5) returns "jpg".
        
        if (i > 0) {
            return fileName.substring(i + 1).toLowerCase();
        }
        return "";
    }
}
