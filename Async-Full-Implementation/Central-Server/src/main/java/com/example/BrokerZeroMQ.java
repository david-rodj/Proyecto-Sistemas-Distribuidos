package com.example;

import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Poller;

public class BrokerZeroMQ {

    public static void main(String[] args) {
        try (ZContext context = new ZContext()) {
            ZMQ.Socket frontend = context.createSocket(ZMQ.ROUTER); // para clientes (facultades)
            ZMQ.Socket backend = context.createSocket(ZMQ.DEALER);  // para workers (servidor central)

            frontend.bind("tcp://*:5555"); // Clientes se conectan aquí
            backend.bind("tcp://*:5556");  // Workers se conectan aquí

            System.out.println("🧭 Broker iniciado en puertos 5555 (clientes) y 5556 (workers)...");

            Poller poller = context.createPoller(2);
            poller.register(frontend, Poller.POLLIN);
            poller.register(backend, Poller.POLLIN);

            while (!Thread.currentThread().isInterrupted()) {
                poller.poll();

                if (poller.pollin(0)) {
                    byte[] clientId = frontend.recv(0);
                    byte[] empty = frontend.recv(0);
                    byte[] request = frontend.recv(0);

                    backend.send(clientId, ZMQ.SNDMORE);
                    backend.send("", ZMQ.SNDMORE);
                    backend.send(request);
                }

                if (poller.pollin(1)) {
                    byte[] workerId = backend.recv(0);
                    byte[] empty = backend.recv(0);
                    byte[] response = backend.recv(0);

                    frontend.send(workerId, ZMQ.SNDMORE);
                    frontend.send("", ZMQ.SNDMORE);
                    frontend.send(response);
                }
            }
        }
    }
}
