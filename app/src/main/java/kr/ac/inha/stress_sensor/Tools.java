package kr.ac.inha.stress_sensor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;


import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import inha.nsl.easytrack.ETServiceGrpc;
import inha.nsl.easytrack.EtService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import kr.ac.inha.stress_sensor.receivers.GeofenceReceiver;
import kr.ac.inha.stress_sensor.services.CustomSensorsService;
import kr.ac.inha.stress_sensor.services.LocationService;

import static android.content.Context.MODE_PRIVATE;
import static kr.ac.inha.stress_sensor.EMAActivity.EMA_NOTIF_HOURS;
import static kr.ac.inha.stress_sensor.MainActivity.PERMISSIONS;
import static kr.ac.inha.stress_sensor.MainActivity.PERMISSION_ALL;
import static kr.ac.inha.stress_sensor.services.CustomSensorsService.EMA_RESPONSE_EXPIRE_TIME;
import static kr.ac.inha.stress_sensor.services.CustomSensorsService.SERVICE_START_X_MIN_BEFORE_EMA;

public class Tools {
    private static final String TAG = "TOOLS";
    static final short
            RES_OK = 0,
            RES_FAIL = 1,
            RES_SRV_ERR = -1;

    static final String DATA_SOURCE_SEPARATOR = " ";

    public static void checkAndSendUsageAccessStats(Context con) throws IOException {
        SharedPreferences loginPrefs = con.getSharedPreferences("UserLogin", MODE_PRIVATE);
        long lastSavedTimestamp = loginPrefs.getLong("lastUsageSubmissionTime", -1);

        Calendar fromCal = Calendar.getInstance();
        if (lastSavedTimestamp == -1)
            fromCal.add(Calendar.DAY_OF_WEEK, -2);
        else
            fromCal.setTime(new Date(lastSavedTimestamp));

        final Calendar tillCal = Calendar.getInstance();
        tillCal.set(Calendar.MILLISECOND, 0);

        PackageManager localPackageManager = con.getPackageManager();
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addCategory("android.intent.category.HOME");
        String launcher_packageName = localPackageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY).activityInfo.packageName;

        UsageStatsManager usageStatsManager = (UsageStatsManager) con.getSystemService(Context.USAGE_STATS_SERVICE);
        List<UsageStats> stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, fromCal.getTimeInMillis(), System.currentTimeMillis());
        for (UsageStats usageStats : stats) {
            //do not include launcher's package name
            if (usageStats.getTotalTimeInForeground() > 0 && !usageStats.getPackageName().contains(launcher_packageName)) {
                AppUseDb.saveAppUsageStat(usageStats.getPackageName(), usageStats.getLastTimeUsed(), usageStats.getTotalTimeInForeground());
            }
        }
        SharedPreferences.Editor editor = loginPrefs.edit();
        editor.putLong("lastUsageSubmissionTime", tillCal.getTimeInMillis());
        editor.apply();
    }

    static boolean hasPermissions(Activity activity, String... permissions) {
        Context context = activity.getApplicationContext();
        if (context != null && permissions != null)
            for (String permission : permissions)
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED)
                    return false;

        assert context != null;
        if (!isAppUsageAccessGranted(context))
            return false;

        return isGPSLocationOn(context);
    }

    private static boolean isGPSLocationOn(Context con) {
        LocationManager lm = (LocationManager) con.getSystemService(Context.LOCATION_SERVICE);
        boolean gps_enabled;
        boolean network_enabled;
        gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        return gps_enabled || network_enabled;
    }

    private static boolean isAppUsageAccessGranted(Context con) {
        try {
            PackageManager packageManager = con.getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(con.getPackageName(), 0);
            AppOpsManager appOpsManager = (AppOpsManager) con.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, applicationInfo.uid, applicationInfo.packageName);
            return (mode == AppOpsManager.MODE_ALLOWED);

        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static void grantPermissions(Activity activity, String... permissions) {
        boolean simple_permissions_granted = true;
        for (String permission : permissions)
            if (ActivityCompat.checkSelfPermission(activity.getApplicationContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                simple_permissions_granted = false;
                break;
            }

        if (!isAppUsageAccessGranted(activity.getApplicationContext()))
            activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        if (!isGPSLocationOn(activity.getApplicationContext()))
            activity.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        if (!simple_permissions_granted)
            ActivityCompat.requestPermissions(activity, PERMISSIONS, PERMISSION_ALL);


    }

    public static void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static ExecutorService executor = Executors.newCachedThreadPool();

    public static synchronized String post(String _url, JSONObject json_body) throws IOException {
        URL url = new URL(_url);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(2000);
        con.setDoOutput(json_body != null);
        con.setDoInput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        try {
            con.connect();
        } catch (SocketTimeoutException e) {
            return "";
        }


        if (json_body != null) {
            DataOutputStream wr = new DataOutputStream(con.getOutputStream());
            wr.writeBytes(convertToUTF8(json_body.toString()));
            wr.flush();
            wr.close();
        }

        int status = con.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            con.disconnect();
            return "";
        } else {
            byte[] buf = new byte[1024];
            int rd;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            BufferedInputStream is = new BufferedInputStream(con.getInputStream());
            while ((rd = is.read(buf)) > 0)
                bos.write(buf, 0, rd);
            is.close();
            con.disconnect();
            bos.close();
            return convertFromUTF8(bos.toByteArray());
        }
    }

    static String postFiles(String _url, String username, String password, File file) throws IOException {
        String responseString;
        HttpPost httppost = new HttpPost(_url);
        HttpClient httpclient = new DefaultHttpClient();

        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
        entityBuilder.setMode(HttpMultipartMode.STRICT);
        entityBuilder.addTextBody("username", username);
        entityBuilder.addTextBody("password", password);
        entityBuilder.addPart("file", new FileBody(file));
        HttpEntity entity = entityBuilder.build();

        httppost.setEntity(entity);

        HttpResponse response = httpclient.execute(httppost);
        HttpEntity httpEntity = response.getEntity();

        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 200) {
            // Server response
            responseString = EntityUtils.toString(httpEntity);
        } else {
            responseString = "Error occurred! Http Status Code: "
                    + statusCode;
        }

        return responseString;
    }

    //region Old method of posting file
    /*static String postFilesA(String _url, String username, String password, File file) throws IOException {
        String responseString = null;

        HttpClient httpclient = new DefaultHttpClient();
        HttpPost httppost = new HttpPost(_url);

        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
        entityBuilder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
        entityBuilder.addTextBody("password", password);
        entityBuilder.addTextBody("username", username);
        entityBuilder.addPart("file", new FileBody(file));

        HttpEntity entity = entityBuilder.build();
        httppost.setEntity(entity);

        HttpResponse response = httpclient.execute(httppost);
        HttpEntity httpEntity = response.getEntity();

        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 200) {
            // Server response
            responseString = EntityUtils.toString(httpEntity);
        } else {
            responseString = "Error occurred! Http Status Code: "
                    + statusCode;
        }

        return responseString;
    }*/
    //endregion

    private static String convertFromUTF8(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String convertToUTF8(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    static void enable_touch(Activity activity) {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    static void disable_touch(Activity activity) {
        activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    private static boolean isReachable;

    public static boolean isNetworkAvailable(final Context context) {
        isReachable = false;
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    String[] arr = context.getString(R.string.grpc_host).split("\\.");
                    byte[] addr = new byte[]{
                            (byte) (Integer.parseInt(arr[0])),
                            (byte) (Integer.parseInt(arr[1])),
                            (byte) (Integer.parseInt(arr[2])),
                            (byte) (Integer.parseInt(arr[3]))
                    };
                    try {
                        InetAddress inetAddress = InetAddress.getByAddress(addr);
                        isReachable = inetAddress.isReachable(100);
                    } catch (Exception e) {
                        isReachable = false;
                    }
                }
            });
            thread.start();
            thread.join();
        } catch (Exception e) {
            isReachable = false;
        }
        return isReachable;
    }

    @SuppressWarnings("unused")
    static float bytes2float(final byte[] data, final int startIndex) {
        byte[] floatBytes = new byte[4];
        System.arraycopy(data, startIndex, floatBytes, 0, 4);
        return ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    @SuppressWarnings("unused")
    public static int bytes2int(final byte[] data, final int startIndex) {
        byte[] intBytes = new byte[4];
        System.arraycopy(data, startIndex, intBytes, 0, 4);
        return ByteBuffer.wrap(intBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    @SuppressWarnings("unused")
    public static long bytes2long(final byte[] data, final int startIndex) {
        byte[] longBytes = new byte[8];
        System.arraycopy(data, startIndex, longBytes, 0, 8);
        return ByteBuffer.wrap(longBytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    public static byte[] short2bytes(final short value) {
        return new byte[]{(byte) value, (byte) (value >> 8)};
    }

    @SuppressWarnings("unused")
    public static byte[] int2bytes(final int value) {
        byte[] ret = new byte[4];
        ret[3] = (byte) (value & 0xFF);
        ret[2] = (byte) ((value >> 8) & 0xFF);
        ret[1] = (byte) ((value >> 16) & 0xFF);
        ret[0] = (byte) ((value >> 24) & 0xFF);
        return ret;
    }

    @SuppressWarnings("unused")
    @SuppressLint("NewApi")
    public static byte[] long2bytes(final long value) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(value);
        return buffer.array();
    }

    @SuppressWarnings("unused")
    public static byte[] float2bytes(float value) {
        return ByteBuffer.allocate(4).putFloat(value).array();
    }

    private static String[] bytes2hexStrings(final byte[] bytes, final int offset) {
        String[] res = new String[bytes.length - offset];
        for (int n = offset; n < bytes.length; n++) {
            int intVal = bytes[n] & 0xff;
            res[n - offset] = "";
            if (intVal < 0x10)
                res[n - offset] += "0";
            res[n - offset] += Integer.toHexString(intVal).toUpperCase();
        }
        return res;
    }

    private static String[] bytes2hexStrings(final byte[] bytes) {
        return bytes2hexStrings(bytes, 0);
    }

    @SuppressWarnings("unused")
    @SuppressLint("NewApi")
    public static String bytes2hexString(final byte[] bytes, final int offset) {
        return String.join(" ", bytes2hexStrings(bytes, offset));
    }

    @SuppressWarnings("unused")
    @SuppressLint("NewApi")
    public static String bytes2hexString(final byte[] bytes) {
        return String.join(" ", bytes2hexStrings(bytes));
    }

    static boolean isMainServiceRunning(Context con) {
        ActivityManager manager = (ActivityManager) con.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (CustomSensorsService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLocationServiceRunning(Context con) {
        ActivityManager manager = (ActivityManager) con.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (LocationService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean heartbeatNotSent(final Context con) throws InterruptedException {
        final SharedPreferences loginPrefs = con.getSharedPreferences("UserLogin", MODE_PRIVATE);

        if (Tools.isNetworkAvailable(con)) {
            new Thread() {
                @Override
                public void run() {
                    super.run();

                    ManagedChannel channel = ManagedChannelBuilder.forAddress(
                            con.getString(R.string.grpc_host),
                            Integer.parseInt(con.getString(R.string.grpc_port))
                    ).usePlaintext().build();
                    ETServiceGrpc.ETServiceBlockingStub stub = ETServiceGrpc.newBlockingStub(channel);
                    EtService.SubmitHeartbeatRequestMessage submitHeartbeatRequestMessage = EtService.SubmitHeartbeatRequestMessage.newBuilder()
                            .setUserId(loginPrefs.getInt(AuthenticationActivity.user_id, -1))
                            .setEmail(loginPrefs.getString(AuthenticationActivity.usrEmail, null))
                            .build();
                    try {
                        @SuppressWarnings("unused")
                        EtService.DefaultResponseMessage responseMessage = stub.submitHeartbeat(submitHeartbeatRequestMessage);
                    } catch (StatusRuntimeException e) {
                        Log.e("Tools", "DataCollectorService.setUpHeartbeatSubmissionThread() exception: " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        channel.shutdown();
                    }
                }
            }.start();
        }

        return false;
    }

    public static boolean checkIfInEMARange(Calendar cal) {
        long t = (cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)) * 1000;
        return (EMAActivity.EMA_NOTIF_MILLIS[0] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[0]) ||
                (EMAActivity.EMA_NOTIF_MILLIS[1] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[1]) ||
                (EMAActivity.EMA_NOTIF_MILLIS[2] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[2]) ||
                (EMAActivity.EMA_NOTIF_MILLIS[3] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[3]) ||
                (EMAActivity.EMA_NOTIF_MILLIS[4] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[4]) ||
                (EMAActivity.EMA_NOTIF_MILLIS[5] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[5]);
    }

    public static short getEMAOrderAtExactTime(Calendar cal) {
        short ema_order = 0;
        if (cal.get(Calendar.HOUR_OF_DAY) == EMA_NOTIF_HOURS[0] && cal.get(Calendar.MINUTE) == 0)
            ema_order = 1;
        else if (cal.get(Calendar.HOUR_OF_DAY) == EMA_NOTIF_HOURS[1] && cal.get(Calendar.MINUTE) == 0)
            ema_order = 2;
        else if (cal.get(Calendar.HOUR_OF_DAY) == EMA_NOTIF_HOURS[2] && cal.get(Calendar.MINUTE) == 0)
            ema_order = 3;
        else if (cal.get(Calendar.HOUR_OF_DAY) == EMA_NOTIF_HOURS[3] && cal.get(Calendar.MINUTE) == 0)
            ema_order = 4;
        else if (cal.get(Calendar.HOUR_OF_DAY) == EMA_NOTIF_HOURS[4] && cal.get(Calendar.MINUTE) == 0)
            ema_order = 5;
        else if (cal.get(Calendar.HOUR_OF_DAY) == EMA_NOTIF_HOURS[5] && cal.get(Calendar.MINUTE) == 0)
            ema_order = 6;

        return ema_order;

    }

    public static int getEMAOrderFromRangeBeforeEMA(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        int ema_order = 0;
        long t = (cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)) * 1000;
        if (EMAActivity.EMA_NOTIF_MILLIS[0] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[0])
            ema_order = 1;
        else if (EMAActivity.EMA_NOTIF_MILLIS[1] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[1])
            ema_order = 2;
        else if (EMAActivity.EMA_NOTIF_MILLIS[2] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[2])
            ema_order = 3;
        else if (EMAActivity.EMA_NOTIF_MILLIS[3] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[3])
            ema_order = 4;
        else if (EMAActivity.EMA_NOTIF_MILLIS[4] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[4])
            ema_order = 5;
        else if (EMAActivity.EMA_NOTIF_MILLIS[5] - SERVICE_START_X_MIN_BEFORE_EMA * 60 * 1000 <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[5])
            ema_order = 6;

        return ema_order;
    }

    static short getEMAOrderFromRangeAfterEMA(Calendar cal) {
        short ema_order = 0;
        long t = (cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)) * 1000;
        if (EMAActivity.EMA_NOTIF_MILLIS[0] <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[0] + EMA_RESPONSE_EXPIRE_TIME * 1000)
            ema_order = 1;
        else if (EMAActivity.EMA_NOTIF_MILLIS[1] <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[1] + EMA_RESPONSE_EXPIRE_TIME * 1000)
            ema_order = 2;
        else if (EMAActivity.EMA_NOTIF_MILLIS[2] <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[2] + EMA_RESPONSE_EXPIRE_TIME * 1000)
            ema_order = 3;
        else if (EMAActivity.EMA_NOTIF_MILLIS[3] <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[3] + EMA_RESPONSE_EXPIRE_TIME * 1000)
            ema_order = 4;
        else if (EMAActivity.EMA_NOTIF_MILLIS[4] <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[4] + EMA_RESPONSE_EXPIRE_TIME * 1000)
            ema_order = 5;
        else if (EMAActivity.EMA_NOTIF_MILLIS[5] <= t && t <= EMAActivity.EMA_NOTIF_MILLIS[5] + EMA_RESPONSE_EXPIRE_TIME * 1000)
            ema_order = 6;

        return ema_order;
    }

    public static void perform_logout(Context con) {

        SharedPreferences loginPrefs = con.getSharedPreferences("UserLogin", MODE_PRIVATE);
        SharedPreferences locationPrefs = con.getSharedPreferences("UserLocations", MODE_PRIVATE);

        SharedPreferences.Editor editorLocation = locationPrefs.edit();
        editorLocation.clear();
        editorLocation.apply();

        SharedPreferences.Editor editorLogin = loginPrefs.edit();
        editorLogin.remove("username");
        editorLogin.remove("password");
        editorLogin.putBoolean("logged_in", false);
        editorLogin.remove("ema_btn_make_visible");
        editorLogin.clear();
        editorLogin.apply();

        GeofenceHelper.removeAllGeofences(con);
    }
}

class GeofenceHelper {
    private static GeofencingClient geofencingClient;
    private static PendingIntent geofencePendingIntent;
    private static final String TAG = "GeofenceHelper";

    static void startGeofence(Context context, String location_id, LatLng position, int radius) {
        if (geofencingClient == null)
            geofencingClient = LocationServices.getGeofencingClient(context);

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        GeofencingRequest geofencingRequest = getGeofenceRequest(createGeofence(location_id, position, radius));
        Log.e(TAG, "Setting location with ID: " + geofencingRequest.getGeofences().get(0).getRequestId());
        geofencingClient.addGeofences(geofencingRequest, getGeofencePendingIntent(context))
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        // Geofences added
                        Log.e(TAG, "Geofence added");
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        // Failed to add geofences
                        Log.e(TAG, "Geofence add failed: " + e.toString());
                    }
                });

    }

    private static GeofencingRequest getGeofenceRequest(Geofence geofence) {
        return new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build();
    }

    private static Geofence createGeofence(String location_id, LatLng position, int radius) {
        return new Geofence.Builder()
                .setRequestId(location_id)
                .setCircularRegion(position.latitude, position.longitude, radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)  // geofence should never expire
                .setNotificationResponsiveness(60 * 1000)          //notifying after 60sec. Can save power
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                .build();
    }

    private static PendingIntent getGeofencePendingIntent(Context context) {
        // Reuse the PendingIntent if we already have it.
        if (geofencePendingIntent != null) {
            return geofencePendingIntent;
        }
        Intent intent = new Intent(context, GeofenceReceiver.class);
        // We use FLAG_UPDATE_CURRENT so that we get the same pending intent back when
        // calling addGeofences() and removeGeofences().
        geofencePendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        return geofencePendingIntent;
    }

    static void removeGeofence(Context context, String reqID) {
        if (geofencingClient == null)
            geofencingClient = LocationServices.getGeofencingClient(context);

        ArrayList<String> reqIDs = new ArrayList<>();
        reqIDs.add(reqID);
        geofencingClient.removeGeofences(reqIDs)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        // Geofences removed
                        Log.e(TAG, "Geofence removed");
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        // Failed to remove geofences
                        Log.e(TAG, "Geofence not removed: " + e.toString());
                    }
                });
    }

    static void removeAllGeofences(Context context) {
        if (geofencingClient == null)
            geofencingClient = LocationServices.getGeofencingClient(context);
        geofencingClient.removeGeofences(getGeofencePendingIntent(context));
    }
}
