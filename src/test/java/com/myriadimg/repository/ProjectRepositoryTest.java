package com.myriadimg.repository;

import com.myriadimg.model.Project;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepositoryTest {

    private ProjectRepository repository;
    private Connection connection;

    @BeforeEach
    void setUp() {
        // Configure DatabaseManager to use an in-memory database for the entire test
        DatabaseManager.setConnectionUrl("jdbc:sqlite::memory:");
        
        // Get a single connection that will be used for this test method
        connection = DatabaseManager.connect();
        assertNotNull(connection, "Database connection should not be null");

        // Initialize the schema on this connection
        try (Statement stmt = connection.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS projects (\n"
                    + " id integer PRIMARY KEY,\n"
                    + " name text NOT NULL,\n"
                    + " path text NOT NULL UNIQUE,\n"
                    + " last_opened text,\n"
                    + " display_order integer DEFAULT 0\n"
                    + ");";
            stmt.execute(sql);
            // Clean the table to ensure a fresh state
            stmt.execute("DELETE FROM projects");
        } catch (SQLException e) {
            fail("Failed to set up in-memory database: " + e.getMessage());
        }
        
        // Pass the connection to the repository (we might need to add a constructor for this)
        // For now, let's assume ProjectRepository uses DatabaseManager.connect() internally
        // and since we've set the URL, it will get the same in-memory DB (if it's named, not anonymous)
        // Let's switch to a named in-memory DB to ensure connection sharing.
        DatabaseManager.setConnectionUrl("jdbc:sqlite:file:testdb?mode=memory&cache=shared");
        
        // Re-run setup with the named in-memory DB
        try {
            if (connection != null) connection.close();
            connection = DatabaseManager.connect();
            assertNotNull(connection);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS projects"); // Drop first to be sure
                String sql = "CREATE TABLE projects (...);"; // Re-create schema
                 sql = "CREATE TABLE IF NOT EXISTS projects (\n"
                        + " id integer PRIMARY KEY,\n"
                        + " name text NOT NULL,\n"
                        + " path text NOT NULL UNIQUE,\n"
                        + " last_opened text,\n"
                        + " display_order integer DEFAULT 0\n"
                        + ");";
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            fail("DB setup failed: " + e.getMessage());
        }

        repository = new ProjectRepository();
    }

    @AfterEach
    void tearDown() throws SQLException {
        // Close the connection and reset the URL
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        DatabaseManager.setConnectionUrl(null);
    }

    @Test
    void testSaveAndFindAll() {
        Project project = new Project("Holiday 2023", "/path/to/holiday");
        repository.save(project);

        List<Project> projects = repository.findAll();
        assertEquals(1, projects.size(), "Should have 1 project");
        
        Project savedProject = projects.get(0);
        assertEquals("Holiday 2023", savedProject.getName());
        assertEquals("/path/to/holiday", savedProject.getPath());
    }

    @Test
    void testUpdateLastOpened() {
        Project project = new Project("Work", "/path/to/work");
        repository.save(project);

        // Simulate opening the project later
        LocalDateTime newDate = LocalDateTime.now().plusDays(1);
        project.setLastOpened(newDate);
        
        // Save again should update because of ON CONFLICT logic in save()
        repository.save(project);

        List<Project> projects = repository.findAll();
        assertEquals(1, projects.size());
        
        // Note: LocalDateTime precision might be lost in DB string conversion, 
        // so we might need to be careful with exact equality. 
        // But let's check if it's roughly the same or parsed back correctly.
        assertEquals(newDate.toString(), projects.get(0).getLastOpened().toString());
    }

    @Test
    void testDelete() {
        Project project = new Project("Temp", "/path/to/temp");
        repository.save(project);
        
        List<Project> projectsBefore = repository.findAll();
        assertEquals(1, projectsBefore.size());
        
        // We need the ID to delete, which is assigned by DB. 
        // The object returned by findAll has the ID.
        Project projectToDelete = projectsBefore.get(0);
        
        repository.delete(projectToDelete);
        
        List<Project> projectsAfter = repository.findAll();
        assertTrue(projectsAfter.isEmpty(), "Project should be deleted");
    }

    @Test
    void testSaveDuplicatePath() {
        Project p1 = new Project("Project A", "/common/path");
        repository.save(p1);
        
        Project p2 = new Project("Project B", "/common/path");
        // This should update the existing record (ON CONFLICT DO UPDATE)
        repository.save(p2);
        
        List<Project> projects = repository.findAll();
        assertEquals(1, projects.size(), "Should still have only 1 project due to unique path constraint");
        assertEquals("Project B", projects.get(0).getName(), "Name should be updated to Project B");
    }
}
