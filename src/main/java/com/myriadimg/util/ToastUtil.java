package com.myriadimg.util;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public class ToastUtil {

    /**
     * Displays a Toast notification in the specified container.
     *
     * @param parentContainer The container (VBox, StackPane, etc.) where the toast will be added.
     * @param title           The title of the toast.
     * @param details         The detailed message.
     * @param isError         If true, applies the error style (red).
     */
    public static void show(Pane parentContainer, String title, String details, boolean isError) {
        // Title (Always visible)
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("toast-title");

        // Details (Always visible now, but styled differently)
        Label detailsLabel = new Label(details);
        detailsLabel.getStyleClass().add("toast-details");
        detailsLabel.setWrapText(true);
        // CONSTRAINT: Keep max width to avoid taking up full screen
        detailsLabel.setMaxWidth(300);

        VBox content = new VBox(5, titleLabel, detailsLabel);
        content.setAlignment(Pos.CENTER_LEFT);

        HBox container = new HBox(10, content); // Added spacing
        container.getStyleClass().add("toast-container");
        if (isError) {
            container.getStyleClass().add("toast-error");
        }
        container.setAlignment(Pos.CENTER_LEFT);

        // Add close button
        Button closeButton = new Button("×");
        closeButton.getStyleClass().add("toast-close-button");
        closeButton.setOnAction(e -> {
            parentContainer.getChildren().remove(container);
        });

        container.getChildren().add(closeButton);
        HBox.setHgrow(content, Priority.ALWAYS);

        parentContainer.getChildren().add(container);

        // Animation: Slide In + Fade In
        FadeTransition fadeIn = new FadeTransition(Duration.millis(300), container);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        fadeIn.play();

        // Auto-hide logic
        PauseTransition delay = new PauseTransition(Duration.seconds(5)); // Duration

        Runnable hide = () -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(500), container);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(ev -> parentContainer.getChildren().remove(container));
            fadeOut.play();
        };

        delay.setOnFinished(e -> hide.run());
        delay.play();

        // Hover interactions to pause auto-hide
        container.setOnMouseEntered(e -> delay.stop());
        container.setOnMouseExited(e -> delay.playFromStart());
    }
}
