package org.thunderatz.tiago.thundertrekking;


import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class SpeechRecognitionServer extends SensorThread {
    SpeechRecognitionServer(Logger l, int target_port) {
        super(l, target_port, "speech_recognition", null);
    }

    @Override
    public void run() {
        log("Pronto para recebimento");
        while (socket != null) {
            try {
                DatagramPacket packet = new DatagramPacket(null, 0);
                socket.receive(packet);

            } catch (IOException e) {
                log(e.toString());
                close();
            }
        }
        log("saindo da thread");
    }
}
