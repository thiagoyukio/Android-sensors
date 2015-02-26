package org.thunderatz.tiago.thundertrekking;

import android.hardware.Camera;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class TorchServer extends SensorThread {
    private Camera camera;
    private Camera.Parameters p;
    TorchServer (Logger l, int target_port, String my_id) {
        super(l, target_port, my_id, null);
    }

    @Override
    public void run() {
        log("Pronto para recebimento");
        try {
            camera = Camera.open();
            p = camera.getParameters();
            socket = new DatagramSocket(port);
            byte[] remote_port = new byte[1];

            while (socket != null) {
                DatagramPacket packet = new DatagramPacket(remote_port, 1);
                socket.receive(packet);

                if (packet.getLength() != 0) {
                    log(packet.getAddress().getHostName() + ":" + Integer.toString(packet.getPort()) + " - torch ON");
                    on();
                } else {
                    log(packet.getAddress().getHostName() + ":" + Integer.toString(packet.getPort()) + " - torch OFF");
                    off();
                }
            }
        } catch (IOException e) {
            log(e.toString());
            close();
        }
        camera.release();
        log("saindo da thread");
    }

    public void on() {
        p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        camera.setParameters(p);
        camera.startPreview();
    }

    public void off() {
        p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        camera.setParameters(p);
        camera.stopPreview();
    }

    public void invert() {
        if (p.getFlashMode() == Camera.Parameters.FLASH_MODE_OFF)
            on();
        else
            off();
    }
}
