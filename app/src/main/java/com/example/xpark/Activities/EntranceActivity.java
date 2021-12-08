// Version : 0.0.2
package com.example.xpark.Activities;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.example.xpark.R;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import android.os.Vibrator;

public class EntranceActivity extends AppCompatActivity implements SensorEventListener
{
    private SeekBar throttleSeekBar;
    private Button buttonConnect;
    private Button buttonFireTrigger;
    private Button buttonReady;
    private TextView wheelAngleTextView;
    private TextView btBaglantiDurumuTextView;
    private Switch gearPositionSwitch;

    private TextView rakipAracDeviceNameTextView; // rakip arac bluetooth cihaz ismi.
    private TextView baglanilanAracDeviceNameTextView; // baglanilan aracin bluetooth cihaz ismi.
    private TextView rakipSkorTextView; // rakip skor bilgisi.
    private TextView kullaniciSkorTextView; // kullanici (ben) skor bilgisi.
    private TextView bataryaBilgiTextView; // batarya bilgisi.
    private TextView macDurumuTextView; // mac durum bilgisi.

    private SensorManager mSensorManager;
    Sensor accelerometer;
    Sensor magnetometer;

    private static final int OPACITY_NO_TOUCH = 100;
    private static final int OPACITY_TOUCH = 255;

    // Direksiyon aci bilgileri
    float[] mGravity;
    float[] mGeomagnetic;

    // Bluetooth islemleri.
    private BluetoothAdapter BA;
    private Set<BluetoothDevice> pairedDevices;
    private Spinner bluetoothDevicesSpinner;
    private static final UUID BT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothSocket btSocket;
    private OutputStream btOutputStream;
    private InputStream btInputStream;
    private boolean btBaglantiDurumu = false;

    // Gonderilecek RC komut paketi.
    private int RC_komut_steeringAngle = 0;
    private int RC_komut_throttlePos = 0;
    private int RC_komut_gearPosition = 0;
    private int RC_komut_fireTrigger = 0;

    private Thread rcCommandSenderThread;
    private Thread rcPacketListenerThread;
    private static final int RC_KOMUT_GONDERIM_BEKLEME_SURESI = 10; // 10 milisecond.
    private static final int RC_KOMUT_ALIM_BEKLEME_SURESI = 500; // .5 second.

    private static final byte RC_COMMAND_PACKET_HEADER_1 = 0x33;
    private static final byte RC_COMMAND_PACKET_HEADER_2 = 0x44;

    private boolean isDeviceFlat = false;
    private Date lastCrcSuccesTime = new Date();

    private boolean deviceResetOKPacketReceived = false;
    private int packetReceiveState;
    private boolean scoreUpdateDone = false;

    private Vibrator vibrator;

    /* Databse Operations */
    private final String RC_BATTLE_SESSION_DB_FILED = "rc_battle_session";
    private boolean battle_session_connection = false;
    private boolean battleSessionUpdaterThreadStopped = false;
    private boolean battleSessionReceiverThreadStopped = false;
    private final Object battleSessionUpdaterMutex = new Object();
    private final Object battleSessionReceiverMutex = new Object();
    private final Object userScoreMutex = new Object();
    private final Object enemyScoreMutex = new Object();
    private boolean enemyConnected = false;
    private String connectedEnemyID;
    private boolean cancelClickedByOwn = false;
    /* Databse Operations */

    @SuppressLint("RestrictedApi")
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        this.vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        //Remove title bar
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);

        try
        {
            this.getSupportActionBar().hide();
        }
        catch (NullPointerException e){}

        //Remove notification bar
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        //set content view AFTER ABOVE sequence (to avoid crash)
        setContentView(R.layout.activity_entrance);

        /* --------------------------------------------------------------------------------------- */
        this.init_gui();
        this.init_listeners();

        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        /* --------------------------------------------------------------------------------------- */
        // Bluetooth islemleri.
        this.init_bluetooth();

        this.startRcCommandSender();
        this.startRcPacketListener();
    }

    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
    }

    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {  }

    public void onSensorChanged(SensorEvent event)
    {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
            mGravity = event.values;
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
            mGeomagnetic = event.values;

        if (mGravity != null && mGeomagnetic != null)
        {
            float R[] = new float[9];
            float I[] = new float[9];
            boolean success = SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic);
            if (success) {
                float orientation[] = new float[3];
                SensorManager.getOrientation(R, orientation);

                double rotation = Math.atan2(R[6],R[7]);
                if(rotation > 3.12)
                    rotation = 3.12;
                if(rotation < -3.12)
                    rotation = -3.12;

                float roll = orientation[2]; // orientation contains: azimut, roll and roll
                int roll_mapped = (int)((((roll)*180/3.12)));

                /*
                To map
                [A, B] --> [a, b]
                [0, 3.12] --> [-90,90] [0,180]
                use this formula
                (val - A)*(b-a)/(B-A) + a
                */
                int rotation_mapped = (int)((((rotation)*180/3.12) - 90));
                if(rotation_mapped < - 180)
                    rotation_mapped = 90;
                if(rotation_mapped < - 90 && rotation_mapped > -180)
                    rotation_mapped = -90;
                rotation_mapped *= -1;

                // Gonderilecek komut guncellenir.
                this.RC_komut_steeringAngle = (rotation_mapped + 90);

                this.wheelAngleTextView.setText(rotation_mapped + "");
                int abs_roll = (int)Math.abs(roll_mapped);
                if(abs_roll < 10 || abs_roll > 170 || roll_mapped > 0)
                {
                    // device is flat.
                    // Yaw angle is not trustable.
                    this.wheelAngleTextView.setTextColor(Color.RED);
                    this.isDeviceFlat = true;
                }
                else
                {
                    // device is non-flat.
                    // Yaw angle is trustable.
                    this.wheelAngleTextView.setTextColor(Color.WHITE);
                    this.isDeviceFlat = false;
                }
            }
        }
    }

    private void init_gui()
    {
        /* GAS SEEK BAR */
        this.throttleSeekBar = (SeekBar)findViewById(R.id.seekBarThrottle);
        this.throttleSeekBar.setMax(255);
        this.throttleSeekBar.getThumb().mutate().setAlpha(OPACITY_NO_TOUCH);
        /* GAS SEEK BAR */

        /* CONNECT BUTTON */
        this.buttonConnect = (Button)findViewById(R.id.connect_button);
        this.btBaglantiDurumuTextView = (TextView)findViewById(R.id.baglantiDurumuTextView);
        this.btBaglantiDurumuTextView.setTextColor(Color.RED);
        /* CONNECT BUTTON */

        /* FIRE BUTTON */
        this.buttonFireTrigger = (Button)findViewById(R.id.fire_button);
        /* FIRE BUTTON */

        /* READY BUTTON */
        this.buttonReady = (Button)findViewById(R.id.button_ready);
        /* READY BUTTON */

        /* wheel angle text view */
        this.wheelAngleTextView = (TextView)findViewById(R.id.wheelAngleText);
        /* wheel angle text view */

        /* Bluetooth Aygit Spinner */
        this.bluetoothDevicesSpinner = (Spinner) findViewById(R.id.bluuetooth_device_select_spinner);
        /* Bluetooth Aygit Spinner */

        /* Gear Position Switch */
        this.gearPositionSwitch = (Switch)findViewById(R.id.gear_switch);
        /* Gear Position Switch */

        this.baglanilanAracDeviceNameTextView = (TextView)findViewById(R.id.kullanici_skor_bilgi_textView);
        this.baglanilanAracDeviceNameTextView.setText("ASD"); // Todo : kaldirilacak.
        this.kullaniciSkorTextView = (TextView)findViewById(R.id.kullanici_skor_textView);

        this.rakipAracDeviceNameTextView = (TextView)findViewById(R.id.rakip_skor_bilgi_textView);
        this.rakipSkorTextView = (TextView)findViewById(R.id.rakip_skor_textView);

        this.bataryaBilgiTextView = (TextView)findViewById(R.id.bataryaYuzdesiTextView);
        this.macDurumuTextView = (TextView)findViewById(R.id.macDurumuTextView);

        /* CRC durumu blinker */
        new Thread(() ->
        {
            while(true)
            {
                try
                {
                    /* check crc timeout */
                    Date currentTime = new Date();
                    synchronized (EntranceActivity.this)
                    {
                        long difference = currentTime.getTime() - lastCrcSuccesTime.getTime();
                        boolean timeout_crc = difference > 5000;
                        this.runOnUiThread(() -> {
                            TextView v = findViewById(R.id.crcDurumuText);
                            if (timeout_crc)
                            {
                                v.setTextColor(Color.RED);
                            } else {

                                v.setTextColor(Color.GREEN);
                            }
                            findViewById(R.id.crcDurumuText).setVisibility(View.INVISIBLE);
                        });
                    }
                    Thread.sleep(200);
                    this.runOnUiThread(() -> {
                        findViewById(R.id.crcDurumuText).setVisibility(View.VISIBLE);
                    });
                    Thread.sleep(200);
                }
                catch (Exception ex)
                {

                }
            }
        }).start();
        /* CRC durumu blinker */
    }

    @SuppressLint("ClickableViewAccessibility")
    private void init_listeners()
    {
        this.throttleSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                System.out.println("Gaz komut bitti");
                seekBar.setProgress(0);
                seekBar.getThumb().mutate().setAlpha(OPACITY_NO_TOUCH);

                // Gonderilecek komut guncellenir.
                EntranceActivity.this.RC_komut_throttlePos = 0;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                System.out.println("Gaz komut basladi");
                seekBar.getThumb().mutate().setAlpha(OPACITY_TOUCH);
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress,
                                          boolean fromUser) {
                // RC komut guncellenir.
                if(progress>=0 && progress<= 255 && !EntranceActivity.this.isDeviceFlat)
                {
                    EntranceActivity.this.RC_komut_throttlePos = progress;
                }
            }
        });

        this.buttonConnect.setOnClickListener(v -> {
            try
            {
                if(!btBaglantiDurumu)
                {
                    BA.cancelDiscovery();

                    /* Secilen bluetooth aygiti elde edilir */
                    String secilenAygitName = EntranceActivity.this.bluetoothDevicesSpinner.getSelectedItem().toString();
                    BluetoothDevice secilenDevice = null;
                    for(BluetoothDevice device : EntranceActivity.this.pairedDevices)
                    {
                        if(device.getName().equals(secilenAygitName))
                        {
                            secilenDevice = device;
                            break;
                        }
                    }

                    if(null != secilenDevice)
                    {
                        connectTargetDevice(secilenDevice);
                    }
                }
                else
                {
                    disconnectTargetDevice();
                }

            }
            catch (Exception ex)
            {
                Toast.makeText(EntranceActivity.this, "Hata : " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        this.buttonFireTrigger.setOnTouchListener((v, event) -> {
            if(event.getAction() == MotionEvent.ACTION_DOWN)
            {
                EntranceActivity.this.RC_komut_fireTrigger = 1;
            }
            else if (event.getAction() == MotionEvent.ACTION_UP)
            {
                EntranceActivity.this.RC_komut_fireTrigger = 0;
            }
            return true;
        });

        this.gearPositionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if(isChecked)
            {
                EntranceActivity.this.RC_komut_gearPosition = 1;
            }
            else
            {
                EntranceActivity.this.RC_komut_gearPosition = 0;
            }
        });

        this.buttonReady.setOnClickListener(v -> {
            if(!this.battle_session_connection)
            {
                this.cancelClickedByOwn = false;
                connectOnlineBattleSession();
            }
            else
            {
                this.cancelClickedByOwn = true;
                disconnectOnlineBattleSession();
            }
        });
    }

    private void init_bluetooth()
    {
        this.BA = BluetoothAdapter.getDefaultAdapter();
        if(null == BA)
        {
            Toast.makeText(this, "Bluetooth Desteklenmiyor !", Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<String> pairedDevicesNameList = new ArrayList<>();
        this.pairedDevices = BA.getBondedDevices();
        for(BluetoothDevice device : this.pairedDevices)
        {
            pairedDevicesNameList.add(device.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter(this, R.layout.support_simple_spinner_dropdown_item, pairedDevicesNameList);
        this.bluetoothDevicesSpinner.setAdapter(adapter);
    }

    private void connectTargetDevice(BluetoothDevice secilenDevice)
    {
        try
        {
            EntranceActivity.this.btSocket = secilenDevice.createRfcommSocketToServiceRecord(BT_UUID);
            EntranceActivity.this.btSocket.connect();

            // Input output stream elde edilir.
            EntranceActivity.this.btOutputStream = EntranceActivity.this.btSocket.getOutputStream();
            EntranceActivity.this.btInputStream = EntranceActivity.this.btSocket.getInputStream();

            this.btBaglantiDurumuTextView.setTextColor(Color.GREEN);
            this.btBaglantiDurumuTextView.setText(R.string.baglanti_durumu_bagli_text);
            this.buttonConnect.setText(R.string.disconnect_button_text);
            this.bluetoothDevicesSpinner.setEnabled(false);
            this.baglanilanAracDeviceNameTextView.setText(secilenDevice.getName()); // Baglanilan cihaz ismi arayuzde gosterilir.

            this.btBaglantiDurumu = true;
        }
        catch (Exception ex)
        {
            Toast.makeText(EntranceActivity.this, "Hata : " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void disconnectTargetDevice()
    {
        try
        {
            // Hedef cihaz bilgileri resetlenir, reset paketi gonderilir.
            // Reset OK paketi alinirsa, baglanti koparilir.
            this.deviceResetOKPacketReceived = false;
            while(!this.deviceResetOKPacketReceived)
            {
                sendDeviceResetPacket();
                System.out.println("Reset paketi gonderiliyor....");
            }

            this.btBaglantiDurumu = false;
            btSocket.close();
            btInputStream.close();
            btOutputStream.close();

            /* paket receive bitene kadar bekle */
            while(this.packetReceiveState != PACKET_RECEIVE_STATES.PACKET_RECEIVE_SUSPENDED)
            {
                System.out.println("Receiver threadin durmasi bekleniyor....");
            }

            this.btBaglantiDurumuTextView.setTextColor(Color.RED);
            this.btBaglantiDurumuTextView.setText(R.string.baglanti_durumu_bagli_degil_text);
            this.buttonConnect.setText(R.string.connect_button_text);
            this.bluetoothDevicesSpinner.setEnabled(true);
            this.baglanilanAracDeviceNameTextView.setText("-");

            new Thread(() -> {
                /* score update gui thread bitene kadar bekle */
                while(!this.scoreUpdateDone)
                {
                    System.out.println("GUI Threadinin Bitmesi Bekleniyor...");
                }

                this.runOnUiThread(() -> {
                    synchronized (userScoreMutex)
                    {
                        this.kullaniciSkorTextView.setText("0");
                    }
                    this.bataryaBilgiTextView.setText("0");
                });

            }).start();
        }
        catch (Exception ex)
        {
            Toast.makeText(EntranceActivity.this, "Hata : " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private long CRC32(int[] data, long n, long poly, long xor)
    {
        long g = 1L << n | poly;
        long crc = 0xFFFFFFFF;
        for(int b : data)
        {
            crc ^= (long) b << (n - 8);
            for (int i = 0; i < 8; i++)
            {
                crc <<=1 ;
                if(4294967296L == (crc & (1L << n)))
                {
                    crc ^= g;
                }
            }
        }

        return crc ^ xor;
    }

    private void startRcCommandSender()
    {
        this.rcCommandSenderThread = new Thread(() ->
        {
            while(true)
            {
                try
                {
                    if(btBaglantiDurumu && !this.isDeviceFlat)
                    {
                        sendRCcommandPacket();
                    }

                    Thread.sleep(RC_KOMUT_GONDERIM_BEKLEME_SURESI);

                }catch (Exception ex)
                {
                    System.out.println("RC komut gonderme hata : " + ex.getMessage());
                }
            }
        });
        this.rcCommandSenderThread.start();
    }

    private void startRcPacketListener()
    {
        this.rcPacketListenerThread = new Thread(() ->
        {
            final int GAME_DATA_SIZE = 4;
            int[] receivedDataBuffer = new int[GAME_DATA_SIZE];
            int receivedDataBuffer_index = 0;

            long[] crcDataBuffer = new long[4];
            int crcDataBuffer_index = 0;
            long crc_received = 0;

            while(true)
            {
                try
                {
                    if(btBaglantiDurumu)
                    {
                        int readed_byte = this.btInputStream.read();
                        if(packetReceiveState == PACKET_RECEIVE_STATES.PACKET_RECEIVE_SUSPENDED)
                        {
                            packetReceiveState = PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_HEADER_1;
                            // Receiver reset.
                            receivedDataBuffer_index = 0;
                            crcDataBuffer_index = 0;
                            crc_received = 0;
                        }

                        if(readed_byte >= 0)
                        {
                            switch (packetReceiveState)
                            {
                                case PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_HEADER_1:
                                {
                                    if(readed_byte == RC_COMMAND_PACKET_HEADER_1)
                                        packetReceiveState = PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_HEADER_2;
                                    break;
                                }

                                case PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_HEADER_2:
                                {
                                    if(readed_byte == RC_COMMAND_PACKET_HEADER_2)
                                        packetReceiveState = PACKET_RECEIVE_STATES.PACKET_RECEIVE_RC_COMMAND;
                                    else
                                        packetReceiveState = PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_HEADER_1; // Reset the receiver.
                                    break;
                                }

                                case PACKET_RECEIVE_STATES.PACKET_RECEIVE_RC_COMMAND:
                                {
                                    receivedDataBuffer[receivedDataBuffer_index++] = readed_byte;
                                    if(GAME_DATA_SIZE == receivedDataBuffer_index)
                                    {
                                        packetReceiveState = PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_COLLECT_CRC;
                                    }
                                    break;
                                }

                                case PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_COLLECT_CRC:
                                {
                                    crcDataBuffer[crcDataBuffer_index++] = readed_byte;
                                    if(4 == crcDataBuffer_index)
                                    {
                                        crc_received |= crcDataBuffer[0] << 24;
                                        crc_received |= crcDataBuffer[1] << 16;
                                        crc_received |= crcDataBuffer[2] << 8;
                                        crc_received |= crcDataBuffer[3];

                                        packetReceiveState = PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_CRC_CHECK;
                                    }
                                    break;
                                }

                                case PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_CRC_CHECK:
                                {
                                    //int[] dataBufferIntArray = rcDataToIntArray(receivedDataBuffer);
                                    long crc_calculated = CRC32(receivedDataBuffer, 32, 0x04C11DB7, 0);

                                    if(crc_calculated == crc_received)
                                    {
                                        // Once, reset OK paketi kontrol edilir.
                                        if(checkResetOKPacket(receivedDataBuffer))
                                        {
                                            // hedef cihaz bilgileri resetlendi.
                                            this.deviceResetOKPacketReceived = true;
                                        }
                                        else
                                        {
                                            synchronized (EntranceActivity.this)
                                            {
                                                this.lastCrcSuccesTime = new Date();
                                            }

                                            this.runOnUiThread(() -> {

                                                int scoreOld = Integer.parseInt(kullaniciSkorTextView.getText().toString());
                                                EntranceActivity.this.scoreUpdateDone = false;
                                                synchronized (this.userScoreMutex)
                                                {
                                                    kullaniciSkorTextView.setText("" + receivedDataBuffer[3]);
                                                }
                                                bataryaBilgiTextView.setText("" + receivedDataBuffer[2]);
                                                EntranceActivity.this.scoreUpdateDone = true;
                                                int scoreNew = Integer.parseInt(kullaniciSkorTextView.getText().toString());

                                                // If shooted, vibrate the device.
                                                if(scoreNew > scoreOld)
                                                {
                                                    vibrator.vibrate(200);
                                                }
                                            });
                                        }
                                    }

                                    // Receiver reset.
                                    receivedDataBuffer_index = 0;
                                    crcDataBuffer_index = 0;
                                    crc_received = 0;

                                    packetReceiveState = PACKET_RECEIVE_STATES.PACKET_RECEIVE_STATE_HEADER_1;
                                    break;
                                }
                            }
                        }
                    }
                    else
                    {
                        packetReceiveState = PACKET_RECEIVE_STATES.PACKET_RECEIVE_SUSPENDED;
                    }
                }
                catch (Exception ex)
                {
                    System.out.println("Rc paket listener : " + ex.getMessage());
                }
            }
        });
        this.rcPacketListenerThread.start();
    }

    private byte[] rcDataToByteArray()
    {
        byte[] byte_array = new byte[4];
        byte_array[0] = (byte)this.RC_komut_steeringAngle;
        byte_array[1] = (byte)this.RC_komut_throttlePos;
        byte_array[2] = (byte)this.RC_komut_gearPosition;
        byte_array[3] = (byte)this.RC_komut_fireTrigger;
        return byte_array;
    }

    private int[] rcDataToIntArray(byte[] bytes)
    {
        int[] byte_array = new int[4];
        for (int i = 0; i < 4; i++) {
            byte_array[i] = bytes[i];
        }
        return byte_array;
    }

    private void sendRCcommandPacket()
    {
        try
        {
            byte[] gonderilecek_paket       = new byte[10];
            byte[] RC_komut_paketi_bytes    = rcDataToByteArray();
            int[]  RC_komut_paketi_int      = rcDataToIntArray(RC_komut_paketi_bytes);

            long crc_32 = CRC32(RC_komut_paketi_int, 32, 0x04C11DB7, 0);

            /* Gonderilecek veri hazirlanir */
            gonderilecek_paket[0] = RC_COMMAND_PACKET_HEADER_1;
            gonderilecek_paket[1] = RC_COMMAND_PACKET_HEADER_2;
            gonderilecek_paket[2] = RC_komut_paketi_bytes[0];
            gonderilecek_paket[3] = RC_komut_paketi_bytes[1];
            gonderilecek_paket[4] = RC_komut_paketi_bytes[2];
            gonderilecek_paket[5] = RC_komut_paketi_bytes[3];

            // CRC hesabi eklenir.
            gonderilecek_paket[6] = (byte) ((crc_32 >> 24) & 0xFF);
            gonderilecek_paket[7] = (byte) ((crc_32 >> 16) & 0xFF);
            gonderilecek_paket[8] = (byte) ((crc_32 >> 8) & 0xFF);
            gonderilecek_paket[9] = (byte) ((crc_32) & 0xFF);
            /* Gonderilecek veri hazirlanir */

            // Veri gonderilir.
            btOutputStream.write(gonderilecek_paket,0,10);
        }
        catch (Exception ex)
        {
            System.out.println(ex.getMessage());
        }
    }

    private void sendRCcommandPacket(byte[] RC_komut_paketi_bytes)
    {
        try
        {
            byte[] gonderilecek_paket       = new byte[10];
            int[]  RC_komut_paketi_int      = rcDataToIntArray(RC_komut_paketi_bytes);

            long crc_32 = CRC32(RC_komut_paketi_int, 32, 0x04C11DB7, 0);

            /* Gonderilecek veri hazirlanir */
            gonderilecek_paket[0] = RC_COMMAND_PACKET_HEADER_1;
            gonderilecek_paket[1] = RC_COMMAND_PACKET_HEADER_2;
            gonderilecek_paket[2] = RC_komut_paketi_bytes[0];
            gonderilecek_paket[3] = RC_komut_paketi_bytes[1];
            gonderilecek_paket[4] = RC_komut_paketi_bytes[2];
            gonderilecek_paket[5] = RC_komut_paketi_bytes[3];

            // CRC hesabi eklenir.
            gonderilecek_paket[6] = (byte) ((crc_32 >> 24) & 0xFF);
            gonderilecek_paket[7] = (byte) ((crc_32 >> 16) & 0xFF);
            gonderilecek_paket[8] = (byte) ((crc_32 >> 8) & 0xFF);
            gonderilecek_paket[9] = (byte) ((crc_32) & 0xFF);
            /* Gonderilecek veri hazirlanir */

            // Veri gonderilir.
            btOutputStream.write(gonderilecek_paket,0,10);
        }
        catch (Exception ex)
        {
            System.out.println(ex.getMessage());
        }
    }

    private boolean checkResetOKPacket(int[] receivedPacket)
    {
        for (int i = 0; i < 4; i++)
            if(255 != receivedPacket[i])
                return false;
        return true;
    }

    private void sendDeviceResetPacket()
    {
        byte[] deviceResetPacket = new byte[4];
        deviceResetPacket[0] = (byte)255;
        deviceResetPacket[1] = (byte)255;
        deviceResetPacket[2] = (byte)255;
        deviceResetPacket[3] = (byte)255;
        sendRCcommandPacket(deviceResetPacket);
    }

    private static class PACKET_RECEIVE_STATES
    {
        private static final int PACKET_RECEIVE_STATE_HEADER_1 = 0;
        private static final int PACKET_RECEIVE_STATE_HEADER_2 = 1;
        private static final int PACKET_RECEIVE_RC_COMMAND = 2;
        private static final int PACKET_RECEIVE_STATE_COLLECT_CRC = 3;
        private static final int PACKET_RECEIVE_STATE_CRC_CHECK = 4;
        private static final int PACKET_RECEIVE_SUSPENDED = 5;
        private static final int PACKET_RECEIVE_STATE_NUM = 6;
    }

    /* Database operations */
    public void connectOnlineBattleSession()
    {
        System.out.println("RC Battle Baglanti gerceklestiriliyor...");
        this.buttonReady.setClickable(false);
        new Thread(() ->
        {
            HashMap<String, String> newEntry = new HashMap<>();
            String target_car_id = this.baglanilanAracDeviceNameTextView.getText().toString();
            newEntry.put("score","0");
            newEntry.put("timestamp","0");

            DatabaseReference ref = FirebaseDatabase.getInstance().getReference(RC_BATTLE_SESSION_DB_FILED).child(target_car_id);
            ref.setValue(newEntry).addOnCompleteListener(task -> {
                if(task.isSuccessful())
                {
                    EntranceActivity.this.battle_session_connection = true;
                    EntranceActivity.this.runOnUiThread(() -> {
                        EntranceActivity.this.buttonReady.setClickable(true);
                        EntranceActivity.this.buttonReady.setText(R.string.cancel_button_text);
                        System.out.println("Baglanti tamamlandi.");
                    });
                    this.startBattleSessionUpdaterThread();
                }
                else
                {
                    EntranceActivity.this.runOnUiThread(() -> {
                        EntranceActivity.this.buttonReady.setClickable(true);
                        System.out.println("Baglanti sirasinda hata olustu");
                    });
                }
            });
        }).start();
    }

    public void disconnectOnlineBattleSession()
    {
        System.out.println("RC Battle Baglanti kesiliyor...");
        this.buttonReady.setClickable(false);
        new Thread(() -> {

            /* Wait until updater thread stops */
            synchronized (battleSessionUpdaterMutex)
            {
                try
                {
                    EntranceActivity.this.battle_session_connection = false;
                    while(!this.battleSessionUpdaterThreadStopped)
                        battleSessionUpdaterMutex.wait();
                }
                catch (Exception ex)
                {
                    System.out.println("Disconnect Button DB Handler Updater Wait : " + ex.getMessage());
                }
            }

            /* Wait until receiver thread stops */
            synchronized (battleSessionReceiverMutex)
            {
                try
                {
                    EntranceActivity.this.battle_session_connection = false;
                    while(!this.battleSessionReceiverThreadStopped)
                        battleSessionReceiverMutex.wait();
                }
                catch (Exception ex)
                {
                    System.out.println("Disconnect Button DB Handler Receiver Wait : " + ex.getMessage());
                }
            }

            DatabaseReference ref = FirebaseDatabase.getInstance().getReference(RC_BATTLE_SESSION_DB_FILED).child(this.baglanilanAracDeviceNameTextView.getText().toString());
            ref.removeValue().addOnCompleteListener(task -> {
                if(task.isSuccessful())
                {
                    EntranceActivity.this.runOnUiThread(() -> {
                        EntranceActivity.this.buttonReady.setClickable(true);
                        EntranceActivity.this.buttonReady.setText(R.string.start_button_text);
                        EntranceActivity.this.macDurumuTextView.setText("");
                        EntranceActivity.this.rakipAracDeviceNameTextView.setText("-");
                        System.out.println("RC Battle Baglanti kesildi");
                    });
                }
                else
                {
                    EntranceActivity.this.runOnUiThread(() -> {
                        EntranceActivity.this.buttonReady.setClickable(true);
                        System.out.println("RC Battle Baglanti Kesilemedi");
                    });

                    /* Restart the updater thread */
                    EntranceActivity.this.battle_session_connection = true;
                    this.startBattleSessionUpdaterThread();
                }
            });
        }).start();
    }

    public void startBattleSessionUpdaterThread()
    {
        new Thread(() -> {

            System.out.println("Battle Session Updater Thread Started");
            DatabaseReference ref = FirebaseDatabase.getInstance().getReference(RC_BATTLE_SESSION_DB_FILED).child(this.baglanilanAracDeviceNameTextView.getText().toString());
            HashMap<String, String> db_entry = new HashMap<>();

            SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

            // Start data receiver thread.
            this.startBattleSessionReceiverThread();

            while(this.battle_session_connection)
            {
                try
                {
                    /* update score and timestamp on the database */
                    Date timestamp = new Date();
                    String userScore = "";
                    synchronized (userScoreMutex)
                    {
                        userScore = kullaniciSkorTextView.getText().toString();
                    }

                    db_entry.put("score", userScore);
                    db_entry.put("timestamp", formatter.format(timestamp));
                    ref.setValue(db_entry);

                    Thread.sleep(1000);
                }
                catch (Exception ex)
                {
                    System.out.println("Battle Session Updater Thread Exception  : " + ex.getMessage());
                }
            }

            this.battleSessionUpdaterThreadStopped = true;
            synchronized (this.battleSessionUpdaterMutex)
            {
                this.battleSessionUpdaterMutex.notifyAll();
            }

            System.out.println("Battle Session Updater Thread Stopped");
        }).start();
    }

    public void startBattleSessionReceiverThread()
    {
        new Thread(() -> {

            System.out.println("Battle Session Receiver Thread Started");
            this.runOnUiThread(() -> {
                this.macDurumuTextView.setText(R.string.enemy_waiting_text);
            });

            /* wait until enemy connected */
            while(this.battle_session_connection && !this.enemyConnected)
            {
                try
                {
                    DatabaseReference ref = FirebaseDatabase.getInstance().getReference(RC_BATTLE_SESSION_DB_FILED);
                    ref.addValueEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            Log.i("DB Retrieve" ,"Current Connection Count : "+snapshot.getChildrenCount());
                            for (DataSnapshot postSnapshot: snapshot.getChildren())
                            {
                                DBSessionEntry post = postSnapshot.getValue(DBSessionEntry.class);
                                post.setId(postSnapshot.getKey());

                                Log.i("DB Retrieve" ,"Connection : " + post);

                                if(!post.getId().equals(EntranceActivity.this.baglanilanAracDeviceNameTextView.getText().toString()))
                                {
                                    // Enemy connected.
                                    EntranceActivity.this.connectedEnemyID = post.getId();
                                    EntranceActivity.this.enemyConnected = true;
                                    ref.removeEventListener(this);
                                }
                            }
                            System.out.println("-----------------------------------");
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {

                        }
                    });

                    System.out.println("No enemy connected. Waiting...");
                    Thread.sleep(1000);
                }
                catch (Exception ex)
                {
                    System.out.println("Enemy wait exception : " + ex.getMessage());
                }
            }

            System.out.println("Enemy Phone Connected ! Starting to listen...");
            EntranceActivity.this.runOnUiThread(() -> {
                this.rakipAracDeviceNameTextView.setText(this.connectedEnemyID);
                this.macDurumuTextView.setText(R.string.match_started_text);
            });

            while(this.battle_session_connection && enemyConnected)
            {
                try
                {
                    /* Get enemy information */
                    DatabaseReference ref = FirebaseDatabase.getInstance().getReference(RC_BATTLE_SESSION_DB_FILED).child(this.connectedEnemyID);
                    ref.addValueEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot)
                        {
                            DBSessionEntry enemyInfo = snapshot.getValue(DBSessionEntry.class);
                            if(null == enemyInfo)
                            {
                                ref.removeEventListener(this);
                                EntranceActivity.this.enemyConnected = false;
                                EntranceActivity.this.disconnectOnlineBattleSession();

                                // Show different messages according to canceller.
                                if(!cancelClickedByOwn)
                                {
                                    EntranceActivity.this.runOnUiThread(() -> Toast.makeText(EntranceActivity.this, "Rakip Bağlantısı Koptu. Maç İptal Edildi.", Toast.LENGTH_LONG).show());
                                }
                                else
                                {
                                    EntranceActivity.this.runOnUiThread(() -> Toast.makeText(EntranceActivity.this, "Bağlantı Koptu. Maç İptal Edildi.", Toast.LENGTH_LONG).show());
                                }
                                return;
                            }
                            enemyInfo.setId(EntranceActivity.this.connectedEnemyID);
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                            Log.e("DB Retrieve : ", "Enemy Data Cannot Retrieved " + error.getMessage());
                        }
                    });

                    Thread.sleep(1000);
                }
                catch (Exception ex)
                {
                    System.out.println("Battle Session Receiver Thread : " + ex.getMessage());
                }
            }

            this.battleSessionReceiverThreadStopped = true;
            this.enemyConnected = false;
            synchronized (this.battleSessionReceiverMutex)
            {
                this.battleSessionReceiverMutex.notifyAll();
            }

            System.out.println("Battle Session Receiver Thread Stopped");
        }).start();
    }

    public static final class DBSessionEntry
    {
        private String id;
        private String timestamp;
        private String score;

        public DBSessionEntry(){

        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public String toString()
        {
            return "[id:" + this.id + ", score:"+this.score + ", timestamp:" + this.timestamp + "]";
        }

        public String getScore() {
            return score;
        }

        public void setScore(String score) {
            this.score = score;
        }
    }
}