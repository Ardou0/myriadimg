package com.myriadimg.service;

import com.myriadimg.model.Asset;
import com.myriadimg.repository.ProjectDatabaseManager;
import com.myriadimg.util.ProjectLogger;
import javafx.concurrent.Task;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
        
        Set<String> existingHashes = ConcurrentHashMap.newKeySet();
        try {
            existingHashes.addAll(dbManager.getAllHashes());
        } catch (Exception e) {
            ProjectLogger.logError(rootPath, "IndexingService", "Failed to load existing hashes from DB", e);
            throw e;
        }
        
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
                        flushBuffer(buffer, processedCount, existingHashes.size());
                        buffer.clear();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (!buffer.isEmpty()) {
                    flushBuffer(buffer, processedCount, existingHashes.size());
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

                    String fileName = fileNamePath.toString().toLowerCase();
                    String extension = getExtension(fileName);
                    
                    if (IMAGE_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension)) {
                        submitTask(() -> processFile(file, assetQueue, existingHashes));
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

        String completionMsg = "Indexing complete. Total new files indexed: " + processedCount.get();
        ProjectLogger.logInfo(rootPath, "IndexingService", completionMsg);
        updateMessage(completionMsg);
        updateProgress(1, 1);
        
        ServiceManager.getInstance().unregisterService(this);
        ServiceManager.getInstance().updateGlobalStatus();
        
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
    protected void cancelled() {
        super.cancelled();
        stopService();
    }
    
    public void stopService() {
        if (isRunning()) {
            cancel();
        }
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

    @Override
    protected void failed() {
        super.failed();
        stopService();
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

    private void processFile(Path file, BlockingQueue<Asset> assetQueue, Set<String> existingHashes) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long size = attrs.size();
            
            String hash = calculatePartialHash(file, size);

            if (!existingHashes.contains(hash)) {
                LocalDateTime creationDate = LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault());
                String pathStr = rootPath.relativize(file).toString();
                
                Asset.AssetType type = Asset.AssetType.UNKNOWN;
                Path fileNamePath = file.getFileName();
                if (fileNamePath == null) return;

                String fileName = fileNamePath.toString().toLowerCase();
                String extension = getExtension(fileName);
                
                if (IMAGE_EXTENSIONS.contains(extension)) {
                    type = Asset.AssetType.IMAGE;
                } else if (VIDEO_EXTENSIONS.contains(extension)) {
                    type = Asset.AssetType.VIDEO;
                }

                Asset asset = new Asset(pathStr, hash, size, creationDate, type);
                assetQueue.put(asset);
                existingHashes.add(hash);
            }
        } catch (Exception e) {
            if (e instanceof ClosedByInterruptException || e instanceof InterruptedException) {
                 // Expected during shutdown
            } else {
                ProjectLogger.logError(rootPath, "IndexingService", "Error processing file " + file, e);
            }
        }
    }

    private String getExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            return fileName.substring(i + 1);
        }
        return "";
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
