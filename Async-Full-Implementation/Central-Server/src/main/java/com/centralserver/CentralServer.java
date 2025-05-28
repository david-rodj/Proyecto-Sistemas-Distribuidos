package com.example;

import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.SocketType;

import java.sql.*;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central Server implementing asynchronous REQ-REP pattern using ROUTER
 * to handle multiple departments concurrently for classroom assignment
 */
public class CentralServer {

    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5557;
        
        System.out.println("Starting Asynchronous Central Server on port " + port + "...");

        // Verify database connection before continuing
        try (Connection testConn = DatabaseConnection.connect()) {
            if (testConn == null || testConn.isClosed()) {
                System.err.println("Could not connect to database. Terminating server.");
                return;
            } else {
                System.out.println("Database connection verified successfully.");
            }
        } catch (SQLException e) {
            System.err.println("Error connecting to database: " + e.getMessage());
            return;
        }

        try (ZContext context = new ZContext()) {
            // ROUTER socket to handle multiple REQ clients asynchronously
            ZMQ.Socket router = context.createSocket(SocketType.ROUTER);
            router.bind("tcp://*:" + port);
            
            System.out.println("ROUTER server listening on port " + port);
            System.out.println("Waiting for requests from departments...");

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Receive message from client (department)
                    // ROUTER format: [identity][empty][message]
                    byte[] identity = router.recv(0);
                    byte[] empty = router.recv(0); // empty frame from REQ
                    String message = router.recvStr(0);
                    
                    String clientId = new String(identity, ZMQ.CHARSET);
                    System.out.println("Received request from client " + clientId + ": " + message);
                    
                    // Process request asynchronously
                    threadPool.execute(() -> {
                        try {
                            String response = processRequest(message);
                            
                            // Send response back to specific client
                            synchronized (router) {
                                router.send(identity, ZMQ.SNDMORE);
                                router.send(empty, ZMQ.SNDMORE);
                                router.send(response, 0);
                            }
                            
                            System.out.println("Response sent to client " + clientId + ": " + response);
                            
                        } catch (Exception e) {
                            System.err.println("Error processing request from client " + clientId + ": " + e.getMessage());
                            e.printStackTrace();
                            
                            // Send error response
                            synchronized (router) {
                                router.send(identity, ZMQ.SNDMORE);
                                router.send(empty, ZMQ.SNDMORE);
                                router.send("Error processing request: " + e.getMessage(), 0);
                            }
                        }
                    });
                    
                } catch (Exception e) {
                    System.err.println("Error receiving message: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
        } catch (Exception e) {
            System.err.println("General server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            threadPool.shutdown();
        }
        
        System.out.println("Central Server terminated.");
    }

    private static String processRequest(String data) {
        try {
            // Validate data format
            if (!validateRequestData(data)) {
                throw new Exception("Invalid request data!");
            }
            
            String[] parts = data.split(";");
            String requestId = parts[0];
            String department = parts[1];
            String program = parts[2];
            String semester = parts[3];
            int classroomsNeeded = Integer.parseInt(parts[4]);
            int laboratoriesNeeded = Integer.parseInt(parts[5]);

            System.out.println("Processing request: RequestID=" + requestId + 
                             ", Department=" + department + ", Program=" + program + 
                             ", Semester=" + semester + ", Classrooms=" + classroomsNeeded + 
                             ", Laboratories=" + laboratoriesNeeded);

            Connection conn = DatabaseConnection.connect();
            if (conn == null) {
                throw new Exception("Could not connect to database");
            }

            // Count available resources
            int availableClassrooms = countRooms(conn, "Classroom", semester, "Available");
            int availableLaboratories = countRooms(conn, "Laboratory", semester, "Available");

            System.out.println("Available resources - Classrooms: " + availableClassrooms + 
                             ", Laboratories: " + availableLaboratories);

            boolean classroomsAssigned = availableClassrooms >= classroomsNeeded;
            boolean laboratoriesAssigned = availableLaboratories >= laboratoriesNeeded;

            // Assign classrooms if available
            if (classroomsAssigned) {
                assignRooms(conn, program, "Classroom", classroomsNeeded, semester);
            }

            // Logic for laboratories (can use classrooms as substitute)
            if (!laboratoriesAssigned && (availableClassrooms - classroomsNeeded) >= (laboratoriesNeeded - availableLaboratories)) {
                // Assign all available laboratories
                if (availableLaboratories > 0) {
                    assignRooms(conn, program, "Laboratory", availableLaboratories, semester);
                }
                // Complete with classrooms
                int classroomsAsLabs = laboratoriesNeeded - availableLaboratories;
                if (classroomsAsLabs > 0) {
                    assignRooms(conn, program, "Classroom", classroomsAsLabs, semester);
                }
                laboratoriesAssigned = true;
            } else if (laboratoriesAssigned) {
                assignRooms(conn, program, "Laboratory", laboratoriesNeeded, semester);
            }

            String status;
            if (classroomsAssigned && laboratoriesAssigned) {
                status = "Approved";
                System.out.println("✅ Request APPROVED for " + program + " in " + semester);
            } else {
                status = "Denied";
                System.out.println("❌ Request DENIED for " + program + " in " + semester + 
                                 " - Insufficient resources");
            }

            // Insert request record
            insertRequest(conn, semester, department, program, classroomsNeeded, laboratoriesNeeded, status);
            conn.close();
            
            return requestId + ";" + status;
            
        } catch (Exception e) {
            e.printStackTrace();
            return "Error processing request: " + e.getMessage();
        }
    }

    private static int countRooms(Connection conn, String type, String semester, String status) throws SQLException {
        String sql = "SELECT COUNT(*) FROM Rooms WHERE type = ? AND status = ? AND semester = ? AND program_id IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, status);
            ps.setString(3, semester);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void assignRooms(Connection conn, String program, String type, int quantity, String semester) throws SQLException {
        // Get program ID
        int programId = 0;
        String programQuery = "SELECT id FROM Program WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(programQuery)) {
            ps.setString(1, program);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                programId = rs.getInt(1);
            } else {
                throw new SQLException("Program not found: " + program);
            }
        }

        // Get available room IDs
        ArrayList<Integer> availableIds = new ArrayList<>();
        String selectQuery = "SELECT id FROM Rooms WHERE status = 'Available' AND type = ? AND semester = ? AND program_id IS NULL LIMIT ?";
        try (PreparedStatement stmt = conn.prepareStatement(selectQuery)) {
            stmt.setString(1, type);
            stmt.setString(2, semester);
            stmt.setInt(3, quantity);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                availableIds.add(rs.getInt("id"));
            }
        }

        if (availableIds.size() < quantity) {
            throw new SQLException("Not enough available rooms of type " + type);
        }

        // Update rooms individually
        String updateQuery = "UPDATE Rooms SET status = 'Occupied', program_id = ? WHERE id = ?";
        try (PreparedStatement updateStmt = conn.prepareStatement(updateQuery)) {
            for (int id : availableIds) {
                updateStmt.setInt(1, programId);
                updateStmt.setInt(2, id);
                updateStmt.executeUpdate();
            }
        }
        
        System.out.println("Assigned " + availableIds.size() + " rooms of type " + type + " to program " + program);
    }

    private static void insertRequest(Connection conn, String semester, String department, String program,
                                    int classrooms, int laboratories, String status) throws SQLException {
        String sql = "INSERT INTO Request (semester, department_id, program_id, classroom_count, laboratory_count, status) " +
                     "VALUES (?, " +
                     "(SELECT id FROM Department WHERE name = ?), " +
                     "(SELECT id FROM Program WHERE name = ?), ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, semester);
            ps.setString(2, department);
            ps.setString(3, program);
            ps.setInt(4, classrooms);
            ps.setInt(5, laboratories);
            ps.setString(6, status);
            ps.executeUpdate();
        }
    }

    private static boolean validateRequestData(String data) {
        try {
            String[] parts = data.split(";");
            if (parts.length != 6) {
                System.err.println("Incorrect data format. Expected 6 parts, received: " + parts.length);
                return false;
            }

            Connection conn = DatabaseConnection.connect();
            if (conn == null) {
                return false;
            }

            String requestId = parts[0];
            String department = parts[1];
            String program = parts[2];
            String semester = parts[3];
            
            // Validate semester
            if (!semester.equals("2025-10") && !semester.equals("2025-20")) {
                System.err.println("Invalid semester: " + semester);
                conn.close();
                return false;
            }

            // Validate department
            String sqlDepartment = "SELECT id FROM Department WHERE name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlDepartment)) {
                ps.setString(1, department);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    System.err.println("Department does not exist: " + department);
                    conn.close();
                    return false;
                }
            }

            // Validate program
            String sqlProgram = "SELECT id FROM Program WHERE name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlProgram)) {
                ps.setString(1, program);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    System.err.println("Program does not exist: " + program);
                    conn.close();
                    return false;
                }
            }

            // Validate quantities
            int classrooms = Integer.parseInt(parts[4]);
            int laboratories = Integer.parseInt(parts[5]);

            if (classrooms < 0 || laboratories < 0) {
                System.err.println("Invalid quantities - Classrooms: " + classrooms + ", Laboratories: " + laboratories);
                conn.close();
                return false;
            }

            conn.close();
            return true;

        } catch (Exception e) {
            System.err.println("Error validating data: " + e.getMessage());
            return false;
        }
    }
}
