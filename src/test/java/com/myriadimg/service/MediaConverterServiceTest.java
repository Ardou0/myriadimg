package com.myriadimg.service;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MediaConverterService}.
 * Tests the media conversion logic, including OpenCV integration and fallback to native FFMPEG.
 * Ensures that complex image and video formats are correctly processed or gracefully handled on failure.
 */
@ExtendWith(MockitoExtension.class)
class MediaConverterServiceTest {

    @Spy
    private MediaConverterService mediaConverterService;

    @Mock
    private NativeFFMPEGService nativeFFMPEGService;

    @BeforeEach
    void setUp() throws Exception {
        // Inject mocked NativeFFMPEGService using reflection to bypass private field access
        Field field = MediaConverterService.class.getDeclaredField("nativeFFMPEGService");
        field.setAccessible(true);
        field.set(mediaConverterService, nativeFFMPEGService);
    }

    /**
     * Verifies that complex images (e.g., HEIC) are successfully converted using OpenCV when available.
     */
    @Test
    void testConvertComplexImage_OpenCVSuccess() {
        File sourceFile = new File("test_image.heic");
        int width = 800;

        // Use a REAL Mat instead of a mock to avoid Native Method NPE in OpenCV internals
        Mat realMat = new Mat(10, 10, opencv_core.CV_8UC3);

        // Spy on the method that calls imread to return our real Mat
        doReturn(realMat).when(mediaConverterService).loadMat(anyString(), anyInt());

        try (MockedConstruction<OpenCVFrameConverter.ToMat> mockOpenCVCtor = mockConstruction(OpenCVFrameConverter.ToMat.class,
                     (mock, context) -> {
                         Frame mockFrame = mock(Frame.class);
                         when(mock.convert(any(Mat.class))).thenReturn(mockFrame);
                     });
             MockedConstruction<Java2DFrameConverter> mockJava2DCtor = mockConstruction(Java2DFrameConverter.class,
                     (mock, context) -> {
                         // Create a valid BufferedImage to simulate success
                         BufferedImage dummyImage = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
                         when(mock.convert(any(Frame.class))).thenReturn(dummyImage);
                     })) {

            // Execute
            byte[] result = mediaConverterService.convertComplexImage(sourceFile, width);

            // Verify
            assertNotNull(result, "Result should not be null on success");
            assertTrue(result.length > 0, "Result should contain bytes");

            // Verify OpenCV was used via our spy
            verify(mediaConverterService).loadMat(eq(sourceFile.getAbsolutePath()), anyInt());

            // Verify fallback was NOT used
            verify(nativeFFMPEGService, never()).decodeComplexImage(any(), anyInt());
            
            // Cleanup manually created Mat
            realMat.release();
        }
    }

    /**
     * Verifies that the service falls back to NativeFFMPEGService if OpenCV fails to load the image.
     */
    @Test
    void testConvertComplexImage_OpenCVFail_NativeFallback() {
        File sourceFile = new File("test_image.heic");
        int width = 800;

        // Simulate OpenCV failure (returns null)
        doReturn(null).when(mediaConverterService).loadMat(anyString(), anyInt());

        // Simulate NativeFFMPEGService success
        BufferedImage dummyImage = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        when(nativeFFMPEGService.decodeComplexImage(any(File.class), anyInt())).thenReturn(dummyImage);

        // Execute
        byte[] result = mediaConverterService.convertComplexImage(sourceFile, width);

        // Verify
        assertNotNull(result, "Result should not be null when fallback succeeds");
        assertTrue(result.length > 0);

        // Verify OpenCV was attempted
        verify(mediaConverterService).loadMat(eq(sourceFile.getAbsolutePath()), anyInt());

        // Verify fallback WAS used
        verify(nativeFFMPEGService).decodeComplexImage(eq(sourceFile), eq(width));
    }

    /**
     * Verifies that the service returns null if both OpenCV and NativeFFMPEGService fail.
     */
    @Test
    void testConvertComplexImage_AllFail() {
        File sourceFile = new File("test_image.heic");
        int width = 800;

        // OpenCV fails
        doReturn(null).when(mediaConverterService).loadMat(anyString(), anyInt());

        // NativeFFMPEGService fails
        when(nativeFFMPEGService.decodeComplexImage(any(File.class), anyInt())).thenReturn(null);

        // Execute
        byte[] result = mediaConverterService.convertComplexImage(sourceFile, width);

        // Verify
        assertNull(result, "Result should be null when all methods fail");
    }

    /**
     * Verifies that complex video conversion delegates correctly to NativeFFMPEGService.
     */
    @Test
    void testConvertComplexVideo_Success() {
        File sourceFile = new File("test_video.mkv");
        int width = 800;
        BufferedImage dummyImage = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        // Mock NativeFFMPEGService success for video
        when(nativeFFMPEGService.decodeComplexVideo(eq(sourceFile), eq(width))).thenReturn(dummyImage);

        // Execute
        byte[] result = mediaConverterService.convertComplexVideo(sourceFile, width);

        // Verify
        assertNotNull(result);
        assertTrue(result.length > 0);
        verify(nativeFFMPEGService).decodeComplexVideo(eq(sourceFile), eq(width));
    }
}
