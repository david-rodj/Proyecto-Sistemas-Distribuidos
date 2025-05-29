package com.healthcheck;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Poller;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Health Check Manager adapted for the faculty project
 * Acts as a transparent intermediary between faculties and central servers
 * Automatically handles failover between machines without notifying faculties
 */
public class HealthCheckManager {
    
    // Server configuration (adjust IPs according to your infrastructure)
    private static final String PRIMARY_SERVER = "tcp://10.43.103.67:5556";   // Primary server (Machine 3)
    private static final String BACKUP_SERVER = "tcp://10.43.103.241:5556";    // Backup server (Machine 1)
    private static final int HEALTHCHECK_INTERVAL = 5; // seconds
    private static final int TIMEOUT_MS = 3000; // 3 seconds timeout
    private static final int FACULTY_PORT = 5555; // Port where faculties connect
    
    private final AtomicBoolean primaryServerActive = new AtomicBoolean(true);
    private final AtomicReference<String> currentServer = new AtomicReference<>(PRIMARY_SERVER);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // For tracking requests in progress
    private final Map<String, String> pendingRequests = new HashMap<>();
    
    private ZContext context;
    private ZMQ.Socket facultySocket;  // Socket for faculties (ROUTER)
    private ZMQ.Socket serverSocket;   // Socket for current server (DEALER)
    
    public static void main(String[] args) {
        System.out.println("Health Check Manager for Faculty System");
        System.out.println("Primary Server: " + PRIMARY_SERVER);
        System.out.println("Backup Server: " + BACKUP_SERVER);
        
        HealthCheckManager manager = new HealthCheckManager();
        
        // Shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Health Check Manager...");
            manager.stop();
        }));
        
        manager.start();
    }
    
    public void start() {
        System.out.println("Starting Health Check Manager...");
        
        context = new ZContext();
        
        // Socket to receive requests from faculties
        facultySocket = context.createSocket(SocketType.ROUTER);
        facultySocket.bind("tcp://*:" + FACULTY_PORT);
        System.out.println("Listening for faculties on port " + FACULTY_PORT);
        
        // Initialize connection with active server
        connectToServer(currentServer.get());
        
        // Start periodic health check
        startHealthCheck();
        
        // Start message proxy
        startMessageProxy();
        
        System.out.println("Health Check Manager started successfully");
        System.out.println("Faculties can connect to this port: " + FACULTY_PORT);
    }
    
    private void connectToServer(String serverAddress) {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
            
            serverSocket = context.createSocket(SocketType.DEALER);
            serverSocket.setReceiveTimeOut(5000); // 5 seconds timeout for receives
            serverSocket.setSendTimeOut(5000);    // 5 seconds timeout for sends
            serverSocket.connect(serverAddress);
            System.out.println("Connected to server: " + serverAddress);
            
        } catch (Exception e) {
            System.err.println("Error connecting to server " + serverAddress + ": " + e.getMessage());
        }
    }
    
    private void startHealthCheck() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkServerHealth();
            } catch (Exception e) {
                System.err.println("Error in health check: " + e.getMessage());
            }
        }, HEALTHCHECK_INTERVAL, HEALTHCHECK_INTERVAL, TimeUnit.SECONDS);
        
        System.out.println("Health check started (every " + HEALTHCHECK_INTERVAL + " seconds)");
    }
    
    private void checkServerHealth() {
        String targetServer = currentServer.get();
        
        try (ZContext healthContext = new ZContext()) {
            ZMQ.Socket healthSocket = healthContext.createSocket(SocketType.REQ);
            healthSocket.setReceiveTimeOut(TIMEOUT_MS);
            healthSocket.setSendTimeOut(TIMEOUT_MS);
            healthSocket.connect(targetServer);
            
            // Send health check ping
            healthSocket.send("HEALTH_CHECK", 0);
            
            // Try to receive response
            String response = healthSocket.recvStr(0);
            
            if ("PONG".equals(response)) {
                handleServerHealthy(targetServer);
            } else {
                System.err.println("Unexpected response from server: " + response);
                handleServerUnhealthy(targetServer);
            }
            
        } catch (Exception e) {
            System.err.println("Health check failed for " + targetServer + ": " + e.getMessage());
            handleServerFailure();
        }
    }
    
    private void handleServerHealthy(String server) {
        if (server.equals(PRIMARY_SERVER) && !primaryServerActive.get()) {
            // Primary server recovered - perform failback
            System.out.println("Primary server recovered, performing failback...");
            performFailback();
        }
        // If current server is healthy, all good
    }
    
    private void handleServerUnhealthy(String server) {
        if (server.equals(PRIMARY_SERVER) && primaryServerActive.get()) {
            // Primary server failed - switch to backup
            System.err.println("Primary server failed, switching to backup...");
            performFailover();
        } else if (server.equals(BACKUP_SERVER) && !primaryServerActive.get()) {
            // Backup server also failed
            System.err.println("CRITICAL: Backup server not responding!");
            System.err.println("CRITICAL: No servers available - system cannot process requests");
            // Here you could implement additional alerts or critical logs
        }
    }
    
    private void handleServerFailure() {
        if (primaryServerActive.get()) {
            System.err.println("Primary server not responding, switching to backup...");
            performFailover();
        } else {
            System.err.println("Backup server not responding!");
            System.err.println("System without available servers");
        }
    }
    
    private synchronized void performFailover() {
        primaryServerActive.set(false);
        currentServer.set(BACKUP_SERVER);
        connectToServer(BACKUP_SERVER);
        System.out.println("Failover completed - using backup server at " + BACKUP_SERVER);
    }
    
    private synchronized void performFailback() {
        primaryServerActive.set(true);
        currentServer.set(PRIMARY_SERVER);
        connectToServer(PRIMARY_SERVER);
        System.out.println("Failback completed - using primary server at " + PRIMARY_SERVER);
    }
    
    private void startMessageProxy() {
        System.out.println("Starting message proxy...");
        
        Thread proxyThread = new Thread(() -> {
            Poller poller = context.createPoller(2);
            poller.register(facultySocket, Poller.POLLIN);
            poller.register(serverSocket, Poller.POLLIN);
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    poller.poll(1000); // Poll every second
                    
                    // Message from faculty -> server
                    if (poller.pollin(0)) {
                        forwardFacultyToServer();
                    }
                    
                    // Response from server -> faculty
                    if (poller.pollin(1)) {
                        forwardServerToFaculty();
                    }
                    
                } catch (Exception e) {
                    System.err.println("Error in proxy: " + e.getMessage());
                    // In case of error, try to reconnect to server
                    reconnectToCurrentServer();
                }
            }
        });
        
        proxyThread.setDaemon(false);
        proxyThread.setName("MessageProxyThread");
        proxyThread.start();
        
        System.out.println("Message proxy started");
    }
    
    private void forwardFacultyToServer() {
        try {
            // Receive message from faculty (ROUTER receives identity frame)
            byte[] facultyId = facultySocket.recv(0);
            byte[] empty = facultySocket.recv(0);  // Empty frame
            byte[] request = facultySocket.recv(0);
            
            String requestStr = new String(request, ZMQ.CHARSET);
            System.out.println("Forwarding request from faculty to server " + currentServer.get());
            System.out.println("Content: " + requestStr);
            
            // Save faculty mapping for response
            String facultyIdStr = new String(facultyId, ZMQ.CHARSET);
            
            // Extract requestId from request for tracking
            String[] parts = requestStr.split(",");
            if (parts.length > 0) {
                String requestId = parts[0];
                pendingRequests.put(requestId, facultyIdStr);
            }
            
            // Forward to current server (DEALER sends direct)
            serverSocket.send(request, 0);
            
        } catch (Exception e) {
            System.err.println("Error forwarding to server: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void forwardServerToFaculty() {
        try {
            // Receive response from server (DEALER receives direct)
            byte[] response = serverSocket.recv(0);
            String responseStr = new String(response, ZMQ.CHARSET);
            
            System.out.println("Forwarding response from server to faculty");
            System.out.println("Response: " + responseStr);
            
            // Extract requestId to find correct faculty
            String[] parts = responseStr.split(",");
            if (parts.length > 0) {
                String requestId = parts[0];
                String facultyId = pendingRequests.remove(requestId);
                
                if (facultyId != null) {
                    // Forward to specific faculty (ROUTER needs identity frame)
                    facultySocket.send(facultyId.getBytes(ZMQ.CHARSET), ZMQ.SNDMORE);
                    facultySocket.send("", ZMQ.SNDMORE);  // Empty frame
                    facultySocket.send(response, 0);
                } else {
                    System.err.println("Faculty not found for requestId: " + requestId);
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error forwarding to faculty: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void reconnectToCurrentServer() {
        try {
            System.out.println("Reconnecting to current server...");
            Thread.sleep(2000); // Wait 2 seconds before reconnecting
            connectToServer(currentServer.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Reconnection interrupted");
        }
    }
    
    public void stop() {
        System.out.println("Stopping Health Check Manager...");
        
        // Stop scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Close ZeroMQ context
        if (context != null) {
            context.close();
        }
        
        System.out.println("Health Check Manager stopped successfully");
    }
}
