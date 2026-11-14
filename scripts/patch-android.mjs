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
];
const missing = perms.filter((p) => !manifest.includes(`"${p}"`));
const lines = missing.map((p) => `    <uses-permission android:name="${p}" />`).join('\n');
manifest = manifest.replace('</manifest>', `${lines}\n    <uses-feature android:name="android.hardware.location.gps" android:required="false" />\n</manifest>`);
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

// 4. Strings for the background-location notification
const stringsPath = path.join(res, 'values/strings.xml');
let strings = fs.readFileSync(stringsPath, 'utf8');
strings = strings.replace('</resources>', `    <string name="capacitor_background_geolocation_notification_channel_name">מעקב ריצה ברקע</string>
    <string name="capacitor_background_geolocation_notification_icon">drawable/ic_tracking</string>
    <string name="capacitor_background_geolocation_notification_color">#FF7A45</string>
</resources>`);
fs.writeFileSync(stringsPath, strings);

// 5. Version: every build installs over the previous one
const gradlePath = path.resolve('android/app/build.gradle');
const run = Number(process.env.GITHUB_RUN_NUMBER || 1);
let gradle = fs.readFileSync(gradlePath, 'utf8');
gradle = gradle.replace(/versionCode \d+/, `versionCode ${run}`).replace(/versionName "[^"]*"/, `versionName "1.${run}"`);
fs.writeFileSync(gradlePath, gradle);

console.log('patched: permissions +' + missing.length + ', icons, notification icon, version ' + run);
