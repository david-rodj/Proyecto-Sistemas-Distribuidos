package com.departmentschool;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import java.util.UUID;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DepartmentSchool {
    
    private static final int ACADEMIC_PROGRAM_PORT = 5554;
    private static final String HEALTHCHECK_ADDRESS = "tcp://localhost:5555";
    private static final int REQUEST_TIMEOUT_MS = 10000; // 10 segundos
    private static final int MAX_CONCURRENT_REQUESTS = 20;
    
    private final ExecutorService requestProcessor = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS);
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutHandler = Executors.newScheduledThreadPool(2);
    
    private String facultyName;
    private String semester;
    
    public DepartmentSchool(String facultyName, String semester) {
        this.facultyName = facultyName;
        this.semester = semester;
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Uso: java DepartmentSchool <FacultyName> <Semester>");
            return;
        }

        String facultyName = args[0];
        String semester = args[1];
        
        DepartmentSchool department = new DepartmentSchool(facultyName, semester);
        department.start();
    }

    public void start() {
        System.out.println("🏫 Iniciando Department School Asíncrono para " + facultyName + " - " + semester);
        
        try (ZContext context = new ZContext()) {
            // Socket REP para recibir de AcademicProgram
            ZMQ.Socket academicSocket = context.createSocket(SocketType.REP);
            academicSocket.bind("tcp://*:" + ACADEMIC_PROGRAM_PORT);
            System.out.println("📡 Escuchando solicitudes de AcademicProgram en puerto " + ACADEMIC_PROGRAM_PORT);

            // Socket DEALER para comunicación asíncrona con HealthCheckManager
            ZMQ.Socket healthCheckSocket = context.createSocket(SocketType.DEALER);
            
            // Generar identidad única para este DEALER
            String dealerIdentity = "DEPT-" + facultyName + "-" + System.currentTimeMillis();
            healthCheckSocket.setIdentity(dealerIdentity.getBytes(ZMQ.CHARSET));
            healthCheckSocket.connect(HEALTHCHECK_ADDRESS);
            System.out.println("🔗 Conectado al HealthCheckManager en " + HEALTHCHECK_ADDRESS);

            // Configurar polling para ambos sockets
            ZMQ.Poller poller = context.createPoller(2);
            poller.register(academicSocket, ZMQ.Poller.POLLIN);
            poller.register(healthCheckSocket, ZMQ.Poller.POLLIN);
            
            // Iniciar limpieza de requests expirados
            startTimeoutHandler();

            System.out.println("✅ Sistema listo para procesar requests de programas académicos...");

            // Main event loop
            while (!Thread.currentThread().isInterrupted()) {
                if (poller.poll(100) > 0) {
                    
                    // Procesar solicitudes de AcademicProgram
                    if (poller.pollin(0)) {
                        handleAcademicProgramRequest(academicSocket, healthCheckSocket);
                    }
                    
                    // Procesar respuestas del HealthCheckManager
                    if (poller.pollin(1)) {
                        handleHealthCheckResponse(healthCheckSocket, academicSocket);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Error en Department School: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private void handleAcademicProgramRequest(ZMQ.Socket academicSocket, ZMQ.Socket healthCheckSocket) {
        try {
            String solicitud = academicSocket.recvStr(ZMQ.DONTWAIT);
            if (solicitud == null) return;
            
            System.out.println("📨 Recibido de AcademicProgram: " + solicitud);

            // Validar formato de solicitud
            String[] parts = solicitud.split(",");
            if (parts.length != 4) {
                academicSocket.send("ERROR,Formato inválido desde AcademicProgram");
                return;
            }

            // Generar requestId único y construir mensaje completo
            String requestId = UUID.randomUUID().toString();
            String completeMessage = String.join(",",
                requestId,           // ID único para correlación
                parts[1],           // semestre (de AcademicProgram)
                facultyName,        // facultad (de este DepartmentSchool)
                parts[0],           // programa
                parts[2],           // número de salones
                parts[3]            // número de laboratorios
            );

            // Crear Future para tracking asíncrono
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            pendingRequests.put(requestId, responseFuture);

            // Procesar request de forma asíncrona
            requestProcessor.submit(() -> {
                try {
                    // Enviar al HealthCheckManager
                    synchronized (healthCheckSocket) {
                        healthCheckSocket.send(completeMessage);
                        logRequest(requestId, completeMessage);
                    }

                    // Configurar timeout para el request
                    timeoutHandler.schedule(() -> {
                        CompletableFuture<String> future = pendingRequests.remove(requestId);
                        if (future != null && !future.isDone()) {
                            future.completeExceptionally(new TimeoutException("Request timeout"));
                            sendTimeoutResponse(academicSocket, requestId);
                        }
                    }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                } catch (Exception e) {
                    System.err.println("❌ Error procesando request " + requestId + ": " + e.getMessage());
                    CompletableFuture<String> future = pendingRequests.remove(requestId);
                    if (future != null) {
                        future.completeExceptionally(e);
                        sendErrorResponse(academicSocket, requestId, e.getMessage());
                    }
                }
            });

            // Configurar callback para cuando llegue la respuesta
            CompletableFuture<String> future = pendingRequests.get(requestId);
            if (future != null) {
                future.whenComplete((response, throwable) -> {
                    if (throwable == null) {
                        try {
                            academicSocket.send(response);
                            logResponse(requestId, response);
                        } catch (Exception e) {
                            System.err.println("❌ Error enviando respuesta a AcademicProgram: " + e.getMessage());
                        }
                    }
                    // El manejo de errores ya se hace en los métodos específicos
                });
            }

        } catch (Exception e) {
            System.err.println("❌ Error manejando request de AcademicProgram: " + e.getMessage());
            try {
                academicSocket.send("ERROR,Error interno del servidor");
            } catch (Exception sendError) {
                System.err.println("❌ Error enviando mensaje de error: " + sendError.getMessage());
            }
        }
    }

    private void handleHealthCheckResponse(ZMQ.Socket healthCheckSocket, ZMQ.Socket academicSocket) {
        try {
            String response = healthCheckSocket.recvStr(ZMQ.DONTWAIT);
            if (response == null) return;

            System.out.println("📥 Respuesta del HealthCheckManager: " + response);

            // Extraer requestId de la respuesta
            String[] responseParts = response.split(",", 2);
            if (responseParts.length < 1) {
                System.err.println("⚠️ Respuesta sin requestId válido: " + response);
                return;
            }

            String requestId = responseParts[0];
            CompletableFuture<String> future = pendingRequests.remove(requestId);
            
            if (future != null && !future.isDone()) {
                future.complete(response);
            } else {
                System.err.println("⚠️ Recibida respuesta para request desconocido o expirado: " + requestId);
            }

        } catch (Exception e) {
            System.err.println("❌ Error procesando respuesta del HealthCheckManager: " + e.getMessage());
        }
    }

    private void startTimeoutHandler() {
        timeoutHandler.scheduleWithFixedDelay(() -> {
            int cleaned = 0;
            long currentTime = System.currentTimeMillis();
            
            // Limpiar requests muy antiguos (más de 2x el timeout)
            var iterator = pendingRequests.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                CompletableFuture<String> future = entry.getValue();
                
                if (future.isDone()) {
                    iterator.remove();
                    cleaned++;
                }
            }
            
            if (cleaned > 0) {
                System.out.println("🧹 Limpiados " + cleaned + " requests completados");
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void sendTimeoutResponse(ZMQ.Socket academicSocket, String requestId) {
        try {
            synchronized (academicSocket) {
                academicSocket.send(requestId + ",ERROR,Timeout - El servidor no respondió a tiempo");
            }
        } catch (Exception e) {
            System.err.println("❌ Error enviando respuesta de timeout: " + e.getMessage());
        }
    }

    private void sendErrorResponse(ZMQ.Socket academicSocket, String requestId, String error) {
        try {
            synchronized (academicSocket) {
                academicSocket.send(requestId + ",ERROR," + error);
            }
        } catch (Exception e) {
            System.err.println("❌ Error enviando respuesta de error: " + e.getMessage());
        }
    }

    private void logRequest(String requestId, String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
        System.out.println(String.format("📤 [%s] Request %s enviado: %s", 
            timestamp, requestId, message.length() > 50 ? message.substring(0, 50) + "..." : message));
    }

    private void logResponse(String requestId, String response) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
        System.out.println(String.format("📨 [%s] Response %s procesada: %s", 
            timestamp, requestId, response.length() > 50 ? response.substring(0, 50) + "..." : response));
    }

    private void shutdown() {
        System.out.println("🛑 Cerrando Department School...");
        
        // Completar todos los requests pendientes con error
        pendingRequests.values().forEach(future -> {
            if (!future.isDone()) {
                future.completeExceptionally(new InterruptedException("Sistema cerrándose"));
            }
        });
        pendingRequests.clear();
        
        requestProcessor.shutdown();
        timeoutHandler.shutdown();
        
        try {
            if (!requestProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                requestProcessor.shutdownNow();
            }
            if (!timeoutHandler.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutHandler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        System.out.println("✅ Shutdown completado");
    }
}
