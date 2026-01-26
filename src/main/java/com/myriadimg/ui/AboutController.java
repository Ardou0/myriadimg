package com.myriadimg.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.myriadimg.util.I18nService;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import java.io.IOException;

public class AboutController {

    @FXML private Label appNameLabel;
    @FXML private Label versionLabel;
    @FXML private Label descriptionLabel;
    @FXML private VBox philosophyBox;
    @FXML private VBox techBox;
    @FXML private VBox aiBox;
    @FXML private VBox issuesBox;
    
    @FXML private Label philosophyHeader;
    @FXML private Label techHeader;
    @FXML private Label aiHeader;
    @FXML private Label issuesHeader;
    @FXML private Label footerLabel;
    @FXML private Button backButton; // Not bound in FXML but good to have reference if needed

    private final I18nService i18n = I18nService.getInstance();

    @FXML
    public void initialize() {
        loadAppInfo();
        updateTexts();
    }

    private void updateTexts() {
        if (philosophyHeader != null) philosophyHeader.setText(i18n.get("about.section_philosophy"));
        if (techHeader != null) techHeader.setText(i18n.get("about.section_tech"));
        if (aiHeader != null) aiHeader.setText(i18n.get("about.section_ai"));
        if (issuesHeader != null) issuesHeader.setText(i18n.get("about.section_issues"));
        if (footerLabel != null) footerLabel.setText(i18n.get("app.copyright"));
        // Back button text is usually an icon "←" or handled in FXML
    }

    @FXML
    private void onHomeClicked() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/myriadimg/ui/dashboard.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) appNameLabel.getScene().getWindow();
            stage.getScene().setRoot(root);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadAppInfo() {
        // Load data from I18n service instead of separate file
        appNameLabel.setText(i18n.get("app.name"));
        versionLabel.setText(i18n.get("app.version"));
        descriptionLabel.setText(i18n.get("about.description"));

        // Philosophy
        JsonNode philoNode = i18n.getNode("about.data.philosophy");
        if (philoNode != null && philoNode.isArray()) {
            for (JsonNode node : philoNode) {
                Label l = new Label("• " + node.asText());
                l.getStyleClass().add("info-list-item");
                l.setWrapText(true);
                philosophyBox.getChildren().add(l);
            }
        }

        // Technologies
        JsonNode techNode = i18n.getNode("about.data.technologies");
        if (techNode != null && techNode.isArray()) {
            for (JsonNode node : techNode) {
                VBox item = new VBox(2);
                Label name = new Label(node.get("name").asText());
                name.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 14px;");
                Label role = new Label(node.get("role").asText());
                role.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
                item.getChildren().addAll(name, role);
                techBox.getChildren().add(item);
            }
        }

        // AI Models
        JsonNode aiNode = i18n.getNode("about.data.ai_models");
        if (aiNode != null && aiNode.isArray()) {
            for (JsonNode node : aiNode) {
                VBox item = new VBox(2);
                Label name = new Label(node.get("name").asText());
                name.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 14px;");
                
                TextFlow details = new TextFlow();
                Text type = new Text(node.get("type").asText());
                type.setStyle("-fx-fill: #666; -fx-font-size: 12px;");
                details.getChildren().add(type);
                
                if (node.has("format")) {
                    Text extra = new Text(" (" + node.get("format").asText() + ")");
                    extra.setStyle("-fx-fill: #888; -fx-font-size: 11px;");
                    details.getChildren().add(extra);
                }
                
                item.getChildren().addAll(name, details);
                aiBox.getChildren().add(item);
            }
        }

        // Known Issues
        JsonNode issuesNode = i18n.getNode("about.data.known_issues");
        if (issuesNode != null && issuesNode.isArray()) {
            for (JsonNode node : issuesNode) {
                Label l = new Label("• " + node.asText());
                l.getStyleClass().add("info-list-item");
                l.setStyle("-fx-text-fill: #d9534f;"); // Red/Orange color for issues
                l.setWrapText(true);
                issuesBox.getChildren().add(l);
            }
        }
    }
}
