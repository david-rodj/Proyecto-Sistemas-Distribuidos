package com.departmentschool;



import org.zeromq.SocketType;

import org.zeromq.ZContext;

import org.zeromq.ZMQ;



import java.util.Scanner;

import java.util.UUID;



public class DepartmentSchool {



    public static void main(String[] args) {

        if (args.length != 3) {

            System.out.println("Uso: java DepartmentSchool <FacultyName> <Semester> <Port>");

            return;

        }



        String facultyName = args[0];

        String semester = args[1];

        int port = Integer.parseInt(args[2]);



        String brokerAddress = "tcp://localhost:5555"; // HealthCheckManager

        try (ZContext context = new ZContext()) {

            ZMQ.Socket socket = context.createSocket(SocketType.REQ);

            socket.connect(brokerAddress);



            Scanner scanner = new Scanner(System.in);

            while (true) {

                System.out.print("Nombre del programa académico: ");

                String programa = scanner.nextLine();



                System.out.print("Cantidad de salones: ");

                int salones = Integer.parseInt(scanner.nextLine());



                System.out.print("Cantidad de laboratorios: ");

                int labs = Integer.parseInt(scanner.nextLine());



                String requestId = UUID.randomUUID().toString();

                String mensaje = String.join(",",

                        requestId, semester, facultyName, programa,

                        String.valueOf(salones), String.valueOf(labs)

                );



                System.out.println("📤 Enviando solicitud al broker...");

                socket.send(mensaje);



                String respuesta = socket.recvStr();

                System.out.println("📨 Respuesta: " + respuesta);

            }

        }

    }

}




