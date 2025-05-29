package com.departmentschool;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import java.util.UUID;

public class DepartmentSchool {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Uso: java DepartmentSchool <FacultyName> <Semester>");
            return;
        }

        String facultyName = args[0];
        String semester = args[1];
        String departmentPort = "5554";
        String brokerAddress = "tcp://localhost:5555"; // HealthCheckManager

        try (ZContext context = new ZContext()) {
            // Socket REP para recibir de AcademicProgram (sin cambios)
            ZMQ.Socket academicSocket = context.createSocket(SocketType.REP);
            academicSocket.bind("tcp://*:" + departmentPort);
            System.out.println("Esperando solicitudes de AcademicProgram en puerto " + departmentPort);

            // Socket DEALER para comunicación asíncrona con HealthCheckManager
            ZMQ.Socket brokerSocket = context.createSocket(SocketType.DEALER);
            brokerSocket.connect(brokerAddress);
            System.out.println("Conectado al HealthCheckManager en " + brokerAddress);

            while (!Thread.currentThread().isInterrupted()) {
                // Recibir solicitud del programa académico
                String solicitud = academicSocket.recvStr();
                System.out.println("Recibido de AcademicProgram: " + solicitud);

                // Insertar semestre y facultad en el mensaje (ID, semestre, facultad, programa, salones, labs)
                String[] parts = solicitud.split(",");
                if (parts.length == 4) {
                    String requestId = UUID.randomUUID().toString();
                    String withContext = requestId + "," + parts[1] + "," + facultyName + "," +
                                         parts[0] + "," + parts[2] + "," + parts[3];

                    // DEALER envía directamente sin frame de identidad
                    brokerSocket.send(withContext);
                    
                    // DEALER recibe respuesta directamente
                    String respuesta = brokerSocket.recvStr();
                    System.out.println("Respuesta del servidor: " + respuesta);
                    academicSocket.send(respuesta);
                } else {
                    academicSocket.send("Formato inválido desde AcademicProgram");
                }
            }
        }
    }
}
