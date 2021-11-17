package com.microsoft.azure.iot.sdk.samples.androidsample;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.Device;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodData;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.Property;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeCallback;
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeReason;
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback;
import com.microsoft.azure.sdk.iot.device.IotHubMessageResult;
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;
import com.microsoft.azure.sdk.iot.device.Message;
import com.microsoft.azure.sdk.iot.device.transport.ExponentialBackoffWithJitter;
import com.microsoft.azure.sdk.iot.device.transport.IotHubConnectionStatus;
import com.microsoft.azure.sdk.iot.device.transport.RetryPolicy;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;


public class MainActivity extends AppCompatActivity {

    private double temperature;
    private double humidity;
    private Message sendMessage;
    private String lastException;

    private DeviceClient client;

    IotHubClientProtocol protocol = IotHubClientProtocol.MQTT;

    Button btnStart;
    Button btnStop;

    TextView txtMsgsSentVal;
    TextView txtLastTempVal;
    TextView txtLastHumidityVal;
    TextView txtLastMsgSentVal;
    TextView txtLastMsgReceivedVal;
    TextView txtLastMsgInterval;
    TextView txtRetryCountVal;
    TextView txtMinBackOffVal;
    TextView txtMaxBackOffVal;
    TextView txtDeltaBackOffVal;
    TextView txtFirstFastRetryVal;

    private int msgSentCount = 0;
    private int receiptsConfirmedCount = 0;
    private int sendFailuresCount = 0;
    private int msgReceivedCount = 0;
    private int sendMessagesInterval = 5000;

    private int retryCount = 50000;
    private int minBackOff = 100;
    private int maxBackOff = 100000;
    private int deltaBackOff = 100;
    private boolean firstFastRetry = false;

    private final Handler handler = new Handler();
    private Thread sendThread;

    private static final int METHOD_SUCCESS = 200;
    public static final int METHOD_THROWS = 403;
    private static final int METHOD_NOT_DEFINED = 404;

    @SuppressLint("WrongConstant")
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);

        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        txtMsgsSentVal = findViewById(R.id.txtMsgsSentVal);
        txtLastTempVal = findViewById(R.id.txtLastTempVal);
        txtLastHumidityVal = findViewById(R.id.txtLastHumidityVal);
        txtLastMsgSentVal = findViewById(R.id.txtLastMsgSentVal);
        txtLastMsgReceivedVal = findViewById(R.id.txtLastMsgReceivedVal);
        txtLastMsgInterval = findViewById(R.id.txtSendIntervalVal);
        txtRetryCountVal = findViewById(R.id.txtRetryCountVal);
        txtMinBackOffVal = findViewById(R.id.txtMinBackOffVal);
        txtMaxBackOffVal = findViewById(R.id.txtMaxBackOffVal);
        txtDeltaBackOffVal = findViewById(R.id.txtDeltaBackOffVal);
        txtFirstFastRetryVal = findViewById(R.id.txtFirstFastRetryVal);

        txtLastMsgSentVal.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);

        btnStop.setEnabled(false);
    }

    private void stop()
    {
        new Thread(() -> {
            try
            {
                sendThread.interrupt();
                client.closeNow();
                System.out.println("Shutting down...");
            }
            catch (Exception e)
            {
                lastException = "Exception while closing IoTHub connection: " + e;
                handler.post(exceptionRunnable);
            }
        }).start();
    }

    public void btnStopOnClick(View v)
    {
        stop();

        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    private void start()
    {
        sendThread = new Thread(() -> {
            try
            {
                initClient();
                for(;;)
                {
                    sendMessages();
                    Thread.sleep(sendMessagesInterval);
                }
            }
            catch (InterruptedException e)
            {
                //return;
            }
            catch (Exception e)
            {
                lastException = "Exception while opening IoTHub connection: " + e;
                handler.post(exceptionRunnable);
            }
        });

        sendThread.start();
    }

    public void btnStartOnClick(View v)
    {
        start();

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
    }

    final Runnable updateRunnable = new Runnable() {
        @SuppressLint({"DefaultLocale", "SetTextI18n"})
        public void run() {
            txtLastTempVal.setText(String.format("%.2f",temperature));
            txtLastHumidityVal.setText(String.format("%.2f",humidity));
            txtMsgsSentVal.setText(Integer.toString(msgSentCount));
            txtLastMsgSentVal.setText("[" + new String(sendMessage.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET) + "]");
            txtLastMsgInterval.setText(String.valueOf(sendMessagesInterval));

            txtRetryCountVal.setText(String.valueOf(retryCount));
            txtMinBackOffVal.setText(String.valueOf(minBackOff));
            txtMaxBackOffVal.setText(String.valueOf(maxBackOff));
            txtDeltaBackOffVal.setText(String.valueOf(deltaBackOff));
            txtFirstFastRetryVal.setText(String.valueOf(firstFastRetry));

        }
    };

    final Runnable exceptionRunnable = new Runnable() {
        public void run() {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setMessage(lastException);
            builder.show();
            System.out.println(lastException);
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
        }
    };

    final Runnable methodNotificationRunnable = () -> {
        Context context = getApplicationContext();
        CharSequence text = "Set Send Messages Interval to " + sendMessagesInterval + "ms";
        int duration = Toast.LENGTH_LONG;

        Toast toast = Toast.makeText(context, text, duration);
        toast.show();
    };

    @SuppressLint("DefaultLocale")
    private void sendMessages()
    {
        temperature = 20.0 + Math.random() * 100;
        humidity = 30.0 + Math.random() * 200;
        BatteryManager bm = (BatteryManager) getApplicationContext().getSystemService(BATTERY_SERVICE);
        int batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        Log.e("battery", String.valueOf(batLevel));
//        String msgStr = "\"temperature\":" + String.format("%.2f", temperature) +
//                ", \"humidity\":" + String.format("%.2f", humidity) +
//                ", \"battery\":" + batLevel;

        String msgStr = "\"temperature\":" + (int) temperature +
                ", \"humidity\":" + (int) humidity +
                ", \"battery\":" + batLevel;
        try
        {
            sendMessage = new Message(msgStr);
            sendMessage.setProperty("temperatureAlert", temperature > 28 ? "true" : "false");
            sendMessage.setMessageId(java.util.UUID.randomUUID().toString());
            System.out.println("Message Sent: " + msgStr);
            EventCallback eventCallback = new EventCallback();
            client.sendEventAsync(sendMessage, eventCallback, msgSentCount);
            msgSentCount++;
            handler.post(updateRunnable);
        }
        catch (Exception e)
        {
            System.err.println("Exception while sending event: " + e);
        }
    }

    protected static class DeviceTwinStatusCallBack implements IotHubEventCallback {
        @Override
        public void execute(IotHubStatusCode status, Object context) {
            System.out.println("IoT Hub responded to device twin operation with status " + status.name());
        }
    }

    private void initClient() throws URISyntaxException, IOException
    {
        String connString = BuildConfig.DeviceConnectionString;
        client = new DeviceClient(connString, protocol);

        // Create a Device object to store the device twin properties
        Device dataCollector = new Device() {
            // Print details when a property value changes
            @Override
            public void PropertyCall(String propertyKey, Object propertyValue, Object context) {
                System.out.println(propertyKey + " changed to " + propertyValue);
                Log.e("devicetwin", propertyKey+ "   "+propertyValue);
                try
                {
                    JSONObject json = new JSONObject(propertyValue.toString());
                    sendMessagesInterval = json.getInt("interval");

                    retryCount = json.getInt("retrycount");
                    minBackOff = json.getInt("minbackoff");
                    maxBackOff = json.getInt("maxbackoff");
                    deltaBackOff = json.getInt("deltabackoff");
                    firstFastRetry = json.getBoolean("firstfastretry");

                    RetryPolicy retryPolicy = new ExponentialBackoffWithJitter(
                            retryCount,
                            minBackOff,
                            maxBackOff,
                            deltaBackOff,
                            firstFastRetry);
                    client.setRetryPolicy(retryPolicy);

                } catch (JSONException e)
                {
                    e.printStackTrace();
                    Log.e("error", e.toString());
                }
                    //sendMessagesInterval = Integer.parseInt(propertyValue.toString());
//                    Toast.makeText(getApplicationContext(), "Intervalo alterado para "+
//                            String.valueOf(sendMessagesInterval)+ "ms", Toast.LENGTH_LONG).show();
//                    txtLastMsgInterval.setText(propertyValue.toString());


            }
        };

        try
        {
            client.registerConnectionStatusChangeCallback(new IotHubConnectionStatusChangeCallbackLogger(), new Object());
            client.open();
            MessageCallback callback = new MessageCallback();
            client.setMessageCallback(callback, null);
            client.subscribeToDeviceMethod(new SampleDeviceMethodCallback(), getApplicationContext(), new DeviceMethodStatusCallBack(), null);
            client.startDeviceTwin(new DeviceTwinStatusCallBack(), null, dataCollector, null);

            // Create a reported property and send it to your IoT hub.
            dataCollector.setReportedProp(new Property("connectivityType", "cellular"));
            client.sendReportedProperties(dataCollector.getReportedProp());

            RetryPolicy retryPolicy = new ExponentialBackoffWithJitter(Integer.MAX_VALUE,100,1000,100,false);
            client.setRetryPolicy(retryPolicy);

        }
        catch (Exception e)
        {
            System.err.println("Exception while opening IoTHub connection: " + e);
            client.closeNow();
            System.out.println("Shutting down...");
        }
    }

    class EventCallback implements IotHubEventCallback
    {
        public void execute(IotHubStatusCode status, Object context)
        {
            int i = context instanceof Integer ? (Integer) context : 0;
            System.out.println("IoT Hub responded to message " + i
                    + " with status " + status.name());

            if((status == IotHubStatusCode.OK) || (status == IotHubStatusCode.OK_EMPTY))
            {
                TextView txtReceiptsConfirmedVal = findViewById(R.id.txtReceiptsConfirmedVal);
                receiptsConfirmedCount++;
                txtReceiptsConfirmedVal.setText(String.valueOf(receiptsConfirmedCount));
            }
            else
            {
                TextView txtSendFailuresVal = findViewById(R.id.txtSendFailuresVal);
                sendFailuresCount++;
                txtSendFailuresVal.setText(String.valueOf(sendFailuresCount));
            }
        }
    }

    class MessageCallback implements com.microsoft.azure.sdk.iot.device.MessageCallback
    {
        public IotHubMessageResult execute(Message msg, Object context)
        {
            System.out.println(
                    "Received message with content: " + new String(msg.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET));
            msgReceivedCount++;
            TextView txtMsgsReceivedVal = findViewById(R.id.txtMsgsReceivedVal);
            txtMsgsReceivedVal.setText(String.valueOf(msgReceivedCount));
            String msg2 = "[" + new String(msg.getBytes(), Message.DEFAULT_IOTHUB_MESSAGE_CHARSET) + "]";
            txtLastMsgReceivedVal.setText(msg2);
            return IotHubMessageResult.COMPLETE;
        }
    }

    protected static class IotHubConnectionStatusChangeCallbackLogger implements IotHubConnectionStatusChangeCallback
    {
        @Override
        public void execute(IotHubConnectionStatus status, IotHubConnectionStatusChangeReason statusChangeReason, Throwable throwable, Object callbackContext)
        {
            System.out.println();
            System.out.println("CONNECTION STATUS UPDATE: " + status);
            System.out.println("CONNECTION STATUS REASON: " + statusChangeReason);
            System.out.println("CONNECTION STATUS THROWABLE: " + (throwable == null ? "null" : throwable.getMessage()));
            System.out.println();

//            if (throwable != null)
//            {
//                throwable.printStackTrace();
//            }
//
//            if (status == IotHubConnectionStatus.DISCONNECTED)
//            {
//                //connection was lost, and is not being re-established. Look at provided exception for
//                // how to resolve this issue. Cannot send messages until this issue is resolved, and you manually
//                // re-open the device client
//            }
//            else if (status == IotHubConnectionStatus.DISCONNECTED_RETRYING)
//            {
//                //connection was lost, but is being re-established. Can still send messages, but they won't
//                // be sent until the connection is re-established
//            }
//            else if (status == IotHubConnectionStatus.CONNECTED)
//            {
//                //Connection was successfully re-established. Can send messages.
//            }
        }
    }

    private int method_setSendMessagesInterval(Object methodData) throws JSONException
    {
        String payload = new String((byte[])methodData, StandardCharsets.UTF_8).replace("\"", "");
        JSONObject obj = new JSONObject(payload);
        sendMessagesInterval = obj.getInt("sendInterval");
        handler.post(methodNotificationRunnable);
        return METHOD_SUCCESS;
    }

    private int method_default(Object data)
    {
        System.out.println("invoking default method for this device: "+ data.toString());
        // Insert device specific code here
        return METHOD_NOT_DEFINED;
    }

    protected static class DeviceMethodStatusCallBack implements IotHubEventCallback
    {
        public void execute(IotHubStatusCode status, Object context)
        {
            System.out.println("IoT Hub responded to device method operation with status " + status.name());
        }
    }

    protected class SampleDeviceMethodCallback implements com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceMethodCallback
    {
        @Override
        public DeviceMethodData call(String methodName, Object methodData, Object context)
        {
            DeviceMethodData deviceMethodData ;
            try {
                if ("setSendMessagesInterval".equals(methodName)) {
                    int status = method_setSendMessagesInterval(methodData);
                    deviceMethodData = new DeviceMethodData(status, "executed " + methodName);
                } else {
                    int status = method_default(methodData);
                    deviceMethodData = new DeviceMethodData(status, "executed " + methodName);
                }
            }
            catch (Exception e)
            {
                deviceMethodData = new DeviceMethodData(METHOD_THROWS, "Method Throws " + methodName);
            }
            return deviceMethodData;
        }
    }
}
