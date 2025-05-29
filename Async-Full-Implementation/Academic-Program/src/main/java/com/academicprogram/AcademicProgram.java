package com.academicprogram;



import org.zeromq.SocketType;

import org.zeromq.ZContext;

import org.zeromq.ZMQ;

import org.zeromq.ZMQ.Poller;



import java.util.UUID;



public class AcademicProgram {



    public static void main(String[] args) {

        if (args.length != 3) {

            System.out.println("Uso: java AcademicProgram <ProgramName> <Salones> <Laboratorios>");

            return;

        }



        String programName = args[0];

        String salones = args[1];

        String laboratorios = args[2];



        String departmentAddress = "tcp://localhost:5554";



        try (ZContext context = new ZContext()) {

            ZMQ.Socket socket = context.createSocket(SocketType.DEALER);

            socket.setIdentity(programName.getBytes(ZMQ.CHARSET));

            socket.connect(departmentAddress);

            System.out.println("🔗 Conectado al DepartmentSchool en " + departmentAddress);



            Poller poller = context.createPoller(1);

            poller.register(socket, Poller.POLLIN);



            String mensaje = String.join(",", programName, salones, laboratorios);

            socket.send(mensaje);

            System.out.println("📤 Solicitud enviada: " + mensaje);



            // Esperar respuesta asincrónica

            int events = poller.poll(3000); // 3 segundos timeout

            if (events > 0 && poller.pollin(0)) {

                String respuesta = socket.recvStr();

                System.out.println("📨 Respuesta recibida: " + respuesta);

            } else {

                System.out.println("⏱️ No se recibió respuesta (timeout)");

            }

        }

    }

}




