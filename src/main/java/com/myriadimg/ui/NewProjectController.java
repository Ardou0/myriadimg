package com.myriadimg.ui;

import com.myriadimg.model.Project;
import com.myriadimg.repository.ProjectRepository;
import com.myriadimg.service.I18nService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;

public class NewProjectController {

    @FXML private Label titleLabel;
    @FXML private Label nameLabel;
    @FXML private TextField nameField;
    @FXML private Label pathLabel;
    @FXML private TextField pathField;
    @FXML private Button cancelButton;
    @FXML private Button createButton;

    private File selectedDirectory;
    private final ProjectRepository projectRepository = new ProjectRepository();
    private final I18nService i18n = I18nService.getInstance();

    @FXML
    public void initialize() {
        updateTexts();
    }

    private void updateTexts() {
        if (titleLabel != null) titleLabel.setText(i18n.get("new_project.title"));
        if (nameLabel != null) nameLabel.setText(i18n.get("new_project.label_name"));
        if (nameField != null) nameField.setPromptText(i18n.get("new_project.placeholder_name"));
        if (pathLabel != null) pathLabel.setText(i18n.get("new_project.label_path"));
        if (pathField != null) pathField.setPromptText(i18n.get("new_project.placeholder_path"));
        if (cancelButton != null) cancelButton.setText(i18n.get("new_project.btn_cancel"));
        if (createButton != null) createButton.setText(i18n.get("new_project.btn_create"));
    }

    @FXML
    private void onBrowse() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle(i18n.get("new_project.label_path"));
        selectedDirectory = directoryChooser.showDialog(pathField.getScene().getWindow());

        if (selectedDirectory != null) {
            pathField.setText(selectedDirectory.getAbsolutePath());
            // Auto-fill name if empty
            if (nameField.getText().isEmpty()) {
                nameField.setText(selectedDirectory.getName());
            }
        }
    }

    @FXML
    private void onCancel() {
        goBackToDashboard();
    }

    @FXML
    private void onCreate() {
        String name = nameField.getText().trim();
        String path = pathField.getText().trim();

        if (name.isEmpty() || path.isEmpty()) {
            showAlert(i18n.get("dashboard.toast.title_error"), i18n.get("new_project.error_empty"));
            return;
        }

        Project newProject = new Project(name, path);

        Task<Void> saveTask = new Task<>() {
            @Override
            protected Void call() {
                projectRepository.save(newProject);
                return null;
            }
        };

        saveTask.setOnSucceeded(e -> openProjectView(newProject));
        saveTask.setOnFailed(e -> {
            e.getSource().getException().printStackTrace();
            showAlert(i18n.get("dashboard.toast.title_error"), i18n.get("new_project.error_create"));
        });

        new Thread(saveTask).start();
    }

    private void goBackToDashboard() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/myriadimg/ui/dashboard.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) nameField.getScene().getWindow();
            stage.getScene().setRoot(root);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void openProjectView(Project project) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/myriadimg/ui/project_view.fxml"));
            Parent root = loader.load();
            
            ProjectViewController controller = loader.getController();
            controller.setProject(project);

            Stage stage = (Stage) nameField.getScene().getWindow();
            stage.getScene().setRoot(root);
        } catch (IOException e) {
            e.printStackTrace();
            showAlert(i18n.get("dashboard.toast.title_error"), "Impossible d'ouvrir la vue du projet.");
        }
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
