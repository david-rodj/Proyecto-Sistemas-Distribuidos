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
            // Socket para recibir requests de DepartmentSchool
            ZMQ.Socket frontend = context.createSocket(SocketType.ROUTER);
            frontend.bind("tcp://*:" + DEPARTMENT_PORT);
            System.out.println("📡 Frontend ROUTER escuchando en puerto " + DEPARTMENT_PORT);

            // Socket para comunicación con servidores backend
            ZMQ.Socket backend = context.createSocket(SocketType.DEALER);
            connectToActiveServer(backend);

            // Configurar polling
            Poller poller = context.createPoller(2);
            poller.register(frontend, Poller.POLLIN);
            poller.register(backend, Poller.POLLIN);

            // Iniciar health checks periódicos
            startHealthChecks(context, backend);
            
            // Iniciar limpieza de requests expirados
            startRequestCleanup();

            System.out.println("✅ Sistema listo para procesar requests...");

            // Main event loop
            while (!Thread.currentThread().isInterrupted()) {
                if (poller.poll(100) > 0) {
                    
                    // Procesar requests entrantes del frontend
                    if (poller.pollin(0)) {
                        handleIncomingRequest(frontend, backend);
                    }

                    // Procesar responses del backend
                    if (poller.pollin(1)) {
                        handleBackendResponse(backend, frontend);
                    }
                }
                
                // Procesar requests pendientes (retry logic)
                processRetries(backend);
            }

        } catch (Exception e) {
            System.err.println("❌ Error en HealthCheck Manager: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private void handleIncomingRequest(ZMQ.Socket frontend, ZMQ.Socket backend) {
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
            
            // Enviar al servidor backend de forma asíncrona
            requestProcessor.submit(() -> {
                try {
                    synchronized (backend) {
                        if (isAnyServerActive()) {
                            backend.send(message.getBytes(ZMQ.CHARSET), 0);
                            logRequest(requestId, message, getCurrentActiveServer());
                        } else {
                            handleNoActiveServers(frontend, clientIdentity, requestId);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("❌ Error enviando request " + requestId + ": " + e.getMessage());
                    handleRequestError(frontend, clientIdentity, requestId, e.getMessage());
                }
            });
            
        } catch (Exception e) {
            System.err.println("❌ Error procesando request entrante: " + e.getMessage());
        }
    }

    private void handleBackendResponse(ZMQ.Socket backend, ZMQ.Socket frontend) {
        try {
            String response = backend.recvStr(ZMQ.DONTWAIT);
            if (response == null) return;
            
            String[] responseParts = response.split(",", 2);
            if (responseParts.length < 1) return;
            
            String requestId = responseParts[0];
            RequestContext context = pendingRequests.remove(requestId);
            
            if (context != null) {
                // Enviar respuesta al cliente original
                frontend.send(context.clientIdentity, ZMQ.SNDMORE);
                frontend.send("", ZMQ.SNDMORE);
                frontend.send(response);
                
                context.responseFuture.complete(response);
                logResponse(requestId, response);
                
                // Reset failure counter on successful response
                synchronized (failureCountLock) {
                    if (primaryServerActive.get()) {
                        primaryFailureCount = 0;
                    } else if (backupServerActive.get()) {
                        backupFailureCount = 0;
                    }
                }
            } else {
                System.err.println("⚠️ Recibida respuesta para request desconocido: " + requestId);
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error procesando respuesta del backend: " + e.getMessage());
        }
    }

    private void startHealthChecks(ZContext context, ZMQ.Socket backend) {
        // Health check para servidor primario
        healthCheckScheduler.scheduleWithFixedDelay(() -> {
            checkServerHealth(context, PRIMARY_HEALTH, "PRIMARY", 
                () -> {
                    synchronized (failureCountLock) {
                        primaryFailureCount = 0;
                        primaryServerActive.set(true);
                        System.out.println("✅ Servidor PRIMARY recuperado");
                        reconnectToActiveServer(backend);
                    }
                },
                () -> {
                    synchronized (failureCountLock) {
                        primaryFailureCount++;
                        if (primaryFailureCount >= CIRCUIT_BREAKER_THRESHOLD) {
                            primaryServerActive.set(false);
                            System.err.println("🚨 Servidor PRIMARY marcado como inactivo");
                            reconnectToActiveServer(backend);
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
                        if (!primaryServerActive.get()) {
                            reconnectToActiveServer(backend);
                        }
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

    private void connectToActiveServer(ZMQ.Socket backend) {
        if (primaryServerActive.get()) {
            backend.connect(PRIMARY_SERVER);
            System.out.println("🔗 Conectado al servidor PRIMARY");
        } else if (backupServerActive.get()) {
            backend.connect(BACKUP_SERVER);
            System.out.println("🔗 Conectado al servidor BACKUP");
        } else {
            System.err.println("🚨 NO HAY SERVIDORES ACTIVOS");
        }
    }

    private void reconnectToActiveServer(ZMQ.Socket backend) {
        synchronized (backend) {
            try {
                backend.disconnect(PRIMARY_SERVER);
                backend.disconnect(BACKUP_SERVER);
            } catch (Exception e) {
                // Ignorar errores de desconexión
            }
            connectToActiveServer(backend);
        }
    }

    private void processRetries(ZMQ.Socket backend) {
        long currentTime = System.currentTimeMillis();
        pendingRequests.entrySet().removeIf(entry -> {
            RequestContext context = entry.getValue();
            if (context.isExpired(TIMEOUT_MS * 2)) {
                // Request expirado, enviar timeout al cliente
                try (ZContext tempContext = new ZContext()) {
                    ZMQ.Socket timeoutSocket = tempContext.createSocket(SocketType.PUSH);
                    // Simular respuesta de timeout (en implementación real, 
                    // necesitaríamos mantener referencia al frontend socket)
                    context.responseFuture.completeExceptionally(
                        new TimeoutException("Request timeout: " + context.requestId));
                } catch (Exception e) {
                    System.err.println("Error enviando timeout: " + e.getMessage());
                }
                return true; // Remover del mapa
            }
            return false;
        });
    }

    private void startRequestCleanup() {
        healthCheckScheduler.scheduleWithFixedDelay(() -> {
            int cleaned = 0;
            long currentTime = System.currentTimeMillis();
            
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

    private boolean isAnyServerActive() {
        return primaryServerActive.get() || backupServerActive.get();
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

    private void handleNoActiveServers(ZMQ.Socket frontend, byte[] clientIdentity, String requestId) {
        sendErrorResponse(frontend, clientIdentity, 
            requestId + ",Error: No hay servidores disponibles");
        pendingRequests.remove(requestId);
    }

    private void handleRequestError(ZMQ.Socket frontend, byte[] clientIdentity, 
                                  String requestId, String error) {
        sendErrorResponse(frontend, clientIdentity, requestId + ",Error: " + error);
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
