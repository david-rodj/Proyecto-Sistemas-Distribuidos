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



/**

 * Department School (Faculty) - Modified to connect through HealthCheckManager

 * Architecture: Faculty -> HealthCheckManager -> Central Server (with failover)

 * The faculty is now unaware of server failures and failovers

 */

public class DepartmentSchool {

    private static final String LOG_DIRECTORY = "logs";

    private static Map<String, String> pendingRequests = new HashMap<>();

    

    public static void main(String[] args) {

        if (args.length < 2 || args.length > 3) {

            System.err.println("Usage: DepartmentSchool <departmentName> <semester> [port]");

            System.err.println("Note: HealthCheckManager must be running on localhost:5555");

            System.err.println("Optional port parameter for multiple faculties on same machine (default: 5554)");

            System.exit(1);

        }

        

        String departmentName = args[0];

        String semester = args[1];

        int facultyPort = args.length == 3 ? Integer.parseInt(args[2]) : 5554; // Default port

        String healthManagerIp = "localhost"; // HealthCheckManager runs on same machine

        int healthManagerPort = 5555;         // Standard port for HealthCheckManager

        

        System.out.println("Starting Department School (Faculty): " + departmentName);

        System.out.println("Semester: " + semester);

        System.out.println("Connecting to HealthCheck Manager at: " + healthManagerIp + ":" + healthManagerPort);

        System.out.println("Note: Server failover is handled transparently by HealthCheck Manager");

        

        // Ensure log directory exists

        try {

            Files.createDirectories(Paths.get(LOG_DIRECTORY));

        } catch (IOException e) {

            System.err.println("Failed to create log directory: " + e.getMessage());

        }

        

        try (ZContext context = new ZContext()) {

            // Socket REP for academic programs - configurable port for multiple faculties

            Socket programSocket = context.createSocket(SocketType.REP);

            String programBindAddress = "tcp://*:" + facultyPort;

            programSocket.bind(programBindAddress);

            System.out.println("Listening for academic programs on " + programBindAddress);

            

            // Socket for communicating with HealthCheck Manager (REQ pattern)

            Socket healthManagerSocket = context.createSocket(SocketType.REQ);

            String healthManagerAddress = "tcp://" + healthManagerIp + ":" + healthManagerPort;

            healthManagerSocket.connect(healthManagerAddress);

            healthManagerSocket.setReceiveTimeOut(10000); // 10 seconds timeout

            healthManagerSocket.setSendTimeOut(5000);     // 5 seconds timeout

            System.out.println("Connected to HealthCheck Manager at " + healthManagerAddress);

            

            // Create poller to handle multiple sockets

            Poller poller = context.createPoller(2);

            poller.register(programSocket, Poller.POLLIN);

                        

            boolean waitingForResponse = false;

            String currentRequestId = null;

            

            while (!Thread.currentThread().isInterrupted()) {

                poller.poll(1000); // Poll every second

                

                // Handle requests from academic programs

                if (poller.pollin(0) && !waitingForResponse) {

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

                    

                    // Business logic validation

                    if (classrooms < 7 || classrooms > 10 || laboratories < 2 || laboratories > 4) {

                        programSocket.send("Error: Numbers out of range. Classrooms must be between 7-10 and laboratories between 2-4", 0);

                        continue;

                    }

                    

                    // Generate unique request ID

                    String requestId = UUID.randomUUID().toString();

                    currentRequestId = requestId;



                    // Store request ID and program name for later correlation

                    pendingRequests.put(requestId, programName);



                    // Send request to HealthCheck Manager (same format as before)

                    // Format: requestId,semester,faculty,program,classrooms,labs

                    String serverMessage = String.join(",", 

                                         requestId,                    // requestId for correlation

                                         semester,                     // semester  

                                         departmentName,               // faculty

                                         programName,                  // program

                                         String.valueOf(classrooms),   // classroom count

                                         String.valueOf(laboratories)); // lab count



                    try {

                        healthManagerSocket.send(serverMessage, 0);

                        System.out.println("Sent to HealthCheck Manager: " + serverMessage);

                        waitingForResponse = true;

                        

                        // Immediate response to academic program (initial ACK)

                        programSocket.send("Request received. Processing... (ID: " + requestId + ")", 0);



                        // Log the request

                        logRequest(departmentName, programName, semester, classrooms, laboratories, requestId);

                        

                    } catch (Exception e) {

                        System.err.println("Error sending to HealthCheck Manager: " + e.getMessage());

                        programSocket.send("Error: Could not process request at this time", 0);

                        pendingRequests.remove(requestId);

                        waitingForResponse = false;

                        currentRequestId = null;

                    }

                }

                

                // Handle responses from HealthCheck Manager

                if (poller.pollin(1) && waitingForResponse) {

                    try {

                        String response = healthManagerSocket.recvStr();

                        System.out.println("Received from HealthCheck Manager: " + response);

                        

                        // Parse response (format: requestId,Result: status)

                        String[] parts = response.split(",", 2);

                        if (parts.length >= 2) {

                            String requestId = parts[0];

                            String result = parts[1];

                            

                            // Verify this is the response we're waiting for

                            if (requestId.equals(currentRequestId)) {

                                // Get program name associated with this request

                                String programName = pendingRequests.get(requestId);

                                if (programName != null) {

                                    // Store the response for this program

                                    storeResponse(departmentName, programName, semester, result);

                                    

                                    // Remove from pending requests

                                    pendingRequests.remove(requestId);

                                    

                                    System.out.println("Request completed for program: " + programName + " - Result: " + result);

                                } else {

                                    System.err.println("Received response for unknown request ID: " + requestId);

                                }

                                

                                // Reset waiting state

                                waitingForResponse = false;

                                currentRequestId = null;

                            } else {

                                System.err.println("Received response for different request. Expected: " + currentRequestId + ", Got: " + requestId);

                            }

                        } else {

                            System.err.println("Invalid response format from HealthCheck Manager: " + response);

                            waitingForResponse = false;

                            currentRequestId = null;

                        }

                        

                    } catch (Exception e) {

                        System.err.println("Error receiving from HealthCheck Manager: " + e.getMessage());

                        waitingForResponse = false;

                        currentRequestId = null;

                    }

                }

                

                // Check for timeout (optional)

                if (waitingForResponse && currentRequestId != null) {

                    // You could implement timeout logic here if needed

                    // For now, we rely on ZeroMQ timeouts

                }

            }

        } catch (Exception e) {

            System.err.println("Error in Department School: " + e.getMessage());

            e.printStackTrace();

        }

        

        System.out.println("Department School " + departmentName + " stopped");

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

        

        System.out.println("Response stored in: " + responseFilePath);

    }

}


