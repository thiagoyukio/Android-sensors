package org.thunderatz.tiago.thundertrekking;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
/*
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
*/
import android.location.Criteria;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.widget.TextView;

import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity implements SensorEventListener, LocationListener, GpsStatus.Listener {

    private static final float low_pass_alpha = 0.85f;
    private TextView log;
    private SensorManager mSensorManager;
    private float[] gravity = new float[] {0.f, 0.f, 0.f};
    private SensorThread gps;
    private SensorThread compass;
    private SensorThread proximity;
    private TorchServer torch;
    private SensorEventListener sensor_listener;
    private LocationListener gps_listener = this;
    private LocationManager locationManager;
    private int satelites_ultimo = -1, satelites_usados_ultimo = -1;
    private boolean gps_ativado = false;
    /*
    private static CameraDevice cameraDevice;
    private static CaptureRequest.Builder cameraBuilder;

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice d) {
            cameraDevice = d;
            log.append("Câmera conectada\n");
        }
        @Override
        public void onDisconnected(CameraDevice d) {
            d.close();
            cameraDevice = null;
            log.append("Câmera desconectada\n");
        }
        @Override
        public void onError(CameraDevice d, int error) {
            d.close();
            cameraDevice = null;
            log.append("Camera: erro " + Integer.toString(error) + "\n");
        }
    };
    */
    Logger logger = new Logger() {
        @Override
        public void add(final String msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    log.append(msg);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PackageManager pm;

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        sensor_listener = this;
        gps_listener = this;

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationManager.addGpsStatusListener(this);

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
        }

        log = (TextView) findViewById(R.id.log);
        log.setMovementMethod(new ScrollingMovementMethod());
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        try {
            Enumeration<NetworkInterface> enumNetworkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (enumNetworkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = enumNetworkInterfaces.nextElement();
                Enumeration<InetAddress> enumInetAddress = networkInterface.getInetAddresses();
                while (enumInetAddress.hasMoreElements()) {
                    InetAddress inetAddress = enumInetAddress.nextElement();
                    log.append("if " + networkInterface.getName() + " (" + networkInterface.getDisplayName() + "): ");
                    if (inetAddress.isLoopbackAddress())
                        log.append("LOOPBACK ");
                    log.append(inetAddress.getHostAddress() + "\n");
                }
            }
        } catch (SocketException e) {
            log.append("NetworkInterface.getNetworkInterfaces(): " + e.toString() + "\n");
        }

        List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        log.append("Sensores:\n");
        for (Sensor sensor : sensors)
            log.append(sensor.getName() + " (" + sensor.getVendor() + ")\n");

        // GPS pode demorar bastante para receber o primeiro fix (até dois minutos nos testes),
        // então deve ser deixado ligado durante as provas do trekking. Uma opçaõ seria ativá-lo
        // pelo PC do trekking enviando um pacote para a thread do GPS e não desativá-lo ao final do
        // programa, mas preferi deixar sempre ligado no celular e usar o pacote apenas para configurar
        // a porta que recebe as leituras. Para todos os outros sensores, ListenerRegisterer.register()
        // ativa o sensor e unregister() desativa entre os usos para salvar bateria
        final String provider;
        Criteria criteria = new Criteria();
        // Ver http://developer.android.com/reference/android/location/Criteria.html
        // Podemos também pedir dados como velocidade
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        provider = locationManager.getBestProvider(criteria, false);
        if (provider != null) {
            logger.add("gps: provider " + provider + "\n");
            locationManager.requestLocationUpdates(provider, 0, 0, this);
        } else
            logger.add("gps: sem gps\n");
        gps = new SensorThread(logger, 1414, "gps", new ListenerRegisterer() {
            @Override
            public boolean register() {
                if (provider == null)
                    return false;
                gps_ativado = true;
                return true;
            }

            @Override
            public void unregister() {
                gps_ativado = false;
            }
        });
        compass = new SensorThread(logger, 1415, "compass", new ListenerRegisterer() {
            @Override
            public boolean register() {
                boolean acelerometro_necessario = false;
                // TYPE_ROTATION_VECTOR retorna rotação como mix do campo magnético e giroscópio
                // (usando campo magnético para leitura da rotação, mas calculando com giroscópio a rotação
                // entre as amostras do campo magnético e permitindo maior frequência de atualização que apenas
                // com campo magnético) e será nosso sensor preferido. Tem maior consumo de bateria também
                if (mSensorManager.registerListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_FASTEST)) {
                    logger.add("Com TYPE_ROTATION_VECTOR\n");
                } else {
                    // Sem giroscópio
                    logger.add("Sem TYPE_ROTATION_VECTOR\n");
                    if (mSensorManager.registerListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_FASTEST)) {
                        // Com leitura do campo magnético terrestre
                        logger.add("Com TYPE_GEOMAGNETIC_ROTATION_VECTOR\n");
                    } else {
                        logger.add("Sem TYPE_GEOMAGNETIC_ROTATION_VECTOR\n");
                        if (mSensorManager.registerListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_FASTEST)) {
                            logger.add("Com TYPE_MAGNETIC_FIELD\n");
                            acelerometro_necessario = true;
                        } else {
                            logger.add("Sem TYPE_MAGNETIC_FIELD\n");
                        }
                    }
                }

                if (acelerometro_necessario)
                    if (mSensorManager.registerListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL))
                        logger.add("Com TYPE_ACCELEROMETER\n");
                    else {
                        logger.add("Sem TYPE_ACCELEROMETER\n");
                        mSensorManager.unregisterListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));
                        return false;
                    }
                return true;
            }

            @Override
            public void unregister() {
                mSensorManager.unregisterListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));
                mSensorManager.unregisterListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR));
                mSensorManager.unregisterListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR));
                mSensorManager.unregisterListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));
                mSensorManager.unregisterListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
            }
        });
        proximity = new SensorThread(logger, 1416, "proximity", new ListenerRegisterer() {
            @Override
            public boolean register() {
                if (mSensorManager.registerListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY), SensorManager.SENSOR_DELAY_FASTEST)) {
                    logger.add("Com TYPE_PROXIMITY\n");
                    return true;
                }
                logger.add("Sem TYPE_PROXIMITY\n");
                return false;
            }

            @Override
            public void unregister() {
                mSensorManager.unregisterListener(sensor_listener, mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY));
            }
        });

        pm = getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            torch = new TorchServer(logger, 1417, "TorchServer");
        } else
            log.append("Sem FEATURE_CAMERA_FLASH\n");
        if (pm.queryIntentActivities(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).size() > 0) {

        } else
            log.append("Sem ACTION_RECOGNIZE_SPEECH\n");
        /*
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sensores");
        wakeLock.acquire();
        */
        //  wakelock.release() quando não houver ninguém conectado
    }

    @Override
    protected void onResume() {
        super.onResume();

        //if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            /*
            // Ver exemplos:
            // https://github.com/googlesamples/android-Camera2Basic/blob/master/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.java
            // http://blog.csdn.net/torvalbill/article/details/40376145
            String cameraId;
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT)
                    break;
            }
            log.append("Abrindo câmera " + cameraId + "\n");
            manager.openCamera(cameraId, mStateCallback, null);
            */
            //cameraBuilder.set(SCALER_CROP_REGION, TORCH);
        //}
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Parar de receber leituras de sensores para economizar bateria
        /* mSensorManager.unregisterListener(this); */
        // Parar câmera
        /*
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        */
    }

    @Override
    protected void onDestroy() {
        gps.close();
        compass.close();
        proximity.close();
        torch.close();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int i;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER: {
                boolean vazio;
                vazio = true;
                for (float n : gravity)
                    if (n != 0.) {
                        vazio = false;
                        break;
                    }

                //if (vazio)
                    gravity = Arrays.copyOf(event.values, 3);
                /*else
                    // filtro low-pass
                    for (i = 0; i < 3; i++)
                        gravity[i] = low_pass_alpha * gravity[i] + (1.f - low_pass_alpha) * event.values[i];*/

                break;
            }

            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR: {
                // Isso nunca foi testado, não tenho celular com esses sensores.
                // Para quem quiser usar:
                // http://developer.android.com/reference/android/hardware/SensorEvent.html#values
                // São melhores que TYPE_MAGNETIC_FIELD e parecem mais fáceis de programar

                float[] rotation = new float[9];
                float[] orientacao_celular = new float[3];
                SensorManager.getRotationMatrixFromVector(rotation, event.values);
                SensorManager.getOrientation(rotation, orientacao_celular);
                break;
            }

            case Sensor.TYPE_MAGNETIC_FIELD: {
                // Enviaremos valores diretamente do sensor de campo magnético e faremos processamento no
                // computador (o cálculo de orientação do Android está meio foda de usar)
                float[] rotacao = new float[9], inclinacao = new float[9], orientacao_celular = new float[3];
                // float inclinacao_geomagnetico;
                boolean vazio;
                // Não enviar leituras enquanto não tivermos uma do acelerômetro
                vazio = true;
                for (float n : gravity) {
                    if (n != 0) {
                        vazio = false;
                        break;
                    }
                }
                if (vazio)
                    return;
                // getRotationMatrix retorna false se houver erro (matriz próxima de nula, por
                // exemplo, em queda livre). Também se não tivermos recebido nenhuma leitura
                // do acelerômetro ainda
                if (SensorManager.getRotationMatrix(rotacao, inclinacao, gravity, event.values)) {
                    ByteBuffer buffer = ByteBuffer.allocate(4 * 3); // espaço para 3 floats
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    SensorManager.getOrientation(rotacao, orientacao_celular);
                    for (float value : orientacao_celular) {
                        buffer.putFloat(value);
                    }
                    // Log.i("compass", "reading " + buffer.getFloat(0) + "," + buffer.getFloat(4) + "," + buffer.getFloat(8));
                    compass.send(buffer.array());
                    // A seguinte linha pode servir para pegar inclinação do campo
                    // inclinacao_geomagnetico = SensorManager.getInclination(inclinacao);

                    // E para testar os valores:
                    /*log.setText("Compass: yaw: " + Double.toString(orientacao_celular[0] * 180.0f / Math.PI) +
                            "\npitch: " + Double.toString(orientacao_celular[1] * 180.0f / Math.PI) +
                            "\nroll: " + Double.toString(orientacao_celular[2] * 180.0f / Math.PI) +
                            "\nincl: " + Double.toString(inclinacao_geomagnetico * 180.0f / Math.PI));*/
                }
                break;
            }

            case Sensor.TYPE_PROXIMITY: {
                ByteBuffer buffer = ByteBuffer.allocate(4 * 1); // espaço para 1 float
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                buffer.putFloat(event.values[0]);
                proximity.send(buffer.array());
                break;
            }

            default:
            log.append("onSensorChanged: Sensor desconhecido recebido\n");
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (gps_ativado) {
            ByteBuffer buffer = ByteBuffer.allocate(8 * 2); // espaço para 2 doubles
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putDouble(location.getLatitude());
            buffer.putDouble(location.getLongitude());
            gps.send(buffer.array());
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        logger.add("Provider ativado: " + provider + "\n");
    }


    @Override
    public void onProviderDisabled(String provider) {
        logger.add("Provider perdido: " + provider + "\n");
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        int satelites;
        logger.add("Status de " + provider + ": ");
        if (status == LocationProvider.OUT_OF_SERVICE)
            logger.add("Fora de serviço");
        else if (status == LocationProvider.TEMPORARILY_UNAVAILABLE)
            logger.add("Temporariamente sem sinal");
        else if (status == LocationProvider.AVAILABLE)
            logger.add("Disponível");
        else
            logger.add("Status desconhecido");

        satelites = extras.getInt("satellites", -1);
        if (satelites != -1)
            logger.add(" (" + Integer.toString(satelites) + " satélites)");
        logger.add("\n");
    }

    @Override
    public void onGpsStatusChanged(int event) {
        GpsStatus status = locationManager.getGpsStatus(null);
        int satelites = 0, satelites_usados = 0;
        switch (event) {
            case GpsStatus.GPS_EVENT_STARTED:
            logger.add("gps: iniciado\n");
            break;

            case GpsStatus.GPS_EVENT_STOPPED:
            logger.add("gps: parado\n");
            break;

            case GpsStatus.GPS_EVENT_FIRST_FIX:
            logger.add("gps: first fix (" + Integer.toString(status.getTimeToFirstFix()) + " ms)\n");
            break;

            case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
            // GpsSatellite fornece vários dados interessantes, podemos buscar mais coisas aqui
            for (GpsSatellite sat : status.getSatellites()) {
                if(sat.usedInFix())
                    satelites_usados++;
                satelites++;
            }
            if (satelites_usados != satelites_usados_ultimo || satelites != satelites_ultimo) {
                logger.add("gps: " + Integer.toString(satelites) + " detectados, " + Integer.toString(satelites_usados) + " usados para fixar\n");
                satelites_usados_ultimo = satelites_usados;
                satelites_ultimo = satelites;
            }
            break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
        log.append(s.getName() + ": acuracia " + Integer.toString(accuracy) + "\n");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            torch.invert();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
