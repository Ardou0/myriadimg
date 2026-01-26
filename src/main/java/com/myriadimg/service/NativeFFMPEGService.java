package com.myriadimg.service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class NativeFFMPEGService {

    private static File ffmpegExecutable = null;

    /**
     * Converts a complex image file (HEIC, RAW, etc.) to a resized BufferedImage.
     * Uses the embedded FFmpeg binary to decode and resize in a single pass.
     * This significantly improves performance by avoiding loading the full-resolution image into RAM.
     *
     * @param imageFile The source file
     * @param targetWidth The target width (aspect ratio is preserved)
     * @return The decoded and resized image
     */
    public BufferedImage decodeComplexImage(File imageFile, int targetWidth) {
        List<String> inputArgs = new ArrayList<>();
        // 1. ANALYSIS
        inputArgs.add("-probesize"); inputArgs.add("50M");
        inputArgs.add("-analyzeduration"); inputArgs.add("100M");
        inputArgs.add("-i"); inputArgs.add(imageFile.getAbsolutePath());
        
        // 2. LIMITATION
        inputArgs.add("-frames:v"); inputArgs.add("1");

        return executeFFmpeg(inputArgs, targetWidth, "img_tmp_");
    }

    /**
     * Extracts a thumbnail from a complex video file (MKV, AVI, etc.).
     *
     * @param videoFile The source video file
     * @param targetWidth The target width
     * @return The extracted image
     */
    public BufferedImage decodeComplexVideo(File videoFile, int targetWidth) {
        List<String> inputArgs = new ArrayList<>();
        // 1. POSITIONING (Trick to avoid black screen)
        // "-ss 00:00:01" -> Skip 1st second to avoid fade-in from black
        // If you really want frame 0, use "00:00:00"
        inputArgs.add("-ss"); inputArgs.add("00:00:00");
        
        inputArgs.add("-i"); inputArgs.add(videoFile.getAbsolutePath());
        
        // 2. SELECTION
        inputArgs.add("-map"); inputArgs.add("0:v:0"); // Take 1st video stream
        inputArgs.add("-frames:v"); inputArgs.add("1"); // Single frame

        return executeFFmpeg(inputArgs, targetWidth, "vid_tmp_");
    }

    private BufferedImage executeFFmpeg(List<String> inputArgs, int targetWidth, String tempPrefix) {
        File tempOutput = null;
        try {
            File ffmpeg = getFFmpegExecutable();
            tempOutput = File.createTempFile(tempPrefix, ".jpg");

            List<String> command = new ArrayList<>();
            command.add(ffmpeg.getAbsolutePath());
            command.add("-hide_banner");
            command.add("-loglevel"); command.add("error");
            command.add("-y");
            
            command.addAll(inputArgs);

            // FILTERS (Same corrections for HEIC and Video for a nice thumbnail)
            // - scale : Resize
            // - format : Force standard color space
            // - eq :
            //    > gamma=1.5 : Brightens dark areas
            //    > saturation=1.2 : Boosts colors (+20%)
            //    > contrast=1.05 : Adds a bit of pop
            command.add("-vf");
            command.add("scale=" + targetWidth + ":-2,format=yuvj420p,eq=gamma=1.5:saturation=1.2:contrast=1.05");

            // Encoding
            command.add("-c:v"); command.add("mjpeg");
            command.add("-q:v"); command.add("2");
            command.add("-update"); command.add("1");
            
            command.add(tempOutput.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(command);
            Process p = pb.start();

            boolean finished = p.waitFor(15, TimeUnit.SECONDS);

            if (!finished) {
                p.destroyForcibly();
                return null;
            }

            if (p.exitValue() != 0 && tempOutput.length() == 0) {
                printErrorStream(p);
                return null;
            }

            return ImageIO.read(tempOutput);

        } catch (Exception e) {
            System.err.println("FFmpeg Error: " + e.getMessage());
            return null;
        } finally {
            if (tempOutput != null && tempOutput.exists()) {
                tempOutput.delete();
            }
        }
    }

    private void printErrorStream(Process p) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.err.println("FFmpeg Error: " + line);
            }
        }
    }

    private synchronized File getFFmpegExecutable() throws IOException {
        if (ffmpegExecutable != null && ffmpegExecutable.exists()) {
            return ffmpegExecutable;
        }

        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        String basePath = "/bin/ffmpeg/";
        String resourceName;
        String exeNameOnDisk;

        if (os.contains("win")) {
            resourceName = "ffmpeg.exe";
            exeNameOnDisk = "ffmpeg.exe";
        } else if (os.contains("mac")) {
            resourceName = "ffmpeg_mac";
            exeNameOnDisk = "ffmpeg";
        } else if (os.contains("nux") || os.contains("nix")) {
            exeNameOnDisk = "ffmpeg";
            if (arch.contains("aarch64") || arch.contains("arm")) {
                resourceName = "ffmpeg_linux_arm";
            } else {
                resourceName = "ffmpeg_linux_amd";
            }
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }

        String fullPath = basePath + resourceName;
        InputStream in = getClass().getResourceAsStream(fullPath);

        if (in == null) {
            throw new FileNotFoundException("Binary not found in JAR: " + fullPath);
        }

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "myriad_tools");
        if (!tempDir.exists()) tempDir.mkdirs();

        File dest = new File(tempDir, exeNameOnDisk);

        if (!dest.exists()) {
            Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!os.contains("win")) {
                try {
                    Set<PosixFilePermission> perms = new HashSet<>();
                    perms.add(PosixFilePermission.OWNER_EXECUTE);
                    perms.add(PosixFilePermission.OWNER_READ);
                    perms.add(PosixFilePermission.OWNER_WRITE);
                    Files.setPosixFilePermissions(dest.toPath(), perms);
                } catch (Exception ignored) {
                    dest.setExecutable(true);
                }
            }
        }

        ffmpegExecutable = dest;
        return dest;
    }
}
