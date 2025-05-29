package com.departmentschool;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Poller;

import java.util.UUID;

public class DepartmentSchool {

    public static String identityglobal;

    public static void main(String[] args) {
        if (args.length != 2) {
            //System.out.println("Uso: java DepartmentSchool <FacultyName> <Semester>");
            //return;
            args = new String[]{"Facultad de Ingenieria", "2025-10"}; // Para pruebas
        }

        String facultyName = args[0];
        String semester = args[1];
        String listenPort = "5554";
        String serverAddress = "tcp://localhost:5555"; // Broker o Servidor directo

        try (ZContext context = new ZContext()) {
            ZMQ.Socket frontend = context.createSocket(SocketType.ROUTER);
            frontend.bind("tcp://*:" + listenPort);
            System.out.println("📥 ROUTER escuchando a AcademicPrograms en puerto " + listenPort);

            ZMQ.Socket backend = context.createSocket(SocketType.DEALER);
            backend.connect(serverAddress);
            System.out.println("🔁 DEALER conectado al servidor en " + serverAddress);

            Poller poller = context.createPoller(2);
            poller.register(frontend, Poller.POLLIN);
            poller.register(backend, Poller.POLLIN);

            while (!Thread.currentThread().isInterrupted()) {
                poller.poll();

                // Mensaje entrante de AcademicProgram
                if (poller.pollin(0)) {
                    byte[] identity = frontend.recv(0);
                    frontend.recv(0); // frame vacío
                    String request = frontend.recvStr();

                    System.out.println(new String(identity));
                    identityglobal = new String(identity);

                    // Esperado: programa,salones,laboratorios,semestre
                    String[] parts = request.split(",");
                    if (parts.length != 4) {
                        frontend.send(identity, ZMQ.SNDMORE);
                        frontend.send("", ZMQ.SNDMORE);
                        frontend.send("Formato inválido. Se esperaban: programa,salones,laboratorios,semestre");
                        continue;
                    }

                    // Generar requestId único y construir mensaje completo
                    String requestId = UUID.randomUUID().toString();
                    String enrichedRequest = String.join(",",
                            requestId,           // ID único para correlación
                            parts[1],            // semestre (de AcademicProgram)
                            facultyName,         // facultad (de este DepartmentSchool)
                            parts[0],            // programa
                            parts[2],            // número de salones
                            parts[3]             // número de laboratorios
                    );

                    backend.send(identity, ZMQ.SNDMORE);
                    //backend.send("", ZMQ.SNDMORE);
                    backend.send(enrichedRequest);
                    System.out.println("📤 Enviada al servidor: " + enrichedRequest);
                }

                // Respuesta del HealthCheckManager
                if (poller.pollin(1)) {
                    backend.recv(0); // identity (descártalo)
                    byte[] reply = backend.recv(0); // este es el mensaje real

                    frontend.send(reply); // Solo un frame al cliente REQ
                    System.out.println("📨 Enviada a AcademicProgram: " + new String(reply));
                }
            } // fin del while

        } // fin del try-with-resources
    } // fin del método main
} // fin de la clase
