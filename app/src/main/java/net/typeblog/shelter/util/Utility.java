package net.typeblog.shelter.util;

import android.annotation.TargetApi;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Environment;
import android.os.UserManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.typeblog.shelter.R;
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver;
import net.typeblog.shelter.services.IShelterService;
import net.typeblog.shelter.ui.DummyActivity;
import net.typeblog.shelter.ui.MainActivity;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Utility {
    // Determine if the current app is the owner of the current profile
    // TODO: Replace all occurrences of duplicated code to call this function instead
    public static boolean isProfileOwner(Context context) {
        return context.getSystemService(DevicePolicyManager.class)
                .isProfileOwnerApp(context.getPackageName());
    }

    // Polyfill for String.join
    public static String stringJoin(String delimiter, String[] list) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return String.join(delimiter, list);
        } else {
            if (list.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.length - 1; i++) {
                sb.append(list[i]).append(delimiter);
            }
            sb.append(list[list.length - 1]);
            return sb.toString();
        }
    }

    // Affiliate an Intent to another profile (i.e. the Work profile that we manage)
    // This method cares nothing about if the other profile even exists.
    // When there is no other profile, this method would just simply throw
    // an IndexOutOfBoundException
    // which can be caught and resolved.
    public static void transferIntentToProfile(Context context, Intent intent) {
        transferIntentToProfileUnsigned(context, intent);
        // Add signature
        AuthenticationUtility.signIntent(intent);
    }

    public static void transferIntentToProfileUnsigned(Context context, Intent intent) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> info = pm.queryIntentActivities(intent, 0);
        Optional<ResolveInfo> i = info.stream()
                .filter((r) -> "android".equals(r.activityInfo.packageName))
                .findFirst();
        if (i.isPresent()) {
            intent.setComponent(new ComponentName(i.get().activityInfo.packageName, i.get().activityInfo.name));
        } else {
            throw new IllegalStateException("Cannot find an intent in other profile");
        }
    }

    // Determine if the work profile is already available
    // If so, return true and set all the corresponding flags to true
    // This is for scenarios where the asynchronous part of the
    // setup process might be finished before the synchronous part
    public static boolean isWorkProfileAvailable(Context context) {
        LocalStorageManager storage = LocalStorageManager.getInstance();
        Intent intent = new Intent(DummyActivity.TRY_START_SERVICE);
        try {
            // DO NOT sign this request, because this won't be actually sent to work profile
            // If this is signed, and is the first request to be signed,
            // then the other side would never receive the auth_key
            Utility.transferIntentToProfileUnsigned(context, intent);
            storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false);
            storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true);
            return true;
        } catch (IllegalStateException e) {
            // If any exception is thrown, this means that the profile is not available
            return false;
        }
    }

    // Enforce policies and configurations in the work profile
    public static void enforceWorkProfilePolicies(Context context) {
        DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
        ComponentName adminComponent = new ComponentName(context.getApplicationContext(), ShelterDeviceAdminReceiver.class);

        // Hide this app in the work profile
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context.getApplicationContext(), MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        // Replaces old broad sharing and browser bridges during upgrades, too.
        manager.clearCrossProfileIntentFilters(adminComponent);
        manager.addCrossProfileIntentFilter(adminComponent, new IntentFilter(DummyActivity.AUTHENTICATE), DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT);

        // Allow cross-profile intents for START_SERVICE
        manager.addCrossProfileIntentFilter(
                adminComponent,
                new IntentFilter(DummyActivity.START_SERVICE),
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT);

        manager.addCrossProfileIntentFilter(
                adminComponent,
                new IntentFilter(DummyActivity.TRY_START_SERVICE),
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT);

        manager.addCrossProfileIntentFilter(
                adminComponent,
                new IntentFilter(DummyActivity.UNFREEZE_AND_LAUNCH),
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT);
        manager.addCrossProfileIntentFilter(
                adminComponent,
                new IntentFilter(DummyActivity.SYNCHRONIZE_PREFERENCE),
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT);

        // Needed by ShelterService and has to be proxied by the MainActivity in main profile
        manager.addCrossProfileIntentFilter(
                adminComponent,
                new IntentFilter(DummyActivity.INSTALL_PACKAGE),
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT);

        manager.addCrossProfileIntentFilter(
                adminComponent,
                new IntentFilter(DummyActivity.UNINSTALL_PACKAGE),
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT);

        manager.setCrossProfileContactsSearchDisabled(adminComponent, true);
        manager.setCrossProfileCallerIdDisabled(adminComponent, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.setCrossProfilePackages(adminComponent, Collections.emptySet());
        }
        for (String provider : manager.getCrossProfileWidgetProviders(adminComponent)) {
            manager.removeCrossProfileWidgetProvider(adminComponent, provider);
        }

        net.typeblog.shelter.vpn.ProtectionPolicy.ensure(context);
        manager.setProfileEnabled(adminComponent);
    }

    public static void enforceUserRestrictions(Context context) {
        DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
        ComponentName adminComponent = new ComponentName(context.getApplicationContext(), ShelterDeviceAdminReceiver.class);
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS);
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Polyfill for UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
            // Don't use this on Android Oreo and later, it will crash
            manager.setSecureSetting(adminComponent, Settings.Secure.INSTALL_NON_MARKET_APPS, "1");
        }

        manager.clearUserRestriction(adminComponent, UserManager.ALLOW_PARENT_PROFILE_APP_LINKING);
    }

    // Detect if the device is MIUI
    public static boolean isMIUI() {
        try {
            Process proc = Runtime.getRuntime().exec("getprop ro.miui.ui.version.name");
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line = reader.readLine().trim();
            return !line.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // From <https://stackoverflow.com/questions/3035692/how-to-convert-a-drawable-to-a-bitmap>
    public static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable)drawable).getBitmap();
        }

        int width = drawable.getIntrinsicWidth();
        width = width > 0 ? width : 1;
        int height = drawable.getIntrinsicHeight();
        height = height > 0 ? height : 1;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    public static void killShelterServices(IShelterService serviceMain, IShelterService serviceWork) {
        // Ensure that all our other services are killed at this point
        try {
            serviceWork.stopShelterService(true);
        } catch (Exception e) {
            // We are stopping anyway
        }

        try {
            serviceMain.stopShelterService(false);
        } catch (Exception e) {
            // We are stopping anyway
        }
    }

    // Delete apps that no longer exist from the auto freeze list
    public static void deleteMissingApps(String pref, List<ApplicationInfoWrapper> apps) {
        List<String> list = new ArrayList<>(
                Arrays.asList(LocalStorageManager.getInstance().getStringList(pref)));
        list.removeIf((it) -> apps.stream().noneMatch((x) -> x.getPackageName().equals(it)));
        LocalStorageManager.getInstance().setStringList(pref, list.toArray(new String[]{}));
    }

    public static void createLauncherShortcut(Context context, Intent launchIntent, Icon icon, String id, String label) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);

            if (shortcutManager.isRequestPinShortcutSupported()) {
                ShortcutInfo info = new ShortcutInfo.Builder(context, id)
                        .setIntent(launchIntent)
                        .setIcon(icon)
                        .setShortLabel(label)
                        .setLongLabel(label)
                        .build();
                Intent addIntent = shortcutManager.createShortcutResultIntent(info);
                shortcutManager.requestPinShortcut(info,
                        PendingIntent.getBroadcast(context, 0, addIntent, PendingIntent.FLAG_IMMUTABLE).getIntentSender());
            } else {
                // TODO: Maybe implement this for launchers without pin shortcut support?
                // TODO: Should be the same with the fallback for Android < O
                // for now just show unsupported
                Toast.makeText(context, context.getString(R.string.unsupported_launcher), Toast.LENGTH_LONG).show();
            }
        } else {
            Intent shortcutIntent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent);
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, label);
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON, drawableToBitmap(icon.loadDrawable(context)));
            context.sendBroadcast(shortcutIntent);
            Toast.makeText(context, R.string.shortcut_create_success, Toast.LENGTH_SHORT).show();
        }
    }

    public static Intent registerWorkShortcut(Context context, long profileSerial, String packageName, String[] linkedPackages) {
        if (isProfileOwner(context) || profileSerial < 0) throw new IllegalStateException("Рабочий профиль недоступен");
        requireShortcutPackage(packageName);
        if (linkedPackages == null) linkedPackages = new String[0];
        if (linkedPackages.length > 128) throw new IllegalArgumentException("Слишком много связанных приложений");
        JSONArray linked = new JSONArray();
        for (String name : linkedPackages) {
            requireShortcutPackage(name);
            linked.put(name);
        }
        String token = UUID.randomUUID().toString();
        try {
            JSONObject record = new JSONObject().put("profileSerial", profileSerial)
                    .put("packageName", packageName).put("linkedPackages", linked);
            if (!context.getSharedPreferences("pocket_work_shortcuts_v1", Context.MODE_PRIVATE)
                    .edit().putString(token, record.toString()).commit()) {
                throw new IllegalStateException("Не удалось сохранить рабочий ярлык");
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("Некорректная цель рабочего ярлыка");
        }
        return new Intent(DummyActivity.PUBLIC_UNFREEZE_AND_LAUNCH)
                .setComponent(new ComponentName(context, DummyActivity.class))
                .putExtra("_pocket_shortcut", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static Intent resolveWorkShortcut(Context context, String token) {
        if (isProfileOwner(context) || token == null || !token.matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("Пересоздай рабочий ярлык в Pocket");
        }
        String stored = context.getSharedPreferences("pocket_work_shortcuts_v1", Context.MODE_PRIVATE)
                .getString(token, null);
        if (stored == null) throw new IllegalArgumentException("Пересоздай рабочий ярлык в Pocket");
        try {
            JSONObject record = new JSONObject(stored);
            long serial = record.getLong("profileSerial");
            if (serial < 0) throw new IllegalArgumentException("Рабочий профиль недоступен");
            String packageName = record.getString("packageName");
            requireShortcutPackage(packageName);
            JSONArray linked = record.getJSONArray("linkedPackages");
            if (linked.length() > 128) throw new IllegalArgumentException("Некорректная цель рабочего ярлыка");
            String[] packages = new String[linked.length()];
            for (int i = 0; i < packages.length; i++) {
                packages[i] = linked.getString(i);
                requireShortcutPackage(packages[i]);
            }
            return new Intent(DummyActivity.UNFREEZE_AND_LAUNCH).putExtra("profileSerial", serial)
                    .putExtra("packageName", packageName).putExtra("linkedPackages", packages);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Пересоздай рабочий ярлык в Pocket");
        }
    }

    private static void requireShortcutPackage(String value) {
        if (value == null || value.length() > 255
                || !value.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("Некорректная цель рабочего ярлыка");
        }
    }

    public static int getMediaStoreId(Context context, String path) {
        Cursor cursor = context.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DATA + " LIKE ? ",
                new String[]{path}, null);
        if (cursor == null || cursor.getCount() == 0) {
            return -1;
        } else {
            cursor.moveToFirst();
            return cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
        }
    }

    // Functions to load scaled down version of Bitmap
    // from <https://developer.android.com/topic/performance/graphics/load-bitmap?hl=es#java>
    public static int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    public static Bitmap decodeSampledBitmap(String filePath,
                                             int reqWidth, int reqHeight) {
        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, options);

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(filePath, options);
    }

    public static Bitmap decodeSampledBitmap(FileDescriptor fd,
                                             int reqWidth, int reqHeight) {
        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd, null, options);

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd, null, options);
    }

    // Get file's extension name
    public static String getFileExtension(String filePath) {
        int index = filePath.lastIndexOf(".");
        if (index > 0) {
            return filePath.substring(index + 1);
        } else {
            return null;
        }
    }

    // Check if USAGE_STATS is granted
    public static boolean checkUsageStatsPermission(Context context) {
        return checkSpecialAccessPermission(context, AppOpsManager.OPSTR_GET_USAGE_STATS);
    }

    // Check if SYSTEM_ALERT_WINDOW is granted
    public static boolean checkSystemAlertPermission(Context context) {
        return checkSpecialAccessPermission(context, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW);
    }

    // Check if all file access r/w is granted
    @TargetApi(Build.VERSION_CODES.R)
    public static boolean checkAllFileAccessPermission() {
        return Environment.isExternalStorageManager();
    }

    // Check special access permission through AppOps
    public static boolean checkSpecialAccessPermission(Context context, String name) {
        AppOpsManager appops = context.getSystemService(AppOpsManager.class);
        int mode = appops.checkOpNoThrow(name, android.os.Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // Pipe an InputStream to OutputStream
    public static void pipe(InputStream is, OutputStream os) throws IOException {
        int n;
        byte[] buffer = new byte[65536];
        while ((n = is.read(buffer)) > -1) {
            os.write(buffer, 0, n);
        }
    }

    // Utilities to build notifications for cross-version compatibility
    private static final String NOTIFICATION_CHANNEL_ID = "ShelterService";
    private static final String NOTIFICATION_CHANNEL_IMPORTANT = "ShelterService-Important";
    public static Notification buildNotification(Context context, String ticker, String title, String desc, int icon) {
        return buildNotification(context, false, ticker, title, desc, icon);
    }

    public static Notification buildNotification(Context context, boolean important, String ticker, String title, String desc, int icon) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return buildNotificationOreo(context, important, ticker, title, desc, icon);
        } else {
            return buildNotificationLollipop(context, important, ticker, title, desc, icon);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static Notification buildNotificationLollipop(Context context, boolean important, String ticker, String title, String desc, int icon) {
        return new Notification.Builder(context)
                .setTicker(ticker)
                .setContentTitle(title)
                .setContentText(desc)
                .setSmallIcon(icon)
                .setPriority(important ? Notification.PRIORITY_MAX : Notification.PRIORITY_MIN)
                .build();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private static Notification buildNotificationOreo(Context context, boolean important, String ticker, String title, String desc, int icon) {
        String id = important ? NOTIFICATION_CHANNEL_IMPORTANT : NOTIFICATION_CHANNEL_ID;
        // Android O and later: Notification Channel
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(id) == null) {
            NotificationChannel chan = new NotificationChannel(
                    id,
                    important ? context.getString(R.string.notifications_important)
                            : context.getString(R.string.app_name),
                    important ? NotificationManager.IMPORTANCE_HIGH
                            : NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(chan);
        }

        // Disable everything: do not disturb the user
        NotificationChannel chan = nm.getNotificationChannel(id);
        if (!important) {
            chan.enableVibration(false);
            chan.enableLights(false);
            chan.setImportance(NotificationManager.IMPORTANCE_MIN);
        } else {
            chan.enableVibration(true);
            chan.setImportance(NotificationManager.IMPORTANCE_HIGH);
        }
        nm.createNotificationChannel(chan);

        // Create foreground notification to keep the service alive
        return new Notification.Builder(context, id)
                .setTicker(ticker)
                .setContentTitle(title)
                .setContentText(desc)
                .setSmallIcon(icon)
                .build();
    }

    // A wrapper over arbitrary ActivityResultContract that provides
    // hardcoded input parameters and do not accept input with launch()
    public static class ActivityResultContractInputWrapper<I, O, T extends ActivityResultContract<I, O>>
            extends ActivityResultContract<Void, O> {
        private final T mInner;
        private final I mInput;

        public ActivityResultContractInputWrapper(T inner, I input) {
            mInner = inner;
            mInput = input;
        }

        @NonNull
        @Override
        public Intent createIntent(@NonNull Context context, Void input) {
            return mInner.createIntent(context, mInput);
        }

        @Override
        public O parseResult(int resultCode, @Nullable Intent intent) {
            return mInner.parseResult(resultCode, intent);
        }
    }
}
