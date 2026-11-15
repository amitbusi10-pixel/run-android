package app.alhaderech.run;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayList;

/**
 * Foreground service that keeps GPS running while the screen is off or the app is in the background,
 * and owns the single ongoing notification (live timer, distance, progress to goal, pause/resume).
 * The notification is updated in place (setOnlyAlertOnce), so it never pops up again.
 */
public class RunTrackerService extends Service {

    static final String ACTION_START = "app.alhaderech.run.START";
    static final String ACTION_UPDATE = "app.alhaderech.run.UPDATE";
    static final String ACTION_STOP = "app.alhaderech.run.STOP";
    static final String ACTION_PAUSE = "app.alhaderech.run.PAUSE";
    static final String ACTION_RESUME = "app.alhaderech.run.RESUME";
    // default importance (not "silent"), so Android shows it on the lock screen; the channel has no sound or vibration
    static final String CHANNEL = "run-live-v2";
    static final int NOTIF_ID = 4242;

    static volatile boolean running = false;
    static volatile boolean paused = false;
    static volatile long startedAt = 0;
    /** every fix since the run started: lat, lng, accuracy, speed, time(ms), bearing */
    static final ArrayList<double[]> buffer = new ArrayList<>();

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private String title = "על הדרך";
    private String text = "הריצה התחילה";
    private String chip = "";
    private double elapsedMs = 0, dist = 0, goal = 0;
    private long elapsedAt = 0;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) action = running ? ACTION_UPDATE : ACTION_STOP;
        switch (action) {
            case ACTION_START:
                goal = intent.getDoubleExtra("goal", 0);
                title = intent.getStringExtra("title");
                text = intent.getStringExtra("text");
                elapsedMs = 0; dist = 0; elapsedAt = System.currentTimeMillis();
                paused = false;
                if (!running) {
                    synchronized (buffer) { buffer.clear(); }
                    startedAt = System.currentTimeMillis();
                }
                running = true;
                goForeground();
                startLocation();
                break;
            case ACTION_UPDATE:
                if (intent != null && intent.hasExtra("title")) {
                    String t = intent.getStringExtra("title");
                    if (t != null && !t.isEmpty()) title = t;
                    text = intent.getStringExtra("text");
                    chip = intent.getStringExtra("chip");
                    elapsedMs = intent.getDoubleExtra("elapsedMs", elapsedMs);
                    elapsedAt = System.currentTimeMillis();
                    paused = intent.getBooleanExtra("paused", paused);
                    dist = intent.getDoubleExtra("dist", dist);
                    goal = intent.getDoubleExtra("goal", goal);
                }
                if (running) notifyNow();
                break;
            case ACTION_PAUSE:
            case ACTION_RESUME: {
                boolean p = ACTION_PAUSE.equals(action);
                // the timer freezes / continues right away; the app sends the exact numbers a moment later
                if (p && !paused) elapsedMs += System.currentTimeMillis() - elapsedAt;
                elapsedAt = System.currentTimeMillis();
                paused = p;
                notifyNow();
                JSObject o = new JSObject();
                o.put("action", p ? "pause" : "resume");
                RunTrackerPlugin.emit("action", o);
                break;
            }
            case ACTION_STOP:
            default:
                stopEverything();
                return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "ריצה פעילה", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("זמן, מרחק וקצב בזמן הריצה");
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.enableVibration(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
                nm.deleteNotificationChannel("run-tracking"); // the old low-importance channel
            }
        }
    }

    private Notification build() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        Intent toggle = new Intent(this, RunTrackerService.class).setAction(paused ? ACTION_RESUME : ACTION_PAUSE);
        PendingIntent togglePi = PendingIntent.getService(this, 1, toggle, PendingIntent.FLAG_UPDATE_CURRENT | immutable);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tracking)
            .setColor(0xFFFF7A45)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, paused ? "המשך" : "השהה", togglePi);
        if (paused) {
            b.setShowWhen(false).setUsesChronometer(false);
        } else {
            // the system ticks the timer every second by itself, without waking the app
            double now = elapsedMs + (System.currentTimeMillis() - elapsedAt);
            b.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - (long) now);
        }
        if (goal > 0) b.setProgress(1000, (int) Math.min(1000, dist * 1000 / goal), false);
        // Android 16 "live update": shown as a chip in the status bar / Samsung Now Bar when allowed
        b.getExtras().putBoolean("android.requestPromotedOngoing", true);
        if (chip != null && !chip.isEmpty()) b.getExtras().putString("android.shortCriticalText", chip);
        return b.build();
    }

    private void goForeground() {
        createChannel();
        int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
        ServiceCompat.startForeground(this, NOTIF_ID, build(), type);
    }

    private void notifyNow() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, build());
    }

    private void startLocation() {
        if (callback != null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        fused = LocationServices.getFusedLocationProviderClient(this);
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .setWaitForAccurateLocation(false)
            .build();
        callback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                for (Location l : result.getLocations()) {
                    double[] p = { l.getLatitude(), l.getLongitude(), l.getAccuracy(),
                        l.hasSpeed() ? l.getSpeed() : -1, l.getTime(), l.hasBearing() ? l.getBearing() : -1 };
                    synchronized (buffer) { buffer.add(p); }
                    JSObject o = new JSObject();
                    o.put("latitude", p[0]);
                    o.put("longitude", p[1]);
                    o.put("accuracy", p[2]);
                    o.put("speed", p[3] < 0 ? null : p[3]);
                    o.put("bearing", p[5] < 0 ? null : p[5]);
                    o.put("time", l.getTime());
                    RunTrackerPlugin.emit("location", o);
                }
            }
        };
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            callback = null;
        }
    }

    private void stopEverything() {
        if (fused != null && callback != null) fused.removeLocationUpdates(callback);
        callback = null;
        running = false;
        paused = false;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (fused != null && callback != null) fused.removeLocationUpdates(callback);
        running = false;
        super.onDestroy();
    }
}
