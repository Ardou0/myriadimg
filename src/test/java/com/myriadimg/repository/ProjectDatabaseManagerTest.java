package com.myriadimg.repository;

import com.myriadimg.model.Asset;
import com.myriadimg.model.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectDatabaseManagerTest {

    private static final String TEST_DB_URL = "jdbc:sqlite:file:testdb?mode=memory&cache=shared";
    private ProjectDatabaseManager dbManager;
    private Connection sharedConnection;

    @BeforeEach
    void setUp() throws SQLException {
        // Keep a shared connection open to preserve the in-memory DB state
        sharedConnection = DriverManager.getConnection(TEST_DB_URL);
        
        // Initialize the manager with the test URL
        dbManager = new ProjectDatabaseManager(TEST_DB_URL, true);
        
        // Clean tables before each test
        try (Statement stmt = sharedConnection.createStatement()) {
            stmt.execute("DELETE FROM asset_tags");
            stmt.execute("DELETE FROM tags");
            stmt.execute("DELETE FROM assets");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (sharedConnection != null && !sharedConnection.isClosed()) {
            sharedConnection.close();
        }
    }

    @Test
    void testBatchInsertAssets_UniquePathConstraint() {
        Asset asset1 = new Asset("path/to/image.jpg", "hash1", 1000, LocalDateTime.now(), Asset.AssetType.IMAGE);
        Asset asset2 = new Asset("path/to/image.jpg", "hash2", 2000, LocalDateTime.now(), Asset.AssetType.IMAGE); // Same path

        // First insert
        dbManager.batchInsertAssets(List.of(asset1));
        
        // Second insert with same path (should be ignored due to INSERT OR IGNORE)
        dbManager.batchInsertAssets(List.of(asset2));

        List<Asset> assets = dbManager.getAllAssets();
        assertEquals(1, assets.size());
        assertEquals("hash1", assets.get(0).getHash(), "Should keep the first asset");
    }

    @Test
    void testTagLifecycle() {
        // 1. Create Tag
        int tagId = dbManager.getOrCreateTag("Beach", "beach", Tag.TagType.AI_SCENE);
        assertTrue(tagId > 0, "Tag ID should be positive");

        // 2. Reuse Tag (should return same ID)
        int tagId2 = dbManager.getOrCreateTag("Beach", "beach", Tag.TagType.AI_SCENE);
        assertEquals(tagId, tagId2, "Should return existing ID for same value");

        // 3. Link to Asset
        Asset asset = new Asset("img1.jpg", "h1", 100, LocalDateTime.now(), Asset.AssetType.IMAGE);
        dbManager.batchInsertAssets(List.of(asset));
        
        dbManager.addTagToAsset("img1.jpg", tagId, 0.95);

        // Verify link (we don't have a direct method to get tags for asset yet, so checking via SQL or count)
        // For now, let's just ensure no exception was thrown. 
        // Ideally, we should add a method getTagsForAsset(path) in ProjectDatabaseManager.
    }

    @Test
    void testAssetDeletionCascadesToTags() throws SQLException {
        // 1. Setup Asset and Tag
        Asset asset = new Asset("img_delete.jpg", "h_del", 500, LocalDateTime.now(), Asset.AssetType.IMAGE);
        dbManager.batchInsertAssets(List.of(asset));
        
        int tagId = dbManager.getOrCreateTag("Sunset", "sunset", Tag.TagType.AI_SCENE);
        dbManager.addTagToAsset("img_delete.jpg", tagId, 0.8);

        // 2. Delete Asset
        dbManager.removeAssets(List.of("img_delete.jpg"));

        // 3. Verify Asset is gone
        assertNull(dbManager.getAssetByPath("img_delete.jpg"));

        // 4. Verify Link is gone (Cascade Delete)
        try (Statement stmt = sharedConnection.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM asset_tags WHERE asset_path = 'img_delete.jpg'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Asset tags should be deleted via cascade");
        }
    }
}
