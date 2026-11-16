package app.alhaderech.run;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;

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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Locale;

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
    /** Android killed the app in the middle of a run and started the service again: carry on */
    static final String ACTION_RESTORE = "app.alhaderech.run.RESTORE";
    static final String PREFS = "run-tracker";
    static final String POINTS_FILE = "run-points.csv";
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
    /** wall-clock moment the timer counts from; kept fixed so the timer never jumps or restarts */
    private long chronoBase = 0;
    /** what the notification shows now; an identical update is skipped, so nothing flickers */
    private String shown = "";
    /**
     * The service measures the distance too, so the notification stays right when the app itself is not running
     * (Android closed it): nativeDist is measured here; distAtJs is nativeDist when the app last sent its numbers.
     */
    private double nativeDist = 0, distAtJs = 0;
    private long jsAt = 0;
    private double[] lastFix = null;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** A run was going on when Android killed the app (the saved state says so, the memory doesn't). */
    static boolean wasRunning(Context c) { return !running && prefs(c).getBoolean("running", false); }

    /** Loads the points saved before the app was killed, so the app can still get them. */
    static void loadPoints(Context c) {
        File f = new File(c.getFilesDir(), POINTS_FILE);
        ArrayList<double[]> pts = new ArrayList<>();
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] a = line.split(",");
                    if (a.length < 6) continue;
                    double[] p = new double[6];
                    for (int i = 0; i < 6; i++) p[i] = Double.parseDouble(a[i]);
                    pts.add(p);
                }
            } catch (Exception e) { /* keep what was read */ }
        }
        synchronized (buffer) { buffer.clear(); buffer.addAll(pts); }
    }

    /** Called from the plugin when the app opens after being killed: start the service again from the saved state. */
    static void restoreIfNeeded(Context c) {
        if (!wasRunning(c)) return;
        SharedPreferences sp = prefs(c);
        running = true;
        paused = sp.getBoolean("paused", false);
        startedAt = sp.getLong("startedAt", System.currentTimeMillis());
        loadPoints(c);
        try {
            ContextCompat.startForegroundService(c, new Intent(c, RunTrackerService.class).setAction(ACTION_RESTORE));
        } catch (Exception e) { /* the service restarts by itself (START_STICKY) */ }
    }

    private void save() {
        prefs(this).edit()
            .putBoolean("running", running).putBoolean("paused", paused).putLong("startedAt", startedAt)
            .putString("title", title).putString("text", text).putString("chip", chip)
            .putFloat("elapsedMs", (float) elapsedMs).putLong("elapsedAt", elapsedAt).putLong("chronoBase", chronoBase)
            .putFloat("dist", (float) dist).putFloat("goal", (float) goal)
            .putFloat("nativeDist", (float) nativeDist).putFloat("distAtJs", (float) distAtJs)
            .apply();
    }

    private void restoreFields() {
        SharedPreferences sp = prefs(this);
        paused = sp.getBoolean("paused", false);
        startedAt = sp.getLong("startedAt", System.currentTimeMillis());
        title = sp.getString("title", title);
        text = sp.getString("text", text);
        chip = sp.getString("chip", "");
        elapsedMs = sp.getFloat("elapsedMs", 0);
        elapsedAt = sp.getLong("elapsedAt", System.currentTimeMillis());
        chronoBase = sp.getLong("chronoBase", System.currentTimeMillis());
        dist = sp.getFloat("dist", 0);
        goal = sp.getFloat("goal", 0);
        nativeDist = sp.getFloat("nativeDist", 0);
        distAtJs = sp.getFloat("distAtJs", 0);
        jsAt = 0; // the app is not running now: the service keeps the notification up to date by itself
        lastFix = null;
        shown = "";
    }

    private void appendPoint(double[] p) {
        try (FileWriter w = new FileWriter(new File(getFilesDir(), POINTS_FILE), true)) {
            w.write(p[0] + "," + p[1] + "," + p[2] + "," + p[3] + "," + (long) p[4] + "," + p[5] + "\n");
        } catch (Exception e) { /* the point is still in memory */ }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        boolean saved = prefs(this).getBoolean("running", false);
        // started again by Android after the app was killed (no intent), or by the plugin after reopening
        if (saved && (action == null || ACTION_RESTORE.equals(action) || (!ACTION_START.equals(action) && !ACTION_STOP.equals(action) && callback == null))) {
            restoreFields();
            running = true;
            loadPoints(this);
            try {
                goForeground();
            } catch (Exception e) {
                // Android did not allow it from the background; the app restores the run when it is opened
                running = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            startLocation();
            if (action == null || ACTION_RESTORE.equals(action)) return START_STICKY;
        }
        if (action == null || ACTION_RESTORE.equals(action)) action = running ? ACTION_UPDATE : ACTION_STOP;
        switch (action) {
            case ACTION_START:
                goal = intent.getDoubleExtra("goal", 0);
                title = intent.getStringExtra("title");
                text = intent.getStringExtra("text");
                elapsedMs = 0; dist = 0; elapsedAt = System.currentTimeMillis();
                chronoBase = elapsedAt; shown = "";
                nativeDist = 0; distAtJs = 0; jsAt = elapsedAt; lastFix = null;
                paused = false;
                if (!running) {
                    synchronized (buffer) { buffer.clear(); }
                    new File(getFilesDir(), POINTS_FILE).delete();
                    startedAt = System.currentTimeMillis();
                }
                running = true;
                save();
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
                    // move the timer only if it is really off (more than 3 seconds), never on every update
                    long base = elapsedAt - (long) elapsedMs;
                    if (Math.abs(base - chronoBase) > 3000) chronoBase = base;
                    jsAt = System.currentTimeMillis();
                    distAtJs = nativeDist;
                }
                if (running) { save(); notifyNow(); }
                break;
            case ACTION_PAUSE:
            case ACTION_RESUME: {
                boolean p = ACTION_PAUSE.equals(action);
                // the timer freezes / continues right away; the app sends the exact numbers a moment later
                if (p && !paused) elapsedMs += System.currentTimeMillis() - elapsedAt;
                elapsedAt = System.currentTimeMillis();
                if (!p && paused) chronoBase = elapsedAt - (long) elapsedMs;
                paused = p;
                lastFix = null; // what was walked during the pause does not count
                if (appQuiet()) selfUpdate(); else { save(); notifyNow(); }
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

    /** The app has not sent numbers for a while (closed by Android, or asleep). */
    private boolean appQuiet() { return System.currentTimeMillis() - jsAt > 8000; }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000, dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** Same rules as the app: skip inaccurate fixes, standing-still noise and impossible jumps. */
    private void measure(double[] p) {
        if (paused || p[2] > 30) return;
        if (lastFix == null) { lastFix = p; return; }
        double d = haversine(lastFix[0], lastFix[1], p[0], p[1]);
        double dt = (p[4] - lastFix[4]) / 1000.0;
        if (d < 4) return;
        if (dt > 0 && d / dt > 9) return;
        nativeDist += d;
        lastFix = p;
    }

    private static String km(double m) { return String.format(Locale.US, "%.2f", Math.max(0, m) / 1000); }

    private static String clock(double ms) {
        long s = (long) (ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /** Builds the notification text here, the same way the app does, when the app is not running. */
    private void selfUpdate() {
        double d = dist + Math.max(0, nativeDist - distAtJs);
        double ms = paused ? elapsedMs : elapsedMs + (System.currentTimeMillis() - elapsedAt);
        title = km(d) + " ק״מ";
        String t = paused ? "הריצה בהשהיה · " + clock(ms) : "הריצה התחילה";
        if (goal > 0) t += d >= goal ? " · הגעת ליעד ✓" : " · נשארו " + km(goal - d) + " ק״מ ליעד";
        text = t;
        chip = String.format(Locale.US, "%.1f ק״מ", d / 1000);
        save();
        notifyNow();
    }

    /** Distance for the progress bar: the app's number plus whatever the service measured since. */
    private double curDist() { return dist + Math.max(0, nativeDist - distAtJs); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "מדידת ריצה", NotificationManager.IMPORTANCE_DEFAULT);
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
        // our own layout: distance and timer in the same big bold type, a line of text, a progress bar
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notif_run);
        rv.setTextViewText(R.id.nv_dist, title);
        rv.setTextViewText(R.id.nv_sub, text);
        // the timer is a Chronometer inside the notification: the system ticks it every second by itself
        long elapsedNow = paused ? (long) elapsedMs : System.currentTimeMillis() - chronoBase;
        rv.setChronometer(R.id.nv_time, SystemClock.elapsedRealtime() - elapsedNow, null, !paused);
        if (goal > 0) {
            rv.setViewVisibility(R.id.nv_prog, android.view.View.VISIBLE);
            rv.setProgressBar(R.id.nv_prog, 1000, (int) Math.min(1000, curDist() * 1000 / goal), false);
        } else {
            rv.setViewVisibility(R.id.nv_prog, android.view.View.GONE);
        }
        b.setStyle(new NotificationCompat.DecoratedCustomViewStyle()).setCustomContentView(rv).setCustomBigContentView(rv);
        b.setShowWhen(false).setWhen(chronoBase); // the base stays fixed (used to check the timer never restarts)
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
        if (nm == null) return;
        int prog = goal > 0 ? (int) Math.min(100, curDist() * 100 / goal) : -1;
        String sig = title + "|" + text + "|" + chip + "|" + paused + "|" + prog + "|" + (paused ? 0 : chronoBase);
        if (sig.equals(shown)) return;
        shown = sig;
        nm.notify(NOTIF_ID, build());
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
                    if (!paused) { // points walked during a pause are not part of the run
                        synchronized (buffer) { buffer.add(p); }
                        appendPoint(p);
                    }
                    measure(p);
                    JSObject o = new JSObject();
                    o.put("latitude", p[0]);
                    o.put("longitude", p[1]);
                    o.put("accuracy", p[2]);
                    o.put("speed", p[3] < 0 ? null : p[3]);
                    o.put("bearing", p[5] < 0 ? null : p[5]);
                    o.put("time", l.getTime());
                    RunTrackerPlugin.emit("location", o);
                }
                if (appQuiet()) selfUpdate();
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
        prefs(this).edit().clear().apply();
        new File(getFilesDir(), POINTS_FILE).delete();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** The app was swiped away from the recent-apps list: end the run's service and remove the notification. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopEverything();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (fused != null && callback != null) fused.removeLocationUpdates(callback);
        running = false;
        super.onDestroy();
    }
}
