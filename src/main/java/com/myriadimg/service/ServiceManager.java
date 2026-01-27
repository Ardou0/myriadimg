package com.myriadimg.service;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class ServiceManager {
    private static ServiceManager instance;
    private final List<ThrottlableService> registeredServices = new CopyOnWriteArrayList<>();
    private final StringProperty globalStatus = new SimpleStringProperty("");

    private ServiceManager() {
        // Listen for language changes to update tray status text
        I18nService.getInstance().addLanguageChangeListener(this::updateGlobalStatus);
    }

    public static synchronized ServiceManager getInstance() {
        if (instance == null) {
            instance = new ServiceManager();
        }
        return instance;
    }

    public void registerService(ThrottlableService service) {
        registeredServices.add(service);
        updateGlobalStatus();
    }

    public void unregisterService(ThrottlableService service) {
        registeredServices.remove(service);
        updateGlobalStatus();
    }

    /**
     * Updates the global status by checking all registered services.
     * This method is thread-safe and can be called from any thread.
     * It ensures that the status check and UI update happen on the FX Application Thread.
     */
    public void updateGlobalStatus() {
        Platform.runLater(() -> {
            List<String> activeTasks = new ArrayList<>();
            for (ThrottlableService service : registeredServices) {
                // isRunning() must be called on the FX thread, which is guaranteed by Platform.runLater
                if (service.isRunning()) {
                    activeTasks.add(service.getStatus());
                }
            }

            if (activeTasks.isEmpty()) {
                globalStatus.set(I18nService.getInstance().get("tray.status.idle"));
            } else {
                String status = String.join(", ", activeTasks);
                globalStatus.set(status);
            }
        });
    }

    public StringProperty globalStatusProperty() {
        return globalStatus;
    }
    
    public List<ThrottlableService> getActiveServices() {
        // This method might also need to be called from the FX thread if the UI iterates and calls isRunning().
        // For now, we assume it's used carefully.
        return registeredServices.stream()
                .filter(ThrottlableService::isRunning)
                .collect(Collectors.toList());
    }

    /**
     * Checks if a service of the given type is currently running.
     * @param serviceClass The class of the service to check.
     * @return true if a service of this type is running, false otherwise.
     */
    public boolean isServiceRunning(Class<? extends ThrottlableService> serviceClass) {
        return registeredServices.stream()
                .anyMatch(s -> serviceClass.isInstance(s) && s.isRunning());
    }
    
    /**
     * Stops all running services of the given type.
     * @param serviceClass The class of the service to stop.
     */
    public void stopService(Class<? extends ThrottlableService> serviceClass) {
        List<ThrottlableService> servicesToStop = registeredServices.stream()
                .filter(s -> serviceClass.isInstance(s) && s.isRunning())
                .collect(Collectors.toList());
                
        for (ThrottlableService service : servicesToStop) {
            if (service instanceof IndexingService) {
                ((IndexingService) service).stopService();
            } else if (service instanceof ThumbnailService) {
                ((ThumbnailService) service).stopService();
            } else if (service instanceof javafx.concurrent.Service) {
                ((javafx.concurrent.Service<?>) service).cancel();
            }
        }
    }

    /**
     * Stops all registered services and clears resources.
     */
    public void shutdown() {
        // Cancel should be called on the FX thread for JavaFX services
        Platform.runLater(() -> {
            for (ThrottlableService service : registeredServices) {
                if (service instanceof IndexingService) {
                    ((IndexingService) service).stopService();
                } else if (service instanceof ThumbnailService) {
                    ((ThumbnailService) service).stopService();
                } else if (service instanceof javafx.concurrent.Service) {
                    ((javafx.concurrent.Service<?>) service).cancel();
                }
            }
            registeredServices.clear();
        });
    }
}
