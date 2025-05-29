package com.healthcheck;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Poller;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class HealthCheckManager {

    private static final String PRIMARY_SERVER = "tcp://192.168.1.103:5556";
    private static final String BACKUP_SERVER = "tcp://192.168.1.101:5556";
    private static final int HEALTHCHECK_INTERVAL = 5; // seconds
    private static final int TIMEOUT_MS = 3000;
    private static final int DEPARTMENT_PORT = 5555;

    private final AtomicBoolean primaryServerActive = new AtomicBoolean(true);

    public static void main(String[] args) {
        new HealthCheckManager().start();
    }

    public void start() {
        try (ZContext context = new ZContext()) {
            ZMQ.Socket frontend = context.createSocket(SocketType.ROUTER);
            frontend.bind("tcp://*:" + DEPARTMENT_PORT);

            ZMQ.Socket backend = context.createSocket(SocketType.DEALER);
            backend.connect(PRIMARY_SERVER);

            Poller poller = context.createPoller(2);
            poller.register(frontend, Poller.POLLIN);
            poller.register(backend, Poller.POLLIN);

            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(() -> {
                try (ZMQ.Socket healthSocket = context.createSocket(SocketType.REQ)) {
                    healthSocket.setReceiveTimeOut(TIMEOUT_MS);
                    healthSocket.connect(PRIMARY_SERVER);
                    healthSocket.send("PING");
                    String reply = healthSocket.recvStr();
                    if ("PONG".equals(reply)) {
                        primaryServerActive.set(true);
                    } else {
                        throw new Exception("No PONG received");
                    }
                } catch (Exception e) {
                    System.err.println("⚠️ Primary server down. Switching to backup...");
                    primaryServerActive.set(false);
                }

                synchronized (backend) {
                    backend.disconnect(PRIMARY_SERVER);
                    backend.disconnect(BACKUP_SERVER);
                    if (primaryServerActive.get()) {
                        backend.connect(PRIMARY_SERVER);
                        System.out.println("✅ Using PRIMARY server");
                    } else {
                        backend.connect(BACKUP_SERVER);
                        System.out.println("🆘 Using BACKUP server");
                    }
                }
            }, 0, HEALTHCHECK_INTERVAL, TimeUnit.SECONDS);

            while (!Thread.currentThread().isInterrupted()) {
                if (poller.poll(1000) > 0) {
                    if (poller.pollin(0)) {
                        // From department → server
                        byte[] identity = frontend.recv(0);
                        byte[] empty = frontend.recv(0);
                        byte[] message = frontend.recv(0);
                        synchronized (backend) {
                            backend.send(identity, ZMQ.SNDMORE);
                            backend.send("", ZMQ.SNDMORE);
                            backend.send(message);
                        }
                    }

                    if (poller.pollin(1)) {
                        // From server → department
                        byte[] identity = backend.recv(0);
                        byte[] empty = backend.recv(0);
                        byte[] message = backend.recv(0);
                        frontend.send(identity, ZMQ.SNDMORE);
                        frontend.send("", ZMQ.SNDMORE);
                        frontend.send(message);
                    }
                }
            }

            scheduler.shutdownNow();
        }
    }
}
