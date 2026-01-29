package com.myriadimg.service;

import com.myriadimg.repository.ProjectDatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ThumbnailServiceTest {

    @Mock
    private ProjectDatabaseManager dbManager;

    @Mock
    private MediaConverterService mediaConverterService;

    @TempDir
    Path tempDir;

    private ThumbnailService thumbnailService;
    private static final int TARGET_SIZE = 200;

    private MockedStatic<ServiceManager> mockedServiceManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Mock the ServiceManager singleton to prevent calls to JavaFX Platform during tests
        mockedServiceManager = mockStatic(ServiceManager.class);
        ServiceManager mockManager = mock(ServiceManager.class);
        mockedServiceManager.when(ServiceManager::getInstance).thenReturn(mockManager);

        // Initialize the service with mocks using the package-private constructor
        thumbnailService = new ThumbnailService(tempDir, dbManager, TARGET_SIZE, mediaConverterService);
    }

    @AfterEach
    void tearDown() {
        // Close the static mock after each test to avoid state leakage
        mockedServiceManager.close();
    }

    @Test
    void testProcessThumbnail_StandardImage_ShouldUseThumbnailator() throws IOException {
        // Arrange
        String filename = "test_image.jpg";
        File imageFile = tempDir.resolve(filename).toFile();
        createDummyImage(imageFile, "jpg");

        // Act
        thumbnailService.processThumbnail(filename);

        // Assert
        // Verify that updateAssetThumbnail was called with non-null bytes
        verify(dbManager, times(1)).updateAssetThumbnail(eq(filename), any(byte[].class));
        // Verify that MediaConverterService was NOT called for a standard JPG
        verify(mediaConverterService, never()).convertComplexImage(any(), anyInt());
        verify(mediaConverterService, never()).convertComplexVideo(any(), anyInt());
    }

    @Test
    void testProcessThumbnail_Video_FallbackToConverter() throws IOException {
        // Arrange
        // We create a dummy file that is NOT a valid video, so JCodec will fail
        String filename = "test_video.mp4";
        File videoFile = tempDir.resolve(filename).toFile();
        videoFile.createNewFile();

        // Mock MediaConverterService to return a dummy thumbnail when called
        byte[] dummyBytes = new byte[]{1, 2, 3};
        when(mediaConverterService.convertComplexVideo(any(File.class), eq(TARGET_SIZE)))
                .thenReturn(dummyBytes);

        // Act
        thumbnailService.processThumbnail(filename);

        // Assert
        // JCodec should fail (invalid file), so it should try MediaConverterService
        verify(mediaConverterService, times(1)).convertComplexVideo(any(File.class), eq(TARGET_SIZE));
        // And finally update the DB
        verify(dbManager, times(1)).updateAssetThumbnail(eq(filename), eq(dummyBytes));
    }

    @Test
    void testProcessThumbnail_ComplexImage_ShouldCallConverter() throws IOException {
        // Arrange
        String filename = "test_complex.heic";
        File heicFile = tempDir.resolve(filename).toFile();
        heicFile.createNewFile();

        byte[] dummyBytes = new byte[]{4, 5, 6};
        when(mediaConverterService.convertComplexImage(any(File.class), eq(TARGET_SIZE)))
                .thenReturn(dummyBytes);

        // Act
        thumbnailService.processThumbnail(filename);

        // Assert
        verify(mediaConverterService, times(1)).convertComplexImage(any(File.class), eq(TARGET_SIZE));
        verify(dbManager, times(1)).updateAssetThumbnail(eq(filename), eq(dummyBytes));
    }

    @Test
    void testProcessThumbnail_FileNotFound_ShouldLogAndSkip() {
        // Arrange
        String filename = "non_existent.jpg";

        // Act
        thumbnailService.processThumbnail(filename);

        // Assert
        verify(dbManager, never()).updateAssetThumbnail(anyString(), any());
        verify(mediaConverterService, never()).convertComplexImage(any(), anyInt());
    }

    @Test
    void testProcessThumbnail_AllFail_ShouldUsePlaceholder() throws IOException {
        // Arrange
        String filename = "broken_video.mp4";
        File videoFile = tempDir.resolve(filename).toFile();
        videoFile.createNewFile();

        // Mock MediaConverterService to return null (failure) or throw exception
        when(mediaConverterService.convertComplexVideo(any(File.class), eq(TARGET_SIZE)))
                .thenReturn(null);

        // Act
        thumbnailService.processThumbnail(filename);

        // Assert
        // Should have tried converter
        verify(mediaConverterService, times(1)).convertComplexVideo(any(File.class), eq(TARGET_SIZE));
        
        // Should still update DB with a placeholder (non-null bytes)
        verify(dbManager, times(1)).updateAssetThumbnail(eq(filename), any(byte[].class));
    }

    // Helper to create a valid image file
    private void createDummyImage(File file, String format) throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, format, file);
    }
}
