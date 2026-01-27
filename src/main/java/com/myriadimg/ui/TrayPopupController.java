package com.myriadimg.ui;

import com.myriadimg.service.ServiceManager;
import com.myriadimg.service.ThrottlableService;
import com.myriadimg.service.I18nService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

/**
 * Controller for the custom JavaFX tray popup window.
 * It displays the status of ongoing background tasks with progress bars.
 */
public class TrayPopupController {

    @FXML
    private VBox tasksContainer;
    
    @FXML
    private Button showButton;
    
    @FXML
    private Button exitButton;

    private Stage popupStage;
    private Stage primaryStage;

    /**
     * Injects the stages from the TrayManager.
     * This is called after the FXML is loaded.
     * It also sets up a listener to hide the popup when it loses focus.
     */
    public void setStages(Stage popupStage, Stage primaryStage) {
        this.popupStage = popupStage;
        this.primaryStage = primaryStage;

        // Add a listener to hide the popup when it loses focus.
        this.popupStage.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                javafx.application.Platform.runLater(() -> {
                    if (this.popupStage.isShowing()) {
                        this.popupStage.hide();
                    }
                });
            }
        });
    }

    @FXML
    private void handleShow() {
        if (primaryStage != null) {
            primaryStage.show();
            primaryStage.toFront();
            primaryStage.requestFocus();
        }
        popupStage.hide();
    }

    @FXML
    private void handleExit() {
        TrayManager.getInstance().exitApplication();
    }

    /**
     * Refreshes the content of the popup with the latest status of active services.
     * This is called just before the popup is shown.
     * It dynamically builds UI components to represent each active task.
     */
    public void refresh() {
        // Update static texts (buttons) in case language changed
        if (showButton != null) {
            showButton.setText(I18nService.getInstance().get("tray.action.show"));
        }
        if (exitButton != null) {
            exitButton.setText(I18nService.getInstance().get("tray.action.exit"));
        }

        tasksContainer.getChildren().clear();
        
        List<ThrottlableService> activeServices = ServiceManager.getInstance().getActiveServices();

        if (activeServices.isEmpty()) {
            Label idleLabel = new Label(I18nService.getInstance().get("tray.popup.no_tasks"));
            idleLabel.getStyleClass().add("empty-state-label");
            idleLabel.setMaxWidth(Double.MAX_VALUE);
            tasksContainer.getChildren().add(idleLabel);
        } else {
            for (ThrottlableService service : activeServices) {
                VBox taskItem = new VBox(4); // Spacing between text and progress bar
                taskItem.getStyleClass().add("task-item");

                // Header: Name + Status Dot
                HBox header = new HBox(5);
                header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                
                Label statusDot = new Label("●");
                statusDot.getStyleClass().add("status-dot");
                
                String serviceNameKey = service.getServiceName();
                String serviceNameText = I18nService.getInstance().get(serviceNameKey);
                
                // Add project name if available
                String projectPath = service.getProjectPath();
                if (projectPath != null && !projectPath.isEmpty()) {
                    String projectName = new File(projectPath).getName();
                    serviceNameText += " (" + projectName + ")";
                }
                
                Label serviceName = new Label(serviceNameText);
                serviceName.getStyleClass().add("task-name");
                
                header.getChildren().addAll(statusDot, serviceName);

                // Status Message (below header)
                Label messageLabel = new Label();
                messageLabel.textProperty().bind(service.messageProperty());
                messageLabel.getStyleClass().add("task-status");
                
                // Progress Bar
                ProgressBar progressBar = new ProgressBar();
                progressBar.setMaxWidth(Double.MAX_VALUE);
                progressBar.progressProperty().bind(service.progressProperty());
                progressBar.getStyleClass().add("modern-progress-bar"); // Use the same style class as in project view

                taskItem.getChildren().addAll(header, messageLabel, progressBar);
                tasksContainer.getChildren().add(taskItem);
            }
        }
    }
}
