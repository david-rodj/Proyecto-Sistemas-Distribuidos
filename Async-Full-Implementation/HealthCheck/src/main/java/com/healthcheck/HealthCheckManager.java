package com.healthcheck;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Poller;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class HealthCheckManager {

    private static final String PRIMARY_SERVER = "tcp://10.43.103.67:5556";
    private static final String BACKUP_SERVER = "tcp://10.43.96.42:5556";
    private static final String PRIMARY_HEALTH = "tcp://10.43.103.67:6000";
    private static final String BACKUP_HEALTH = "tcp://10.43.96.42:6000";

    private static final int HEALTHCHECK_INTERVAL = 3; // seconds
    private static final int TIMEOUT_MS = 2000;
    private static final int DEPARTMENT_PORT = 5555;
    private static final int MAX_RETRIES = 3;
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    private final AtomicBoolean primaryServerActive = new AtomicBoolean(true);
    private final AtomicBoolean backupServerActive = new AtomicBoolean(true);
    private final ExecutorService requestProcessor = Executors.newFixedThreadPool(10);
    private final ScheduledExecutorService healthCheckScheduler = Executors.newScheduledThreadPool(2);
    
    // Circuit breaker state
    private int primaryFailureCount = 0;
    private int backupFailureCount = 0;
    private final Object failureCountLock = new Object();
    
    // Request tracking for async responses
    private final Map<String, RequestContext> pendingRequests = new ConcurrentHashMap<>();
    
    private static class RequestContext {
        final byte[] clientIdentity;
        final long timestamp;
        final String requestId;
        final CompletableFuture<String> responseFuture;
        
        RequestContext(byte[] clientIdentity, String requestId) {
            this.clientIdentity = clientIdentity.clone();
            this.timestamp = System.currentTimeMillis();
            this.requestId = requestId;
            this.responseFuture = new CompletableFuture<>();
        }
        
        boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - timestamp > timeoutMs;
        }
    }

    public static void main(String[] args) {
        new HealthCheckManager().start();
    }

    public void start() {
        System.out.println("🚀 Iniciando HealthCheck Manager Asíncrono...");
        
        try (ZContext context = new ZContext()) {
            // Socket para recibir requests de DepartmentSchool (ROUTER)
            ZMQ.Socket frontend = context.createSocket(SocketType.ROUTER);
            frontend.bind("tcp://*:" + DEPARTMENT_PORT);
            System.out.println("📡 Frontend ROUTER escuchando en puerto " + DEPARTMENT_PORT);

            // Sockets REQ para comunicación con servidores backend
            ZMQ.Socket primaryReq = context.createSocket(SocketType.REQ);
            ZMQ.Socket backupReq = context.createSocket(SocketType.REQ);
            
            // Configurar timeouts para los sockets REQ
            primaryReq.setReceiveTimeOut(TIMEOUT_MS);
            backupReq.setReceiveTimeOut(TIMEOUT_MS);
            
            // Conectar a los servidores
            primaryReq.connect(PRIMARY_SERVER);
            backupReq.connect(BACKUP_SERVER);
            System.out.println("🔗 Conectado a servidores PRIMARY y BACKUP");

            // Configurar polling solo para frontend (los REQ se manejan síncronamente en threads)
            Poller poller = context.createPoller(1);
            poller.register(frontend, Poller.POLLIN);

            // Iniciar health checks periódicos
            startHealthChecks(context);
            
            // Iniciar limpieza de requests expirados
            startRequestCleanup();

            System.out.println("✅ Sistema listo para procesar requests...");

            // Main event loop
            while (!Thread.currentThread().isInterrupted()) {
                if (poller.poll(100) > 0) {
                    // Procesar requests entrantes del frontend
                    if (poller.pollin(0)) {
                        handleIncomingRequest(frontend, primaryReq, backupReq);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Error en HealthCheck Manager: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private void handleIncomingRequest(ZMQ.Socket frontend, ZMQ.Socket primaryReq, ZMQ.Socket backupReq) {
        try {
            // Recibir mensaje del cliente
            byte[] clientIdentity = frontend.recv(ZMQ.DONTWAIT);
            if (clientIdentity == null) return;
            
            byte[] empty = frontend.recv(0);
            byte[] messageBytes = frontend.recv(0);
            
            String message = new String(messageBytes, ZMQ.CHARSET);
            String[] parts = message.split(",");
            
            if (parts.length < 1) {
                sendErrorResponse(frontend, clientIdentity, "Formato de mensaje inválido");
                return;
            }
            
            String requestId = parts[0];
            
            // Crear contexto para tracking asíncrono
            RequestContext context = new RequestContext(clientIdentity, requestId);
            pendingRequests.put(requestId, context);
            
            // Procesar request de forma asíncrona
            requestProcessor.submit(() -> {
                try {
                    String response = processRequestWithFailover(message, primaryReq, backupReq);
                    
                    // Enviar respuesta al cliente original
                    synchronized (frontend) {
                        frontend.send(context.clientIdentity, ZMQ.SNDMORE);
                        frontend.send("", ZMQ.SNDMORE);
                        frontend.send(response);
                    }
                    
                    context.responseFuture.complete(response);
                    logResponse(requestId, response);
                    
                } catch (Exception e) {
                    System.err.println("❌ Error procesando request " + requestId + ": " + e.getMessage());
                    handleRequestError(frontend, context.clientIdentity, requestId, e.getMessage());
                } finally {
                    pendingRequests.remove(requestId);
                }
            });
            
        } catch (Exception e) {
            System.err.println("❌ Error procesando request entrante: " + e.getMessage());
        }
    }

    private String processRequestWithFailover(String message, ZMQ.Socket primaryReq, ZMQ.Socket backupReq) {
        String[] parts = message.split(",");
        String requestId = parts[0];
        
        logRequest(requestId, message, getCurrentActiveServer());
        
        // Intentar con servidor primario primero si está activo
        if (primaryServerActive.get()) {
            try {
                synchronized (primaryReq) {
                    primaryReq.send(message);
                    String response = primaryReq.recvStr();
                    
                    if (response != null) {
                        // Reset failure counter on successful response
                        synchronized (failureCountLock) {
                            primaryFailureCount = 0;
                        }
                        return response;
                    }
                }
            } catch (Exception e) {
                System.err.println("⚠️ Error con servidor PRIMARY: " + e.getMessage());
                synchronized (failureCountLock) {
                    primaryFailureCount++;
                    if (primaryFailureCount >= CIRCUIT_BREAKER_THRESHOLD) {
                        primaryServerActive.set(false);
                        System.err.println("🚨 Servidor PRIMARY marcado como inactivo");
                    }
                }
            }
        }
        
        // Intentar con servidor backup si primary falló o está inactivo
        if (backupServerActive.get()) {
            try {
                synchronized (backupReq) {
                    backupReq.send(message);
                    String response = backupReq.recvStr();
                    
                    if (response != null) {
                        // Reset failure counter on successful response
                        synchronized (failureCountLock) {
                            backupFailureCount = 0;
                        }
                        return response;
                    }
                }
            } catch (Exception e) {
                System.err.println("⚠️ Error con servidor BACKUP: " + e.getMessage());
                synchronized (failureCountLock) {
                    backupFailureCount++;
                    if (backupFailureCount >= CIRCUIT_BREAKER_THRESHOLD) {
                        backupServerActive.set(false);
                        System.err.println("🚨 Servidor BACKUP marcado como inactivo");
                    }
                }
            }
        }
        
        // Si ambos servidores fallaron
        return requestId + ",Error: No hay servidores disponibles";
    }

    private void startHealthChecks(ZContext context) {
        // Health check para servidor primario
        healthCheckScheduler.scheduleWithFixedDelay(() -> {
            checkServerHealth(context, PRIMARY_HEALTH, "PRIMARY", 
                () -> {
                    synchronized (failureCountLock) {
                        primaryFailureCount = 0;
                        primaryServerActive.set(true);
                        System.out.println("✅ Servidor PRIMARY recuperado");
                    }
                },
                () -> {
                    synchronized (failureCountLock) {
                        primaryFailureCount++;
                        if (primaryFailureCount >= CIRCUIT_BREAKER_THRESHOLD) {
                            primaryServerActive.set(false);
                            System.err.println("🚨 Servidor PRIMARY marcado como inactivo");
                        }
                    }
                });
        }, 0, HEALTHCHECK_INTERVAL, TimeUnit.SECONDS);

        // Health check para servidor backup
        healthCheckScheduler.scheduleWithFixedDelay(() -> {
            checkServerHealth(context, BACKUP_HEALTH, "BACKUP",
                () -> {
                    synchronized (failureCountLock) {
                        backupFailureCount = 0;
                        backupServerActive.set(true);
                        System.out.println("✅ Servidor BACKUP recuperado");
                    }
                },
                () -> {
                    synchronized (failureCountLock) {
                        backupFailureCount++;
                        if (backupFailureCount >= CIRCUIT_BREAKER_THRESHOLD) {
                            backupServerActive.set(false);
                            System.err.println("🚨 Servidor BACKUP marcado como inactivo");
                        }
                    }
                });
        }, 1, HEALTHCHECK_INTERVAL, TimeUnit.SECONDS);
    }

    private void checkServerHealth(ZContext context, String healthEndpoint, String serverName, 
                                  Runnable onSuccess, Runnable onFailure) {
        try (ZMQ.Socket healthSocket = context.createSocket(SocketType.REQ)) {
            healthSocket.setReceiveTimeOut(TIMEOUT_MS);
            healthSocket.connect(healthEndpoint);
            healthSocket.send("PING");
            
            String reply = healthSocket.recvStr();
            if ("PONG".equals(reply)) {
                onSuccess.run();
            } else {
                System.err.println("⚠️ " + serverName + " - Respuesta inesperada: " + reply);
                onFailure.run();
            }
        } catch (Exception e) {
            System.err.println("❌ " + serverName + " health check falló: " + e.getMessage());
            onFailure.run();
        }
    }

    private void startRequestCleanup() {
        healthCheckScheduler.scheduleWithFixedDelay(() -> {
            int cleaned = 0;
            
            var iterator = pendingRequests.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getValue().isExpired(TIMEOUT_MS * 3)) {
                    iterator.remove();
                    cleaned++;
                }
            }
            
            if (cleaned > 0) {
                System.out.println("🧹 Limpiados " + cleaned + " requests expirados");
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private String getCurrentActiveServer() {
        if (primaryServerActive.get()) return "PRIMARY";
        if (backupServerActive.get()) return "BACKUP";
        return "NONE";
    }

    private void sendErrorResponse(ZMQ.Socket frontend, byte[] clientIdentity, String error) {
        try {
            frontend.send(clientIdentity, ZMQ.SNDMORE);
            frontend.send("", ZMQ.SNDMORE);
            frontend.send("ERROR," + error);
        } catch (Exception e) {
            System.err.println("Error enviando respuesta de error: " + e.getMessage());
        }
    }

    private void handleRequestError(ZMQ.Socket frontend, byte[] clientIdentity, 
                                  String requestId, String error) {
        synchronized (frontend) {
            sendErrorResponse(frontend, clientIdentity, requestId + ",Error: " + error);
        }
        pendingRequests.remove(requestId);
    }

    private void logRequest(String requestId, String message, String server) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
        System.out.println(String.format("📤 [%s] Request %s -> %s: %s", 
            timestamp, requestId, server, message.substring(0, Math.min(50, message.length()))));
    }

    private void logResponse(String requestId, String response) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
        System.out.println(String.format("📥 [%s] Response %s: %s", 
            timestamp, requestId, response.substring(0, Math.min(50, response.length()))));
    }

    private void shutdown() {
        System.out.println("🛑 Cerrando HealthCheck Manager...");
        requestProcessor.shutdown();
        healthCheckScheduler.shutdown();
        try {
            if (!requestProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                requestProcessor.shutdownNow();
            }
            if (!healthCheckScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                healthCheckScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("✅ Shutdown completado");
    }
}
