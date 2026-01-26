package com.myriadimg.service;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyStringProperty;

public interface ThrottlableService {
    boolean isRunning();
    String getStatus();
    
    // New methods for UI binding
    String getServiceName(); // Key for I18n
    ReadOnlyDoubleProperty progressProperty();
    ReadOnlyStringProperty messageProperty();
    
    // To identify which project this service belongs to
    String getProjectPath();
}
