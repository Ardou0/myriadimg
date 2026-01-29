package com.myriadimg.service;

import com.myriadimg.model.Asset;
import com.myriadimg.repository.ProjectDatabaseManager;
import com.myriadimg.util.ProjectLogger;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IndexingService}.
 * Tests the file system scanning, asset hashing, and database synchronization logic.
 * Ensures that new files are indexed, existing files are skipped, and non-media files are ignored.
 */
@ExtendWith(MockitoExtension.class)
class IndexingServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private ProjectDatabaseManager dbManager;

    private IndexingService indexingService;

    // Static mocks for singletons and platform dependencies
    private static MockedStatic<ServiceManager> serviceManagerMock;
    private static MockedStatic<I18nService> i18nServiceMock;
    private static MockedStatic<ProjectLogger> projectLoggerMock;
    private static MockedStatic<Platform> platformMock;

    @BeforeAll
    static void beforeAll() {
        // Mock I18nService singleton to avoid resource loading issues
        i18nServiceMock = mockStatic(I18nService.class);
        I18nService i18nInstance = mock(I18nService.class);
        i18nServiceMock.when(I18nService::getInstance).thenReturn(i18nInstance);
        when(i18nInstance.get(anyString())).thenReturn("test string");
        doNothing().when(i18nInstance).addLanguageChangeListener(any());

        // Mock ServiceManager singleton
        serviceManagerMock = mockStatic(ServiceManager.class);
        ServiceManager serviceManagerInstance = mock(ServiceManager.class);
        serviceManagerMock.when(ServiceManager::getInstance).thenReturn(serviceManagerInstance);

        // Mock ProjectLogger to prevent file I/O during tests
        projectLoggerMock = mockStatic(ProjectLogger.class);

        // Mock JavaFX Platform.runLater to execute runnables immediately on the same thread
        platformMock = mockStatic(Platform.class);
        platformMock.when(() -> Platform.runLater(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        });
    }

    @AfterAll
    static void afterAll() {
        // Close static mocks to release resources
        serviceManagerMock.close();
        i18nServiceMock.close();
        projectLoggerMock.close();
        platformMock.close();
    }

    @BeforeEach
    void setUp() {
        // A new service instance is created for each test to ensure isolation
        indexingService = new IndexingService(tempDir, dbManager);
    }

    /**
     * Verifies that new media files in the directory are correctly identified, hashed, and inserted into the database.
     */
    @Test
    void testCall_WithNewFiles_ShouldIndexAndSave() throws Exception {
        // 1. Arrange: Create dummy media files
        Files.writeString(tempDir.resolve("image1.jpg"), "content1");
        Files.writeString(tempDir.resolve("image2.png"), "content2");
        Files.writeString(tempDir.resolve("video1.mp4"), "content3");
        
        when(dbManager.getAllHashes()).thenReturn(Collections.emptySet());

        // 2. Act: Run the indexing service
        indexingService.call();

        // 3. Assert: Verify assets were batched and inserted
        ArgumentCaptor<List<Asset>> captor = ArgumentCaptor.forClass(List.class);
        verify(dbManager, atLeastOnce()).batchInsertAssets(captor.capture());

        List<Asset> allCapturedAssets = captor.getAllValues().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        assertEquals(3, allCapturedAssets.size(), "Should index exactly 3 files");
        Set<String> paths = allCapturedAssets.stream().map(Asset::getPath).collect(Collectors.toSet());
        assertTrue(paths.contains("image1.jpg"));
        assertTrue(paths.contains("image2.png"));
        assertTrue(paths.contains("video1.mp4"));
    }

    /**
     * Verifies that files already present in the database (by hash) are skipped during indexing.
     */
    @Test
    void testCall_WithExistingFiles_ShouldSkip() throws Exception {
        // 1. Arrange: Create one existing file and one new file
        Path existingFile = tempDir.resolve("existing.jpg");
        Path newFile = tempDir.resolve("new.png");
        
        Files.writeString(existingFile, "I am the existing file");
        Files.writeString(newFile, "I am the new file");

        // Simulate that the first file is already known in the DB
        String existingHash = calculatePartialHash(existingFile);
        when(dbManager.getAllHashes()).thenReturn(new HashSet<>(Set.of(existingHash)));

        // 2. Act
        indexingService.call();

        // 3. Assert: Only the new file should be inserted
        ArgumentCaptor<List<Asset>> captor = ArgumentCaptor.forClass(List.class);
        verify(dbManager, atLeastOnce()).batchInsertAssets(captor.capture());

        List<Asset> allCapturedAssets = captor.getAllValues().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        assertEquals(1, allCapturedAssets.size(), "Should only index the new file");
        assertEquals("new.png", allCapturedAssets.get(0).getPath());
    }

    /**
     * Verifies that non-media files (e.g., txt, zip) are ignored by the indexer.
     */
    @Test
    void testCall_WithNonMediaFiles_ShouldIgnore() throws Exception {
        // 1. Arrange: Create a mix of media and non-media files
        Files.createFile(tempDir.resolve("image.jpg"));
        Files.createFile(tempDir.resolve("document.txt"));
        Files.createFile(tempDir.resolve("archive.zip"));
        when(dbManager.getAllHashes()).thenReturn(Collections.emptySet());

        // 2. Act
        indexingService.call();

        // 3. Assert: Only the image should be indexed
        ArgumentCaptor<List<Asset>> captor = ArgumentCaptor.forClass(List.class);
        verify(dbManager, atLeastOnce()).batchInsertAssets(captor.capture());

        List<Asset> allCapturedAssets = captor.getAllValues().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        assertEquals(1, allCapturedAssets.size());
        assertEquals("image.jpg", allCapturedAssets.get(0).getPath());
    }

    /**
     * Verifies that the service handles empty directories gracefully without errors.
     */
    @Test
    void testCall_WithEmptyDirectory_ShouldDoNothing() throws Exception {
        // 1. Arrange
        when(dbManager.getAllHashes()).thenReturn(Collections.emptySet());

        // 2. Act
        indexingService.call();

        // 3. Assert: Verify no assets (or empty list) were inserted
        verify(dbManager, atMostOnce()).batchInsertAssets(argThat(List::isEmpty));
    }

    /**
     * Verifies that stopping the service triggers the task cancellation mechanism.
     */
    @Test
    void testStopService_ShouldAttemptToCancelTask() {
        // 1. Arrange: Spy on the service to check internal state
        IndexingService spiedService = spy(new IndexingService(tempDir, dbManager));
        doReturn(true).when(spiedService).isRunning();

        // 2. Act
        spiedService.stopService();

        // 3. Assert
        verify(spiedService).cancel();
    }

    /**
     * Verifies that hidden files and directories (starting with '.') are ignored.
     */
    @Test
    void testCall_ShouldIgnoreHiddenFoldersAndFiles() throws Exception {
        // 1. Arrange: Create hidden folders and files
        Path hiddenDir = tempDir.resolve(".hidden");
        Files.createDirectories(hiddenDir);
        Files.createFile(hiddenDir.resolve("secret.jpg"));

        Path visibleDir = tempDir.resolve("visible");
        Files.createDirectories(visibleDir);
        Files.createFile(visibleDir.resolve(".DS_Store")); // Hidden file
        Files.createFile(visibleDir.resolve("valid.jpg")); // Valid file

        when(dbManager.getAllHashes()).thenReturn(Collections.emptySet());

        // 2. Act
        indexingService.call();

        // 3. Assert: Only the valid visible file should be indexed
        ArgumentCaptor<List<Asset>> captor = ArgumentCaptor.forClass(List.class);
        verify(dbManager, atLeastOnce()).batchInsertAssets(captor.capture());

        List<Asset> allCapturedAssets = captor.getAllValues().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        assertEquals(1, allCapturedAssets.size(), "Should only index valid.jpg");
        assertTrue(allCapturedAssets.get(0).getPath().endsWith("valid.jpg"));
        assertFalse(allCapturedAssets.get(0).getPath().contains(".hidden"));
    }

    /**
     * Helper method to calculate a file's partial hash, mirroring the logic in IndexingService.
     * Used to set up test expectations for existing files.
     */
    private String calculatePartialHash(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4096);
            int bytesRead = channel.read(buffer);
            buffer.flip();
            if (bytesRead > 0) {
                digest.update(buffer);
            }
            String sizeStr = String.valueOf(Files.size(file));
            digest.update(sizeStr.getBytes());
        }
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
