package org.thunderatz.tiago.thundertrekking;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class SensorThread extends Thread {
    int port;
    String id;
    protected DatagramSocket socket;
    protected Logger logger;
    protected InetAddress client_addr;
    protected int client_port = 0;
    protected ListenerRegisterer registerer;

    SensorThread(Logger l, int target_port, String my_id, ListenerRegisterer r) {
        logger = l;
        port = target_port;
        id = my_id + "(" + Integer.toString(port) + "): ";
        registerer = r;
        start();
    }

    protected void log(String msg) {
        logger.add(id + msg + "\n");
    }

    @Override
    public void run() {
        if (registerer != null) {
            try {
                socket = new DatagramSocket(port);
                byte[] remote_port = new byte[5];

                while (socket != null) {
                    DatagramPacket packet = new DatagramPacket(remote_port, 5);
                    int new_port;
                    log("Esperando conexões");
                    socket.receive(packet);
                    String remote = new String(remote_port, 0, packet.getLength()).trim();
                    client_addr = packet.getAddress();

                    // Pacote vazio para sensores
                    if (remote.isEmpty()) {
                        registerer.unregister();
                        log("Parando transmissão");
                        continue;
                    }

                    try {
                        client_port = Integer.parseInt(new String(remote_port).trim());
                    } catch (NumberFormatException e) {
                        log(e.toString());
                        continue;
                    }

                    log(client_addr.getHostName() + ":" + Integer.toString(packet.getPort()) + " direcionando para " + Integer.toString(client_port));
                    if (registerer != null) {
                        // Se não pudemos registrar (sensor não existe), enviar pacote vazio para clientes
                        // indicando que não temos o sensor
                        if (!registerer.register()) {
                            log("registerer.register retornou erro\n");
                            DatagramPacket empty;
                            empty = new DatagramPacket(null, 0, client_addr, client_port);
                            try {
                                socket.send(empty);
                            } catch (IOException e) {
                                log(e.toString());
                                client_port = 0;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log(e.toString());
                close();
            }
            log("saindo da thread");
        }
    }

    public void send(byte[] data) {
        if (client_port == 0) {
            log("send: Sem clientes");
            return;
        }

        final DatagramPacket packet = new DatagramPacket(data, data.length, client_addr, client_port);
        new Thread(new Runnable() {
            public void run() {
                try {
                    socket.send(packet);
                } catch (IOException e) {
                    log(e.toString());
                }
            }
        }).start();
    }

    public void close() {
        log("close");
        if (registerer != null) {
            registerer.unregister();
            socket.close();
            socket = null;
            client_port = 0;
        }
    }
}

