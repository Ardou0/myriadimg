package com.myriadimg.service;

import com.myriadimg.repository.ProjectDatabaseManager;
import com.myriadimg.util.ProjectLogger;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.scene.image.Image;
import net.coobird.thumbnailator.Thumbnails;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ThumbnailService extends Service<Void> implements ThrottlableService {

    private final Path projectRoot;
    private final ProjectDatabaseManager dbManager;
    private final int targetSize;
    
    private static final int MAX_THREADS = 4; 
    private static final float JPEG_QUALITY = 0.6f;
    
    private Consumer<String> onThumbnailGenerated;
    private final MediaConverterService mediaConverterService = new MediaConverterService();
    
    // Global ExecutorService to handle cancellation cleanly
    private ThreadPoolExecutor executor;

    public ThumbnailService(Path projectRoot, ProjectDatabaseManager dbManager, int targetSize) {
        this.projectRoot = projectRoot;
        this.dbManager = dbManager;
        this.targetSize = targetSize;
        ServiceManager.getInstance().registerService(this);
    }
    
    @Override
    public String getStatus() {
        Path fileName = projectRoot.getFileName();
        String projectName = (fileName != null) ? fileName.toString() : projectRoot.toString();
        return I18nService.getInstance().get("tray.status.thumbnails") + " (" + projectName + ")";
    }
    
    @Override
    public String getServiceName() {
        return "tray.status.thumbnails";
    }

    @Override
    public String getProjectPath() {
        return projectRoot.toString();
    }
    
    public void setOnThumbnailGenerated(Consumer<String> callback) {
        this.onThumbnailGenerated = callback;
    }

    @Override
    protected Task<Void> createTask() {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                ProjectLogger.logInfo(projectRoot, "ThumbnailService", "Starting thumbnail generation scan");
                ServiceManager.getInstance().updateGlobalStatus();
                
                List<String> pendingAssets;
                try {
                    pendingAssets = dbManager.getAssetsWithoutThumbnail();
                } catch (Exception e) {
                    ProjectLogger.logError(projectRoot, "ThumbnailService", "Failed to fetch pending assets", e);
                    throw e;
                }
                
                if (pendingAssets.isEmpty()) {
                    updateMessage("All thumbnails are up to date.");
                    updateProgress(1, 1);
                    ProjectLogger.logInfo(projectRoot, "ThumbnailService", "No thumbnails to generate");
                    return null;
                }

                int total = pendingAssets.size();
                updateMessage("Generating " + total + " thumbnails...");
                updateProgress(0, total);
                
                int threadPoolSize = Math.min(MAX_THREADS, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
                
                executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threadPoolSize);
                
                AtomicInteger completed = new AtomicInteger(0);

                for (String relativePath : pendingAssets) {
                    if (isCancelled()) {
                        break;
                    }
                    
                    executor.submit(() -> {
                        if (isCancelled()) return; // Double check inside thread
                        try {
                            processThumbnail(relativePath);
                        } finally {
                            int current = completed.incrementAndGet();
                            updateProgress(current, total);
                            if (current % 5 == 0 || current == total) {
                                updateMessage("Processing: " + current + "/" + total);
                            }
                        }
                    });
                }
                
                executor.shutdown();
                try {
                    while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        if (isCancelled()) {
                            executor.shutdownNow();
                            updateMessage("Cancelling...");
                            break;
                        }
                    }
                    
                    if (executor.isTerminated()) {
                        updateMessage("Completed (" + total + " images).");
                        ProjectLogger.logInfo(projectRoot, "ThumbnailService", "Completed generation for " + total + " thumbnails");
                    } else {
                        updateMessage("Interrupted.");
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    updateMessage("Interrupted.");
                    ProjectLogger.logError(projectRoot, "ThumbnailService", "Main thread interrupted", e);
                }
                
                return null;
            }
            
            @Override
            protected void cancelled() {
                super.cancelled();
                shutdownExecutors();
                updateMessage("Generation cancelled.");
                ServiceManager.getInstance().updateGlobalStatus();
            }
            
            @Override
            protected void succeeded() {
                super.succeeded();
                shutdownExecutors();
                ServiceManager.getInstance().updateGlobalStatus();
            }

            @Override
            protected void failed() {
                super.failed();
                shutdownExecutors();
                ServiceManager.getInstance().updateGlobalStatus();
            }
        };
    }
    
    private void shutdownExecutors() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }
    
    // Public method to force stop the service cleanly
    public void stopService() {
        if (isRunning()) {
            cancel();
        }
        shutdownExecutors();
        ServiceManager.getInstance().unregisterService(this);
    }

    private void processThumbnail(String relativePath) {
        File file = new File(projectRoot.toFile(), relativePath);
        if (!file.exists()) {
            ProjectLogger.logError(projectRoot, "ThumbnailService", "File not found for thumbnail generation: " + relativePath, null);
            return;
        }

        try {
            byte[] thumbnailBytes = null;
            String lowerPath = relativePath.toLowerCase();
            
            boolean isVideo = lowerPath.endsWith(".mp4") || lowerPath.endsWith(".mov") || lowerPath.endsWith(".avi") || lowerPath.endsWith(".mkv");
            boolean isComplexImage = lowerPath.endsWith(".heic") || lowerPath.endsWith(".heif") || lowerPath.endsWith(".avif") || lowerPath.endsWith(".raw") || lowerPath.endsWith(".cr2") || lowerPath.endsWith(".nef");

            if (isVideo) {
                // 1. Try standard Java extraction (JCodec)
                BufferedImage original = null;
                try {
                    Picture picture = FrameGrab.getFrameFromFile(file, 1);
                    original = AWTUtil.toBufferedImage(picture);
                } catch (Exception e) {
                    // JCodec failed, try complex video extraction via FFmpeg
                    ProjectLogger.logInfo(projectRoot, "ThumbnailService", "JCodec failed for " + relativePath + ", trying FFmpeg fallback.");
                }
                
                if (original != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    Thumbnails.of(original)
                            .size(targetSize, targetSize)
                            .outputFormat("jpg")
                            .outputQuality(JPEG_QUALITY)
                            .toOutputStream(baos);
                    thumbnailBytes = baos.toByteArray();
                } else {
                    // 2. Try FFmpeg fallback via EmbeddedConverter
                    try {
                        thumbnailBytes = mediaConverterService.convertComplexVideo(file, targetSize);
                    } catch (Exception e) {
                        ProjectLogger.logError(projectRoot, "ThumbnailService", "FFmpeg video extraction failed: " + relativePath, e);
                    }
                    
                    // 3. Final Fallback to placeholder
                    if (thumbnailBytes == null) {
                        BufferedImage placeholder = createVideoPlaceholder(file.getName());
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        Thumbnails.of(placeholder)
                                .size(targetSize, targetSize)
                                .outputFormat("jpg")
                                .outputQuality(JPEG_QUALITY)
                                .toOutputStream(baos);
                        thumbnailBytes = baos.toByteArray();
                    }
                }
            } else if (isComplexImage) {
                try {
                    // Use EmbeddedConverter (OpenCV + FFmpeg) for complex images
                    thumbnailBytes = mediaConverterService.convertComplexImage(file, targetSize);
                } catch (Exception e) {
                    ProjectLogger.logError(projectRoot, "ThumbnailService", "Failed to convert complex image: " + relativePath, e);
                }
                
                // Fallback to placeholder if conversion failed
                if (thumbnailBytes == null) {
                    BufferedImage placeholder = createUnsupportedPlaceholder(file.getName());
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try {
                        Thumbnails.of(placeholder)
                                .size(targetSize, targetSize)
                                .outputFormat("jpg")
                                .outputQuality(JPEG_QUALITY)
                                .toOutputStream(baos);
                        thumbnailBytes = baos.toByteArray();
                    } catch (Exception ex) {
                        // Should not happen
                    }
                }
            } else {
                // Use Thumbnailator directly. It uses ImageIO under the hood, 
                // and we have added TwelveMonkeys plugins to support many formats (TIFF, PSD, WebP, etc.)
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    Thumbnails.of(file)
                            .size(targetSize, targetSize)
                            .outputFormat("jpg")
                            .outputQuality(JPEG_QUALITY)
                            .toOutputStream(baos);
                    thumbnailBytes = baos.toByteArray();
                } catch (net.coobird.thumbnailator.tasks.UnsupportedFormatException e) {
                     // If format is not supported, try complex image converter as a last resort before placeholder
                     try {
                         thumbnailBytes = mediaConverterService.convertComplexImage(file, targetSize);
                     } catch (Exception ex) {
                         thumbnailBytes = null;
                     }
                     
                     if (thumbnailBytes == null) {
                         ProjectLogger.logInfo(projectRoot, "ThumbnailService", "Unsupported image format: " + relativePath + ". Using placeholder.");
                         BufferedImage placeholder = createUnsupportedPlaceholder(file.getName());
                         ByteArrayOutputStream baos = new ByteArrayOutputStream();
                         Thumbnails.of(placeholder)
                                .size(targetSize, targetSize)
                                .outputFormat("jpg")
                                .outputQuality(JPEG_QUALITY)
                                .toOutputStream(baos);
                         thumbnailBytes = baos.toByteArray();
                     }
                } catch (Exception e) {
                    ProjectLogger.logError(projectRoot, "ThumbnailService", "Failed to process image: " + relativePath, e);
                }
            }

            if (thumbnailBytes != null) {
                synchronized (dbManager) {
                    dbManager.updateAssetThumbnail(relativePath, thumbnailBytes);
                }
                
                // Notify listener if set
                if (onThumbnailGenerated != null) {
                    onThumbnailGenerated.accept(relativePath);
                }
            }

        } catch (Exception e) {
            ProjectLogger.logError(projectRoot, "ThumbnailService", "Critical error processing thumbnail: " + relativePath, e);
        }
    }
    
    private BufferedImage createVideoPlaceholder(String text) {
        BufferedImage img = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.DARK_GRAY);
        g.fillRect(0, 0, targetSize, targetSize);
        g.setColor(java.awt.Color.WHITE);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawString("VIDEO", 10, targetSize / 2);
        g.drawString(text.length() > 15 ? text.substring(0, 12) + "..." : text, 10, targetSize / 2 + 20);
        g.dispose();
        return img;
    }

    private BufferedImage createUnsupportedPlaceholder(String text) {
        BufferedImage img = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.GRAY);
        g.fillRect(0, 0, targetSize, targetSize);
        g.setColor(java.awt.Color.WHITE);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawString("N/A", 10, targetSize / 2);
        g.drawString(text.length() > 15 ? text.substring(0, 12) + "..." : text, 10, targetSize / 2 + 20);
        g.dispose();
        return img;
    }
    
    public Image loadThumbnail(String relativePath) {
        java.io.InputStream is = dbManager.getThumbnailStream(relativePath);
        if (is != null) {
            return new Image(is);
        }
        return null;
    }
}
