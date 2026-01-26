package com.myriadimg.ui;

import com.myriadimg.service.ServiceManager;
import com.myriadimg.util.I18nService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.ResourceBundle;

/**
 * Manages the application's system tray icon and a custom JavaFX popup menu.
 * This provides a modern, styled interface for background tasks.
 */
public class TrayManager {

    private static TrayManager instance;
    private SystemTray tray;
    private TrayIcon trayIcon;
    private Stage primaryStage;
    
    // Custom JavaFX Popup components
    private Stage trayPopupStage;
    private TrayPopupController popupController;

    private TrayManager() {}

    public static synchronized TrayManager getInstance() {
        if (instance == null) {
            instance = new TrayManager();
        }
        return instance;
    }

    /**
     * Initializes the system tray icon.
     * @param stage The primary JavaFX stage of the application.
     */
    public void init(Stage stage) {
        this.primaryStage = stage;
        
        // Initialize the popup stage on the JavaFX thread
        Platform.runLater(this::initPopupStage);

        // Initialize AWT Tray on the EDT
        EventQueue.invokeLater(() -> {
            if (!SystemTray.isSupported()) {
                System.err.println("System tray not supported on this platform!");
                return;
            }

            tray = SystemTray.getSystemTray();

            try {
                Image image = loadTrayIcon();
                // Note: No AWT PopupMenu is attached. We handle clicks manually.
                trayIcon = new TrayIcon(image, "MyriadImg");
                trayIcon.setImageAutoSize(true);

                // Add mouse listener for left click to show custom popup
                trayIcon.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        // Handle Left Click (BUTTON1)
                        if (e.getButton() == MouseEvent.BUTTON1) {
                            Platform.runLater(() -> toggleTrayPopup(e.getX(), e.getY()));
                        }
                    }
                });

                tray.add(trayIcon);
                setupStatusListener();

            } catch (AWTException | IOException e) {
                System.err.println("Failed to initialize system tray icon.");
                e.printStackTrace();
            }
        });
    }

    private void initPopupStage() {
        try {
            // Create a ResourceBundle adapter for the JSON-based I18nService
            ResourceBundle bundle = new ResourceBundle() {
                @Override
                protected Object handleGetObject(String key) {
                    return I18nService.getInstance().get(key);
                }

                @Override
                public Enumeration<String> getKeys() {
                    return Collections.emptyEnumeration();
                }
                
                @Override
                public boolean containsKey(String key) {
                    return true;
                }
            };

            FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/com/myriadimg/ui/tray_popup_view.fxml"), bundle);
            
            Parent root = fxmlLoader.load();
            popupController = fxmlLoader.getController();

            trayPopupStage = new Stage();
            trayPopupStage.initOwner(primaryStage);
            trayPopupStage.initStyle(StageStyle.TRANSPARENT); // Transparent for rounded corners/shadows
            trayPopupStage.setAlwaysOnTop(true);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT); // Important for CSS rounded corners
            trayPopupStage.setScene(scene);

            // Pass stage references to controller
            popupController.setStages(trayPopupStage, primaryStage);

        } catch (IOException e) {
            System.err.println("Failed to load tray popup FXML: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void toggleTrayPopup(double mouseX, double mouseY) {
        if (trayPopupStage == null) return;

        if (trayPopupStage.isShowing()) {
            trayPopupStage.hide();
        } else {
            popupController.refresh();
            
            // Smart Positioning Logic
            Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
            double popupWidth = 300; // Fixed width from FXML
            double margin = 10;
            
            // Initial show off-screen to calculate height if needed, or just use a safe default
            // We'll adjust position in runLater after height is known.
            
            // Determine X position
            double x;
            if (mouseX > screenBounds.getMinX() + screenBounds.getWidth() / 2) {
                // Mouse is on the right side -> Align popup to the left of mouse or right edge
                // Let's align right edge of popup to mouse X, but clamp to screen
                x = Math.min(mouseX + popupWidth/2, screenBounds.getMaxX()) - popupWidth - margin;
            } else {
                // Mouse is on the left side -> Align left edge of popup to mouse X
                x = Math.max(mouseX - popupWidth/2, screenBounds.getMinX()) + margin;
            }
            
            // Determine Y position (preliminary)
            double y;
            boolean isBottom = mouseY > screenBounds.getMinY() + screenBounds.getHeight() / 2;
            
            if (isBottom) {
                 // Mouse is at bottom -> Popup should be above
                 y = mouseY - 200; // Placeholder
            } else {
                 // Mouse is at top -> Popup should be below
                 y = mouseY + margin;
            }
            
            trayPopupStage.setX(x);
            trayPopupStage.setY(y);
            
            trayPopupStage.show();
            
            // Final adjustment once height is known
            Platform.runLater(() -> {
                double realHeight = trayPopupStage.getHeight();
                double finalY;
                
                if (isBottom) {
                    // Align bottom of popup above the mouse (or taskbar)
                    // We use screenBounds.getMaxY() as a hard limit for taskbar
                    // But we prefer using mouse position as anchor
                    finalY = Math.min(mouseY, screenBounds.getMaxY()) - realHeight - margin;
                    
                    // Ensure we don't go off top
                    if (finalY < screenBounds.getMinY()) {
                        finalY = screenBounds.getMinY() + margin;
                    }
                } else {
                    // Align top of popup below the mouse
                    finalY = Math.max(mouseY, screenBounds.getMinY()) + margin;
                    
                    // Ensure we don't go off bottom
                    if (finalY + realHeight > screenBounds.getMaxY()) {
                        finalY = screenBounds.getMaxY() - realHeight - margin;
                    }
                }
                
                trayPopupStage.setY(finalY);
                trayPopupStage.toFront();
                trayPopupStage.requestFocus();
            });
        }
    }

    private void setupStatusListener() {
        ServiceManager.getInstance().globalStatusProperty().addListener((obs, oldVal, newVal) -> {
            String statusText = (newVal == null || newVal.isEmpty()) ? "Idle" : newVal;
            final String tooltip = "MyriadImg - " + statusText;
            EventQueue.invokeLater(() -> {
                if (trayIcon != null) {
                    trayIcon.setToolTip(tooltip);
                }
            });
        });
    }

    public void showNotification(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            EventQueue.invokeLater(() -> trayIcon.displayMessage(title, message, type));
        }
    }

    private Image loadTrayIcon() throws IOException {
        URL iconUrl = getClass().getResource("/icons/app_icon_16.png");
        if (iconUrl != null) {
            return ImageIO.read(iconUrl);
        } else {
            return createDefaultIcon();
        }
    }

    private Image createDefaultIcon() {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(30, 144, 255));
        g.fillOval(0, 0, 16, 16);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString("M", 3, 13);
        g.dispose();
        return image;
    }

    public void exitApplication() {
        ServiceManager.getInstance().shutdown();
        EventQueue.invokeLater(() -> {
            if (tray != null && trayIcon != null) {
                tray.remove(trayIcon);
            }
        });
        Platform.exit();
        System.exit(0);
    }
}
