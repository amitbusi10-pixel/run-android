// Runs in CI after `npx cap add android`: permissions, icons, notification icon, version.
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve('android/app/src/main');
const res = path.join(root, 'res');

// 1. Permissions
const manifestPath = path.join(root, 'AndroidManifest.xml');
let manifest = fs.readFileSync(manifestPath, 'utf8');
const perms = [
  'android.permission.ACCESS_COARSE_LOCATION',
  'android.permission.ACCESS_FINE_LOCATION',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_LOCATION',
  'android.permission.POST_NOTIFICATIONS',
  'android.permission.WAKE_LOCK',
  'android.permission.VIBRATE',
  'android.permission.POST_PROMOTED_NOTIFICATIONS',
  'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
];
const missing = perms.filter((p) => !manifest.includes(`"${p}"`));
const lines = missing.map((p) => `    <uses-permission android:name="${p}" />`).join('\n');
manifest = manifest.replace('</manifest>', `${lines}\n    <uses-feature android:name="android.hardware.location.gps" android:required="false" />\n</manifest>`);
// the live notification is shown immediately, so the exact-alarm permission is not needed
if (!manifest.includes('xmlns:tools')) manifest = manifest.replace('<manifest ', '<manifest xmlns:tools="http://schemas.android.com/tools" ');
manifest = manifest.replace('</manifest>', '    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" tools:node="remove" />\n</manifest>');
// the run-tracking foreground service (native/RunTrackerService.java)
manifest = manifest.replace('</application>', '        <service android:name=".RunTrackerService" android:exported="false" android:foregroundServiceType="location" />\n    </application>');
// keep the screen in portrait, like the web app
manifest = manifest.replace('android:name=".MainActivity"', 'android:name=".MainActivity"\n            android:screenOrientation="portrait"');
fs.writeFileSync(manifestPath, manifest);

// 2. Launcher icons (generated from icon-512.png, committed in resources/android)
const iconSrc = path.resolve('resources/android');
for (const dir of fs.readdirSync(iconSrc)) {
  const to = path.join(res, dir);
  fs.mkdirSync(to, { recursive: true });
  for (const f of fs.readdirSync(path.join(iconSrc, dir))) fs.copyFileSync(path.join(iconSrc, dir, f), path.join(to, f));
}
const bgPath = path.join(res, 'values/ic_launcher_background.xml');
if (fs.existsSync(bgPath)) fs.writeFileSync(bgPath, fs.readFileSync(bgPath, 'utf8').replace(/#[0-9A-Fa-f]{6,8}/, '#0E1622'));

// 3. Notification icon: white route on transparent, as a vector
fs.mkdirSync(path.join(res, 'drawable'), { recursive: true });
fs.writeFileSync(path.join(res, 'drawable/ic_tracking.xml'), `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="2.4"
        android:strokeLineCap="round" android:pathData="M5,18 C9,15 9,12 8,10 C7,7 9,5.5 12,5.5 C14,5.5 15.5,6.5 16.5,8" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M5,18m-2.2,0a2.2,2.2 0,1 1,4.4 0a2.2,2.2 0,1 1,-4.4 0" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M18,9.5m-3,0a3,3 0,1 1,6 0a3,3 0,1 1,-6 0" />
</vector>
`);

// 4. Native run tracker: copy the sources, register the plugin, add Google location services
const javaDir = path.join(root, 'java/app/alhaderech/run');
for (const f of fs.readdirSync(path.resolve('native'))) fs.copyFileSync(path.resolve('native', f), path.join(javaDir, f));
const mainPath = path.join(javaDir, 'MainActivity.java');
fs.writeFileSync(mainPath, `package app.alhaderech.run;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RunTrackerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);
const appGradlePath = path.resolve('android/app/build.gradle');
let appGradle = fs.readFileSync(appGradlePath, 'utf8');
appGradle = appGradle.replace(/dependencies \{/, 'dependencies {\n    implementation "com.google.android.gms:play-services-location:21.3.0"');
fs.writeFileSync(appGradlePath, appGradle);

// 5. Version: every build installs over the previous one
const gradlePath = path.resolve('android/app/build.gradle');
const run = Number(process.env.GITHUB_RUN_NUMBER || 1);
let gradle = fs.readFileSync(gradlePath, 'utf8');
gradle = gradle.replace(/versionCode \d+/, `versionCode ${run}`).replace(/versionName "[^"]*"/, `versionName "1.${run}"`);
fs.writeFileSync(gradlePath, gradle);

console.log('patched: permissions +' + missing.length + ', icons, notification icon, version ' + run);
