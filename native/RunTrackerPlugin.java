package app.alhaderech.run;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Bridge between the web app and RunTrackerService.
 * JS: start / update / stop / status / getSince / requestBatteryExemption,
 * events: "location" (every GPS fix) and "action" (pause / resume pressed in the notification).
 */
@CapacitorPlugin(
    name = "RunTracker",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class RunTrackerPlugin extends Plugin {

    static RunTrackerPlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    static void emit(String event, JSObject data) {
        RunTrackerPlugin p = instance;
        if (p != null) p.notifyListeners(event, data, true);
    }

    private boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED;
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED || needsNotificationPermission()) {
            requestPermissionForAliases(new String[] { "location", "notifications" }, call, "afterPermissions");
            return;
        }
        startService(call);
    }

    @PermissionCallback
    private void afterPermissions(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            call.reject("אין הרשאת מיקום", "NOT_AUTHORIZED");
            return;
        }
        startService(call);
    }

    private void startService(PluginCall call) {
        Context ctx = getContext();
        Intent i = new Intent(ctx, RunTrackerService.class).setAction(RunTrackerService.ACTION_START);
        i.putExtra("goal", call.getDouble("goal", 0.0));
        i.putExtra("title", call.getString("title", "על הדרך"));
        i.putExtra("text", call.getString("text", "הריצה התחילה"));
        ContextCompat.startForegroundService(ctx, i);
        call.resolve();
    }

    @PluginMethod
    public void update(PluginCall call) {
        RunTrackerService.restoreIfNeeded(getContext());
        if (!RunTrackerService.running) { call.resolve(); return; }
        Context ctx = getContext();
        Intent i = new Intent(ctx, RunTrackerService.class).setAction(RunTrackerService.ACTION_UPDATE);
        i.putExtra("title", call.getString("title", ""));
        i.putExtra("text", call.getString("text", ""));
        i.putExtra("chip", call.getString("chip", ""));
        i.putExtra("elapsedMs", call.getDouble("elapsedMs", 0.0));
        i.putExtra("paused", call.getBoolean("paused", false));
        i.putExtra("dist", call.getDouble("dist", 0.0));
        i.putExtra("goal", call.getDouble("goal", 0.0));
        ctx.startService(i);
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context ctx = getContext();
        if (RunTrackerService.running || RunTrackerService.prefs(ctx).getBoolean("running", false)) {
            ctx.startService(new Intent(ctx, RunTrackerService.class).setAction(RunTrackerService.ACTION_STOP));
        }
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        RunTrackerService.restoreIfNeeded(getContext());
        JSObject r = new JSObject();
        r.put("running", RunTrackerService.running);
        r.put("paused", RunTrackerService.paused);
        r.put("startedAt", RunTrackerService.startedAt);
        call.resolve(r);
    }

    /** GPS fixes the service received after `time` (ms), so the app can fill in what it missed. */
    @PluginMethod
    public void getSince(PluginCall call) {
        double since = call.getDouble("time", 0.0);
        RunTrackerService.restoreIfNeeded(getContext());
        JSArray arr = new JSArray();
        synchronized (RunTrackerService.buffer) {
            for (double[] p : RunTrackerService.buffer) {
                if (p[4] > since) {
                    JSObject o = new JSObject();
                    o.put("latitude", p[0]);
                    o.put("longitude", p[1]);
                    o.put("accuracy", p[2]);
                    o.put("speed", p[3]);
                    o.put("time", (long) p[4]);
                    o.put("bearing", p[5]);
                    arr.put(o);
                }
            }
        }
        JSObject r = new JSObject();
        r.put("points", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void requestBatteryExemption(PluginCall call) {
        Context ctx = getContext();
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        JSObject r = new JSObject();
        if (pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            // some phones do not have this screen
        }
        r.put("granted", false);
        call.resolve(r);
    }

    @PluginMethod
    public void isBatteryExempt(PluginCall call) {
        Context ctx = getContext();
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        JSObject r = new JSObject();
        r.put("granted", pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName()));
        call.resolve(r);
    }
}
