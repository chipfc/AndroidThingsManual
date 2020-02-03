package com.androidthings.helloworld;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManager;
import com.google.android.things.pio.UartDevice;
import com.google.android.things.pio.UartDeviceCallback;
import com.squareup.okhttp.Callback;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private Gpio mLedPin;

    TextView txtIP;
    EditText txtUserName;
    EditText txtPassword;
    Button btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initGPIO();
        initUART();
        setupBlinkyTimer();
        startMQTT();
        initUI();
    }

    private void initUI(){
        txtIP = findViewById(R.id.txtIP);
        txtUserName = findViewById(R.id.txtUserName);
        txtPassword = findViewById(R.id.txtPassword);
        btnConnect = findViewById(R.id.btnConnect);
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectWifiNetwork();
            }
        });
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try{
            mLedPin.close();
        }catch (Exception e){
            Log.d("LedBlinky", "Error in closing pin");
        }
        closeUART();
    }

    private void initGPIO(){
        PeripheralManager manager = PeripheralManager.getInstance();
        try {
            mLedPin = manager.openGpio("BCM16");
            mLedPin.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
        }catch (Exception e){
            Log.d("LedBlinky", "Error in opening pin");
        }
    }


    private byte counter = 0;
    private void setupBlinkyTimer(){
        Timer mTimer = new Timer();
        TimerTask mTask = new TimerTask() {
            @Override
            public void run() {

                //Log.d("LedBlinky", "Sent " + counter);
                //byte[] data = new byte[]{counter++};
                //if(counter >= 10) counter = 0;
                //sendUARTData(data);

                try {
                    mLedPin.setValue(!mLedPin.getValue());
                }catch (Exception e){
                    Log.d("LedBlinky", "Error in toggling pin");
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        txtIP.setText(getWifiIPAddress(MainActivity.this));
                    }
                });
            }
        };
        mTimer.schedule(mTask, 2000, 1000);
    }

    // UART Configuration Parameters
    private static final String UART_NAME = "UART0";
    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = 1;
    private UartDevice mUartDevice;


    private UartDeviceCallback mCallback = new UartDeviceCallback() {
        @Override
        public boolean onUartDeviceDataAvailable(UartDevice uartDevice) {
            try {
                byte[] buffer = new byte[128];
                int noBytes = 0;
                String strData = "";
                while ((noBytes = mUartDevice.read(buffer, buffer.length)) > 0) {

                    String str = new String(buffer,0,noBytes, "UTF-8");
                    Log.d("LedBlinky","Buffer is: " + str);

                    strData += str;
                }
                Log.d("LedBlinky","Full data is: " + strData);

                String[] splitData = strData.split(Pattern.quote(","));
                if(splitData[0].equals("1")){
                    Log.d("LedBlinky", "Temperature: " + splitData[1]);
                } else if(splitData[0].equals("2")){
                    Log.d("LedBlinky", "Light level: " + splitData[1]);
                }
                sendDataToThingSpeak(splitData[0], splitData[1]);

            }catch (Exception e){}
            return false;
        }
    };


    //byte[] buffer = new byte[1];
    //while (mUartDevice.read(buffer, buffer.length) > 0) {
    //    Log.d("LedBlinky", "Received: " + buffer[0]);
    //}

    private void initUART(){
        try {
            PeripheralManager manager = PeripheralManager.getInstance();
            mUartDevice = manager.openUartDevice(UART_NAME);
            mUartDevice.setBaudrate(BAUD_RATE);
            mUartDevice.setDataSize(DATA_BITS);
            mUartDevice.setParity(UartDevice.PARITY_NONE);
            mUartDevice.setStopBits(STOP_BITS);
            mUartDevice.registerUartDeviceCallback(mCallback);
        }catch (Exception e){
            Log.d("LedBlinky", "Error in opening UART");
        }
    }

    private void closeUART() {
        try{
            mUartDevice.unregisterUartDeviceCallback(mCallback);
            mUartDevice.close();
            mUartDevice = null;
        }catch (Exception e){
            Log.d("LedBlinky", "Error in closing UART");
        }
    }

    private void sendUARTData(byte[] data){
        try {
            mUartDevice.write(data, data.length);
        }catch (Exception e){
            Log.d("LedBlinky", "Error in sending data");
        }
    }


    private void sendDataToThingSpeak(String ID, String value){
        OkHttpClient okHttpClient = new OkHttpClient();
        Request.Builder builder = new Request.Builder();
        String apiURL = "https://api.thingspeak.com/update?api_key=ZDC919EE3WYRYGSE&field"
                + ID + "=" + value;
        Request request = builder.url(apiURL).build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Request request, IOException e) {

            }

            @Override
            public void onResponse(Response response) throws IOException {

            }
        });
    }

    MQTTHelper mqttHelper;
    private void startMQTT(){
        mqttHelper = new MQTTHelper(getApplicationContext());
        mqttHelper.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean b, String s) {

            }

            @Override
            public void connectionLost(Throwable throwable) {

            }

            @Override
            public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {

            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

            }
        });
    }


    private void sendDataToMQTT(String ID, String value){

        MqttMessage msg = new MqttMessage();
        msg.setId(1234);
        msg.setQos(0);
        msg.setRetained(true);

        String data = ID.equals("1")?"Temperature":"Light";
        data += ": " + value;

        byte[] b = data.getBytes(Charset.forName("UTF-8"));
        msg.setPayload(b);

        try {
            mqttHelper.mqttAndroidClient.publish("sensor/RP3", msg);

        }catch (MqttException e){
        }
    }


    private String getWifiIPAddress(Context context){

        WifiManager wifiManager = (WifiManager) (context.getSystemService(context.WIFI_SERVICE));
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ipAddress = wifiInfo.getIpAddress();
        String ipString = String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        return ipString;
    }

    private void connectWifiNetwork(){
        String userName = txtUserName.getText().toString();
        String passWord = txtPassword.getText().toString();
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.SSID = String.format("\"%s\"", userName);
        wifiConfig.preSharedKey = String.format("\"%s\"", passWord);


        WifiManager wifiManager = (WifiManager)getSystemService(WIFI_SERVICE);
        int netId = wifiManager.addNetwork(wifiConfig);

        wifiManager.disconnect();
        wifiManager.enableNetwork(netId, true);
        wifiManager.reconnect();
    }

}
