package com.healthcheck;



import org.zeromq.ZContext;

import org.zeromq.ZMQ;

import org.zeromq.ZMQ.Poller;

import org.zeromq.SocketType;



import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.Executors;

import java.util.concurrent.ScheduledExecutorService;

import java.util.concurrent.TimeUnit;



public class HealthCheckManager {



    private static final String PRIMARY_SERVER = "tcp://10.43.103.67:5556";

    private static final String BACKUP_SERVER = "tcp://10.43.96.42:5556";

    private static final String PRIMARY_HEALTH = "tcp://10.43.103.67:6000";

    private static final String BACKUP_HEALTH = "tcp://10.43.96.42:6000";



    private static final int PORT_DEPARTMENT = 5555;

    private static final int HEALTHCHECK_INTERVAL = 5;

    private static final int TIMEOUT_MS = 5000;



    private static final AtomicBoolean usePrimary = new AtomicBoolean(true);



    public static void main(String[] args) {

        try (ZContext context = new ZContext()) {

            ZMQ.Socket frontend = context.createSocket(SocketType.ROUTER);

            frontend.bind("tcp://*:" + PORT_DEPARTMENT);

            System.out.println("ðŸ”Œ Escuchando solicitudes de DepartmentSchool en puerto " + PORT_DEPARTMENT);



            ZMQ.Socket backend = context.createSocket(SocketType.DEALER);

            backend.connect(PRIMARY_SERVER);

            System.out.println("ðŸ” Inicialmente conectado al servidor PRIMARY");



            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();



            ZMQ.Socket healthSocket = context.createSocket(SocketType.REQ);

            healthSocket.setReceiveTimeOut(TIMEOUT_MS);

            healthSocket.connect(PRIMARY_HEALTH);



            scheduler.scheduleAtFixedRate(() -> {

                try {

                    healthSocket.send("PING");

                    String resp = healthSocket.recvStr();

                    if (!"PONG".equals(resp)) throw new Exception("No PONG");



                    if (!usePrimary.get()) {

                        System.out.println("âœ… PRIMARY recuperado");

                        synchronized (backend) {

                            backend.disconnect(BACKUP_SERVER);

                            backend.connect(PRIMARY_SERVER);

                        }

                        usePrimary.set(true);

                    }

                } catch (Exception e) {

                    if (usePrimary.get()) {

                        System.out.println("âš ï¸ PRIMARY caÃ­do, cambiando a BACKUP");

                        synchronized (backend) {

                            backend.disconnect(PRIMARY_SERVER);

                            backend.connect(BACKUP_SERVER);

                        }

                        healthSocket.disconnect(PRIMARY_HEALTH);

                        healthSocket.connect(BACKUP_HEALTH);

                        usePrimary.set(false);

                    }

                }

            }, 0, HEALTHCHECK_INTERVAL, TimeUnit.SECONDS);



            Poller poller = context.createPoller(2);

            poller.register(frontend, Poller.POLLIN);

            poller.register(backend, Poller.POLLIN);



            while (!Thread.currentThread().isInterrupted()) {

                if (poller.poll(1000) > 0) {

                    if (poller.pollin(0)) {

                        byte[] identity = frontend.recv(0);

                        frontend.recv(0);

                        byte[] msg = frontend.recv(0);



                        synchronized (backend) {

                            backend.send(identity, ZMQ.SNDMORE);

                            backend.send("", ZMQ.SNDMORE);

                            backend.send(msg);

                        }

                        System.out.println("ðŸ“¤ Mensaje reenviado al servidor: " + new String(msg));

                    }



                    if (poller.pollin(1)) {

                        byte[] identity = backend.recv(0);

                        backend.recv(0);

                        byte[] reply = backend.recv(0);



                        frontend.send(identity, ZMQ.SNDMORE);

                        frontend.send("", ZMQ.SNDMORE);

                        frontend.send(reply);

                        System.out.println("ðŸ“¬ Respuesta enviada a DepartmentSchool: " + new String(reply));

                    }

                }

            }



            scheduler.shutdownNow();

        }

    }

}


