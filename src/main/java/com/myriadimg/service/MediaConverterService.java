package com.myriadimg.service;

import net.coobird.thumbnailator.Thumbnails;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.opencv_core.Mat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

// Static import to simplify OpenCV code
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR;

public class MediaConverterService {

    private final NativeFFMPEGService nativeFFMPEGService = new NativeFFMPEGService();

    /**
     * Converts a complex image file (HEIC, RAW, etc.) to JPEG (byte array).
     */
    public byte[] convertComplexImage(File sourceFile, int width) {
        // 1. Primary attempt with OpenCV (most robust for images)
        byte[] result = convertWithOpenCV(sourceFile, width);

        if (result != null) return result;

        // 2. Secondary attempt with NativeFFMPEGService (external FFmpeg binary)
        result = convertWithNativeLoader(sourceFile, width);

        if (result != null) return result;

        // 3. Fallback: Returns null to let the calling service handle the placeholder
        return null;
    }

    /**
     * Converts a complex video file (MKV, AVI, etc.) to JPEG (byte array).
     */
    public byte[] convertComplexVideo(File sourceFile, int width) {
        try {
            // Ask FFmpeg directly to extract and resize the image
            BufferedImage image = nativeFFMPEGService.decodeComplexVideo(sourceFile, width);
            if (image != null) {
                return compressToJpeg(image);
            }
        } catch (Exception e) {
            System.err.println("NativeFFMPEGService: Failed to decode video " + sourceFile.getName() + " -> " + e.getMessage());
        }
        return null;
    }

    private byte[] convertWithOpenCV(File sourceFile, int width) {
        Mat mat = null;
        try {
            // OpenCV loads the image directly. It handles HEIC if codecs are present (which is the case via JavaCV).
            // IMREAD_COLOR forces conversion to BGR (OpenCV standard) and ignores alpha transparency which can be buggy
            mat = loadMat(sourceFile.getAbsolutePath(), IMREAD_COLOR);

            if (mat == null || mat.empty()) {
                return null;
            }

            // Conversion: Mat (C++) -> Frame (JavaCV) -> BufferedImage (Java AWT)
            try (OpenCVFrameConverter.ToMat openCVConverter = new OpenCVFrameConverter.ToMat();
                 Java2DFrameConverter java2dConverter = new Java2DFrameConverter()) {

                Frame frame = openCVConverter.convert(mat);
                BufferedImage image = java2dConverter.convert(frame);

                if (image == null) return null;

                return resizeAndCompress(image, width);
            }
        } catch (Exception e) {
            return null;
        } finally {
            // Very important: Release native memory of OpenCV Matrix
            if (mat != null) {
                mat.release();
            }
        }
    }

    // Protected method to facilitate testing (Spying)
    protected Mat loadMat(String path, int flags) {
        return imread(path, flags);
    }

    private byte[] convertWithNativeLoader(File sourceFile, int width) {
        try {
            // Ask FFmpeg directly to resize the image
            // This avoids loading a 4K/8K image into RAM to shrink it later
            BufferedImage image = nativeFFMPEGService.decodeComplexImage(sourceFile, width);
            if (image != null) {
                // The image is already the right size (or close), just compress to JPEG
                return compressToJpeg(image);
            }
        } catch (Exception e) {
            System.err.println("NativeFFMPEGService: Failed to decode " + sourceFile.getName() + " -> " + e.getMessage());
        }
        return null;
    }

    private byte[] resizeAndCompress(BufferedImage image, int width) throws IOException {
        // Check image type to avoid implicit conversions to gray
        BufferedImage rgbImage = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB && image.getType() != BufferedImage.TYPE_INT_ARGB) {
            rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Thumbnails.of(rgbImage)
                .width(width)
                .outputQuality(0.80)
                .outputFormat("jpg")
                .toOutputStream(baos);
        return baos.toByteArray();
    }

    private byte[] compressToJpeg(BufferedImage image) throws IOException {
        // Simplified method because the image is already resized by FFmpeg
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Thumbnails.of(image)
                .scale(1.0) // No resizing
                .outputQuality(0.80)
                .outputFormat("jpg")
                .toOutputStream(baos);
        return baos.toByteArray();
    }
}
