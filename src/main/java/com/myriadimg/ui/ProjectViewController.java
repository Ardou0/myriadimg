package com.myriadimg.ui;

import com.myriadimg.model.Asset;
import com.myriadimg.model.Project;
import com.myriadimg.model.Tag;
import com.myriadimg.repository.ProjectDatabaseManager;
import com.myriadimg.service.IndexingService;
import com.myriadimg.service.ServiceManager;
import com.myriadimg.service.ThrottlableService;
import com.myriadimg.service.ThumbnailService;
import com.myriadimg.service.I18nService;
import com.myriadimg.util.ProjectLogger;
import com.myriadimg.util.SettingsManager;
import com.myriadimg.util.ToastUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ProjectViewController {

    @FXML private StackPane rootStackPane;
    @FXML private SplitPane mainSplitPane;
    @FXML private Label projectNameLabel;
    @FXML private Button backButton;
    @FXML private Button scanButton;
    @FXML private HBox scanStatusBox;
    @FXML private ProgressBar scanProgressBar;
    @FXML private Label scanStatusLabel;

    @FXML private TextField searchField;
    @FXML private ComboBox<String> sortCombo;
    @FXML private ComboBox<String> viewModeCombo;
    @FXML private Slider thumbnailSizeSlider;

    @FXML private TitledPane typeTagsPane;
    @FXML private TitledPane locationTagsPane;
    @FXML private TitledPane aiTagsPane;
    @FXML private TitledPane peoplePane;
    @FXML private TitledPane manualTagsPane;
    @FXML private TitledPane specialSettingsPane; // New Pane
    @FXML private CheckBox showIconsCheckbox; // New Checkbox
    @FXML private VBox typeTagsContainer;
    @FXML private VBox locationTagsContainer;
    @FXML private VBox aiTagsContainer;
    @FXML private VBox peopleContainer;
    @FXML private VBox manualTagsContainer;

    // Monitoring Section
    @FXML private Label activityTitleLabel;
    @FXML private Label activityIndexingLabel;
    @FXML private Button activityIndexingButton;
    @FXML private Label activityThumbnailsLabel;
    @FXML private Button activityThumbnailsButton;
    @FXML private Label activityAiLabel;
    @FXML private Button activityAiButton;

    @FXML private Label statusScanLabel;
    @FXML private ProgressBar statusScanProgress;
    @FXML private Label statusThumbLabel;
    @FXML private ProgressBar statusThumbProgress;
    @FXML private Label statusAiLabel;
    @FXML private ProgressBar statusAiProgress;

    @FXML private StackPane viewContainer;
    
    // Grid View Components (Virtualization)
    private ListView<List<Asset>> gridListView;
    private TreeView<Object> folderTreeView;
    // Details Controller (Injected via fx:include)
    @FXML private AssetDetailsController assetDetailsController;

    @FXML private Label summaryLabel;
    @FXML private Label summaryValue;

    // Confirmation Overlay
    @FXML private VBox confirmationOverlay;
    @FXML private Label confirmationTitle;
    @FXML private Label confirmationMessage;
    @FXML private Button confirmAllButton;
    @FXML private Button confirmMissingButton;
    @FXML private Button cancelButton;

    @FXML private VBox toastContainer;

    private Project currentProject;
    private ProjectDatabaseManager dbManager;
    private ThumbnailService thumbnailService;
    private final I18nService i18n = I18nService.getInstance();
    private final SettingsManager settings = SettingsManager.getInstance();

    // Cache of all assets to avoid re-querying DB on view switch
    private List<Asset> allAssets = new ArrayList<>();
    private List<Asset> filteredAssets = new ArrayList<>();
    
    // Filter state
    private final Set<String> selectedTagValues = new HashSet<>();
    
    // Faceted Search State
    private final Map<String, HBox> tagUiElements = new HashMap<>();
    private final Map<String, Label> tagCountLabels = new HashMap<>();
    
    private int currentFolderColumns = -1;

    // Image Loading Management
    private final ExecutorService imageLoadExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("ImageLoader");
        return t;
    });

    public void setProject(Project project) {
        this.currentProject = project;
        projectNameLabel.setText(project.getName());
        
        if (assetDetailsController != null) {
            assetDetailsController.setProject(project);
        }

        // Initialize DB and Services in background to avoid UI freeze
        new Thread(() -> {
            try {
                this.dbManager = new ProjectDatabaseManager(project.getPath());

                // Check for required scans (flags from DB migration or other sources)
                boolean scanRequired = dbManager.isScanRequired();
                boolean thumbRequired = dbManager.isThumbnailScanRequired();
                boolean tagRequired = dbManager.isTagScanRequired();

                // Reset flags immediately as we will trigger the actions
                if (scanRequired) dbManager.setScanRequired(false);
                if (thumbRequired) dbManager.setThumbnailScanRequired(false);
                if (tagRequired) dbManager.setTagScanRequired(false);

                this.thumbnailService = new ThumbnailService(Paths.get(project.getPath()), dbManager, 200);
                
                if (assetDetailsController != null) {
                    assetDetailsController.setThumbnailService(thumbnailService);
                }
                
                // Setup live update callback
                this.thumbnailService.setOnThumbnailGenerated(this::onThumbnailGenerated);

                Platform.runLater(() -> {
                    // Ensure type tags exist for all assets (maintenance task)
                    new Thread(() -> {
                        dbManager.ensureTypeTagsExist();
                        Platform.runLater(() -> {
                            loadAssetsFromDb();
                            loadTagsFromDb();
                        });
                    }).start();

                    // Trigger required actions
                    if (scanRequired) {
                        onScanClicked();
                    }

                    // Always check for thumbnails (covers thumbRequired case where column was added)
                    startThumbnailGeneration();

                    if (tagRequired) {
                        onStartAiAnalysis();
                    }
                    
                    // Restore view mode from project settings
                    String savedMode = settings.getProjectViewMode(project.getPath());
                    // Map saved mode key to UI text
                    String uiMode = "grid".equals(savedMode) ? i18n.get("project.view_mode.grid") :
                                    "folders".equals(savedMode) ? i18n.get("project.view_mode.folders") :
                                    i18n.get("project.view_mode.grid");
                                    
                    viewModeCombo.getSelectionModel().select(uiMode);
                    
                    // Check for running services for THIS project and re-bind UI
                    checkAndBindRunningServices();
                });
            } catch (Exception e) {
                ProjectLogger.logError(Paths.get(project.getPath()), "ProjectViewController", "Error initializing project", e);
                Platform.runLater(() -> ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_error"), "Error loading project: " + e.getMessage(), true));
            }
        }).start();
    }
    
    private void checkAndBindRunningServices() {
        List<ThrottlableService> activeServices = ServiceManager.getInstance().getActiveServices();
        
        for (ThrottlableService service : activeServices) {
            // Check if service belongs to current project
            if (service.getProjectPath() != null && service.getProjectPath().equals(currentProject.getPath())) {
                
                if (service instanceof IndexingService) {
                    scanButton.setDisable(true);
                    scanButton.setText(i18n.get("project.btn_scan_running"));
                    
                    statusScanLabel.textProperty().bind(service.messageProperty());
                    statusScanProgress.progressProperty().bind(service.progressProperty());
                    
                    ((IndexingService) service).stateProperty().addListener((obs, oldState, newState) -> {
                        if (newState == javafx.concurrent.Worker.State.SUCCEEDED || 
                            newState == javafx.concurrent.Worker.State.FAILED || 
                            newState == javafx.concurrent.Worker.State.CANCELLED) {
                            
                            scanButton.setDisable(false);
                            scanButton.setText(i18n.get("project.btn_scan"));
                            statusScanLabel.textProperty().unbind();
                            statusScanProgress.progressProperty().unbind();
                            
                            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                                updateStatusBubble(statusScanLabel, i18n.get("project.activity.status.completed"), "success");
                                statusScanProgress.setProgress(1.0);
                                loadAssetsFromDb(); // Refresh view
                                loadTagsFromDb(); // Refresh tags
                            } else {
                                updateStatusBubble(statusScanLabel, i18n.get("project.activity.status.error"), "error");
                                statusScanProgress.setProgress(0);
                            }
                        }
                    });
                    
                } else if (service instanceof ThumbnailService) {
                    statusThumbLabel.textProperty().bind(service.messageProperty());
                    statusThumbProgress.progressProperty().bind(service.progressProperty());
                    
                    ((ThumbnailService) service).stateProperty().addListener((obs, oldState, newState) -> {
                        if (newState == javafx.concurrent.Worker.State.SUCCEEDED || 
                            newState == javafx.concurrent.Worker.State.FAILED || 
                            newState == javafx.concurrent.Worker.State.CANCELLED) {
                            
                            statusThumbLabel.textProperty().unbind();
                            statusThumbProgress.progressProperty().unbind();
                            
                            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                                updateStatusBubble(statusThumbLabel, i18n.get("project.activity.status.completed"), "success");
                                statusThumbProgress.setProgress(1.0);
                            } else {
                                updateStatusBubble(statusThumbLabel, i18n.get("project.activity.status.error"), "error");
                                statusThumbProgress.setProgress(0);
                            }
                        }
                    });
                }
            }
        }
    }
    
    private void onThumbnailGenerated(String relativePath) {
        // Targeted update: Find the specific ImageView and update it
        Platform.runLater(() -> {
            if (gridListView != null && gridListView.isVisible()) {
                // lookupAll finds all nodes in the scene graph (visible cells) matching the selector
                Set<Node> nodes = gridListView.lookupAll(".asset-thumbnail");
                for (Node node : nodes) {
                    if (node instanceof ImageView) {
                        ImageView view = (ImageView) node;
                        // Check if this view is displaying the updated asset
                        if (relativePath.equals(view.getUserData())) {
                            loadImageAsync(view, relativePath);
                        }
                    }
                }
            }
            // Also update tree view if visible
            if (folderTreeView != null && folderTreeView.isVisible()) {
                Set<Node> nodes = folderTreeView.lookupAll(".asset-thumbnail");
                for (Node node : nodes) {
                    if (node instanceof ImageView) {
                        ImageView view = (ImageView) node;
                        if (relativePath.equals(view.getUserData())) {
                            loadImageAsync(view, relativePath);
                        }
                    }
                }
            }
        });
    }

    @FXML
    public void initialize() {
        // Initialize Helper Controller
        if (assetDetailsController != null) {
            assetDetailsController.setToastContainer(toastContainer);
        }

        // Initialize Grid ListView
        gridListView = new ListView<>();
        gridListView.getStyleClass().add("image-grid-list");
        gridListView.getStyleClass().add("modern-scroll-pane"); // Add modern scrollbar style
        gridListView.setCellFactory(lv -> new AssetGridCell());
        gridListView.setFocusTraversable(false);
        // Remove default list style
        gridListView.setStyle("-fx-background-color: #F4F6F8;");

        // Initialize Folder TreeView (Virtualized replacement for ScrollPane/VBox)
        folderTreeView = new TreeView<>();
        folderTreeView.setShowRoot(false);
        folderTreeView.getStyleClass().add("modern-scroll-pane");
        folderTreeView.setCellFactory(tv -> new FolderTreeCell());
        folderTreeView.setStyle("-fx-background-color: #F4F6F8;");

        updateUIWithI18n();
        setupCombos();
        // setupFilters(); // Removed static setup, now dynamic
        setupLayoutPersistence();
        setupConfirmationOverlay();

        thumbnailSizeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (viewModeCombo.getSelectionModel().getSelectedIndex() == 0) { // Grid mode
                repartitionGrid();
            } else {
                // Also update folder view if visible
                renderFolderView();
            }
        });
        
        // Listen to width changes to re-layout grid
        viewContainer.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (viewModeCombo.getSelectionModel().getSelectedIndex() == 0) { // Grid mode
                repartitionGrid();
            } else {
                // Check if columns would change
                double width = newVal.doubleValue();
                if (width <= 0) width = 800;
                double cardWidth = thumbnailSizeSlider.getValue() + 20;
                double gap = 10;
                double scrollBarAllowance = 40;
                int newCols = Math.max(1, (int) ((width - scrollBarAllowance) / (cardWidth + gap)));
                
                if (newCols != currentFolderColumns) {
                    renderFolderView();
                }
            }
        });

        viewModeCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            refreshView();
            
            // Save view mode to project settings
            if (currentProject != null && newVal != null) {
                String modeKey = "grid";

                if (newVal.equals(i18n.get("project.view_mode.folders"))) modeKey = "folders";
                
                settings.setProjectViewMode(currentProject.getPath(), modeKey);
            }
        });
        
        // Listen to showIconsCheckbox
        if (showIconsCheckbox != null) {
            showIconsCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                applyFilters();
            });
        }
    }

    private void repartitionGrid() {
        if (filteredAssets.isEmpty()) {
            gridListView.setItems(FXCollections.observableArrayList());
            return;
        }

        double width = viewContainer.getWidth();
        // Si la largeur n'est pas encore disponible (ex: au démarrage), on attend
        if (width <= 0) {
            // On peut réessayer plus tard si nécessaire, ou utiliser une valeur par défaut raisonnable
            // Mais pour l'instant, gardons le fallback
            width = 800;
        }

        double cardWidth = thumbnailSizeSlider.getValue() + 20; // + padding/margin
        double gap = 10;
        double scrollBarAllowance = 20; // Space for scrollbar

        // Calculate columns (at least 1)
        int columns = Math.max(1, (int) ((width - scrollBarAllowance) / (cardWidth + gap)));

        // Calculate padding to center the grid
        double contentWidth = columns * cardWidth + (columns - 1) * gap;
        double totalPadding = width - contentWidth - scrollBarAllowance;

        // Ensure padding is non-negative and balanced
        double leftPadding = Math.max(0, totalPadding / 2);

        // Apply padding to the ListView to center the content
        // Important: Reset padding first to avoid accumulation issues if any
        gridListView.setPadding(new Insets(10, 0, 10, leftPadding));

        List<List<Asset>> rows = new ArrayList<>();
        for (int i = 0; i < filteredAssets.size(); i += columns) {
            int end = Math.min(i + columns, filteredAssets.size());
            rows.add(new ArrayList<>(filteredAssets.subList(i, end)));
        }

        ObservableList<List<Asset>> items = FXCollections.observableArrayList(rows);
        gridListView.setItems(items);

        // Scroll to top when refreshing content
        gridListView.scrollTo(0);
    }
    
    private void updateRowBox(HBox root, List<VBox> cardPool, List<Asset> rowAssets) {
        if (rowAssets == null) {
            root.getChildren().clear();
            return;
        }
        
        // Ensure we have enough cards in the pool
        while (cardPool.size() < rowAssets.size()) {
            cardPool.add(createEmptyCard());
        }
        
        // Sync children without clearing
        int currentSize = root.getChildren().size();
        int targetSize = rowAssets.size();
        
        if (currentSize > targetSize) {
            root.getChildren().remove(targetSize, currentSize);
        } else if (currentSize < targetSize) {
            for (int i = currentSize; i < targetSize; i++) {
                root.getChildren().add(cardPool.get(i));
            }
        }
        
        for (int i = 0; i < rowAssets.size(); i++) {
            Asset asset = rowAssets.get(i);
            VBox card = cardPool.get(i);
            
            ImageView view = findImageView(card);
            String oldPath = (view != null) ? (String) view.getUserData() : null;
            boolean sameAsset = asset.getPath().equals(oldPath);
            
            populateAssetCard(card, asset); // Updates userData
            
            if (view != null) {
                if (!sameAsset) {
                    view.setImage(null); // Clear previous image to avoid showing wrong image while loading
                    loadImageAsync(view, asset);
                } else {
                    // Same asset, update image if needed (e.g. better quality available)
                    loadImageAsync(view, asset);
                }
            }
        }
    }

    private class AssetGridCell extends ListCell<List<Asset>> {
        private final HBox root;
        private final List<VBox> cardPool = new ArrayList<>();
        
        public AssetGridCell() {
            root = new HBox(10);
            root.setAlignment(Pos.CENTER_LEFT); // Keep Left alignment so last row aligns with grid
            root.setPadding(new Insets(5));
            root.setStyle("-fx-background-color: transparent;");
            setGraphic(root);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        }
        
        @Override
        protected void updateItem(List<Asset> rowAssets, boolean empty) {
            super.updateItem(rowAssets, empty);
            
            if (empty || rowAssets == null) {
                root.getChildren().clear();
                return;
            }
            
            updateRowBox(root, cardPool, rowAssets);
        }
    }
    
    private void loadImageAsync(ImageView view, Asset asset) {
        loadImageAsync(view, asset.getPath());
    }

    private void loadImageAsync(ImageView view, String path) {
        // Use CompletableFuture to load image off-thread
        CompletableFuture.supplyAsync(() -> thumbnailService.loadThumbnail(path), imageLoadExecutor)
            .thenAccept(image -> {
                if (image != null) {
                    Platform.runLater(() -> {
                        // Verify if the view is still meant for this asset
                        if (path.equals(view.getUserData())) {
                            view.setImage(image);
                        }
                    });
                }
            });
    }

    private VBox createEmptyCard() {
        VBox card = new VBox(5);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("project-card");
        
        StackPane thumbContainer = new StackPane();
        thumbContainer.getStyleClass().add("thumbnail-container");
        
        ImageView thumbView = new ImageView();
        thumbView.setPreserveRatio(true);
        // Add style class for lookup
        thumbView.getStyleClass().add("asset-thumbnail");
        
        thumbContainer.getChildren().add(thumbView);
        card.getChildren().addAll(thumbContainer, new Label());
        
        return card;
    }
    
    private void populateAssetCard(VBox card, Asset asset) {
        double size = thumbnailSizeSlider.getValue();
        
        // Fixer strictement la taille de la carte pour l'uniformité
        double cardWidth = size + 20;
        double cardHeight = size + 50;
        
        card.setPrefSize(cardWidth, cardHeight);
        card.setMinSize(cardWidth, cardHeight);
        card.setMaxSize(cardWidth, cardHeight);
        
        StackPane thumbContainer = (StackPane) card.getChildren().get(0);
        thumbContainer.setPrefSize(size, size);
        thumbContainer.setMinSize(size, size);
        thumbContainer.setMaxSize(size, size);
        
        ImageView thumbView = (ImageView) thumbContainer.getChildren().get(0);
        thumbView.setFitWidth(size);
        thumbView.setFitHeight(size);
        thumbView.setUserData(asset.getPath()); // Store path to verify async load
        
        // Handle Video Label
        boolean hasLabel = thumbContainer.getChildren().stream().anyMatch(n -> n instanceof Label);
        boolean isVideo = asset.getType() == Asset.AssetType.VIDEO;
        
        if (isVideo && !hasLabel) {
            Label typeLabel = new Label("VIDEO");
            typeLabel.getStyleClass().add("video-label");
            StackPane.setAlignment(typeLabel, Pos.BOTTOM_RIGHT);
            thumbContainer.getChildren().add(typeLabel);
        } else if (!isVideo && hasLabel) {
            thumbContainer.getChildren().removeIf(n -> n instanceof Label);
        }
        
        Label nameLabel = (Label) card.getChildren().get(1);
        nameLabel.setText(new File(asset.getPath()).getName());
        nameLabel.getStyleClass().add("asset-name");
        nameLabel.setMaxWidth(size);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        
        card.setOnMouseClicked(e -> {
            if (assetDetailsController != null) {
                assetDetailsController.showAssetDetails(asset);
            }
        });
    }
    
    private ImageView findImageView(VBox card) {
        if (card.getChildren().isEmpty()) return null;
        Node n = card.getChildren().get(0);
        if (n instanceof StackPane) {
            StackPane sp = (StackPane) n;
            if (!sp.getChildren().isEmpty() && sp.getChildren().get(0) instanceof ImageView) {
                return (ImageView) sp.getChildren().get(0);
            }
        }
        return null;
    }

    private void setupLayoutPersistence() {
        List<Double> positions = settings.getProjectDividerPositions();
        if (positions.size() == 2) {
            mainSplitPane.setDividerPositions(positions.get(0), positions.get(1));
        }

        for (SplitPane.Divider divider : mainSplitPane.getDividers()) {
            divider.positionProperty().addListener((obs, oldVal, newVal) -> {
                saveDividerPositions();
            });
        }
    }

    private void saveDividerPositions() {
        double[] positions = mainSplitPane.getDividerPositions();
        settings.setProjectDividerPositions(positions);
    }

    private void updateUIWithI18n() {
        backButton.setText(i18n.get("project.btn_back"));
        scanButton.setText(i18n.get("project.btn_scan"));
        searchField.setPromptText(i18n.get("project.search_placeholder"));

        typeTagsPane.setText(i18n.get("project.sidebar.tags_type"));
        locationTagsPane.setText("Lieux"); // TODO: Add to i18n
        aiTagsPane.setText(i18n.get("project.sidebar.tags_ai"));
        peoplePane.setText(i18n.get("project.sidebar.people"));
        manualTagsPane.setText(i18n.get("project.sidebar.tags_manual"));
        
        // Special Settings
        specialSettingsPane.setText(i18n.get("project.sidebar.tags_special"));
        showIconsCheckbox.setText(i18n.get("project.sidebar.show_icons"));
        
        // Activity Monitor
        activityTitleLabel.setText(i18n.get("project.activity.title"));
        activityIndexingLabel.setText(i18n.get("project.activity.indexing"));
        activityIndexingButton.setText(i18n.get("project.activity.btn_relaunch"));
        activityThumbnailsLabel.setText(i18n.get("project.activity.thumbnails"));
        activityThumbnailsButton.setText(i18n.get("project.activity.btn_redo"));
        activityAiLabel.setText(i18n.get("project.activity.ai_analysis"));
        activityAiButton.setText(i18n.get("project.activity.btn_start"));

        // Initial status text
        statusScanLabel.setText(i18n.get("project.activity.status.inactive"));
        statusThumbLabel.setText(i18n.get("project.activity.status.inactive"));
        statusAiLabel.setText(i18n.get("project.activity.status.pending"));
        
        if (assetDetailsController != null) {
            assetDetailsController.updateUIWithI18n();
        }
    }

    private void setupCombos() {
        sortCombo.getItems().clear();
        sortCombo.getItems().addAll(
                i18n.get("project.sort.date_desc"),
                i18n.get("project.sort.date_asc"),
                i18n.get("project.sort.name_asc")
        );
        sortCombo.getSelectionModel().select(0);

        viewModeCombo.getItems().clear();
        viewModeCombo.getItems().addAll(
                i18n.get("project.view_mode.grid"),
                i18n.get("project.view_mode.folders")
                // Folders and Themes removed
        );
        viewModeCombo.getSelectionModel().select(0);
    }

    private void loadTagsFromDb() {
        if (dbManager == null) return;
        
        Map<Tag.TagType, List<Tag>> groupedTags = dbManager.getAllTagsGroupedByType();
        
        Platform.runLater(() -> {
            // Clear existing
            typeTagsContainer.getChildren().clear();
            locationTagsContainer.getChildren().clear();
            aiTagsContainer.getChildren().clear();
            peopleContainer.getChildren().clear();
            manualTagsContainer.getChildren().clear();
            
            tagUiElements.clear();
            tagCountLabels.clear();
            
            // Populate Type Tags
            if (groupedTags.containsKey(Tag.TagType.TYPE)) {
                for (Tag tag : groupedTags.get(Tag.TagType.TYPE)) {
                    addFilterCheckbox(typeTagsContainer, tag, false);
                }
            }
            
            // Populate Location Tags
            if (groupedTags.containsKey(Tag.TagType.LOCATION)) {
                for (Tag tag : groupedTags.get(Tag.TagType.LOCATION)) {
                    addFilterCheckbox(locationTagsContainer, tag, false);
                }
            }

            // Populate AI Tags (Scene + Object)
            List<Tag> aiTags = new ArrayList<>();
            if (groupedTags.containsKey(Tag.TagType.AI_SCENE)) aiTags.addAll(groupedTags.get(Tag.TagType.AI_SCENE));
            if (groupedTags.containsKey(Tag.TagType.AI_OBJECT)) aiTags.addAll(groupedTags.get(Tag.TagType.AI_OBJECT));
            
            for (Tag tag : aiTags) {
                addFilterCheckbox(aiTagsContainer, tag, false);
            }
            
            // Populate People
            if (groupedTags.containsKey(Tag.TagType.AI_PERSON)) {
                for (Tag tag : groupedTags.get(Tag.TagType.AI_PERSON)) {
                    addFilterCheckbox(peopleContainer, tag, true);
                }
            }
            
            // Populate Manual Tags
            if (groupedTags.containsKey(Tag.TagType.MANUAL)) {
                for (Tag tag : groupedTags.get(Tag.TagType.MANUAL)) {
                    addFilterCheckbox(manualTagsContainer, tag, false);
                }
            }
            
            // Initial update of facets
            updateTagFacets();
        });
    }

    private void addFilterCheckbox(VBox container, Tag tag, boolean editable) {
        HBox cell = new HBox(8); // More spacing
        cell.setAlignment(Pos.CENTER_LEFT);
        cell.getStyleClass().add("tag-cell");
        
        // Store reference to UI element
        tagUiElements.put(tag.getValue(), cell);

        CheckBox cb = new CheckBox();
        cb.getStyleClass().add("modern-checkbox");
        
        // Restore selection state if reloaded
        if (selectedTagValues.contains(tag.getValue())) {
            cb.setSelected(true);
        }
        
        cb.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                selectedTagValues.add(tag.getValue());
            } else {
                selectedTagValues.remove(tag.getValue());
            }
            applyFilters();
        });

        // Use pseudo for display, but we will update the count dynamically
        Label label = new Label(tag.getPseudo());
        label.getStyleClass().add("filter-label"); // Dark text
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        
        // Store reference to label for updating count
        tagCountLabels.put(tag.getValue(), label);

        if (editable) {
            Button editBtn = new Button("✎"); // Pencil icon
            editBtn.getStyleClass().add("edit-icon");
            editBtn.setVisible(false); // Visible on hover

            cell.setOnMouseEntered(e -> editBtn.setVisible(true));
            cell.setOnMouseExited(e -> editBtn.setVisible(false));

            editBtn.setOnAction(e -> {
                // Switch to edit mode
                TextField tf = new TextField(label.getText());
                tf.getStyleClass().add("text-field-modern");
                tf.setPrefHeight(24);

                // Replace label with textfield
                int idx = cell.getChildren().indexOf(label);
                cell.getChildren().set(idx, tf);
                tf.requestFocus();

                // Commit on Enter
                tf.setOnKeyPressed(ev -> {
                    if (ev.getCode() == KeyCode.ENTER) {
                        label.setText(tf.getText());
                        cell.getChildren().set(idx, label);
                        // TODO: Save new name to DB
                    } else if (ev.getCode() == KeyCode.ESCAPE) {
                        cell.getChildren().set(idx, label);
                    }
                });

                // Commit on focus lost
                tf.focusedProperty().addListener((obs, oldVal, newVal) -> {
                    if (!newVal) {
                        label.setText(tf.getText());
                        // Check if still in children (might have been removed by ESC)
                        if (cell.getChildren().contains(tf)) {
                            cell.getChildren().set(idx, label);
                        }
                        // TODO: Save new name to DB
                    }
                });
            });

            cell.getChildren().add(editBtn);
        }
        cell.getChildren().addAll(cb, label);
        container.getChildren().add(cell);
    }
    
    private void applyFilters() {
        // Start with all assets
        List<Asset> baseList = allAssets;
        
        // Filter out icons if checkbox is unchecked
        if (showIconsCheckbox != null) {
            if (!showIconsCheckbox.isSelected()) {
                // Normal mode: Show everything EXCEPT icons
                baseList = baseList.stream()
                    .filter(asset -> asset.getType() != Asset.AssetType.ICON)
                    .collect(Collectors.toList());
            } else {
                // Icon mode: Show ONLY icons
                baseList = baseList.stream()
                    .filter(asset -> asset.getType() == Asset.AssetType.ICON)
                    .collect(Collectors.toList());
            }
        }
        
        if (selectedTagValues.isEmpty()) {
            filteredAssets = new ArrayList<>(baseList);
        } else {
            filteredAssets = baseList.stream()
                .filter(asset -> {
                    Set<String> assetTagValues = asset.getTags().stream()
                        .map(Tag::getValue)
                        .collect(Collectors.toSet());
                        
                    return assetTagValues.containsAll(selectedTagValues);
                })
                .collect(Collectors.toList());
        }
        
        updateTagFacets();
        refreshView();
    }
    
    private void updateTagFacets() {
        // Calculate counts for all tags based on current filteredAssets
        Map<String, Integer> currentCounts = new HashMap<>();
        
        for (Asset asset : filteredAssets) {
            for (Tag tag : asset.getTags()) {
                currentCounts.put(tag.getValue(), currentCounts.getOrDefault(tag.getValue(), 0) + 1);
            }
        }
        
        // Update UI
        for (Map.Entry<String, HBox> entry : tagUiElements.entrySet()) {
            String tagValue = entry.getKey();
            HBox uiElement = entry.getValue();
            Label label = tagCountLabels.get(tagValue);
            
            int count = currentCounts.getOrDefault(tagValue, 0);
            boolean isSelected = selectedTagValues.contains(tagValue);
            
            // Update label text with new count (preserve original name part)
            if (label != null) {
                String currentText = label.getText();
                // Assuming format "Name (Count)" or just "Name"
                String namePart = currentText.contains("(") ? currentText.substring(0, currentText.lastIndexOf("(")).trim() : currentText;
                label.setText(namePart + " (" + count + ")");
            }
            
            // Visibility logic:
            // Show if count > 0 OR if it is currently selected (so user can uncheck it)
            boolean visible = count > 0 || isSelected;
            
            uiElement.setVisible(visible);
            uiElement.setManaged(visible);
        }
    }

    private void setupConfirmationOverlay() {
        cancelButton.setOnAction(e -> confirmationOverlay.setVisible(false));
        
        // Close on background click
        confirmationOverlay.setOnMouseClicked(e -> {
            if (e.getTarget() == confirmationOverlay) {
                confirmationOverlay.setVisible(false);
            }
        });
    }

    @FXML
    private void onBackClicked() {
        try {
            shutdown(); // Clean up resources
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/myriadimg/ui/dashboard.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) rootStackPane.getScene().getWindow();
            stage.getScene().setRoot(root);
        } catch (IOException e) {
            ProjectLogger.logError(null, "ProjectViewController", "Failed to return to dashboard", e);
            ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_error"), "Failed to return to dashboard", true);
        }
    }
    
    public void shutdown() {
        if (imageLoadExecutor != null && !imageLoadExecutor.isShutdown()) {
            imageLoadExecutor.shutdownNow();
        }
    }

    @FXML
    private void onScanClicked() {
        if (currentProject == null) return;

        // Check if scan is already running
        if (ServiceManager.getInstance().isServiceRunning(IndexingService.class)) {
            ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_info"), i18n.get("project.toast.task_already_running"), false);
            return;
        }

        scanButton.setDisable(true);
        scanButton.setText(i18n.get("project.btn_scan_running"));

        // Unbind any previous binding before setting text directly
        if (statusScanLabel.textProperty().isBound()) {
            statusScanLabel.textProperty().unbind();
        }
        if (statusScanProgress.progressProperty().isBound()) {
            statusScanProgress.progressProperty().unbind();
        }

        statusScanProgress.setProgress(-1); // Indeterminate progress

        IndexingService scanTask = new IndexingService(Paths.get(currentProject.getPath()), dbManager);

        // Bind Monitor UI properties
        statusScanLabel.textProperty().bind(scanTask.messageProperty());
        statusScanProgress.progressProperty().bind(scanTask.progressProperty());

        scanTask.setOnSucceeded(e -> {
            scanButton.setDisable(false);
            scanButton.setText(i18n.get("project.btn_scan"));

            // Unbind before setting text directly
            statusScanLabel.textProperty().unbind();
            statusScanProgress.progressProperty().unbind();

            // Update Monitor UI final state
            updateStatusBubble(statusScanLabel, i18n.get("project.activity.status.completed"), "success");
            statusScanProgress.setProgress(1.0);

            ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_success"), i18n.get("project.scan_status.complete"), false);

            loadAssetsFromDb();
            loadTagsFromDb(); // Refresh tags
            startThumbnailGeneration(); // Start generating thumbnails for new assets
        });

        scanTask.setOnFailed(e -> {
            scanButton.setDisable(false);
            scanButton.setText(i18n.get("project.btn_scan"));

            // Unbind before setting text directly
            statusScanLabel.textProperty().unbind();
            statusScanProgress.progressProperty().unbind();

            // Update Monitor UI final state
            updateStatusBubble(statusScanLabel, i18n.get("project.activity.status.error"), "error");
            statusScanProgress.setProgress(0);

            Throwable ex = scanTask.getException();
            if (ex != null) {
                ProjectLogger.logError(Paths.get(currentProject.getPath()), "IndexingService", "Scan failed", ex);
                ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_error"), "Scan failed: " + ex.getMessage(), true);
            }
        });
        
        scanTask.setOnCancelled(e -> {
            scanButton.setDisable(false);
            scanButton.setText(i18n.get("project.btn_scan"));
            
            statusScanLabel.textProperty().unbind();
            statusScanProgress.progressProperty().unbind();
            
            updateStatusBubble(statusScanLabel, "Cancelled", "warning");
            statusScanProgress.setProgress(0);
        });

        new Thread(scanTask).start();
    }

    @FXML
    private void onRetryThumbnails() {
        if (dbManager == null) return;

        // Check if thumbnails generation is already running
        if (ServiceManager.getInstance().isServiceRunning(ThumbnailService.class)) {
            ToastUtil.show(toastContainer, i18n.get("dashboard.toast.title_info"), i18n.get("project.toast.task_already_running"), false);
            return;
        }

        // Show inline confirmation overlay instead of Alert
        confirmationTitle.setText(i18n.get("dialogs.thumbnails.title"));
        confirmationMessage.setText(i18n.get("dialogs.thumbnails.message"));
        confirmAllButton.setText(i18n.get("dialogs.thumbnails.btn_all"));
        confirmMissingButton.setText(i18n.get("dialogs.thumbnails.btn_missing"));
        cancelButton.setText(i18n.get("dialogs.thumbnails.btn_cancel"));

        confirmAllButton.setOnAction(e -> {
            confirmationOverlay.setVisible(false);
            new Thread(() -> {
                dbManager.resetThumbnails();
                Platform.runLater(this::startThumbnailGeneration);
            }).start();
        });

        confirmMissingButton.setOnAction(e -> {
            confirmationOverlay.setVisible(false);
            startThumbnailGeneration();
        });

        confirmationOverlay.setVisible(true);
    }

    @FXML
    private void onStartAiAnalysis() {
        ToastUtil.show(toastContainer, "AI", "AI analysis is not yet implemented.", false);
    }

    private void startThumbnailGeneration() {
        if (ServiceManager.getInstance().isServiceRunning(ThumbnailService.class)) return;

        // Unbind any previous binding before setting text directly
        if (statusThumbLabel.textProperty().isBound()) {
            statusThumbLabel.textProperty().unbind();
        }
        if (statusThumbProgress.progressProperty().isBound()) {
            statusThumbProgress.progressProperty().unbind();
        }

        statusThumbProgress.setProgress(-1); // Indeterminate progress

        thumbnailService.reset(); // Reset the service state

        // Bind Monitor UI properties
        statusThumbLabel.textProperty().bind(thumbnailService.messageProperty());
        statusThumbProgress.progressProperty().bind(thumbnailService.progressProperty());

        thumbnailService.setOnSucceeded(e -> {
            // Unbind before setting text directly
            statusThumbLabel.textProperty().unbind();
            statusThumbProgress.progressProperty().unbind();

            // Update Monitor UI final state
            updateStatusBubble(statusThumbLabel, i18n.get("project.activity.status.completed"), "success");
            statusThumbProgress.setProgress(1.0);
        });

        thumbnailService.setOnFailed(e -> {
            // Unbind before setting text directly
            statusThumbLabel.textProperty().unbind();
            statusThumbProgress.progressProperty().unbind();

            // Update Monitor UI final state
            updateStatusBubble(statusThumbLabel, i18n.get("project.activity.status.error"), "error");
            statusThumbProgress.setProgress(0);
            
            Throwable ex = thumbnailService.getException();
            if (ex != null) {
                ProjectLogger.logError(Paths.get(currentProject.getPath()), "ThumbnailService", "Thumbnail generation failed", ex);
            }
        });
        
        thumbnailService.setOnCancelled(e -> {
            statusThumbLabel.textProperty().unbind();
            statusThumbProgress.progressProperty().unbind();
            
            updateStatusBubble(statusThumbLabel, "Cancelled", "warning");
            statusThumbProgress.setProgress(0);
        });

        thumbnailService.start();
    }

    private void updateStatusBubble(Label label, String text, String styleClass) {
        label.setText(text);
        label.getStyleClass().removeAll("inactive", "active", "success", "error", "warning");
        label.getStyleClass().add(styleClass);
    }

    private void loadAssetsFromDb() {
        if (dbManager == null) return;

        // Load counts
        int imageCount = dbManager.getAssetCount(Asset.AssetType.IMAGE);
        int videoCount = dbManager.getAssetCount(Asset.AssetType.VIDEO);
        int iconCount = dbManager.getAssetCount(Asset.AssetType.ICON);
        
        // Update summary label with counts
        Platform.runLater(() -> {
            if (summaryValue != null) {
                summaryValue.setText(String.format("%d Photos, %d Videos, %d Icons", imageCount, videoCount, iconCount));
            }
            
            // Update showIconsCheckbox text with count
            if (showIconsCheckbox != null) {
                showIconsCheckbox.setText(i18n.get("project.sidebar.show_icons") + " (" + iconCount + ")");
            }
        });
        
        if (assetDetailsController != null) {
            assetDetailsController.updateSummary(imageCount, videoCount);
        }

        // Load assets for grid
        this.allAssets = dbManager.getAllAssets();
        // Re-apply filters to respect current checkbox state
        applyFilters();
    }

    private void refreshView() {
        String mode = viewModeCombo.getSelectionModel().getSelectedItem();
        
        // Fallback if selection is null (e.g. during init)
        if (mode == null) {
            mode = i18n.get("project.view_mode.grid");
        }

        if (i18n.get("project.view_mode.folders").equals(mode)) {
            renderFolderView();
        } else {
            renderGridView();
        }
    }

    private void renderGridView() {
        viewContainer.getChildren().clear();
        viewContainer.getChildren().add(gridListView);
        Platform.runLater(this::repartitionGrid);
    }
    
    private void renderFolderView() {
        viewContainer.getChildren().clear();
        viewContainer.getChildren().add(folderTreeView);

        // Calculate columns
        double width = viewContainer.getWidth();
        if (width <= 0) width = 800;
        double cardWidth = thumbnailSizeSlider.getValue() + 20;
        double gap = 10;
        double scrollBarAllowance = 40; // Increased for tree indentation
        int columns = Math.max(1, (int) ((width - scrollBarAllowance) / (cardWidth + gap)));
        
        currentFolderColumns = columns;

        // Build the tree structure
        FolderNode rootNode = new FolderNode("Root");
        TreeItem<Object> rootItem = new TreeItem<>(rootNode);
        rootItem.setExpanded(true);

        Map<String, TreeItem<Object>> folderItems = new HashMap<>();

        for (Asset asset : filteredAssets) {
            Path p = Paths.get(asset.getPath());
            Path parent = p.getParent();

            TreeItem<Object> currentItem = rootItem;
            String currentPath = "";

            if (parent != null) {
                for (Path part : parent) {
                    String partName = part.toString();
                    currentPath = currentPath.isEmpty() ? partName : currentPath + File.separator + partName;

                    if (!folderItems.containsKey(currentPath)) {
                        FolderNode node = new FolderNode(partName);
                        TreeItem<Object> newItem = new TreeItem<>(node);
                        folderItems.put(currentPath, newItem);
                        currentItem.getChildren().add(newItem);
                        currentItem = newItem;
                    } else {
                        currentItem = folderItems.get(currentPath);
                    }

                    // Update count
                    ((FolderNode)currentItem.getValue()).totalAssets++;
                }
            }

            // Add asset to the current folder node's list
            Object value = currentItem.getValue();
            if (value instanceof FolderNode) {
                ((FolderNode) value).assets.add(asset);
            }
        }
        
        // Iterate over all created TreeItems to add asset rows
        List<TreeItem<Object>> allItems = new ArrayList<>(folderItems.values());
        allItems.add(rootItem);
        
        for (TreeItem<Object> item : allItems) {
            Object value = item.getValue();
            if (value instanceof FolderNode) {
                FolderNode node = (FolderNode) value;
                if (!node.assets.isEmpty()) {
                    // Create rows
                    for (int i = 0; i < node.assets.size(); i += columns) {
                        int end = Math.min(i + columns, node.assets.size());
                        List<Asset> row = new ArrayList<>(node.assets.subList(i, end));
                        item.getChildren().add(new TreeItem<>(row));
                    }
                }
            }
        }

        folderTreeView.setRoot(rootItem);
    }

    private static class FolderNode {
        String name;
        int totalAssets = 0;
        List<Asset> assets = new ArrayList<>();

        FolderNode(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
    
    private class FolderTreeCell extends TreeCell<Object> {
        private final HBox rowBox;
        private final List<VBox> cardPool = new ArrayList<>();
        
        // Folder Header Components
        private final HBox headerBox;
        private final Label folderLabel;
        private final SVGPath arrowIcon;

        public FolderTreeCell() {
             // Asset Row
             rowBox = new HBox(10);
             rowBox.setAlignment(Pos.CENTER_LEFT);
             rowBox.setPadding(new Insets(5));
             rowBox.setStyle("-fx-background-color: transparent;");
             
             // Folder Header (Custom UI to replace default TreeCell look)
             headerBox = new HBox(10);
             headerBox.setAlignment(Pos.CENTER_LEFT);
             // Clean style: No border, just padding. 
             // We will rely on the text size and icon to distinguish it.
             headerBox.setStyle("-fx-padding: 8px 0px; -fx-cursor: hand;");
             
             // Custom Arrow
             arrowIcon = new SVGPath();
             arrowIcon.setContent("M 0 0 L 5 5 L 0 10 z"); // Simple triangle pointing right
             arrowIcon.setStyle("-fx-fill: #7F8C8D;"); // Grey color
             
             folderLabel = new Label();
             // Larger font, dark text
             folderLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2C3E50;");
             
             headerBox.getChildren().addAll(arrowIcon, folderLabel);
             
             // Handle click on header to toggle expansion
             headerBox.setOnMouseClicked(e -> {
                 if (getTreeItem() != null) {
                     getTreeItem().setExpanded(!getTreeItem().isExpanded());
                 }
                 e.consume();
             });
        }
        
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);

            // Always hide the default disclosure node (the default arrow)
            // We use our own custom arrow inside the headerBox
            setDisclosureNode(null);

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setStyle("-fx-background-color: transparent;");
            } else {
                if (item instanceof FolderNode) {
                    FolderNode node = (FolderNode) item;
                    folderLabel.setText(node.name + " (" + node.totalAssets + ")");
                    
                    // Rotate arrow based on expansion state
                    if (getTreeItem() != null && getTreeItem().isExpanded()) {
                        arrowIcon.setRotate(90);
                    } else {
                        arrowIcon.setRotate(0);
                    }
                    
                    setText(null);
                    setGraphic(headerBox);
                    
                    // Remove selection background (focus effect) and ensure text stays dark
                    setStyle("-fx-background-color: transparent; -fx-text-fill: #2C3E50; -fx-selection-bar: transparent; -fx-selection-bar-non-focused: transparent;");
                    
                } else if (item instanceof List) {
                    // Asset Row
                    setText(null);
                    List<Asset> rowAssets = (List<Asset>) item;
                    
                    updateRowBox(rowBox, cardPool, rowAssets);
                    setGraphic(rowBox);
                    setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-selection-bar: transparent; -fx-selection-bar-non-focused: transparent;");
                }
            }
        }
    }

}