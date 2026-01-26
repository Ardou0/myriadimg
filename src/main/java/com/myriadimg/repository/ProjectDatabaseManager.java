package com.myriadimg.repository;

import com.myriadimg.model.Asset;
import com.myriadimg.model.Tag;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProjectDatabaseManager {
    private static final String DB_FOLDER = ".MyriadImg";
    private static final String DB_NAME = "index.sqlite";
    private static final int CURRENT_DB_VERSION = 1;

    private final String projectRootPath;
    private final String connectionUrl;

    public ProjectDatabaseManager(String projectRootPath) {
        this.projectRootPath = projectRootPath;
        File dbFolder = new File(projectRootPath, DB_FOLDER);
        if (!dbFolder.exists()) {
            if (!dbFolder.mkdirs()) {
                System.err.println("Could not create project database directory: " + dbFolder.getAbsolutePath());
            }
        }
        File dbFile = new File(dbFolder, DB_NAME);
        this.connectionUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        initialize();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(connectionUrl);
    }

    private void initialize() {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                // 1. Enable Foreign Keys
                stmt.execute("PRAGMA foreign_keys = ON;");

                // 2. Check Version
                int version = 0;
                try {
                    ResultSet rs = stmt.executeQuery("SELECT version FROM metadata LIMIT 1");
                    if (rs.next()) {
                        version = rs.getInt("version");
                    }
                } catch (SQLException e) {
                    // Metadata table doesn't exist yet
                }

                // 3. Migrate if needed
                if (version < CURRENT_DB_VERSION) {
                    migrate(conn, version);
                }
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Project database initialization failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void migrate(Connection conn, int currentVersion) throws SQLException {
        Statement stmt = conn.createStatement();

        if (currentVersion < 1) {
            // Initial Schema
            stmt.execute("CREATE TABLE IF NOT EXISTS metadata (version integer PRIMARY KEY);");
            stmt.execute("INSERT OR REPLACE INTO metadata (version) VALUES (" + CURRENT_DB_VERSION + ");");

            stmt.execute("CREATE TABLE IF NOT EXISTS assets (\n"
                    + " path text PRIMARY KEY,\n"
                    + " hash text,\n"
                    + " size integer,\n"
                    + " creation_date text,\n"
                    + " type text,\n"
                    + " thumbnail_blob blob\n"
                    + ");");

            stmt.execute("CREATE TABLE IF NOT EXISTS tags (\n"
                    + " id integer PRIMARY KEY AUTOINCREMENT,\n"
                    + " value text NOT NULL UNIQUE,\n"
                    + " pseudo text,\n"
                    + " type text\n"
                    + ");");

            stmt.execute("CREATE TABLE IF NOT EXISTS asset_tags (\n"
                    + " asset_path text NOT NULL,\n"
                    + " tag_id integer NOT NULL,\n"
                    + " confidence real DEFAULT 1.0,\n"
                    + " PRIMARY KEY (asset_path, tag_id),\n"
                    + " FOREIGN KEY (asset_path) REFERENCES assets(path) ON DELETE CASCADE,\n"
                    + " FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE\n"
                    + ");");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_assets_hash ON assets(hash);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tags_value ON tags(value);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_asset_tags_tag_id ON asset_tags(tag_id);");
        }
    }

    public void batchInsertAssets(List<Asset> assets) {
        String sql = "INSERT OR IGNORE INTO assets(path, hash, size, creation_date, type) VALUES(?,?,?,?,?)";

        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Asset asset : assets) {
                    pstmt.setString(1, asset.getPath());
                    pstmt.setString(2, asset.getHash());
                    pstmt.setLong(3, asset.getSize());
                    pstmt.setString(4, asset.getCreationDate() != null ? asset.getCreationDate().toString() : null);
                    pstmt.setString(5, asset.getType().name());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Batch insert failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void cleanupNonMediaAssets() {
        // Delete assets that are not IMAGE or VIDEO
        String sql = "DELETE FROM assets WHERE type NOT IN ('IMAGE', 'VIDEO')";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            int deleted = stmt.executeUpdate(sql);
            if (deleted > 0) {
                System.out.println("Cleaned up " + deleted + " non-media assets from database.");
            }
        } catch (SQLException e) {
            System.err.println("Failed to cleanup non-media assets: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void removeAssets(List<String> pathsToRemove) {
        if (pathsToRemove == null || pathsToRemove.isEmpty()) {
            return;
        }
        
        String sql = "DELETE FROM assets WHERE path = ?";
        
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int batchSize = 0;
                for (String path : pathsToRemove) {
                    pstmt.setString(1, path);
                    pstmt.addBatch();
                    batchSize++;
                    
                    if (batchSize >= 1000) {
                        pstmt.executeBatch();
                        batchSize = 0;
                    }
                }
                if (batchSize > 0) {
                    pstmt.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Failed to remove assets: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void updateAssetThumbnail(String path, byte[] thumbnailData) {
        String sql = "UPDATE assets SET thumbnail_blob = ? WHERE path = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setBytes(1, thumbnailData);
            pstmt.setString(2, path);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    public InputStream getThumbnailStream(String path) {
        String sql = "SELECT thumbnail_blob FROM assets WHERE path = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, path);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    byte[] imgBytes = rs.getBytes("thumbnail_blob");
                    if (imgBytes != null && imgBytes.length > 0) {
                        return new ByteArrayInputStream(imgBytes);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    public List<String> getAssetsWithoutThumbnail() {
        List<String> paths = new ArrayList<>();
        String sql = "SELECT path FROM assets WHERE thumbnail_blob IS NULL AND (type = 'IMAGE' OR type = 'VIDEO')";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                paths.add(rs.getString("path"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return paths;
    }
    
    public void resetThumbnails() {
        String sql = "UPDATE assets SET thumbnail_blob = NULL";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    public Set<String> getAllHashes() {
        Set<String> hashes = new HashSet<>();
        String sql = "SELECT hash FROM assets";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                hashes.add(rs.getString("hash"));
            }
        } catch (SQLException e) {
            System.err.println("Failed to load hashes: " + e.getMessage());
            e.printStackTrace();
        }
        return hashes;
    }

    public Set<String> getAllAssetPaths() {
        Set<String> paths = new HashSet<>();
        String sql = "SELECT path FROM assets";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                paths.add(rs.getString("path"));
            }
        } catch (SQLException e) {
            System.err.println("Failed to load asset paths: " + e.getMessage());
            e.printStackTrace();
        }
        return paths;
    }

    // Tag Management Methods

    public int getOrCreateTag(String value, String pseudo, Tag.TagType type) {
        String selectSql = "SELECT id FROM tags WHERE value = ?";
        String insertSql = "INSERT INTO tags(value, pseudo, type) VALUES(?, ?, ?)";

        try (Connection conn = connect()) {
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, value);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, value);
                pstmt.setString(2, pseudo);
                pstmt.setString(3, type.name());
                pstmt.executeUpdate();
                
                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error managing tag: " + e.getMessage());
            e.printStackTrace();
        }
        return -1;
    }

    public void addTagToAsset(String assetPath, int tagId, double confidence) {
        String sql = "INSERT OR IGNORE INTO asset_tags(asset_path, tag_id, confidence) VALUES(?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, assetPath);
            pstmt.setInt(2, tagId);
            pstmt.setDouble(3, confidence);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error linking tag to asset: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public int getAssetCount(Asset.AssetType type) {
        String sql = "SELECT COUNT(*) FROM assets WHERE type = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, type.name());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }
    
    public List<Asset> getAllAssets() {
        List<Asset> assets = new ArrayList<>();
        String sql = "SELECT * FROM assets ORDER BY creation_date DESC";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                assets.add(mapResultSetToAsset(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return assets;
    }
    
    public Asset getAssetByPath(String path) {
        String sql = "SELECT * FROM assets WHERE path = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, path);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToAsset(rs);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private Asset mapResultSetToAsset(ResultSet rs) throws SQLException {
        String path = rs.getString("path");
        String hash = rs.getString("hash");
        long size = rs.getLong("size");
        String dateStr = rs.getString("creation_date");
        LocalDateTime date = dateStr != null ? LocalDateTime.parse(dateStr) : null;
        Asset.AssetType type = Asset.AssetType.valueOf(rs.getString("type"));
        
        return new Asset(path, hash, size, date, type);
    }
}
