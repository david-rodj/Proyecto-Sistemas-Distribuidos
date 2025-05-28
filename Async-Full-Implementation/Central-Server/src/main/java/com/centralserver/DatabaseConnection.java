package com.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Database connection utility class for the Central Server
 * Manages connections to the MySQL database
 */
public class DatabaseConnection {

    private static final String DB_URL = "jdbc:mysql://localhost:3306/DistribuidosDB";
    private static final String USER = "host1"; 
    private static final String PASSWORD = "12345678"; 
    
    // Connection pool settings
    private static final String CONNECTION_PROPERTIES = 
        "?useSSL=false" +
        "&serverTimezone=UTC" +
        "&allowPublicKeyRetrieval=true" +
        "&useUnicode=true" +
        "&characterEncoding=UTF-8" +
        "&autoReconnect=true" +
        "&maxReconnects=3" +
        "&initialTimeout=2";

    /**
     * Establishes a connection to the database
     * @return Connection object or null if connection fails
     */
    public static Connection connect() {
        try {
            // Load MySQL JDBC driver explicitly (optional for newer versions)
            Class.forName("com.mysql.cj.jdbc.Driver");
            
            String fullUrl = DB_URL + CONNECTION_PROPERTIES;
            Connection conn = DriverManager.getConnection(fullUrl, USER, PASSWORD);
            
            // Set connection properties
            conn.setAutoCommit(true);
            
            return conn;
            
        } catch (ClassNotFoundException e) {
            System.err.println("MySQL JDBC Driver not found: " + e.getMessage());
            return null;
        } catch (SQLException e) {
            System.err.println("Error connecting to database: " + e.getMessage());
            System.err.println("SQL State: " + e.getSQLState());
            System.err.println("Error Code: " + e.getErrorCode());
            return null;
        }
    }
    
    /**
     * Tests the database connection
     * @return true if connection is successful, false otherwise
     */
    public static boolean testConnection() {
        try (Connection conn = connect()) {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            System.err.println("Error testing database connection: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets database connection information
     * @return String with connection details
     */
    public static String getConnectionInfo() {
        return "Database URL: " + DB_URL + "\nUser: " + USER;
    }
}
