package com.myriadimg.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.myriadimg.util.ProjectLogger;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.distance.DistanceUtils;
import org.locationtech.spatial4j.shape.Point;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetadataService {

    private static MetadataService instance;
    private List<City> cities;
    
    // Regex for ISO-6709 format (e.g., +48.8566+002.3522/)
    // Matches standard format and variants with optional whitespace
    private static final Pattern ISO6709_PATTERN = Pattern.compile("([+-]\\d+\\.?\\d*)\\s*([+-]\\d+\\.?\\d*)");

    private MetadataService() {
        loadCities();
    }

    /**
     * Returns the singleton instance of the MetadataService.
     *
     * @return The singleton instance.
     */
    public static synchronized MetadataService getInstance() {
        if (instance == null) {
            instance = new MetadataService();
        }
        return instance;
    }

    /**
     * Loads the city database from the resources for offline reverse geocoding (GeoNames).
     * The file is expected to be at /geolocation/cities15000.txt.
     */
    private void loadCities() {
        cities = new ArrayList<>();
        try (InputStream is = getClass().getResourceAsStream("/geolocation/cities15000.txt")) {
            if (is == null) {
                ProjectLogger.logError(null, "MetadataService", "Could not find cities15000.txt in resources", null);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t");
                    if (parts.length > 5) {
                        try {
                            String name = parts[1];
                            double lat = Double.parseDouble(parts[4]);
                            double lon = Double.parseDouble(parts[5]);
                            cities.add(new City(name, lat, lon));
                        } catch (NumberFormatException e) {
                            // Skip invalid lines
                        }
                    }
                }
            }
        } catch (IOException e) {
            ProjectLogger.logError(null, "MetadataService", "Error loading cities database", e);
        }
    }

    /**
     * Extracts the best available creation date for the given file.
     * 
     * @param file The file to analyze.
     * @param projectRoot The root path of the project for logging purposes.
     * @return The extracted LocalDateTime, or the file modification time if extraction fails.
     */
    public LocalDateTime extractDate(File file, Path projectRoot) {
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
            // Log metadata read errors as warnings, as it's common for some files to lack metadata or be corrupt
            ProjectLogger.logError(projectRoot, "MetadataService", "Failed to read metadata for date extraction: " + file.getName(), e);
        }

        // 2. Fallback to File System
        try {
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            return LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            ProjectLogger.logError(projectRoot, "MetadataService", "Failed to read file attributes: " + file.getName(), e);
            // Last resort
            return LocalDateTime.ofInstant(new Date(file.lastModified()).toInstant(), ZoneId.systemDefault());
        }
    }

    /**
     * Extracts location information (City) from GPS coordinates in EXIF or Video Metadata using offline reverse geocoding.
     * If coordinates are found, it performs a nearest-neighbor search in the loaded city database.
     *
     * @param file The file to analyze.
     * @param projectRoot The root path of the project for logging purposes.
     * @return The name of the nearest city (e.g., "Paris"), or "Unknown" if no coordinates are found or no city is nearby.
     */
    public String extractLocation(File file, Path projectRoot) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);
            
            // Try Standard GPS Directory (Images)
            GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDirectory != null) {
                GeoLocation geoLocation = gpsDirectory.getGeoLocation();
                if (geoLocation != null && !geoLocation.isZero()) {
                    return findNearestCity(geoLocation.getLatitude(), geoLocation.getLongitude());
                }
            }
            
            // Search in ALL directories and tags for ISO-6709 pattern
            // This covers QuickTime, MP4, and other formats where location is stored as a string
            for (Directory directory : metadata.getDirectories()) {
                for (com.drew.metadata.Tag tag : directory.getTags()) {
                    String value = tag.getDescription();
                    if (value == null || value.isBlank()) continue;
                    
                    // Check if the value matches the ISO-6709 pattern (e.g. +48.8566+002.3522/)
                    Matcher matcher = ISO6709_PATTERN.matcher(value);
                    if (matcher.find()) {
                        try {
                            double lat = Double.parseDouble(matcher.group(1));
                            double lon = Double.parseDouble(matcher.group(2));
                            
                            // Validate range to avoid InvalidShapeException and garbage data
                            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                                continue;
                            }
                            
                            // Validate not zero (unless it's a valid location, but 0,0 is usually default/error)
                            if (Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001) {
                                continue;
                            }

                            String city = findNearestCity(lat, lon);
                            if (!"Unknown".equals(city)) {
                                return city;
                            }
                        } catch (NumberFormatException e) {
                            // Continue searching if parsing fails
                        }
                    }
                }
            }

        } catch (Exception e) {
            ProjectLogger.logError(projectRoot, "MetadataService", "Failed to extract location for: " + file.getName(), e);
        }
        return "Unknown";
    }

    /**
     * Finds the nearest city to the given coordinates using the loaded city database.
     *
     * @param lat The latitude.
     * @param lon The longitude.
     * @return The name of the nearest city if within 50km, otherwise "Unknown".
     */
    private String findNearestCity(double lat, double lon) {
        // Double check validation
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return "Unknown";
        }

        if (cities == null || cities.isEmpty()) {
            return "Unknown";
        }

        SpatialContext ctx = SpatialContext.GEO;
        try {
            Point p = ctx.getShapeFactory().pointXY(lon, lat);
            
            City nearest = null;
            double minDistance = Double.MAX_VALUE;

            for (City city : cities) {
                Point cityPoint = ctx.getShapeFactory().pointXY(city.lon, city.lat);
                double distance = ctx.getDistCalc().distance(p, cityPoint); // Distance in degrees
                
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = city;
                }
            }

            if (nearest != null) {
                // Convert degrees to km roughly to check if it's reasonably close (e.g. < 50km)
                double distanceKm = DistanceUtils.degrees2Dist(minDistance, DistanceUtils.EARTH_MEAN_RADIUS_KM);
                if (distanceKm < 50) {
                    return nearest.name;
                }
            }
        } catch (Exception e) {
            // Catch any spatial exceptions safely
            return "Unknown";
        }
        
        return "Unknown";
    }

    /**
     * Internal data structure representing a city with its coordinates.
     */
    private static class City {
        String name;
        double lat;
        double lon;

        City(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }
}