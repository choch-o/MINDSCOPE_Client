package kr.ac.inha.stress_sensor;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

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

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import inha.nsl.easytrack.ETServiceGrpc;
import inha.nsl.easytrack.EtService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import kr.ac.inha.stress_sensor.receivers.GeofenceReceiver;
import kr.ac.inha.stress_sensor.services.MainService;
import kr.ac.inha.stress_sensor.services.LocationService;

import static android.app.Notification.CATEGORY_ALARM;
import static android.content.Context.MODE_PRIVATE;
import static kr.ac.inha.stress_sensor.EMAActivity.EMA_NOTIF_HOURS;
import static kr.ac.inha.stress_sensor.services.MainService.EMA_RESPONSE_EXPIRE_TIME;

public class Tools {

    static final String DATA_SOURCE_SEPARATOR = " ";
    static int PERMISSION_ALL = 1;
    public static String[] PERMISSIONS = {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    /* Zaturi start */
    static String[] COMMUNICATION_APPS = {
            "com.kakao.talk",                       // KakaoTalk
            "com.facebook.orca",                    // Messenger
            "com.facebook.mlite",                   // Messenger Lite
            "jp.naver.line.android",                // Line
            "com.linecorp.linelite",                // Line Lite
            "com.Slack",                            // Slack
            "com.google.android.apps.messaging",    // Text message
            "kr.co.vcnc.android.couple",            // Between
            "org.telegram.messenger",               // Telegram
            "com.discord",                          // Discord
            "com.tencent.mm",                       // WeChat
            "com.samsung.android.messaging",        // Samsumg Messaging
    };
    public static final int ZATURI_NOTIFICATION_ID = 2222;
    public static final int STRESS_CONFIG = 0;
    public static final int STRESS_NEXT_TIME = 1;
    public static final int STRESS_MUTE_TODAY = 2;
    public static final int STRESS_DO_INTERVENTION = 3;
    /* Zaturi end */

    public static boolean hasPermissions(Context con, String... permissions) {
        Context context = con.getApplicationContext();
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

    static AlertDialog requestPermissions(final Activity activity) {
        final AlertDialog.Builder alertDialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.permissions))
                .setMessage(activity.getString(R.string.grant_permissions))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Tools.grantPermissions(activity, PERMISSIONS);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        return alertDialog.show();
    }

    private static void grantPermissions(Activity activity, String... permissions) {
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

        /* Zaturi start */
        SharedPreferences interventionPrefs = con.getSharedPreferences("intervention", MODE_PRIVATE);
        SharedPreferences stressReportPrefs = con.getSharedPreferences("stressReport", MODE_PRIVATE);
        // Check if between 11am and 11pm
        if (isBetween11am11pm()) {
            // If user didn't perform stress intervention today
            if (!interventionPrefs.getBoolean("didIntervention", false)) {
                // Check if the last self stress report was LITTLE_HIGH or HIGH
                int lastSelfStressReport = stressReportPrefs.getInt("reportAnswer", 0);
                if (lastSelfStressReport == 1 || lastSelfStressReport == 2) {

                    // Retrieve usage events in the last 3 seconds
                    UsageEvents.Event currentEvent;
                    UsageEvents usageEvents = usageStatsManager.queryEvents(
                            System.currentTimeMillis() - 3000,
                            System.currentTimeMillis());
                    // Get time of last phone usage (except communication apps)
    //            long zaturiLastPhoneUsage = loginPrefs.getLong("zaturiLastPhoneUsage", -1);
                    long zaturiLastPhoneUsage = loginPrefs.getLong("zaturiLastPhoneUsage", System.currentTimeMillis());
                    while (usageEvents.hasNextEvent()) {
                        currentEvent = new UsageEvents.Event();
                        usageEvents.getNextEvent(currentEvent);
                        String packageName = currentEvent.getPackageName();
                        // For all apps except communication apps
                        Log.d("ZATURI", "packageName: " + packageName);
                        if (!Arrays.asList(COMMUNICATION_APPS).contains(packageName)
                                && !packageName.contains(launcher_packageName)) {
                            // When an app is opened/resumed
                            if (currentEvent.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                                // If the interval from last usage > 10 min
                                if (System.currentTimeMillis() - zaturiLastPhoneUsage > 600000) {
                                    // Turn on flag for timer and set the timer
                                    // & remember the package name
                                    SharedPreferences.Editor editor = loginPrefs.edit();
                                    editor.putBoolean("zaturiTimerOn", true);
                                    editor.putLong("zaturiTimerStart", currentEvent.getTimeStamp());
                                    editor.putString("zaturiPackage", packageName);
                                    editor.apply();
                                }
                            } else if (currentEvent.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED
                                    || currentEvent.getEventType() == UsageEvents.Event.ACTIVITY_STOPPED) {
                                // When an app closes
                                // Check if timer is on
                                if (loginPrefs.getBoolean("zaturiTimerOn", false)) {
                                    // Check if the package name matches
                                    if (packageName.equals(loginPrefs.getString("zaturiPackage", ""))) {
                                        // Check if the usage duration < 30 sec
                                        if (currentEvent.getTimeStamp() -
                                                loginPrefs.getLong("zaturiTimerStart", 0) < 30000) {
                                            // Then send a notification
                                            sendStressInterventionNoti(con);

                                            // Reset the variables
                                            SharedPreferences.Editor editor = loginPrefs.edit();
                                            editor.putBoolean("zaturiTimerOn", false);
                                            editor.putLong("zaturiTimerStart", 0);
                                            editor.putString("zaturiPackage", "");
                                            editor.apply();
                                        }
                                    }
                                }

                                // Save last phone usage time (except for communication app usage)
                                SharedPreferences.Editor editor = loginPrefs.edit();
                                editor.putLong("zaturiLastPhoneUsage", currentEvent.getTimeStamp());
                                editor.apply();
                            }
                        }
                    }
                }
            }
        }
        /* Zaturi end */

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

    public static void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    static void enable_touch(Activity activity) {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    static void disable_touch(Activity activity) {
        activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    private static boolean isReachable;

    public static boolean isNetworkAvailable() {
        try {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InetAddress address = InetAddress.getByName("www.google.com");
                        isReachable = !address.toString().equals("");
                    } catch (Exception e) {
                        e.printStackTrace();
                        isReachable = false;
                    }
                }
            });
            thread.start();
            thread.join();
        } catch (Exception e) {
            e.printStackTrace();
            isReachable = false;
        }

        return isReachable;
    }

    static boolean isMainServiceRunning(Context con) {
        ActivityManager manager = (ActivityManager) con.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (MainService.class.getName().equals(service.service.getClassName())) {
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

        if (Tools.isNetworkAvailable()) {
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

    public static int getEMAOrderAtExactTime(Calendar cal) {
        for (short i = 0; i < EMA_NOTIF_HOURS.length; i++) {
            if (cal.get(Calendar.HOUR_OF_DAY) == EMA_NOTIF_HOURS[i] && cal.get(Calendar.MINUTE) == 0) {
                return i + 1;
            }
        }
        return 0;

    }

    static int getEMAOrderFromRangeAfterEMA(Calendar cal) {
        long t = (cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)) * 1000;
        for (int i = 0; i < EMA_NOTIF_HOURS.length; i++) {
            if ((EMA_NOTIF_HOURS[i] * 3600 * 1000) <= t && t <= (EMA_NOTIF_HOURS[i] * 3600 * 1000) + EMA_RESPONSE_EXPIRE_TIME * 1000)
                return i + 1;

        }
        return 0;
    }

    static void perform_logout(Context con) {

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

    public static boolean inRange(long value, long start, long end) {
        return start < value && value < end;
    }

    /* Zaturi start */
    private static boolean isBetween11am11pm() {
        // Check if the current time is between 11AM and 11PM
        int start = 11;
        int end = 23;
        int hours = (end - start) % 24;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, start);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        long startHourMilli = cal.getTimeInMillis();
        cal.add(Calendar.HOUR_OF_DAY, hours);
        long endHourMilli = cal.getTimeInMillis();
        return System.currentTimeMillis() >= startHourMilli
                && System.currentTimeMillis() <= endHourMilli;
    }

    private static void sendStressInterventionNoti(Context con) {
        // Get current intervention
        SharedPreferences prefs = con.getSharedPreferences("intervention", MODE_PRIVATE);
        String curIntervention = prefs.getString("curIntervention", "");

        // Create and send stress intervention notification

        final NotificationManager notificationManager = (NotificationManager)
                con.getSystemService(Context.NOTIFICATION_SERVICE);

//        Intent notificationIntent = new Intent(MainService.this, EMAActivity.class);
        Intent nextTimeIntent = new Intent();
        // TODO: Save to server : 다음에 하기 (STRESS_NEXT_TIME)
//        saveStressIntervention(con, System.currentTimeMillis(), curIntervention, STRESS_NEXT_TIME);

        Intent muteTodayIntent = new Intent();
        // TODO: Save to server : 오늘의 알림 끄기 (STRESS_MUTE_TODAY)
        //  Change didIntervention to true
//        saveStressIntervention(con, System.currentTimeMillis(), curIntervention, STRESS_MUTE_TODAY);
//        SharedPreferences.Editor editor = prefs.edit();
//        editor.putBoolean("didIntervention", true);
//        editor.apply();

        Intent stressRelIntent = new Intent();
        // TODO: Save to server : 스트레스 해소하기 (STRESS_DO_INTERVENTION)
        //  Go to 마음 케어 tab
        //  Change didIntervention to true
//        saveStressIntervention(con, System.currentTimeMillis(), curIntervention, STRESS_DO_INTERVENTION);
//        SharedPreferences.Editor editor = prefs.edit();
//        editor.putBoolean("didIntervention", true);
//        editor.apply();

        PendingIntent nextTimePI = PendingIntent.getActivity(con,
                1, nextTimeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent muteTodayPI = PendingIntent.getActivity(con,
                1, muteTodayIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stressRelPI = PendingIntent.getActivity(con,
                1, stressRelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Action nextTimeAction =
                new NotificationCompat.Action.Builder(
                        R.mipmap.ic_launcher_no_bg,
                        con.getString(R.string.next_time),
                        nextTimePI).build();
        NotificationCompat.Action muteTodayAction =
                new NotificationCompat.Action.Builder(
                        R.mipmap.ic_launcher_no_bg,
                        con.getString(R.string.mute_today),
                        muteTodayPI).build();
        NotificationCompat.Action stressRelAction =
                new NotificationCompat.Action.Builder(
                        R.mipmap.ic_launcher_no_bg,
                        con.getString(R.string.do_intervention),
                        stressRelPI).build();

        String channelId = con.getString(R.string.notif_channel_id);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                con.getApplicationContext(), channelId);
        builder.setContentTitle(con.getString(R.string.app_name))
                .setTimeoutAfter(1000 * EMA_RESPONSE_EXPIRE_TIME)
                .setContentText(curIntervention)
                .setTicker("New Message Alert!")
                .setAutoCancel(true)
                .setContentIntent(stressRelPI)
                .setCategory(CATEGORY_ALARM)
                .addAction(nextTimeAction)
                .addAction(muteTodayAction)
                .addAction(stressRelAction)
                .setSmallIcon(R.mipmap.ic_launcher_no_bg)
                .setPriority(NotificationCompat.PRIORITY_MAX);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId,
                    con.getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        final Notification notification = builder.build();
        if (notificationManager != null) {
            notificationManager.notify(ZATURI_NOTIFICATION_ID, notification);
        }
    }

    private static void saveStressIntervention(Context con, long timestamp, String curIntervention, int action) {
        SharedPreferences prefs = con.getSharedPreferences("Configurations", MODE_PRIVATE);

        int dataSourceId = prefs.getInt("STRESS_INTERVENTION", -1);
        assert dataSourceId != -1;
        DbMgr.saveMixedData(dataSourceId, timestamp, 1.0f, timestamp, curIntervention, action);
    }
    /* Zaturi end */
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
