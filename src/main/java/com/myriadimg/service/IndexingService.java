package com.myriadimg.service;

import com.myriadimg.model.Asset;
import com.myriadimg.model.Tag;
import com.myriadimg.repository.ProjectDatabaseManager;
import com.myriadimg.util.FileUtils;
import com.myriadimg.util.ProjectLogger;
import javafx.concurrent.Task;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class IndexingService extends Task<Void> implements ThrottlableService {

    private final Path rootPath;
    private final ProjectDatabaseManager dbManager;
    
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "tiff", "img",
            "heic", "heif", "avif",
            "cr2", "nef", "arw", "dng", "orf", "rw2", "raf", "cr3"
    );
    
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "avi", "mkv", "webm", "m4v", "flv", "wmv");
    
    private static final Set<String> ICON_EXTENSIONS = Set.of("ico", "icns", "svg");

    private static final int BATCH_SIZE = 100;
    private static final int HASH_BUFFER_SIZE = 4096; // 4KB
    
    private volatile ThreadPoolExecutor executor;
    private Thread dbWriterThread;
    private BlockingQueue<Runnable> workQueue;
    
    // Lock for executor replacement/shutdown
    private final Object executorLock = new Object();

    public IndexingService(Path rootPath, ProjectDatabaseManager dbManager) {
        this.rootPath = rootPath;
        this.dbManager = dbManager;
        ServiceManager.getInstance().registerService(this);
    }

    @Override
    public String getStatus() {
        Path fileName = rootPath.getFileName();
        String projectName = (fileName != null) ? fileName.toString() : rootPath.toString();
        return I18nService.getInstance().get("tray.status.indexing") + " (" + projectName + ")";
    }
    
    @Override
    public String getServiceName() {
        return "tray.status.indexing";
    }

    @Override
    public String getProjectPath() {
        return rootPath.toString();
    }

    @Override
    protected Void call() throws Exception {
        ProjectLogger.logInfo(rootPath, "IndexingService", "Starting indexing scan on: " + rootPath);
        ServiceManager.getInstance().updateGlobalStatus();
        
        Map<String, String> existingAssets = new ConcurrentHashMap<>();
        try {
            existingAssets.putAll(dbManager.getAssetPathToHashMap());
        } catch (Exception e) {
            ProjectLogger.logError(rootPath, "IndexingService", "Failed to load existing assets from DB", e);
            throw e;
        }
        
        // Track found files to detect deletions
        Set<String> foundFiles = ConcurrentHashMap.newKeySet();
        
        updateMessage("Scanning files...");
        updateProgress(-1, 1);
        
        this.workQueue = new ArrayBlockingQueue<>(1000);
        
        int threadPoolSize = Math.max(1, Runtime.getRuntime().availableProcessors());

        synchronized (executorLock) {
            executor = new ThreadPoolExecutor(
                    threadPoolSize,
                    threadPoolSize,
                    0L, TimeUnit.MILLISECONDS,
                    workQueue,
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }

        BlockingQueue<Asset> assetQueue = new LinkedBlockingQueue<>(10000);
        AtomicInteger processedCount = new AtomicInteger(0);
        
        dbWriterThread = new Thread(() -> {
            List<Asset> buffer = new ArrayList<>();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Asset asset = assetQueue.poll(1, TimeUnit.SECONDS);
                    
                    if (asset == null) {
                        if (executor.isTerminated() && assetQueue.isEmpty()) {
                            break;
                        }
                        continue;
                    }
                    
                    buffer.add(asset);
                    if (buffer.size() >= BATCH_SIZE) {
                        flushBuffer(buffer, processedCount, existingAssets.size());
                        buffer.clear();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (!buffer.isEmpty()) {
                    flushBuffer(buffer, processedCount, existingAssets.size());
                }
            }
        });
        dbWriterThread.start();

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (isCancelled()) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path fileName = dir.getFileName();
                    if (fileName != null && fileName.toString().startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (isCancelled()) {
                        return FileVisitResult.TERMINATE;
                    }
                    
                    Path fileNamePath = file.getFileName();
                    if (fileNamePath == null) return FileVisitResult.CONTINUE;

                    if (fileNamePath.toString().startsWith("._")) {
                        return FileVisitResult.CONTINUE;
                    }

                    String fileName = fileNamePath.toString();
                    String extension = FileUtils.getExtension(fileName);
                    
                    if (IMAGE_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension) || ICON_EXTENSIONS.contains(extension)) {
                        String pathStr = rootPath.relativize(file).toString();
                        foundFiles.add(pathStr);
                        submitTask(() -> processFile(file, assetQueue, existingAssets));
                    }
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    ProjectLogger.logError(rootPath, "IndexingService", "Failed to visit file: " + file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            ProjectLogger.logError(rootPath, "IndexingService", "Error walking file tree", e);
        }
        
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.HOURS)) {
                executor.shutdownNow();
                ProjectLogger.logError(rootPath, "IndexingService", "Executor timed out, forcing shutdown", null);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            ProjectLogger.logError(rootPath, "IndexingService", "Main thread interrupted during await", e);
        }
        
        try {
            dbWriterThread.join();
        } catch (InterruptedException e) {
            dbWriterThread.interrupt();
        }
        
        // Clean up orphaned assets (files in DB but not found on disk)
        updateMessage("Cleaning up deleted files...");
        try {
            dbManager.removeAssetsNotInList(foundFiles);
        } catch (Exception e) {
            ProjectLogger.logError(rootPath, "IndexingService", "Failed to clean up orphaned assets", e);
        }

        String completionMsg = "Indexing complete. Total new files indexed: " + processedCount.get();
        ProjectLogger.logInfo(rootPath, "IndexingService", completionMsg);
        updateMessage(completionMsg);
        updateProgress(1, 1);
        
        return null;
    }
    
    private void submitTask(Runnable task) {
        synchronized (executorLock) {
            if (executor != null && !executor.isShutdown()) {
                executor.submit(task);
            }
        }
    }

    @Override
    protected void succeeded() {
        super.succeeded();
        shutdownAndUnregister();
    }

    @Override
    protected void cancelled() {
        super.cancelled();
        shutdownAndUnregister();
    }

    @Override
    protected void failed() {
        super.failed();
        shutdownAndUnregister();
    }
    
    public void stopService() {
        if (isRunning()) {
            cancel();
        } else {
            shutdownAndUnregister();
        }
    }
    
    private void shutdownAndUnregister() {
        synchronized (executorLock) {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdownNow();
            }
        }
        if (dbWriterThread != null && dbWriterThread.isAlive()) {
            dbWriterThread.interrupt();
        }
        ServiceManager.getInstance().unregisterService(this);
        ServiceManager.getInstance().updateGlobalStatus();
    }
    
    private void flushBuffer(List<Asset> buffer, AtomicInteger processedCount, int existingCount) {
        try {
            synchronized (dbManager) {
                dbManager.batchInsertAssets(buffer);
            }
            int count = processedCount.addAndGet(buffer.size());
            updateMessage("Indexed " + (existingCount + count) + " files...");
        } catch (Exception e) {
            ProjectLogger.logError(rootPath, "IndexingService", "Failed to flush batch to DB", e);
        }
    }

    private void processFile(Path file, BlockingQueue<Asset> assetQueue, Map<String, String> existingAssets) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long size = attrs.size();
            
            String hash = calculatePartialHash(file, size);
            String pathStr = rootPath.relativize(file).toString();

            // Check if file is already indexed with same hash
            if (existingAssets.containsKey(pathStr) && existingAssets.get(pathStr).equals(hash)) {
                return; // Skip if path and hash match
            }

            // Always extract metadata to check for updates
            // Pass rootPath to MetadataService for correct logging context
            LocalDateTime creationDate = MetadataService.getInstance().extractDate(file.toFile(), rootPath);
            String location = MetadataService.getInstance().extractLocation(file.toFile(), rootPath);

            Asset.AssetType type = Asset.AssetType.UNKNOWN;
            Path fileNamePath = file.getFileName();
            if (fileNamePath == null) return;

            String fileName = fileNamePath.toString();
            String extension = FileUtils.getExtension(fileName);
            
            if (ICON_EXTENSIONS.contains(extension)) {
                type = Asset.AssetType.ICON;
            } else if (VIDEO_EXTENSIONS.contains(extension)) {
                type = Asset.AssetType.VIDEO;
            } else if (IMAGE_EXTENSIONS.contains(extension)) {
                // Check if it's a disguised icon (small PNG/GIF with transparency)
                if (isDisguisedIcon(file, extension, size)) {
                    type = Asset.AssetType.ICON;
                } else {
                    type = Asset.AssetType.IMAGE;
                }
            }

            Asset asset = new Asset(pathStr, hash, size, creationDate, type);
            
            // Add location tag if present
            if (type != Asset.AssetType.ICON && location != null && !location.isEmpty() && !"Unknown".equals(location)) {
                Tag locationTag = new Tag(location.toLowerCase(), location, Tag.TagType.LOCATION);
                asset.addTag(locationTag);
            }

            assetQueue.put(asset);
            existingAssets.put(pathStr, hash);

        } catch (Exception e) {
            if (e instanceof ClosedByInterruptException || e instanceof InterruptedException) {
                 // Expected during shutdown
            } else {
                ProjectLogger.logError(rootPath, "IndexingService", "Error processing file " + file, e);
            }
        }
    }
    
    /**
     * Heuristic to detect if an image is likely an icon/logo/system asset.
     * Criteria:
     *   Very small file size (< 20KB).
     *   Small dimensions (<= 256x256).
     *   Contains transparency (Photos usually don't).
     *   Weird aspect ratio (Banners, strips) or square.
     */
    private boolean isDisguisedIcon(Path file, String extension, long size) {
        // Check file size (< 20KB isn't a picture size but an icon one)
        if (size < 20 * 1024) {
             return true;
        }

        try {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) return false;

            int width = img.getWidth();
            int height = img.getHeight();
            
            // Check Dimensions (Small images are likely icons)
            if (width <= 256 && height <= 256) {
                return true;
            }

            // Check Aspect Ratio
            // Standard photos are usually between 1:2 and 2:1.
            double ratio = (double) width / height;
            if (ratio > 3.0 || ratio < 0.33 || ratio == 1) {
                return true;
            }

            // Check Transparency
            // Even if it's large (HD), if it has transparency, it's likely an asset/logo, not a photo.
            if (calculateTransparencyPercentage(img) > 1.0) { // > 1% transparency
                return true;
            }
            
        } catch (Exception e) {
            // Ignore read errors
        } catch (OutOfMemoryError e) {
            ProjectLogger.logError(rootPath, "IndexingService", "OOM checking icon: " + file, null);
        }
        
        return false;
    }

    private double calculateTransparencyPercentage(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return 0.0;
        }

        int width = img.getWidth();
        int height = img.getHeight();
        long totalPixels = 0;
        long transparentPixels = 0;
        
        // Optimization: Sample pixels for large images
        int step = 1;
        if (width * height > 1_000_000) {
            step = 10; // Check 1 in 100 pixels
        }

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int pixel = img.getRGB(x, y);
                int alpha = (pixel >> 24) & 0xFF;
                if (alpha < 250) {
                    transparentPixels++;
                }
                totalPixels++;
            }
        }

        if (totalPixels == 0) return 0.0;
        return (double) transparentPixels / totalPixels * 100.0;
    }

    private String calculatePartialHash(Path file, long fileSize) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(HASH_BUFFER_SIZE);
            channel.read(buffer);
            buffer.flip();
            digest.update(buffer);
            String sizeStr = String.valueOf(fileSize);
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
