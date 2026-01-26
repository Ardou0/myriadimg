package com.myriadimg.repository;

import com.myriadimg.model.Project;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ProjectRepository {

    public void save(Project project) {
        String sql = "INSERT INTO projects(name, path, last_opened, display_order) VALUES(?,?,?,?) " +
                     "ON CONFLICT(path) DO UPDATE SET last_opened = excluded.last_opened, name = excluded.name";

        Connection conn = DatabaseManager.connect();
        if (conn == null) return;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, project.getName());
            pstmt.setString(2, project.getPath());
            pstmt.setString(3, project.getLastOpened().toString());
            pstmt.setInt(4, project.getDisplayOrder());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving project: " + e.getMessage());
        } finally {
            closeConnection(conn);
        }
    }

    public void update(Project project) {
        String sql = "UPDATE projects SET name = ?, path = ? WHERE id = ?";
        Connection conn = DatabaseManager.connect();
        if (conn == null) return;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, project.getName());
            pstmt.setString(2, project.getPath());
            pstmt.setInt(3, project.getId());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error updating project: " + e.getMessage());
        } finally {
            closeConnection(conn);
        }
    }

    public void delete(Project project) {
        String sql = "DELETE FROM projects WHERE id = ?";
        Connection conn = DatabaseManager.connect();
        if (conn == null) return;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, project.getId());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error deleting project: " + e.getMessage());
        } finally {
            closeConnection(conn);
        }
    }

    public void updateOrder(List<Project> projects) {
        String sql = "UPDATE projects SET display_order = ? WHERE id = ?";
        Connection conn = DatabaseManager.connect();
        if (conn == null) return;

        try {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < projects.size(); i++) {
                    Project p = projects.get(i);
                    pstmt.setInt(1, i);
                    pstmt.setInt(2, p.getId());
                    pstmt.addBatch();
                    p.setDisplayOrder(i);
                }
                pstmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Error updating order: " + e.getMessage());
        } finally {
            closeConnection(conn);
        }
    }

    public List<Project> findAll() {
        String sql = "SELECT id, name, path, last_opened, display_order FROM projects ORDER BY display_order ASC, last_opened DESC";
        List<Project> projects = new ArrayList<>();

        Connection conn = DatabaseManager.connect();
        if (conn == null) return projects;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                projects.add(new Project(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("path"),
                        LocalDateTime.parse(rs.getString("last_opened")),
                        rs.getInt("display_order")
                ));
            }
        } catch (SQLException e) {
            System.err.println("Error loading projects: " + e.getMessage());
        } finally {
            closeConnection(conn);
        }
        return projects;
    }

    private void closeConnection(Connection conn) {
        try {
            if (conn != null) conn.close();
        } catch (SQLException e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }
}
