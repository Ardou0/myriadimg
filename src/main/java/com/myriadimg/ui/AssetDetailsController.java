package com.myriadimg.ui;

import com.myriadimg.model.Asset;
import com.myriadimg.model.Project;
import com.myriadimg.service.I18nService;
import com.myriadimg.service.ThumbnailService;
import com.myriadimg.util.ProjectLogger;
import com.myriadimg.util.ToastUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * Controller responsible for the right-hand side panel displaying asset details.
 * Extracted from ProjectViewController to adhere to SRP.
 */
public class AssetDetailsController {

    @FXML private VBox detailsContainer;
    @FXML private Label detailsTitleLabel;
    @FXML private Label noSelectionLabel;
    @FXML private VBox selectionDetailsBox;
    @FXML private ImageView selectedImageView;
    @FXML private Label selectedImageName;
    @FXML private Label selectedImageDate;
    @FXML private Label pathLabel;
    @FXML private Label selectedImagePath;
    @FXML private Label tagsLabel;
    @FXML private FlowPane selectedImageTags;
    @FXML private Button openButton;
    @FXML private Button deleteButton;
    @FXML private Label summaryLabel;
    @FXML private Label summaryValue;

    private Project currentProject;
    private Asset selectedAsset;
    private ThumbnailService thumbnailService;
    private final I18nService i18n = I18nService.getInstance();
    private VBox toastContainer; // Reference to main view's toast container

    public void initialize() {
        // Initial setup if needed
        updateUIWithI18n();
    }

    public void setProject(Project project) {
        this.currentProject = project;
    }

    public void setThumbnailService(ThumbnailService thumbnailService) {
        this.thumbnailService = thumbnailService;
    }

    public void setToastContainer(VBox toastContainer) {
        this.toastContainer = toastContainer;
    }

    public void updateUIWithI18n() {
        if (detailsTitleLabel != null) detailsTitleLabel.setText(i18n.get("project.sidebar.details"));
        if (openButton != null) openButton.setText(i18n.get("project.sidebar.btn_open"));
        if (deleteButton != null) deleteButton.setText(i18n.get("project.sidebar.btn_delete"));
        if (pathLabel != null) pathLabel.setText(i18n.get("project.sidebar.path_label"));
        if (tagsLabel != null) tagsLabel.setText(i18n.get("project.sidebar.tags_label"));
        if (noSelectionLabel != null) noSelectionLabel.setText(i18n.get("project.sidebar.no_selection"));
        if (summaryLabel != null) summaryLabel.setText(i18n.get("project.sidebar.summary_label"));
    }

    public void updateSummary(int images, int videos) {
        if (summaryValue != null) {
            summaryValue.setText(i18n.get("project.sidebar.summary_value", images, videos));
        }
    }

    public void showAssetDetails(Asset asset) {
        this.selectedAsset = asset;
        
        if (noSelectionLabel != null) {
            noSelectionLabel.setVisible(false);
            noSelectionLabel.setManaged(false);
        }
        
        if (selectionDetailsBox != null) {
            selectionDetailsBox.setVisible(true);
            selectionDetailsBox.setManaged(true);
        }

        if (selectedImageName != null) selectedImageName.setText(new File(asset.getPath()).getName());
        if (selectedImageDate != null) selectedImageDate.setText(asset.getCreationDate() != null ? asset.getCreationDate().toString() : "Unknown date");
        
        // Update path label
        if (currentProject != null && selectedImagePath != null) {
            File file = new File(currentProject.getPath(), asset.getPath());
            selectedImagePath.setText(file.getAbsolutePath());
            
            // Load real image for preview
            loadPreviewImage(file, asset);
        }

        if (selectedImageTags != null) {
            selectedImageTags.getChildren().clear();
            // TODO: Load tags from DB for this asset
        }
    }

    private void loadPreviewImage(File file, Asset asset) {
        if (selectedImageView == null) return;
        
        try {
            if (file.exists()) {
                String lower = asset.getPath().toLowerCase();
                boolean isHeic = lower.endsWith(".heic") || lower.endsWith(".heif");

                if (asset.getType() == Asset.AssetType.IMAGE && !isHeic) {
                    Image image = new Image(file.toURI().toString(), 220, 200, true, true);
                    selectedImageView.setImage(image);
                } else {
                    // For HEIC or VIDEO, use the thumbnail
                    if (thumbnailService != null) {
                        CompletableFuture.supplyAsync(() -> thumbnailService.loadThumbnail(asset.getPath()))
                                .thenAccept(image -> Platform.runLater(() -> {
                                    if (selectedImageView != null) selectedImageView.setImage(image);
                                }));
                    }
                }
                
                // Show warning for HEIC files
                if (isHeic) {
                    showHeicWarning();
                } else {
                    hideHeicWarning();
                }
            } else {
                selectedImageView.setImage(null);
            }
        } catch (Exception e) {
            ProjectLogger.logError(currentProject != null ? Paths.get(currentProject.getPath()) : null, 
                    "AssetDetailsController", "Error loading preview image", e);
            selectedImageView.setImage(null);
        }
    }

    private void showHeicWarning() {
        if (selectionDetailsBox == null) return;
        
        // Check if warning already exists
        if (selectionDetailsBox.getChildren().stream().anyMatch(n -> n.getId() != null && n.getId().equals("heicWarningBox"))) {
            return;
        }
        
        VBox warningBox = new VBox(5);
        warningBox.setId("heicWarningBox");
        warningBox.getStyleClass().add("warning-box"); // Use CSS class instead of inline styles
        warningBox.setPadding(new Insets(10));
        
        Label title = new Label("⚠ " + i18n.get("project.sidebar.warning_heic_title"));
        title.getStyleClass().add("warning-title");
        
        Label desc = new Label(i18n.get("project.sidebar.warning_heic_desc"));
        desc.getStyleClass().add("warning-desc");
        desc.setWrapText(true);
        
        warningBox.getChildren().addAll(title, desc);
        
        // Add after image view (index 1 usually, but safer to find index)
        int index = selectionDetailsBox.getChildren().indexOf(selectedImageView);
        if (index != -1 && index + 1 < selectionDetailsBox.getChildren().size()) {
            selectionDetailsBox.getChildren().add(index + 1, warningBox);
        } else {
            selectionDetailsBox.getChildren().add(warningBox);
        }
    }
    
    private void hideHeicWarning() {
        if (selectionDetailsBox != null) {
            selectionDetailsBox.getChildren().removeIf(n -> n.getId() != null && n.getId().equals("heicWarningBox"));
        }
    }

    @FXML
    private void onOpenClicked() {
        if (selectedAsset == null || currentProject == null) return;

        File file = new File(currentProject.getPath(), selectedAsset.getPath());
        if (file.exists()) {
            try {
                Desktop.getDesktop().open(file);
            } catch (IOException e) {
                ProjectLogger.logError(Paths.get(currentProject.getPath()), "AssetDetailsController", "Could not open file", e);
                ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_error"), "Could not open file: " + e.getMessage(), true);
            }
        } else {
            ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_error"), "File not found on disk", true);
        }
    }

    @FXML
    private void onDeleteClicked() {
        // TODO: Implement delete logic
        ToastUtil.show(toastContainer, "Info", "Delete not implemented yet", false);
    }

    @FXML
    private void onPathClicked() {
        if (selectedAsset == null || currentProject == null) return;
        
        File file = new File(currentProject.getPath(), selectedAsset.getPath());
        String absolutePath = file.getAbsolutePath();
        
        ClipboardContent content = new ClipboardContent();
        content.putString(absolutePath);
        Clipboard.getSystemClipboard().setContent(content);
        
        ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_success"), i18n.get("dashboard.toast.path_copied"), false);
    }
    
    public void clearSelection() {
        this.selectedAsset = null;
        if (noSelectionLabel != null) {
            noSelectionLabel.setVisible(true);
            noSelectionLabel.setManaged(true);
        }
        if (selectionDetailsBox != null) {
            selectionDetailsBox.setVisible(false);
            selectionDetailsBox.setManaged(false);
        }
    }
}
