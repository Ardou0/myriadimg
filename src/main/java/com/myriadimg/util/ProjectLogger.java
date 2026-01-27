package com.myriadimg.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe logging utility that writes to the .MyriadImg folder of the project.
 * Synchronized to prevent file corruption during concurrent access by workers.
 * Includes features for log rotation and smart error aggregation.
 */
public class ProjectLogger {

    private static final Object LOCK = new Object();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LOG_FILE_NAME = "application.log";
    private static final String OLD_LOG_FILE_NAME = "application.log.old";
    private static final long MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB

    // Cache to avoid calling Files.createDirectories excessively
    private static final Set<Path> INITIALIZED_DIRS = ConcurrentHashMap.newKeySet();

    // Storage for aggregated errors: ProjectPath -> (ErrorSignature -> Count)
    private static final Map<Path, Map<String, AtomicInteger>> AGGREGATED_ERRORS = new ConcurrentHashMap<>();

    /**
     * Logs an error to the specified project's log file.
     *
     * @param projectRoot The project root (containing .MyriadImg folder)
     * @param context     The class or service name (e.g., "IndexingService")
     * @param message     The error message
     * @param error       The exception (can be null)
     */
    public static void logError(Path projectRoot, String context, String message, Throwable error) {
        writeLog(projectRoot, "ERROR", context, message, error);
    }

    /**
     * Logs an informational message to the specified project's log file.
     *
     * @param projectRoot The project root
     * @param context     The context
     * @param message     The message
     */
    public static void logInfo(Path projectRoot, String context, String message) {
        writeLog(projectRoot, "INFO", context, message, null);
    }

    /**
     * Logs a recurring error. The first occurrence is logged immediately.
     * Subsequent identical errors (same context, message, and exception type) are aggregated
     * and can be flushed later using {@link #flush(Path)}.
     *
     * @param projectRoot The project root
     * @param context     The context
     * @param message     The message
     * @param error       The exception
     */
    public static void logRecurringError(Path projectRoot, String context, String message, Throwable error) {
        if (projectRoot == null) return;

        String signature = generateErrorSignature(context, message, error);
        
        AGGREGATED_ERRORS.putIfAbsent(projectRoot, new ConcurrentHashMap<>());
        Map<String, AtomicInteger> projectErrors = AGGREGATED_ERRORS.get(projectRoot);
        
        projectErrors.putIfAbsent(signature, new AtomicInteger(0));
        AtomicInteger counter = projectErrors.get(signature);

        if (counter.getAndIncrement() == 0) {
            // Log the first occurrence immediately
            writeLog(projectRoot, "ERROR", context, message, error);
        }
    }

    /**
     * Flushes aggregated errors to the log file.
     * Writes a summary of how many times each recurring error occurred since the last flush.
     *
     * @param projectRoot The project root
     */
    public static void flush(Path projectRoot) {
        if (projectRoot == null) return;

        Map<String, AtomicInteger> projectErrors = AGGREGATED_ERRORS.remove(projectRoot);
        if (projectErrors == null || projectErrors.isEmpty()) {
            return;
        }

        projectErrors.forEach((signature, count) -> {
            int total = count.get();
            if (total > 1) {
                // We already logged the first one, so we report the additional occurrences
                int additional = total - 1;
                writeLog(projectRoot, "SUMMARY", "ErrorAggregation", 
                        String.format("The following error occurred %d additional times: %s", additional, signature), null);
            }
        });
    }

    private static String generateErrorSignature(String context, String message, Throwable error) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(context).append("] ").append(message);
        if (error != null) {
            sb.append(" | ").append(error.getClass().getName());
            if (error.getMessage() != null) {
                sb.append(": ").append(error.getMessage());
            }
        }
        return sb.toString();
    }

    private static void writeLog(Path projectRoot, String level, String context, String message, Throwable error) {
        if (projectRoot == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(LocalDateTime.now().format(DATE_FORMAT)).append("] ");
        sb.append("[").append(level).append("] ");
        sb.append("[").append(context).append("] ");
        sb.append(message);

        if (error != null) {
            sb.append(System.lineSeparator());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            error.printStackTrace(pw);
            sb.append(sw.toString());
        }
        sb.append(System.lineSeparator());

        // Critical synchronization to prevent file corruption
        synchronized (LOCK) {
            try {
                Path logDir = projectRoot.resolve(".MyriadImg");
                
                // Optimization: Only check/create directory if not already done for this session
                if (INITIALIZED_DIRS.add(logDir)) {
                    if (!Files.exists(logDir)) {
                        Files.createDirectories(logDir);
                    }
                }
                
                Path logFile = logDir.resolve(LOG_FILE_NAME);

                // Log Rotation
                if (Files.exists(logFile) && Files.size(logFile) > MAX_LOG_SIZE_BYTES) {
                    Path oldFile = logDir.resolve(OLD_LOG_FILE_NAME);
                    Files.move(logFile, oldFile, StandardCopyOption.REPLACE_EXISTING);
                }
                
                Files.writeString(
                    logFile, 
                    sb.toString(), 
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.APPEND, 
                    StandardOpenOption.WRITE
                );
                
            } catch (IOException e) {
                // Fallback to console if file writing fails
                System.err.println("CRITICAL: Unable to write to project log.");
                e.printStackTrace();
                if (error != null) error.printStackTrace();
            }
        }
    }
}
