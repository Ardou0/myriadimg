package com.myriadimg.repository;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages the global application database (myriadimg_global.db).
 * This database stores the list of projects and global settings, but not the assets themselves.
 * It resides in the user's home directory under .MyriadImgGlobal.
 */
public class DatabaseManager {
    private static final String DB_NAME = "myriadimg_global.db";
    private static final String APP_DIR = ".MyriadImgGlobal";

    // Field for custom connection URL (for testing)
    private static String customConnectionUrl = null;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("SQLite JDBC driver not found: " + e.getMessage());
        }
    }

    /**
     * Sets a custom JDBC connection URL.
     * Useful for testing (e.g., "jdbc:sqlite::memory:") or using a different database location.
     * Pass null to revert to the default behavior.
     *
     * @param url The JDBC connection URL to use.
     */
    public static void setConnectionUrl(String url) {
        customConnectionUrl = url;
    }

    /**
     * Establishes a connection to the global SQLite database.
     * Creates the application directory if it doesn't exist and no custom URL is set.
     *
     * @return A Connection object or null if connection failed.
     */
    public static Connection connect() {
        if (customConnectionUrl != null) {
            try {
                return DriverManager.getConnection(customConnectionUrl);
            } catch (SQLException e) {
                System.err.println("Connection failed to custom URL: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }

        String userHome = System.getProperty("user.home");
        File appDir = new File(userHome, APP_DIR);
        if (!appDir.exists()) {
            if (!appDir.mkdirs()) {
                System.err.println("Could not create application directory: " + appDir.getAbsolutePath());
            }
        }
        
        File dbFile = new File(appDir, DB_NAME);
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url);
        } catch (SQLException e) {
            System.err.println("Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
        return conn;
    }

    /**
     * Initializes the global database schema.
     * Creates the 'projects' table if it doesn't exist and handles basic migrations.
     */
    public static void initialize() {
        if (customConnectionUrl == null) {
            String userHome = System.getProperty("user.home");
            File appDir = new File(userHome, APP_DIR);
            File dbFile = new File(appDir, DB_NAME);
            System.out.println("Global Database Location: " + dbFile.getAbsolutePath());
        } else {
            System.out.println("Global Database Location: " + customConnectionUrl);
        }

        // Added display_order column
        String sql = "CREATE TABLE IF NOT EXISTS projects (\n"
                + " id integer PRIMARY KEY,\n"
                + " name text NOT NULL,\n"
                + " path text NOT NULL UNIQUE,\n"
                + " last_opened text,\n"
                + " display_order integer DEFAULT 0\n"
                + ");";

        Connection conn = connect();
        if (conn == null) {
            System.err.println("Cannot initialize database: Connection is null.");
            return;
        }

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            
            // Migration: Check if column exists, if not add it (simple migration for SQLite)
            try {
                stmt.execute("ALTER TABLE projects ADD COLUMN display_order integer DEFAULT 0");
            } catch (SQLException e) {
                // Column likely exists, ignore
            }

        } catch (SQLException e) {
            System.err.println("Database initialization failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                conn.close();
            } catch (SQLException e) {
                System.err.println("Error closing connection: " + e.getMessage());
            }
        }
    }
}
