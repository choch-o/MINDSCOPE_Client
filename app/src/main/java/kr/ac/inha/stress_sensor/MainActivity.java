package kr.ac.inha.stress_sensor;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Period;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.os.Bundle;

import org.json.JSONArray;

import org.json.JSONObject;

import android.app.Activity;
import android.widget.Button;
import android.widget.Toast;
import android.view.MenuItem;
import android.content.Intent;
import android.widget.Toolbar;

import org.json.JSONException;

import android.widget.TextView;
import android.content.IntentFilter;
import android.widget.RelativeLayout;

import android.content.SharedPreferences;


import inha.nsl.easytrack.ETServiceGrpc;
import inha.nsl.easytrack.EtService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import kr.ac.inha.stress_sensor.receivers.ConnectionMonitor;
import kr.ac.inha.stress_sensor.receivers.ConnectionReceiver;
import kr.ac.inha.stress_sensor.services.AudioRecorder;
import kr.ac.inha.stress_sensor.services.CustomSensorsService;


public class MainActivity extends Activity {

    //region Constants
    private static final String TAG = "MainActivity";
    static int PERMISSION_ALL = 1;
    static String[] PERMISSIONS = {
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };
    //endregion

    //region UI variables
    private Button btnEMA;
    private TextView tvServiceStatus;
    private TextView tvInternetStatus;
    public TextView tvFileCount;
    public TextView tvDayNum;
    public TextView tvEmaNum;
    public TextView tvHBPhone;
    public TextView tvDataLoadedPhone;
    private RelativeLayout loadingPanel;
    private TextView ema_tv_1;
    private TextView ema_tv_2;
    private TextView ema_tv_3;
    private TextView ema_tv_4;
    private TextView ema_tv_5;
    private TextView ema_tv_6;
    //endregion

    private AudioRecorder audioRecorder;
    private Intent customSensorsService;
    ConnectionReceiver connectionReceiver;
    ConnectionMonitor connectionMonitor;
    IntentFilter intentFilter;

    private SharedPreferences loginPrefs;
    private SharedPreferences locationPrefs;
    SharedPreferences configPrefs;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        DbMgr.init(getApplicationContext());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setActionBar((Toolbar) findViewById(R.id.my_toolbar));
        }

        //region Init UI variables
        btnEMA = findViewById(R.id.btn_late_ema);
        tvServiceStatus = findViewById(R.id.tvStatus);
        tvInternetStatus = findViewById(R.id.connectivityStatus);
        tvFileCount = findViewById(R.id.filesCountTextView);
        loadingPanel = findViewById(R.id.loadingPanel);
        tvDayNum = findViewById(R.id.txt_day_num);
        tvEmaNum = findViewById(R.id.ema_responses_phone);
        tvHBPhone = findViewById(R.id.heartbeat_phone);
        tvDataLoadedPhone = findViewById(R.id.data_loaded_phone);
        ema_tv_1 = findViewById(R.id.ema_tv_1);
        ema_tv_2 = findViewById(R.id.ema_tv_2);
        ema_tv_3 = findViewById(R.id.ema_tv_3);
        ema_tv_4 = findViewById(R.id.ema_tv_4);
        ema_tv_5 = findViewById(R.id.ema_tv_5);
        ema_tv_6 = findViewById(R.id.ema_tv_6);
        //endregion

        final SwipeRefreshLayout pullToRefresh = findViewById(R.id.pullToRefresh);
        pullToRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                updateStats();
                try {
                    if (Tools.heartbeatNotSent(getApplicationContext())) {
                        Tools.perform_logout(MainActivity.this);
                        stopService(customSensorsService);
                        finish();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                pullToRefresh.setRefreshing(false);
            }
        });

        //region Registering BroadcastReciever for connectivity changed
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // only for LOLLIPOP and newer versions
            connectionMonitor = new ConnectionMonitor(this);
            connectionMonitor.enable();
        } else {
            intentFilter = new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE");
            intentFilter.addAction(getPackageName() + "android.net.wifi.WIFI_STATE_CHANGED");
            connectionReceiver = new ConnectionReceiver();
            registerReceiver(connectionReceiver, intentFilter);
        }
        //endregion

    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (Tools.heartbeatNotSent(getApplicationContext())) {
                Tools.perform_logout(MainActivity.this);
                stopService(customSensorsService);
                finish();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        loginPrefs = getSharedPreferences("UserLogin", MODE_PRIVATE);
        locationPrefs = getSharedPreferences("UserLocations", MODE_PRIVATE);
        configPrefs = getSharedPreferences("Configurations", Context.MODE_PRIVATE);

        int ema_order = Tools.getEMAOrderFromRangeAfterEMA(Calendar.getInstance());
        if (ema_order == 0) {
            btnEMA.setVisibility(View.GONE);
        } else {
            boolean ema_btn_visible = loginPrefs.getBoolean("ema_btn_make_visible", true);
            if (!ema_btn_visible) {
                btnEMA.setVisibility(View.GONE);
            } else {
                btnEMA.setVisibility(View.VISIBLE);
            }
        }


        customSensorsService = new Intent(this, CustomSensorsService.class);
        initUserStats(true, 0, 0, null);

        if (Tools.isNetworkAvailable(this)) {
            loadCampaign();
        } else if (configPrefs.getBoolean("campaignLoaded", false)) {
            try {
                setUpCampaignConfigurations(
                        configPrefs.getString("name", null),
                        configPrefs.getString("notes", null),
                        configPrefs.getString("creatorEmail", null),
                        Objects.requireNonNull(configPrefs.getString("configJson", null)),
                        configPrefs.getLong("startTimestamp", -1),
                        configPrefs.getLong("endTimestamp", -1),
                        configPrefs.getInt("participantCount", -1)
                );
                restartServiceClick(null);
            } catch (JSONException e) {
                e.printStackTrace();
                finish();
                return;
            }
        } else {
            Toast.makeText(this, "Please connect to the Internet for the first launch!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        updateStats();
    }

    private String formatMinutes(int minutes) {
        if (minutes > 60) {
            if (minutes > 1440) {
                return minutes / 60 / 24 + "days";
            } else {
                int h = minutes / 60;
                float dif = (float) minutes / 60 - h;
                //Toast.makeText(MainActivity.this, dif + "", Toast.LENGTH_SHORT).show();
                int m = (int) (dif * 60);
                return h + "h " + m + "m";
            }
        } else
            return minutes + "m";
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        loadingPanel.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loadingPanel.setVisibility(View.GONE);
    }

    public void initUserStats(boolean error, long joinedTimesamp, long hbPhone, String dataLoadedPhone) {
        if (Tools.isMainServiceRunning(MainActivity.this)) {
            tvServiceStatus.setTextColor(getResources().getColor(R.color.green));
            tvServiceStatus.setText(getString(R.string.service_runnig));
        } else {
            tvServiceStatus.setTextColor(getResources().getColor(R.color.red));
            tvServiceStatus.setText(getString(R.string.service_stopped));
        }
        if (!error) {
            tvDayNum.setVisibility(View.VISIBLE);
            tvEmaNum.setVisibility(View.VISIBLE);
            tvDataLoadedPhone.setVisibility(View.VISIBLE);
            tvHBPhone.setVisibility(View.VISIBLE);

            tvInternetStatus.setTextColor(getResources().getColor(R.color.green));

            tvInternetStatus.setText(getString(R.string.internet_on));

            Calendar now = Calendar.getInstance();

            float joinTimeDif = now.getTimeInMillis() - joinedTimesamp;
            int dayNum = (int) Math.ceil(joinTimeDif / 1000 / 3600 / 24); // in days

            float hbTimeDif = now.getTimeInMillis() - hbPhone;
            int heart_beat = (int) Math.ceil(hbTimeDif / 1000 / 60); // in minutes

            if (heart_beat > 30)
                tvHBPhone.setTextColor(getResources().getColor(R.color.red));
            else
                tvHBPhone.setTextColor(getResources().getColor(R.color.green));


            if (dayNum > 1) {
                tvDayNum.setText(getString(R.string.day_num, dayNum) + "s");
            } else {
                tvDayNum.setText(getString(R.string.day_num, dayNum));
            }


            tvDataLoadedPhone.setText(getString(R.string.data_loaded, dataLoadedPhone));
            String last_active_text = hbPhone == 0 ? "just now" : formatMinutes(heart_beat) + " ago";
            tvHBPhone.setText(getString(R.string.last_active, last_active_text));
        } else {
            tvInternetStatus.setTextColor(getResources().getColor(R.color.red));
            tvInternetStatus.setText(getString(R.string.internet_off));
            tvDayNum.setVisibility(View.GONE);
            tvEmaNum.setVisibility(View.GONE);
            tvDataLoadedPhone.setVisibility(View.GONE);
            tvHBPhone.setVisibility(View.GONE);
            ema_tv_1.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
            ema_tv_2.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
            ema_tv_3.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
            ema_tv_4.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
            ema_tv_5.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
            ema_tv_6.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
        }
    }

    private void updateEmaResponseView(List<String> values) {
        ema_tv_1.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
        ema_tv_2.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
        ema_tv_3.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
        ema_tv_4.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
        ema_tv_5.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
        ema_tv_6.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.unchecked_box, 0, 0);
        if (values != null) {
            tvEmaNum.setText(getString(R.string.ema_responses, values.size()));
            for (String val : values) {
                switch (Integer.parseInt(val.split(Tools.DATA_SOURCE_SEPARATOR)[1])) {
                    case 1:
                        ema_tv_1.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.checked_box, 0, 0);
                        break;
                    case 2:
                        ema_tv_2.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.checked_box, 0, 0);
                        break;
                    case 3:
                        ema_tv_3.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.checked_box, 0, 0);
                        break;
                    case 4:
                        ema_tv_4.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.checked_box, 0, 0);
                        break;
                    case 5:
                        ema_tv_5.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.checked_box, 0, 0);
                        break;
                    case 6:
                        ema_tv_6.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.checked_box, 0, 0);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    public void updateStats() {
        if (Tools.isNetworkAvailable(this))
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ManagedChannel channel = ManagedChannelBuilder.forAddress(getString(R.string.grpc_host), Integer.parseInt(getString(R.string.grpc_port))).usePlaintext().build();
                    ETServiceGrpc.ETServiceBlockingStub stub = ETServiceGrpc.newBlockingStub(channel);
                    EtService.RetrieveParticipantStatisticsRequestMessage retrieveParticipantStatisticsRequestMessage = EtService.RetrieveParticipantStatisticsRequestMessage.newBuilder()
                            .setUserId(loginPrefs.getInt(AuthenticationActivity.user_id, -1))
                            .setEmail(loginPrefs.getString(AuthenticationActivity.usrEmail, null))
                            .setTargetEmail(loginPrefs.getString(AuthenticationActivity.usrEmail, null))
                            .setTargetCampaignId(Integer.parseInt(getString(R.string.stress_campaign_id)))
                            .build();
                    try {
                        EtService.RetrieveParticipantStatisticsResponseMessage responseMessage = stub.retrieveParticipantStatistics(retrieveParticipantStatisticsRequestMessage);
                        if (responseMessage.getDoneSuccessfully()) {
                            final long join_timestamp = responseMessage.getCampaignJoinTimestamp();
                            final long hb_phone = responseMessage.getLastHeartbeatTimestamp();
                            final int samples_amount = responseMessage.getAmountOfSubmittedDataSamples();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    initUserStats(false, join_timestamp, hb_phone, String.valueOf(samples_amount));
                                }
                            });
                        } else {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    initUserStats(true, 0, 0, null);
                                }
                            });
                        }
                    } catch (StatusRuntimeException e) {
                        Log.e("Tools", "DataCollectorService.setUpHeartbeatSubmissionThread() exception: " + e.getMessage());
                        e.printStackTrace();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                initUserStats(true, 0, 0, null);
                            }
                        });

                    }

                    Calendar fromCal = Calendar.getInstance();
                    fromCal.set(Calendar.HOUR_OF_DAY, 0);
                    fromCal.set(Calendar.MINUTE, 0);
                    fromCal.set(Calendar.SECOND, 0);
                    fromCal.set(Calendar.MILLISECOND, 0);
                    Calendar tillCal = (Calendar) fromCal.clone();
                    tillCal.set(Calendar.HOUR_OF_DAY, 23);
                    tillCal.set(Calendar.MINUTE, 59);
                    tillCal.set(Calendar.SECOND, 59);
                    EtService.RetrieveFilteredDataRecordsRequestMessage retrieveFilteredDataRecordsRequestMessage = EtService.RetrieveFilteredDataRecordsRequestMessage.newBuilder()
                            .setUserId(loginPrefs.getInt(AuthenticationActivity.user_id, -1))
                            .setEmail(loginPrefs.getString(AuthenticationActivity.usrEmail, null))
                            .setTargetEmail(loginPrefs.getString(AuthenticationActivity.usrEmail, null))
                            .setTargetCampaignId(Integer.parseInt(getString(R.string.stress_campaign_id)))
                            .setTargetDataSourceId(configPrefs.getInt("SURVEY_EMA", -1))
                            .setFromTimestamp(fromCal.getTimeInMillis())
                            .setTillTimestamp(tillCal.getTimeInMillis())
                            .build();
                    try {
                        final EtService.RetrieveFilteredDataRecordsResponseMessage responseMessage = stub.retrieveFilteredDataRecords(retrieveFilteredDataRecordsRequestMessage);
                        if (responseMessage.getDoneSuccessfully()) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updateEmaResponseView(responseMessage.getValueList());
                                }
                            });
                        } else {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    initUserStats(true, 0, 0, null);
                                }
                            });
                        }
                    } catch (StatusRuntimeException e) {
                        Log.e("Tools", "DataCollectorService.setUpHeartbeatSubmissionThread() exception: " + e.getMessage());
                        e.printStackTrace();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateEmaResponseView(null);
                            }
                        });

                    } finally {
                        channel.shutdown();
                    }
                }
            }).start();
        else {
            Toast.makeText(MainActivity.this, "Please connect to Internet!", Toast.LENGTH_SHORT).show();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    initUserStats(true, 0, 0, null);
                }
            });
        }
    }

    public void lateEMAClick(View view) {
        short ema_order = Tools.getEMAOrderFromRangeAfterEMA(Calendar.getInstance());
        if (ema_order != 0) {
            Intent intent = new Intent(this, EMAActivity.class);
            intent.putExtra("ema_order", ema_order);
            startActivity(intent);
        }
    }

    public void restartServiceClick(MenuItem item) {
        if (!Tools.isMainServiceRunning(getApplicationContext())) {
            customSensorsService = new Intent(this, CustomSensorsService.class);
            stopService(customSensorsService);
            if (!Tools.hasPermissions(this, PERMISSIONS)) {
                Tools.grantPermissions(this, PERMISSIONS);
            } else {
                if (configPrefs.getLong("startTimestamp", 0) <= System.currentTimeMillis()) {
                    Log.e(TAG, "RESTART SERVICE");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(customSensorsService);
                    } else {
                        startService(customSensorsService);
                    }
                }
            }
        }
    }

    public void setLocationsClick(MenuItem item) {
        Intent intent = new Intent(MainActivity.this, LocationsSettingActivity.class);
        startActivity(intent);
    }

    public void logoutClick(MenuItem item) {
        Tools.perform_logout(this);
        stopService(customSensorsService);
        finish();
    }

    private void loadCampaign() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ManagedChannel channel = ManagedChannelBuilder.forAddress(getString(R.string.grpc_host), Integer.parseInt(getString(R.string.grpc_port))).usePlaintext().build();

                try {
                    ETServiceGrpc.ETServiceBlockingStub stub = ETServiceGrpc.newBlockingStub(channel);
                    EtService.RetrieveCampaignRequestMessage retrieveCampaignRequestMessage = EtService.RetrieveCampaignRequestMessage.newBuilder()
                            .setUserId(loginPrefs.getInt(AuthenticationActivity.user_id, -1))
                            .setEmail(loginPrefs.getString(AuthenticationActivity.usrEmail, null))
                            .setCampaignId(Integer.parseInt(getString(R.string.stress_campaign_id)))
                            .build();

                    EtService.RetrieveCampaignResponseMessage retrieveCampaignResponseMessage = stub.retrieveCampaign(retrieveCampaignRequestMessage);
                    if (retrieveCampaignResponseMessage.getDoneSuccessfully()) {
                        setUpCampaignConfigurations(
                                retrieveCampaignResponseMessage.getName(),
                                retrieveCampaignResponseMessage.getNotes(),
                                retrieveCampaignResponseMessage.getCreatorEmail(),
                                retrieveCampaignResponseMessage.getConfigJson(),
                                retrieveCampaignResponseMessage.getStartTimestamp(),
                                retrieveCampaignResponseMessage.getEndTimestamp(),
                                retrieveCampaignResponseMessage.getParticipantCount()
                        );
                        SharedPreferences.Editor editor = configPrefs.edit();
                        editor.putString("name", retrieveCampaignResponseMessage.getName());
                        editor.putString("notes", retrieveCampaignResponseMessage.getNotes());
                        editor.putString("creatorEmail", retrieveCampaignResponseMessage.getCreatorEmail());
                        editor.putString("configJson", retrieveCampaignResponseMessage.getConfigJson());
                        editor.putLong("startTimestamp", retrieveCampaignResponseMessage.getStartTimestamp());
                        editor.putLong("endTimestamp", retrieveCampaignResponseMessage.getEndTimestamp());
                        editor.putInt("participantCount", retrieveCampaignResponseMessage.getParticipantCount());
                        editor.putBoolean("campaignLoaded", true);
                        editor.apply();
                        restartServiceClick(null);
                    }
                } catch (StatusRuntimeException | JSONException e) {
                    e.printStackTrace();
                } finally {
                    channel.shutdown();
                }
            }
        }).start();
    }

    private void setUpCampaignConfigurations(String name, String notes, String creatorEmail, String configJson, long startTimestamp, long endTimestamp, int participantCount) throws JSONException {
        String oldConfigJson = configPrefs.getString(String.format(Locale.getDefault(), "%s_configJson", name), null);
        if (configJson.equals(oldConfigJson))
            return;

        SharedPreferences.Editor editor = configPrefs.edit();
        editor.putString(String.format(Locale.getDefault(), "%s_configJson", name), configJson);

        JSONArray dataSourceConfigurations = new JSONArray(configJson);
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < dataSourceConfigurations.length(); n++) {
            JSONObject dataSourceConfig = dataSourceConfigurations.getJSONObject(n);
            String _name = dataSourceConfig.getString("name");
            int _dataSourceId = dataSourceConfig.getInt("data_source_id");
            editor.putInt(_name, _dataSourceId);
            String _json = dataSourceConfig.getString("config_json");
            editor.putString(String.format(Locale.getDefault(), "config_json_%s", _name), _json);
            sb.append(_name).append(',');
        }
        if (sb.length() > 0)
            sb.replace(sb.length() - 1, sb.length(), "");
        editor.putString("dataSourceNames", sb.toString());
        editor.apply();
    }
}
