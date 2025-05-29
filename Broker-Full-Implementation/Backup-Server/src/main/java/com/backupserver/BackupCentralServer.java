package com.example.backup;

import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.SocketType;

import java.sql.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;

/**
 * Central Backup Server - Replica of the primary server
 * Runs on Machine 1 as backup for the server on Machine 3
 * Includes health check handling and the same business logic
 */
public class BackupCentralServer {

    private static final ExecutorService pool = Executors.newFixedThreadPool(10);
    private static final int SERVER_PORT = 5556; // Same port as primary server

    public static void main(String[] args) {
        System.out.println("Starting Central BACKUP Server...");
        System.out.println("Location: Machine 1 (Backup)");
        System.out.println("Port: " + SERVER_PORT);

        // Verify database connection before continuing
        if (!verifyDBConnection()) {
            System.err.println("Could not connect to database. Terminating backup server.");
            return;
        }

        try (ZContext context = new ZContext()) {
            // Socket to receive both business requests and health checks
            ZMQ.Socket socket = context.createSocket(SocketType.REP);
            socket.bind("tcp://*:" + SERVER_PORT);

            System.out.println("Backup Server ready and waiting for connections...");
            System.out.println("Responding to health checks and business requests");

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Receive message
                    String message = socket.recvStr(0);
                    
                    if ("HEALTH_CHECK".equals(message)) {
                        // Respond to health check
                        socket.send("PONG", 0);
                        System.out.println("Health check responded");
                    } else {
                        // Process business request
                        System.out.println("Processing request: " + message);
                        
                        // Process in thread pool to avoid blocking
                        String response = processRequest(message);
                        socket.send(response, 0);
                        
                        System.out.println("Response sent: " + response);
                    }
                    
                } catch (Exception e) {
                    System.err.println("Error processing message: " + e.getMessage());
                    e.printStackTrace();
                    
                    // Send error response
                    try {
                        socket.send("Error,Internal server error", 0);
                    } catch (Exception sendError) {
                        System.err.println("Error sending error response: " + sendError.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Critical error in backup server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            pool.shutdown();
            System.out.println("Backup Server stopped");
        }
    }

    private static boolean verifyDBConnection() {
        try (Connection testConn = BackupDBConnection.connect()) {
            if (testConn == null || testConn.isClosed()) {
                System.err.println("Could not connect to database.");
                return false;
            } else {
                System.out.println("Database connection verified successfully.");
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Error connecting to database: " + e.getMessage());
            return false;
        }
    }

    private static String processRequest(String data) {
        try {
            // Expected format: requestId,semester,faculty,program,classroomCount,labCount
            String[] parts = data.split(",");
            if (parts.length != 6) {
                return "Error,Invalid request format. Expected 6 fields.";
            }
            
            String requestId = parts[0];
            String semester = parts[1];
            String faculty = parts[2];
            String program = parts[3];
            int classroomCount = Integer.parseInt(parts[4]);
            int labCount = Integer.parseInt(parts[5]);

            System.out.println("Processing request ID: " + requestId);
            System.out.println("Details: " + faculty + " - " + program + " - Classrooms: " + classroomCount + " - Labs: " + labCount);

            if (!validateData(semester, faculty, program, classroomCount, labCount)) {
                return requestId + ",Error: Invalid data in request!";
            }

            Connection conn = BackupDBConnection.connect();
            if (conn == null) {
                return requestId + ",Error: Could not connect to database";
            }

            int availableClassrooms = countRooms(conn, "Salon", semester, "Disponible");
            int availableLabs = countRooms(conn, "Laboratorio", semester, "Disponible");

            System.out.println("Available resources - Classrooms: " + availableClassrooms + ", Labs: " + availableLabs);

            boolean assignedClassrooms = availableClassrooms >= classroomCount;
            boolean assignedLabs = availableLabs >= labCount;

            if (assignedClassrooms) {
                assignRooms(conn, program, "Salon", classroomCount);
                System.out.println("Classrooms assigned: " + classroomCount);
            }

            if (!assignedLabs && (availableClassrooms - classroomCount) >= (labCount - availableLabs)) {
                assignRooms(conn, program, "Laboratorio", availableLabs);
                assignRooms(conn, program, "Salon", labCount - availableLabs);
                assignedLabs = true;
                System.out.println("Labs assigned with additional classrooms");
            } else if (assignedLabs) {
                assignRooms(conn, program, "Laboratorio", labCount);
                System.out.println("Labs assigned: " + labCount);
            }

            String status;
            if (assignedClassrooms && assignedLabs) {
                status = "Aprobada";
                System.out.println("Request APPROVED for " + program);
            } else {
                System.err.println("ALERT: Not enough rooms for " + program + " in " + semester);
                status = "Denegada";
            }

            insertRequest(conn, semester, faculty, program, classroomCount, labCount, status);
            conn.close();
            
            return requestId + ",Result: " + status;
            
        } catch (Exception e) {
            System.err.println("Error processing request: " + e.getMessage());
            e.printStackTrace();
            return "Error,Error processing request: " + e.getMessage();
        }
    }

    private static boolean validateData(String semester, String faculty, String program, int classroomCount, int labCount) {
        try {
            Connection conn = BackupDBConnection.connect();
            if (conn == null) return false;

            // Validate semester
            if (!semester.equals("2025-10") && !semester.equals("2025-20")) {
                System.err.println("Invalid semester: " + semester);
                conn.close();
                return false;
            }

            // Validate faculty
            String sql = "SELECT id FROM Facultad WHERE nombre = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, faculty);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    System.err.println("Faculty does not exist: " + faculty);
                    conn.close();
                    return false;
                }
            }

            // Validate program
            sql = "SELECT id FROM Programa WHERE nombre = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, program);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    System.err.println("Program does not exist: " + program);
                    conn.close();
                    return false;
                }
            }

            // Validate quantities
            if (classroomCount < 0 || labCount < 0) {
                System.err.println("Invalid quantities - Classrooms: " + classroomCount + ", Labs: " + labCount);
                conn.close();
                return false;
            }

            conn.close();
            return true;

        } catch (Exception e) {
            System.err.println("Error in validation: " + e.getMessage());
            return false;
        }
    }

    private static int countRooms(Connection conn, String type, String semester, String status) throws SQLException {
        String sql = "SELECT COUNT(*) FROM Aulas a WHERE a.tipo = ? AND a.status = ? AND a.semestre = ? AND a.programa_id IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, status);
            ps.setString(3, semester);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void assignRooms(Connection conn, String program, String type, int quantity) throws SQLException {
        int program_id = 0;

        // Step 1: Get program ID
        String program_id_query = "SELECT id FROM Programa WHERE nombre = ?";
        try (PreparedStatement ps = conn.prepareStatement(program_id_query)) {
            ps.setString(1, program);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                program_id = rs.getInt(1);
            }
            rs.close();
        }

        // Step 2: Get available room IDs
        ArrayList<Integer> availableIds = new ArrayList<>();
        String status_query = "SELECT id FROM Aulas WHERE status = 'Disponible' AND tipo = ? LIMIT ?";
        try (PreparedStatement stmt = conn.prepareStatement(status_query)) {
            stmt.setString(1, type);
            stmt.setInt(2, quantity);
            ResultSet rs_status = stmt.executeQuery();
            while (rs_status.next()) {
                availableIds.add(rs_status.getInt("id"));
            }
        }

        // Step 3: Update rooms individually
        String update_query = "UPDATE Aulas SET status = ?, programa_id = ? WHERE id = ?";
        try (PreparedStatement updateStmt = conn.prepareStatement(update_query)) {
            for (int id : availableIds) {
                updateStmt.setString(1, "Ocupado");
                updateStmt.setInt(2, program_id);
                updateStmt.setInt(3, id);
                updateStmt.executeUpdate();
            }
        }
    }

    private static void insertRequest(Connection conn, String semester, String faculty, String program,
                                     int classroomCount, int labCount, String status) throws SQLException {

        String sql = "INSERT INTO Solicitud (semestre, facultad_id, programa_id, cant_salon, cant_lab, status) " +
                     "VALUES (?, " +
                     "(SELECT id FROM Facultad WHERE nombre = ?), " +
                     "(SELECT id FROM Programa WHERE nombre = ?), ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, semester);
            ps.setString(2, faculty);
            ps.setString(3, program);
            ps.setInt(4, classroomCount);
            ps.setInt(5, labCount);
            ps.setString(6, status);
            ps.executeUpdate();
        }
    }
}

/**
 * Database connection class for backup server
 * Must point to the same database as the primary server
 */
class BackupDBConnection {
    // Adjust according to your database configuration
    private static final String DB_URL = "jdbc:mysql://192.168.1.103:3306/DistribuidosDB"; // IP of machine with DB
    private static final String USER = "host1"; 
    private static final String PASSWORD = "12345678"; 

    public static Connection connect() {
        try {
            Connection conn = DriverManager.getConnection(DB_URL, USER, PASSWORD);
            System.out.println("DB connection established (Backup Server)");
            return conn;
        } catch (SQLException e) {
            System.err.println("Error connecting to database (Backup): " + e.getMessage());
            return null;
        }
    }
}
