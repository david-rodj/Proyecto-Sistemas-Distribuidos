package com.departmentschool;



import org.zeromq.SocketType;

import org.zeromq.ZContext;

import org.zeromq.ZMQ;

import org.zeromq.ZMQ.Poller;



import java.util.UUID;



public class DepartmentSchool {



    public static void main(String[] args) {

        if (args.length != 2) {

            System.out.println("Uso: java DepartmentSchool <FacultyName> <Semester>");

            return;

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



                if (poller.pollin(0)) {

                    byte[] identity = frontend.recv(0);

                    frontend.recv(0); // frame vacío

                    String request = frontend.recvStr();



                    // Esperado: programa,salones,laboratorios

                    String[] parts = request.split(",");

                    if (parts.length == 3) {

                        String requestId = UUID.randomUUID().toString();

                        String enrichedRequest = String.join(",", requestId, semester, facultyName,

                                                              parts[0], parts[1], parts[2]);



                        backend.send(identity, ZMQ.SNDMORE);

                        backend.send("", ZMQ.SNDMORE);

                        backend.send(enrichedRequest);

                        System.out.println("📤 Enviada al servidor: " + enrichedRequest);

                    } else {

                        frontend.send(identity, ZMQ.SNDMORE);

                        frontend.send("", ZMQ.SNDMORE);

                        frontend.send("Formato inválido. Se esperaban: programa,salones,laboratorios");

                    }

                }



                if (poller.pollin(1)) {

                    byte[] identity = backend.recv(0);

                    backend.recv(0); // frame vacío

                    String reply = backend.recvStr();



                    frontend.send(identity, ZMQ.SNDMORE);

                    frontend.send("", ZMQ.SNDMORE);

                    frontend.send(reply);

                    System.out.println("📨 Enviada a AcademicProgram: " + reply);

                }

            }

        }

    }

}
