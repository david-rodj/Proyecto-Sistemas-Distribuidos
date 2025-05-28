package com.departmentschool;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Poller;
import org.zeromq.ZMQ.Socket;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DepartmentSchool {
    private static final String LOG_DIRECTORY = "logs";
    private static Map<String, String> pendingRequests = new HashMap<>();
    
    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: DepartmentSchool <departmentName> <semester> <centralServerIp> <centralServerPort>");
            System.exit(1);
        }
        
        String departmentName = args[0];
        String semester = args[1];
        String centralServerIp = args[2];
        int centralServerPort = Integer.parseInt(args[3]);
        
        System.out.println("Starting Department School: " + departmentName);
        System.out.println("Semester: " + semester);
        System.out.println("Connecting to Central Server at: " + centralServerIp + ":" + centralServerPort);
        
        // Ensure log directory exists
        try {
            Files.createDirectories(Paths.get(LOG_DIRECTORY));
        } catch (IOException e) {
            System.err.println("Failed to create log directory: " + e.getMessage());
        }
        
        try (ZContext context = new ZContext()) {
            // Socket REP for academic programs
            Socket programSocket = context.createSocket(SocketType.REP);
            String programBindAddress = "tcp://*:5555";
            programSocket.bind(programBindAddress);
            System.out.println("Listening for academic programs on " + programBindAddress);
            
            // Socket REQ for communicating with the central server (asynchronous REQ-REP)
            Socket serverSocket = context.createSocket(SocketType.REQ);
            String serverAddress = "tcp://" + centralServerIp + ":" + centralServerPort;
            serverSocket.connect(serverAddress);
            System.out.println("Connected to central server at " + serverAddress);
            
            // Create poller to handle multiple sockets
            Poller poller = context.createPoller(2);
            poller.register(programSocket, Poller.POLLIN);
            poller.register(serverSocket, Poller.POLLIN);
            
            while (!Thread.currentThread().isInterrupted()) {
                poller.poll();
                
                // Handle requests from academic programs
                if (poller.pollin(0)) {
                    String request = programSocket.recvStr();
                    System.out.println("Received from academic program: " + request);
                    
                    String[] parts = request.split(";");
                    // Validate data
                    if (parts.length != 4) {
                        programSocket.send("Error: Invalid request format", 0);
                        continue;
                    }
                    
                    String programName = parts[0];
                    String programSemester = parts[1];
                    int classrooms = 0;
                    int laboratories = 0;
                    
                    try {
                        classrooms = Integer.parseInt(parts[2]);
                        laboratories = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException e) {
                        programSocket.send("Error: Invalid numeric data in request", 0);
                        continue;
                    }
                    
                    // Business logic (e.g., validate ranges)
                    if (classrooms < 7 || classrooms > 10 || laboratories < 2 || laboratories > 4) {
                        programSocket.send("Error: Numbers out of range. Classrooms must be between 7-10 and laboratories between 2-4", 0);
                        continue;
                    }
                    
                    // Generate unique request ID
                    String requestId = UUID.randomUUID().toString();
                    
                    // Store request ID and program name for later correlation
                    pendingRequests.put(requestId, programName);
                    
                    // Send request to central server (REQ-REP asynchronous pattern)
                    String serverMessage = String.join(";", 
                                             requestId,
                                             departmentName, 
                                             programName,
                                             semester, 
                                             String.valueOf(classrooms), 
                                             String.valueOf(laboratories));
                    
                    serverSocket.send(serverMessage, 0);
                    System.out.println("Sent to central server: " + serverMessage);
                    
                    // Immediate response to academic program (initial ACK)
                    programSocket.send("Request received. Processing... (ID: " + requestId + ")", 0);
                    
                    // Log the request
                    logRequest(departmentName, programName, semester, classrooms, laboratories, requestId);
                }
                
                // Handle responses from the central server
                if (poller.pollin(1)) {
                    String response = serverSocket.recvStr();
                    System.out.println("Received from central server: " + response);
                    
                    String[] parts = response.split(";");
                    if (parts.length >= 2) {
                        String requestId = parts[0];
                        String result = parts[1];
                        
                        // Get program name associated with this request
                        String programName = pendingRequests.get(requestId);
                        if (programName != null) {
                            // Store the response for this program
                            storeResponse(departmentName, programName, semester, result);
                            
                            // Remove from pending requests
                            pendingRequests.remove(requestId);
                            
                            System.out.println("✅ Request " + requestId + " completed for program " + 
                                             programName + " with result: " + result);
                        } else {
                            System.err.println("Received response for unknown request ID: " + requestId);
                        }
                    } else {
                        System.err.println("Invalid response format from server: " + response);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Logs a request to a file
     */
    private static void logRequest(String departmentName, String programName, String semester, 
                                  int classrooms, int laboratories, String requestId) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logEntry = String.format("%s - Request ID: %s - Department: %s - Program: %s - Semester: %s - " +
                                       "Classrooms: %d - Laboratories: %d\n",
                                       timestamp, requestId, departmentName, programName, semester, classrooms, laboratories);
        
        String logFilePath = LOG_DIRECTORY + "/requests_" + departmentName.replaceAll("\\s+", "_") + ".log";
        
        try (FileWriter writer = new FileWriter(logFilePath, true)) {
            writer.write(logEntry);
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }
    
    /**
     * Stores the server response for a program in a semester-specific file
     */
    private static void storeResponse(String departmentName, String programName, String semester, String result) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logEntry = String.format("%s - Department: %s - Program: %s - Result: %s\n",
                                       timestamp, departmentName, programName, result);
        
        String responseFilePath = LOG_DIRECTORY + "/" + semester + "_" + 
                                 programName.replaceAll("\\s+", "_") + ".txt";
        
        try (FileWriter writer = new FileWriter(responseFilePath, true)) {
            writer.write(logEntry);
        } catch (IOException e) {
            System.err.println("Error writing response to file: " + e.getMessage());
        }
    }
}
