package com.myriadimg;

import com.myriadimg.repository.DatabaseManager;
import com.myriadimg.ui.TrayManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class Launcher extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        // Initialize global database
        try {
            DatabaseManager.initialize();
        } catch (Exception e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Prevent implicit exit when last window closes (we want to stay in tray)
        Platform.setImplicitExit(false);

        FXMLLoader fxmlLoader = new FXMLLoader(Launcher.class.getResource("/com/myriadimg/ui/dashboard.fxml"));
        Parent root = fxmlLoader.load();
        
        // Set minimum size constraints
        stage.setMinWidth(1280);
        stage.setMinHeight(768);
        
        Scene scene = new Scene(root, 1280, 800); // Default size larger
        stage.setTitle("MyriadImg");
        stage.setScene(scene);
        
        // Initialize Tray
        TrayManager.getInstance().init(stage);
        
        // Handle close request to minimize to tray instead of exit
        stage.setOnCloseRequest(e -> {
            // e.consume(); // Uncomment if you want to force minimize to tray on X
            // stage.hide();
            // For now, let's keep standard behavior but ensure background threads stop if we actually exit
            // Or if we want "Minimize to Tray" behavior:
             e.consume();
             stage.hide();
             TrayManager.getInstance().showNotification("MyriadImg", "Application minimized to tray", java.awt.TrayIcon.MessageType.INFO);
        });

        stage.show();
    }

    public static void main(String[] args) {
        // Force loading of native libraries from classpath if needed
        // This is sometimes required for JNI libraries packaged in JARs
        System.setProperty("java.library.path", System.getProperty("java.library.path"));
        launch();
    }
}
