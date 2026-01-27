package com.myriadimg.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.myriadimg.model.Project;
import com.myriadimg.repository.ProjectRepository;
import com.myriadimg.service.I18nService;
import com.myriadimg.util.SettingsManager;
import com.myriadimg.util.ToastUtil;
import javafx.animation.FadeTransition;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class DashboardController {

    @FXML private StackPane rootStackPane;
    @FXML private FlowPane projectsContainer;
    @FXML private ScrollPane scrollPane;
    @FXML private Button addProjectButton;
    @FXML private VBox toastContainer;
    @FXML private Button infoButton;
    @FXML private Button helpButton;
    @FXML private Label dashboardTitleLabel;
    @FXML private MenuButton langButton;
    
    // First Run Overlay
    @FXML private VBox firstRunOverlay;
    @FXML private Label welcomeTitle;
    @FXML private Label welcomeSubtitle;
    @FXML private Label welcomeMessage;
    @FXML private Button startTutorialButton;

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final I18nService i18n = I18nService.getInstance();
    private final SettingsManager settings = SettingsManager.getInstance();

    private Project draggedProject;
    private VBox draggedCard;

    @FXML
    public void initialize() {
        updateUIWithI18n();
        loadProjects();
        scrollPane.setFitToWidth(true);
        
        checkFirstRun();
    }
    
    private void checkFirstRun() {
        if (settings.isFirstRun()) {
            firstRunOverlay.setVisible(true);
            welcomeTitle.setText(i18n.get("welcome.title"));
            welcomeSubtitle.setText(i18n.get("welcome.subtitle"));
            welcomeMessage.setText(i18n.get("welcome.message"));
            startTutorialButton.setText(i18n.get("welcome.btn_start"));
        }
    }
    
    @FXML
    private void onStartTutorial() {
        // Hide overlay
        FadeTransition ft = new FadeTransition(Duration.millis(300), firstRunOverlay);
        ft.setFromValue(1.0);
        ft.setToValue(0.0);
        ft.setOnFinished(e -> {
            firstRunOverlay.setVisible(false);
            // Open Help Modal
            onHelpClicked();
            // Update settings
            settings.setFirstRun(false);
        });
        ft.play();
    }

    private void updateUIWithI18n() {
        if (addProjectButton != null) addProjectButton.setText(i18n.get("dashboard.btn_new_project"));
        if (dashboardTitleLabel != null) dashboardTitleLabel.setText(i18n.get("dashboard.title"));
        if (langButton != null) langButton.setText(i18n.getCurrentLang().toUpperCase());
    }

    private void loadProjects() {
        Task<List<Project>> task = new Task<>() {
            @Override
            protected List<Project> call() {
                return projectRepository.findAll();
            }
        };

        task.setOnSucceeded(e -> updateProjectList(task.getValue()));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            ex.printStackTrace();
            ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_error"), i18n.get("dashboard.toast.error_load"), true);
        });

        new Thread(task).start();
    }

    private void updateProjectList(List<Project> projects) {
        projectsContainer.getChildren().clear();

        if (projects.isEmpty()) {
            Label emptyLabel = new Label(i18n.get("dashboard.empty_state"));
            emptyLabel.getStyleClass().add("empty-message");
            projectsContainer.setAlignment(Pos.CENTER);
            projectsContainer.getChildren().add(emptyLabel);
        } else {
            projectsContainer.setAlignment(Pos.TOP_LEFT);
            for (Project project : projects) {
                projectsContainer.getChildren().add(createProjectCard(project));
            }
        }
    }

    private VBox createProjectCard(Project project) {
        VBox card = new VBox(15);
        card.getStyleClass().add("project-card");
        card.setPrefSize(160, 160);
        card.setMinSize(160, 160);
        card.setMaxSize(160, 160);
        card.setUserData(project);

        boolean initialExists = new File(project.getPath()).exists();
        if (!initialExists) {
            card.getStyleClass().add("offline");
        }

        Tooltip tooltip = new Tooltip();
        tooltip.setText(i18n.get("dashboard.tooltip.path") + project.getPath() + "\n" +
                        i18n.get("dashboard.tooltip.last_opened") + project.getLastOpened().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        tooltip.getStyleClass().add("tooltip");
        tooltip.setShowDelay(Duration.millis(500));
        Tooltip.install(card, tooltip);

        Node icon = createFolderIcon(initialExists);

        Label nameLabel = new Label(project.getName());
        nameLabel.getStyleClass().add("project-name");
        nameLabel.setWrapText(true);
        nameLabel.setAlignment(Pos.CENTER);
        nameLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        card.getChildren().addAll(icon, nameLabel);
        
        card.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                boolean currentlyExists = new File(project.getPath()).exists();

                if (currentlyExists) {
                    card.getStyleClass().remove("offline");
                    openProject(project);
                } else {
                    if (!card.getStyleClass().contains("offline")) {
                        card.getStyleClass().add("offline");
                    }
                    ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_inaccessible"), i18n.get("dashboard.toast.error_access", project.getPath()), true);
                }
            }
        });

        ContextMenu contextMenu = new ContextMenu();
        MenuItem propsItem = new MenuItem(i18n.get("dashboard.context_menu.properties"));
        propsItem.setOnAction(e -> showPropertiesModal(project));
        MenuItem deleteItem = new MenuItem(i18n.get("dashboard.context_menu.delete"));
        deleteItem.setOnAction(e -> showDeleteConfirmation(project));
        contextMenu.getItems().addAll(propsItem, deleteItem);
        card.setOnContextMenuRequested(e -> contextMenu.show(card, e.getScreenX(), e.getScreenY()));

        setupDragAndDrop(card, project);
        
        return card;
    }

    private void setupDragAndDrop(VBox card, Project project) {
        card.setOnDragDetected(event -> {
            Dragboard db = card.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(String.valueOf(project.getId()));
            db.setContent(content);

            draggedProject = project;
            draggedCard = card;

            card.getStyleClass().add("card-dragging");

            event.consume();
        });

        card.setOnDragOver(event -> {
            if (event.getGestureSource() != card && event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        card.setOnDragEntered(event -> {
            if (event.getGestureSource() != card && event.getDragboard().hasString()) {
                card.setOpacity(0.7);
            }
        });

        card.setOnDragExited(event -> {
            if (event.getGestureSource() != card && event.getDragboard().hasString()) {
                card.setOpacity(1.0);
            }
        });

        card.setOnDragDropped(event -> {
            boolean success = false;
            if (draggedProject != null && draggedCard != null) {
                Node targetNode = (Node) event.getGestureTarget();
                while (targetNode != null && !(targetNode instanceof VBox && targetNode.getStyleClass().contains("project-card"))) {
                    targetNode = targetNode.getParent();
                }

                if (targetNode != null && targetNode != draggedCard) {
                    VBox targetCard = (VBox) targetNode;
                    reorderProjects(draggedCard, targetCard);
                    success = true;
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });

        card.setOnDragDone(event -> {
            card.getStyleClass().remove("card-dragging");
            draggedProject = null;
            draggedCard = null;
            event.consume();
        });
    }

    private void reorderProjects(VBox sourceCard, VBox targetCard) {
        int sourceIdx = projectsContainer.getChildren().indexOf(sourceCard);
        int targetIdx = projectsContainer.getChildren().indexOf(targetCard);

        if (sourceIdx != -1 && targetIdx != -1) {
            projectsContainer.getChildren().remove(sourceIdx);
            projectsContainer.getChildren().add(targetIdx, sourceCard);
            saveNewOrder();
        }
    }
    
    private void saveNewOrder() {
        List<Project> newOrder = new ArrayList<>();
        for (Node node : projectsContainer.getChildren()) {
            if (node instanceof VBox) {
                Project p = (Project) node.getUserData();
                if (p != null) newOrder.add(p);
            }
        }

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                projectRepository.updateOrder(newOrder);
                return null;
            }
        };
        new Thread(task).start();
    }

    private void showDeleteConfirmation(Project project) {
        VBox modalContent = new VBox(20);
        modalContent.getStyleClass().add("modal-box");
        modalContent.setAlignment(Pos.CENTER);

        Label title = new Label(i18n.get("dialogs.delete.title"));
        title.getStyleClass().add("modal-title");

        Label text = new Label(i18n.get("dialogs.delete.message", project.getName()));
        text.getStyleClass().add("modal-text");
        text.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        HBox buttons = new HBox(15);
        buttons.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button(i18n.get("dialogs.delete.btn_cancel"));
        cancelBtn.getStyleClass().add("button-secondary");

        Button confirmBtn = new Button(i18n.get("dialogs.delete.btn_confirm"));
        confirmBtn.getStyleClass().add("button-danger");

        buttons.getChildren().addAll(cancelBtn, confirmBtn);
        modalContent.getChildren().addAll(title, text, buttons);

        StackPane overlay = createModalOverlay(modalContent);

        cancelBtn.setOnAction(e -> closeModal(overlay));
        confirmBtn.setOnAction(e -> {
            deleteProject(project);
            closeModal(overlay);
        });

        rootStackPane.getChildren().add(overlay);
    }

    private void showPropertiesModal(Project project) {
        VBox modalContent = new VBox(20);
        modalContent.getStyleClass().add("modal-box");
        modalContent.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(i18n.get("dialogs.properties.title"));
        title.getStyleClass().add("modal-title");

        VBox nameBox = new VBox(5);
        Label nameLabel = new Label(i18n.get("dialogs.properties.label_name"));
        nameLabel.setStyle("-fx-font-weight: bold;");
        TextField nameField = new TextField(project.getName());
        nameField.getStyleClass().add("text-field-modern");
        nameBox.getChildren().addAll(nameLabel, nameField);

        VBox pathBox = new VBox(5);
        Label pathLabel = new Label(i18n.get("dialogs.properties.label_path"));
        pathLabel.setStyle("-fx-font-weight: bold;");
        HBox pathRow = new HBox(10);
        pathRow.setAlignment(Pos.CENTER_LEFT);

        TextField pathField = new TextField(project.getPath());
        pathField.getStyleClass().add("text-field-modern");
        pathField.setEditable(false);
        HBox.setHgrow(pathField, javafx.scene.layout.Priority.ALWAYS);

        Button browseBtn = new Button(i18n.get("dialogs.properties.btn_browse"));
        browseBtn.getStyleClass().add("button-browse");
        browseBtn.prefHeightProperty().bind(pathField.heightProperty());
        browseBtn.minHeightProperty().bind(pathField.heightProperty());

        pathRow.getChildren().addAll(pathField, browseBtn);
        pathBox.getChildren().addAll(pathLabel, pathRow);

        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setInitialDirectory(new File(project.getPath()).exists() ? new File(project.getPath()) : null);
            File selected = dc.showDialog(rootStackPane.getScene().getWindow());
            if (selected != null) {
                pathField.setText(selected.getAbsolutePath());
            }
        });

        HBox buttons = new HBox(15);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        
        Button cancelBtn = new Button(i18n.get("dialogs.properties.btn_cancel"));
        cancelBtn.getStyleClass().add("button-secondary");
        
        Button saveBtn = new Button(i18n.get("dialogs.properties.btn_save"));
        saveBtn.getStyleClass().add("button-primary");

        buttons.getChildren().addAll(cancelBtn, saveBtn);
        modalContent.getChildren().addAll(title, nameBox, pathBox, buttons);

        StackPane overlay = createModalOverlay(modalContent);

        cancelBtn.setOnAction(e -> closeModal(overlay));
        saveBtn.setOnAction(e -> {
            String newName = nameField.getText().trim();
            String newPath = pathField.getText().trim();
            if (!newName.isEmpty() && !newPath.isEmpty()) {
                Project updated = new Project(project.getId(), newName, newPath, project.getLastOpened(), project.getDisplayOrder());
                
                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() {
                        projectRepository.update(updated);
                        return null;
                    }
                };
                task.setOnSucceeded(ev -> {
                    loadProjects();
                    ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_success"), i18n.get("dashboard.toast.success_update"), false);
                });
                new Thread(task).start();
                closeModal(overlay);
            }
        });

        rootStackPane.getChildren().add(overlay);
    }

    @FXML
    private void onInfoClicked() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/myriadimg/ui/about_view.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) infoButton.getScene().getWindow();
            stage.getScene().setRoot(root);
        } catch (IOException e) {
            e.printStackTrace();
            ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_error"), i18n.get("dialogs.error_info"), true);
        }
    }

    @FXML
    private void onHelpClicked() {
        VBox modalContent = new VBox(20);
        modalContent.getStyleClass().add("modal-box");
        modalContent.setAlignment(Pos.TOP_LEFT);
        modalContent.setMaxWidth(600);
        modalContent.setMaxHeight(500);

        Label title = new Label(i18n.get("help.title"));
        title.getStyleClass().add("modal-title");

        VBox steps = new VBox(15);

        JsonNode stepsNode = i18n.getNode("help.steps");
        if (stepsNode != null && stepsNode.isArray()) {
            for (JsonNode step : stepsNode) {
                steps.getChildren().add(createTutorialStep(
                    step.get("id").asText(),
                    step.get("title").asText(),
                    step.get("desc").asText()
                ));
            }
        }

        steps.setPadding(new javafx.geometry.Insets(0, 15, 0, 0));

        ScrollPane scroll = new ScrollPane(steps);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("modern-scroll-pane");
        scroll.setStyle("-fx-background-color: transparent;");

        Button closeBtn = new Button(i18n.get("help.btn_close"));
        closeBtn.getStyleClass().add("button-primary");
        closeBtn.setAlignment(Pos.CENTER);

        HBox btnContainer = new HBox(closeBtn);
        btnContainer.setAlignment(Pos.CENTER);
        btnContainer.setPadding(new javafx.geometry.Insets(10, 0, 0, 0));

        modalContent.getChildren().addAll(title, scroll, btnContainer);

        javafx.scene.layout.VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);

        StackPane overlay = createModalOverlay(modalContent);
        closeBtn.setOnAction(e -> closeModal(overlay));

        rootStackPane.getChildren().add(overlay);
    }
    
    @FXML
    private void onLangFr() {
        i18n.loadLanguage("fr");
        reloadDashboard();
    }

    @FXML
    private void onLangEn() {
        i18n.loadLanguage("en");
        reloadDashboard();
    }

    private void reloadDashboard() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/myriadimg/ui/dashboard.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) rootStackPane.getScene().getWindow();
            stage.getScene().setRoot(root);
        } catch (IOException e) {
            e.printStackTrace();
            ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_error"), "Failed to reload dashboard after language change.", true);
        }
    }

    private HBox createTutorialStep(String number, String title, String desc) {
        HBox row = new HBox(15);
        row.getStyleClass().add("tutorial-step");
        row.setAlignment(Pos.CENTER_LEFT);

        Label numLabel = new Label(number);
        numLabel.getStyleClass().add("tutorial-icon");
        numLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 18px;");

        VBox text = new VBox(3);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
        Label descLabel = new Label(desc);
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 13px;");
        
        text.getChildren().addAll(titleLabel, descLabel);
        
        row.getChildren().addAll(numLabel, text);
        return row;
    }

    private StackPane createModalOverlay(Node content) {
        StackPane overlay = new StackPane(content);
        overlay.getStyleClass().add("modal-overlay");
        overlay.setAlignment(Pos.CENTER);

        overlay.setOnMouseClicked(e -> {
            if (e.getTarget() == overlay) {
                closeModal(overlay);
            }
        });
        
        FadeTransition ft = new FadeTransition(Duration.millis(200), overlay);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.play();
        return overlay;
    }

    private void closeModal(StackPane overlay) {
        FadeTransition ft = new FadeTransition(Duration.millis(200), overlay);
        ft.setFromValue(1);
        ft.setToValue(0);
        ft.setOnFinished(e -> rootStackPane.getChildren().remove(overlay));
        ft.play();
    }

    private void deleteProject(Project project) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                projectRepository.delete(project);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            loadProjects();
            ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_success"), i18n.get("dashboard.toast.success_delete"), false);
        });
        new Thread(task).start();
    }

    private Node createFolderIcon(boolean exists) {
        SVGPath path = new SVGPath();
        path.setContent("M9.31066 4.5H3V18.75L3.75 19.5H20.25L21 18.75V6H10.8107L10.2804 5.46968L10.2804 5.4697L9.31066 4.5ZM10.1894 7.5H10.1893L8.68934 6H4.5V18H19.5V9.75001H12.4394L10.1894 7.5ZM19.5 8.25001V7.5H12.3107L13.0607 8.25001H19.5Z");
        path.getStyleClass().add("folder-icon");
        path.setScaleX(2.5);
        path.setScaleY(2.5);
        StackPane iconPane = new StackPane(path);
        iconPane.setPrefSize(64, 64);
        return iconPane;
    }

    @FXML
    private void onAddProject() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/myriadimg/ui/new_project.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) addProjectButton.getScene().getWindow();
            stage.getScene().setRoot(root);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void openProject(Project project) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/myriadimg/ui/project_view.fxml"));
            Parent root = loader.load();
            ProjectViewController controller = loader.getController();
            controller.setProject(project);
            Stage stage = (Stage) projectsContainer.getScene().getWindow();
            stage.getScene().setRoot(root);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
