package com.myriadimg.repository;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private static final String DB_NAME = "myriadimg_global.db";
    private static final String APP_DIR = ".MyriadImgGlobal";

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("SQLite JDBC driver not found: " + e.getMessage());
        }
    }

    public static Connection connect() {
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

    public static void initialize() {
        String userHome = System.getProperty("user.home");
        File appDir = new File(userHome, APP_DIR);
        File dbFile = new File(appDir, DB_NAME);
        System.out.println("Global Database Location: " + dbFile.getAbsolutePath());

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
