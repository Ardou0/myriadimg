package com.myriadimg.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utilitaire de logging thread-safe qui écrit dans le dossier .MyriadImg du projet.
 * Synchronisé pour éviter la corruption de fichier lors d'accès concurrents par les workers.
 */
public class ProjectLogger {

    private static final Object LOCK = new Object();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LOG_FILE_NAME = "application.log";

    /**
     * Log une erreur dans le fichier de log du projet spécifié.
     *
     * @param projectRoot La racine du projet (où se trouve le dossier .MyriadImg)
     * @param context     Le nom de la classe ou du service (ex: "IndexingService")
     * @param message     Le message d'erreur
     * @param error       L'exception (peut être null)
     */
    public static void logError(Path projectRoot, String context, String message, Throwable error) {
        writeLog(projectRoot, "ERROR", context, message, error);
    }

    /**
     * Log une information dans le fichier de log du projet spécifié.
     */
    public static void logInfo(Path projectRoot, String context, String message) {
        writeLog(projectRoot, "INFO", context, message, null);
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

        // Synchronisation critique pour éviter que plusieurs threads n'écrivent en même temps
        // et ne corrompent le fichier de log.
        synchronized (LOCK) {
            try {
                Path logDir = projectRoot.resolve(".MyriadImg");
                if (!Files.exists(logDir)) {
                    Files.createDirectories(logDir);
                }
                
                Path logFile = logDir.resolve(LOG_FILE_NAME);
                
                Files.writeString(
                    logFile, 
                    sb.toString(), 
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.APPEND, 
                    StandardOpenOption.WRITE
                );
                
            } catch (IOException e) {
                // Si on ne peut pas logger dans le fichier, on fallback sur la console
                System.err.println("CRITICAL: Impossible d'écrire dans le log projet.");
                e.printStackTrace();
                if (error != null) error.printStackTrace();
            }
        }
    }
}
