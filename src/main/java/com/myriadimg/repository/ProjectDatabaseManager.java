package com.myriadimg.repository;

import com.myriadimg.model.Asset;
import com.myriadimg.model.Tag;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Manages the project-specific database (index.sqlite).
 * This database is located inside the .MyriadImg folder within each project root.
 * It stores metadata about assets, tags, and analysis results for that specific project.
 */
public class ProjectDatabaseManager {
    private static final String DB_FOLDER = ".MyriadImg";
    private static final String DB_NAME = "index.sqlite";
    private static final int CURRENT_DB_VERSION = 2; // Incremented version

    private static final String SQL_CREATE_METADATA = """
            CREATE TABLE IF NOT EXISTS metadata (
            version integer PRIMARY KEY,
            scan_required integer DEFAULT 0,
            thumbnail_scan_required integer DEFAULT 0,
            tag_scan_required integer DEFAULT 0
            );""";

    private static final String SQL_CREATE_ASSETS = """
            CREATE TABLE IF NOT EXISTS assets (
             path text PRIMARY KEY,
             hash text,
             size integer,
             creation_date text,
             type text,
             thumbnail_blob blob
            );""";

    private static final String SQL_CREATE_TAGS = """
            CREATE TABLE IF NOT EXISTS tags (
             id integer PRIMARY KEY AUTOINCREMENT,
             value text NOT NULL UNIQUE,
             pseudo text,
             type text
            );""";

    private static final String SQL_CREATE_ASSET_TAGS = """
            CREATE TABLE IF NOT EXISTS asset_tags (
             asset_path text NOT NULL,
             tag_id integer NOT NULL,
             confidence real DEFAULT 1.0,
             PRIMARY KEY (asset_path, tag_id),
             FOREIGN KEY (asset_path) REFERENCES assets(path) ON DELETE CASCADE,
             FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
            );""";

    private static final String SQL_INDEX_ASSETS_HASH = "CREATE INDEX IF NOT EXISTS idx_assets_hash ON assets(hash);";
    private static final String SQL_INDEX_TAGS_VALUE = "CREATE INDEX IF NOT EXISTS idx_tags_value ON tags(value);";
    private static final String SQL_INDEX_ASSET_TAGS_TAG_ID = "CREATE INDEX IF NOT EXISTS idx_asset_tags_tag_id ON asset_tags(tag_id);";

    private final String connectionUrl;

    public ProjectDatabaseManager(String projectRootPath) {
        File dbFolder = new File(projectRootPath, DB_FOLDER);
        if (!dbFolder.exists()) {
            if (!dbFolder.mkdirs()) {
                throw new DatabaseException("Could not create project database directory: " + dbFolder.getAbsolutePath());
            }
        }
        File dbFile = new File(dbFolder, DB_NAME);
        this.connectionUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        initialize();
    }

    // Constructor for testing purposes
    public ProjectDatabaseManager(String connectionUrl, boolean isTest) {
        this.connectionUrl = connectionUrl;
        if (isTest) {
            initialize();
        }
    }

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(connectionUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        return conn;
    }

    /**
     * Initializes the project database schema and handles migrations.
     * Uses transactions to ensure schema integrity.
     */
    private void initialize() {
        try (Connection conn = connect()) {
            // Enable WAL mode for better concurrency and performance
            // Skip WAL mode for in-memory databases as it's not supported/needed
            if (!connectionUrl.contains(":memory:")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL;");
                    stmt.execute("PRAGMA synchronous=NORMAL;");
                }
            }

            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                // Foreign keys are already enabled in connect(), but no harm checking/setting again if needed.
                // However, connect() is called above, so it's active for 'conn'.

                // 2. Check Version
                int version = 0;
                try {
                    ResultSet rs = stmt.executeQuery("SELECT version FROM metadata ORDER BY version DESC LIMIT 1");
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
            throw new DatabaseException("Project database initialization failed", e);
        }
    }

    private void migrate(Connection conn, int currentVersion) throws SQLException {
        System.out.println("Migrating database from version " + currentVersion + " to " + CURRENT_DB_VERSION);
        
        // Define expected schema for the current version
        Map<String, Map<String, String>> expectedSchema = new HashMap<>();
        
        // Metadata table
        Map<String, String> metadataCols = new HashMap<>();
        metadataCols.put("version", "integer PRIMARY KEY");
        metadataCols.put("scan_required", "integer DEFAULT 0");
        metadataCols.put("thumbnail_scan_required", "integer DEFAULT 0");
        metadataCols.put("tag_scan_required", "integer DEFAULT 0");
        expectedSchema.put("metadata", metadataCols);
        
        // Assets table
        Map<String, String> assetsCols = new HashMap<>();
        assetsCols.put("path", "text PRIMARY KEY");
        assetsCols.put("hash", "text");
        assetsCols.put("size", "integer");
        assetsCols.put("creation_date", "text");
        assetsCols.put("type", "text");
        assetsCols.put("thumbnail_blob", "blob");
        expectedSchema.put("assets", assetsCols);
        
        // Tags table
        Map<String, String> tagsCols = new HashMap<>();
        tagsCols.put("id", "integer PRIMARY KEY AUTOINCREMENT");
        tagsCols.put("value", "text NOT NULL UNIQUE");
        tagsCols.put("pseudo", "text");
        tagsCols.put("type", "text");
        expectedSchema.put("tags", tagsCols);
        
        // Asset_tags table
        Map<String, String> assetTagsCols = new HashMap<>();
        assetTagsCols.put("asset_path", "text NOT NULL");
        assetTagsCols.put("tag_id", "integer NOT NULL");
        assetTagsCols.put("confidence", "real DEFAULT 1.0");
        expectedSchema.put("asset_tags", assetTagsCols);

        Statement stmt = conn.createStatement();

        // 1. Create tables if they don't exist
        createTablesIfNotExist(stmt);

        // 2. Check and update columns for each table
        boolean schemaChanged = false;
        boolean thumbnailSchemaChanged = false;
        boolean tagSchemaChanged = false;

        for (Map.Entry<String, Map<String, String>> entry : expectedSchema.entrySet()) {
            String tableName = entry.getKey();
            Map<String, String> expectedColumns = entry.getValue();
            
            Set<String> existingColumns = getExistingColumns(conn, tableName);
            
            for (Map.Entry<String, String> colEntry : expectedColumns.entrySet()) {
                String colName = colEntry.getKey();
                String colDef = colEntry.getValue();
                
                if (!existingColumns.contains(colName)) {
                    System.out.println("Adding missing column " + colName + " to table " + tableName);
                    addColumn(stmt, tableName, colName, colDef);
                    
                    // Determine which flag to raise based on the column added
                    if (tableName.equals("assets") && colName.equals("thumbnail_blob")) {
                        thumbnailSchemaChanged = true;
                    } else if (tableName.equals("tags") || tableName.equals("asset_tags")) {
                        tagSchemaChanged = true;
                    } else {
                        schemaChanged = true;
                    }
                }
            }
        }

        // 3. Update version
        stmt.execute("INSERT OR REPLACE INTO metadata (version) VALUES (" + CURRENT_DB_VERSION + ");");
        
        // 4. Set scan flags based on what changed
        if (currentVersion < CURRENT_DB_VERSION) {
            if (thumbnailSchemaChanged) {
                stmt.execute("UPDATE metadata SET thumbnail_scan_required = 1 WHERE version = " + CURRENT_DB_VERSION);
            }
            if (tagSchemaChanged) {
                stmt.execute("UPDATE metadata SET tag_scan_required = 1 WHERE version = " + CURRENT_DB_VERSION);
            }
            if (schemaChanged) {
                stmt.execute("UPDATE metadata SET scan_required = 1 WHERE version = " + CURRENT_DB_VERSION);
            }
        }
    }

    private void createTablesIfNotExist(Statement stmt) throws SQLException {
        stmt.execute(SQL_CREATE_METADATA);
        stmt.execute(SQL_CREATE_ASSETS);
        stmt.execute(SQL_CREATE_TAGS);
        stmt.execute(SQL_CREATE_ASSET_TAGS);

        stmt.execute(SQL_INDEX_ASSETS_HASH);
        stmt.execute(SQL_INDEX_TAGS_VALUE);
        stmt.execute(SQL_INDEX_ASSET_TAGS_TAG_ID);
    }

    private Set<String> getExistingColumns(Connection conn, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private void addColumn(Statement stmt, String tableName, String columnName, String columnDefinition) throws SQLException {
        String safeDefinition = columnDefinition.replaceAll("(?i)PRIMARY KEY", "").replaceAll("(?i)AUTOINCREMENT", "").trim();
        
        String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + safeDefinition;
        stmt.execute(sql);
    }
    
    public boolean isScanRequired() {
        return checkFlag("scan_required");
    }
    
    public void setScanRequired(boolean required) {
        setFlag("scan_required", required);
    }
    
    public boolean isThumbnailScanRequired() {
        return checkFlag("thumbnail_scan_required");
    }
    
    public void setThumbnailScanRequired(boolean required) {
        setFlag("thumbnail_scan_required", required);
    }
    
    public boolean isTagScanRequired() {
        return checkFlag("tag_scan_required");
    }
    
    public void setTagScanRequired(boolean required) {
        setFlag("tag_scan_required", required);
    }

    private boolean checkFlag(String column) {
        String sql = "SELECT " + column + " FROM metadata ORDER BY version DESC LIMIT 1";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(column) == 1;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to check flag " + column, e);
        }
        return false;
    }

    private void setFlag(String column, boolean value) {
        String sql = "UPDATE metadata SET " + column + " = ? WHERE version = (SELECT MAX(version) FROM metadata)";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, value ? 1 : 0);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to set flag " + column, e);
        }
    }

    /**
     * Inserts or updates a list of assets into the database in a single batch transaction.
     * It updates the asset metadata and refreshes tags.
     */
    public void batchInsertAssets(List<Asset> assets) {
        // Use INSERT OR REPLACE to update existing assets with new metadata (e.g. creation date)
        String sql = "INSERT OR REPLACE INTO assets(path, hash, size, creation_date, type, thumbnail_blob) " +
                     "VALUES(?,?,?,?,?, (SELECT thumbnail_blob FROM assets WHERE path = ?))";

        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Asset asset : assets) {
                    pstmt.setString(1, asset.getPath());
                    pstmt.setString(2, asset.getHash());
                    pstmt.setLong(3, asset.getSize());
                    pstmt.setString(4, asset.getCreationDate() != null ? asset.getCreationDate().toString() : null);
                    pstmt.setString(5, asset.getType().name());
                    pstmt.setString(6, asset.getPath());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                
                // Handle the Tags
                for (Asset asset : assets) {
                    // Skip tag generation for ICON type
                    if (asset.getType() == Asset.AssetType.ICON) {
                        continue;
                    }

                    // TYPE tags
                    String typeTagValue = asset.getType().name().toLowerCase();
                    String typeTagPseudo = asset.getType() == Asset.AssetType.IMAGE ? "Photo" : "Video";
                    int typeTagId = getOrCreateTag(conn, typeTagValue, typeTagPseudo, Tag.TagType.TYPE);
                    addTagToAsset(conn, asset.getPath(), typeTagId, 1.0);
                    
                    // Handle other tags (e.g. LOCATION)
                    if (asset.getTags() != null) {
                        for (Tag tag : asset.getTags()) {
                            if (tag.getType() == Tag.TagType.TYPE) continue;
                            int otherTagId = getOrCreateTag(conn, tag.getValue(), tag.getPseudo(), tag.getType());
                            addTagToAsset(conn, asset.getPath(), otherTagId, 1.0);
                        }
                    }
                }
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Batch insert failed", e);
        }
    }

    /**
     * Removes a list of assets from the database by their paths.
     * Uses batch processing for performance.
     */
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
            throw new DatabaseException("Failed to remove assets", e);
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
            throw new DatabaseException("Failed to update asset thumbnail for " + path, e);
        }
    }
    
    public InputStream getThumbnailStream(String path) {
        // Warning: This loads the entire BLOB into memory. For very large images, this could be an issue.
        // Consider streaming directly from the ResultSet if the JDBC driver supports it efficiently,
        // or using a separate file-based cache for large thumbnails.
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
            throw new DatabaseException("Failed to get thumbnail stream for " + path, e);
        }
        return null;
    }
    
    public List<String> getAssetsWithoutThumbnail() {
        List<String> paths = new ArrayList<>();
        String sql = "SELECT path FROM assets WHERE (thumbnail_blob IS NULL OR thumbnail_blob = '') AND (type = 'IMAGE' OR type = 'VIDEO' or type = 'ICON')";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                paths.add(rs.getString("path"));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get assets without thumbnail", e);
        }
        return paths;
    }
    
    public void resetThumbnails() {
        String sql = "UPDATE assets SET thumbnail_blob = NULL";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to reset thumbnails", e);
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
            throw new DatabaseException("Failed to load hashes", e);
        }
        return hashes;
    }

    public Map<String, String> getAssetPathToHashMap() {
        Map<String, String> map = new HashMap<>();
        String sql = "SELECT path, hash FROM assets";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                map.put(rs.getString("path"), rs.getString("hash"));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load asset path-hash map", e);
        }
        return map;
    }

    /**
     * Retrieves a tag ID by its value, or creates it if it doesn't exist.
     * This ensures tag uniqueness and reuse.
     */
    public int getOrCreateTag(String value, String pseudo, Tag.TagType type) {
        try (Connection conn = connect()) {
            return getOrCreateTag(conn, value, pseudo, type);
        } catch (SQLException e) {
            throw new DatabaseException("Error managing tag: " + value, e);
        }
    }

    // Internal version that accepts a connection for transaction support
    private int getOrCreateTag(Connection conn, String value, String pseudo, Tag.TagType type) throws SQLException {
        String selectSql = "SELECT id FROM tags WHERE value = ?";
        String insertSql = "INSERT INTO tags(value, pseudo, type) VALUES(?, ?, ?)";

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
        } catch (SQLException e) {
            throw new DatabaseException("Error managing tag: " + value, e);
        }
        return -1;
    }

    public void addTagToAsset(String assetPath, int tagId, double confidence) {
        try (Connection conn = connect()) {
            addTagToAsset(conn, assetPath, tagId, confidence);
        } catch (SQLException e) {
            throw new DatabaseException("Error linking tag to asset: " + assetPath, e);
        }
    }

    // Internal version that accepts a connection for transaction support
    private void addTagToAsset(Connection conn, String assetPath, int tagId, double confidence) throws SQLException {
        String sql = "INSERT OR IGNORE INTO asset_tags(asset_path, tag_id, confidence) VALUES(?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, assetPath);
            pstmt.setInt(2, tagId);
            pstmt.setDouble(3, confidence);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Error linking tag to asset: " + assetPath, e);
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
            throw new DatabaseException("Failed to get asset count for type " + type, e);
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
                assets.add(mapResultSetToAsset(conn, rs));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get all assets", e);
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
                    return mapResultSetToAsset(conn, rs);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get asset by path: " + path, e);
        }
        return null;
    }
    
    private Asset mapResultSetToAsset(Connection conn, ResultSet rs) throws SQLException {
        String path = rs.getString("path");
        String hash = rs.getString("hash");
        long size = rs.getLong("size");
        String dateStr = rs.getString("creation_date");
        LocalDateTime date = dateStr != null ? LocalDateTime.parse(dateStr) : null;
        Asset.AssetType type = Asset.AssetType.valueOf(rs.getString("type"));
        
        Asset asset = new Asset(path, hash, size, date, type);
        
        // Load tags for this asset
        List<Tag> tags = getTagsForAsset(conn, path);
        asset.setTags(tags);
        
        return asset;
    }

    private List<Tag> getTagsForAsset(Connection conn, String assetPath) throws SQLException {
        List<Tag> tags = new ArrayList<>();
        String sql = """
            SELECT t.id, t.value, t.pseudo, t.type 
            FROM tags t 
            JOIN asset_tags at ON t.id = at.tag_id 
            WHERE at.asset_path = ?
        """;
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, assetPath);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String value = rs.getString("value");
                    String pseudo = rs.getString("pseudo");
                    Tag.TagType type = Tag.TagType.valueOf(rs.getString("type"));
                    tags.add(new Tag(id, value, pseudo, type));
                }
            }
        }
        return tags;
    }

    public Map<Tag.TagType, List<Tag>> getAllTagsGroupedByType() {
        Map<Tag.TagType, List<Tag>> groupedTags = new EnumMap<>(Tag.TagType.class);
        
        // Initialize lists for all types
        for (Tag.TagType type : Tag.TagType.values()) {
            groupedTags.put(type, new ArrayList<>());
        }

        String sql = """
            SELECT t.id, t.value, t.pseudo, t.type, COUNT(at.asset_path) as count
            FROM tags t
            LEFT JOIN asset_tags at ON t.id = at.tag_id
            GROUP BY t.id
            ORDER BY t.pseudo ASC
        """;

        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                int id = rs.getInt("id");
                String value = rs.getString("value");
                String pseudo = rs.getString("pseudo");
                Tag.TagType type = Tag.TagType.valueOf(rs.getString("type"));
                int count = rs.getInt("count");
                Tag tag = new Tag(id, value, pseudo, type);
                tag.setPseudo(pseudo + " (" + count + ")");
                
                groupedTags.get(type).add(tag);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load grouped tags", e);
        }
        
        return groupedTags;
    }

    public void ensureTypeTagsExist() {
        String sqlMissingTags = """
            SELECT a.path, a.type 
            FROM assets a 
            LEFT JOIN asset_tags at ON a.path = at.asset_path 
            LEFT JOIN tags t ON at.tag_id = t.id AND t.type = 'TYPE'
            WHERE t.id IS NULL
        """;
        
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sqlMissingTags)) {
                
                while (rs.next()) {
                    String path = rs.getString("path");
                    String typeStr = rs.getString("type");
                    Asset.AssetType type = Asset.AssetType.valueOf(typeStr);
                    
                    // Skip ICON type
                    if (type == Asset.AssetType.ICON) continue;
                    
                    String typeTagValue = type.name().toLowerCase();
                    String typeTagPseudo = type == Asset.AssetType.IMAGE ? "Photo" : "Video";
                    
                    int tagId = getOrCreateTag(conn, typeTagValue, typeTagPseudo, Tag.TagType.TYPE);
                    addTagToAsset(conn, path, tagId, 1.0);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to ensure type tags exist", e);
        }
    }
    
    public void cleanupOrphanedAssets() {
        String sql = "DELETE FROM assets WHERE path NOT IN (SELECT path FROM assets)";
        // This query doesn't make sense as written (deletes nothing).
        // The goal is to remove assets from DB that no longer exist on disk.
        // But the DB doesn't know about the disk state directly here.
        // This method should probably take a list of valid paths and delete everything else.
    }
    
    public void removeAssetsNotInList(Set<String> validPaths) {
        if (validPaths == null) return;
        
        // This can be heavy if the list is huge.
        // Alternative: Iterate over DB assets and check if they are in validPaths.
        
        String selectSql = "SELECT path FROM assets";
        List<String> pathsToRemove = new ArrayList<>();
        
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql)) {
            while (rs.next()) {
                String path = rs.getString("path");
                if (!validPaths.contains(path)) {
                    pathsToRemove.add(path);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to identify orphaned assets", e);
        }
        
        if (!pathsToRemove.isEmpty()) {
            removeAssets(pathsToRemove);
            System.out.println("Removed " + pathsToRemove.size() + " orphaned assets from database.");
        }
    }
}
