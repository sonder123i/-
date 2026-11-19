package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.gogogo.run.bridge.ILocationInjector;
import com.zcshou.gogogo.run.ui.home.RunHomeActivity;
import com.zcshou.joystick.JoyStick;

public class ServiceGo extends Service implements ILocationInjector {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    public static final String ACTION_INJECTION_LOST = "com.zcshou.gogogo.run.ACTION_INJECTION_LOST";
    private final java.util.Random mRandom = new java.util.Random();
    private long mLastInjectionLostAt = 0L;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // run 引擎接管标志：true 时原版摇杆注入循环停摆，坐标由 ILocationInjector.injectLocation 驱动
    private volatile boolean mEngineDriven = false;
    private volatile boolean mNotificationShown = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    // 摇杆相关
    private JoyStick mJoyStick;

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        initGoLocation();

        initJoyStick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 仅当 intent 携带显式坐标时才重置位置并驱动旧注入循环；
        // 无坐标的启动（巡航/漫游绑定接管前）不允许把位置重置为默认济南值再注入，
        // 否则用户位置会先闪跳济南再跳回目标点。
        if (intent != null
                && intent.hasExtra(MainActivity.LNG_MSG_ID)
                && intent.hasExtra(MainActivity.LAT_MSG_ID)) {
            mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
            mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
            mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
        }
        // 仅在被显式 start 时挂前台通知；漫游绑定模式由 RoamService 承担前台
        if (!mNotificationShown) {
            initNotification();
            mNotificationShown = true;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    private void initNotification() {
        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        // 点击通知回到统一首页（旧版手动界面已停用）
        Intent clickIntent = new Intent(this, RunHomeActivity.class);
        clickIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .setSmallIcon(R.drawable.ic_stat_cloudtrace)
                .build();

        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                mSpeed = speed;
                // 根据当前的经纬度和距离，计算下一个经纬度
                // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                // 具体见：http://wp.mlab.tw/?p=2200
                mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                mCurLat += disLat / 110.574;
                mCurBea = (float) angle;
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
            }
        });
        // 旧版摇杆浮窗停用：由新的自由漫游页 / 统一悬浮窗接管
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop && !mEngineDriven) {
                        setLocationNetwork();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };
        // 不在 onCreate 自启动注入循环：等待显式坐标（onStartCommand / setPosition）
        // 或引擎 setEngineDriven 接管后再驱动，避免以默认坐标空注
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(noisyAccuracy());          // 3.5~10.5m 正态波动，避免固定精度穿帮
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 12 + mRandom.nextInt(8));
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (SecurityException e) {
            notifyInjectionLost();
            XLog.e("SERVICEGO: ERROR - setLocationGPS permission");
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(noisyNetworkAccuracy());    // 基站/Wi-Fi 精度略粗
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 12 + mRandom.nextInt(8));
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (SecurityException e) {
            notifyInjectionLost();
            XLog.e("SERVICEGO: ERROR - setLocationNetwork permission");
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    // ==================== ILocationInjector 桥梁实现（run 引擎专用） ====================

    @Override
    public void injectLocation(double latitude, double longitude, double altitude,
                               float speedMps, float bearingDegrees, float accuracyMeters,
                               float verticalAccuracyMeters, float speedAccuracyMps,
                               float bearingAccuracyDegrees) {
        mCurLat = latitude;
        mCurLng = longitude;
        mCurAlt = altitude;
        mCurBea = bearingDegrees;
        mSpeed = speedMps;
        try {
            long wallTime = System.currentTimeMillis();
            long elapsedNanos = SystemClock.elapsedRealtimeNanos();

            Location gps = buildEngineLocation(LocationManager.GPS_PROVIDER, latitude, longitude,
                    altitude, speedMps, bearingDegrees, accuracyMeters, verticalAccuracyMeters,
                    speedAccuracyMps, bearingAccuracyDegrees, wallTime, elapsedNanos);
            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, gps);

            if (mLocManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                Location network = new Location(gps);
                network.setProvider(LocationManager.NETWORK_PROVIDER);
                mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, network);
            }
        } catch (SecurityException e) {
            notifyInjectionLost();
            XLog.e("SERVICEGO: ERROR - injectLocation permission");
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - injectLocation");
        }
    }

    private Location buildEngineLocation(String provider, double latitude, double longitude,
                                         double altitude, float speedMps, float bearingDegrees,
                                         float accuracyMeters, float verticalAccuracyMeters,
                                         float speedAccuracyMps, float bearingAccuracyDegrees,
                                         long wallTime, long elapsedNanos) {
        Location loc = new Location(provider);
        loc.setLatitude(latitude);
        loc.setLongitude(longitude);
        loc.setAltitude(altitude);
        loc.setSpeed(speedMps);
        loc.setBearing(bearingDegrees);
        loc.setAccuracy(noisyAccuracy());
        loc.setVerticalAccuracyMeters(verticalAccuracyMeters);
        loc.setSpeedAccuracyMetersPerSecond(speedAccuracyMps);
        loc.setBearingAccuracyDegrees(bearingAccuracyDegrees);
        loc.setTime(wallTime);
        loc.setElapsedRealtimeNanos(elapsedNanos);
        Bundle bundle = new Bundle();
        bundle.putInt("satellites", 12 + mRandom.nextInt(8));
        loc.setExtras(bundle);
        return loc;
    }

    /** 3.5~10.5m 的正态波动水平精度，避免每次固定 1.0m 被识别为模拟信号 */
    private float noisyAccuracy() {
        float value = 6.5f + (float) mRandom.nextGaussian() * 1.5f;
        return Math.max(3.5f, Math.min(10.5f, value));
    }

    /** NETWORK Provider 的精度略粗：8~18m */
    private float noisyNetworkAccuracy() {
        float value = 12.5f + (float) mRandom.nextGaussian() * 2.5f;
        return Math.max(8f, Math.min(18f, value));
    }

    private void notifyInjectionLost() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastInjectionLostAt < 5000L) return;
        mLastInjectionLostAt = now;
        try {
            Intent intent = new Intent(ACTION_INJECTION_LOST);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - notifyInjectionLost");
        }
    }

    @Override
    public void setEngineDriven(boolean enabled) {
        mEngineDriven = enabled;
        if (enabled) {
            // 上一轮 releaseInjection 已移除测试 Provider，同一 Service 实例再次接管前需幂等重建
            removeTestProviderNetwork();
            addTestProviderNetwork();
            removeTestProviderGPS();
            addTestProviderGPS();
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mJoyStick.hide();
        } else {
            // 旧摇杆浮窗已整体停用（漫游摇杆/悬浮胶囊接管），false 分支只恢复注入循环
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
        }
    }

    @Override
    public void releaseInjection() {
        mEngineDriven = false;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        removeTestProviderNetwork();
        removeTestProviderGPS();
    }

    public class ServiceGoBinder extends Binder {
        /** run 引擎通过 Binder 拿到注入桥梁实例 */
        public ILocationInjector getInjector() {
            return ServiceGo.this;
        }

        public void setPosition(double lng, double lat, double alt) {
            if (mEngineDriven) {
                return;
            }
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
    }
}


