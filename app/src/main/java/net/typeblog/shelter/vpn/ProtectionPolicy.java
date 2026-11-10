package net.typeblog.shelter.vpn;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver;
import org.json.JSONObject;
import java.util.Collections;
import java.util.Set;

public final class ProtectionPolicy {
    private ProtectionPolicy() {}
    public static boolean isOwner(Context context) {
        DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
        return manager != null && manager.isProfileOwnerApp(context.getPackageName());
    }
    public static boolean confirmed(Context context) {
        if (!isOwner(context)) return false;
        try {
            DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
            ComponentName admin = new ComponentName(context, ShelterDeviceAdminReceiver.class);
            Set<String> exceptions = manager.getAlwaysOnVpnLockdownWhitelist(admin);
            return context.getPackageName().equals(manager.getAlwaysOnVpnPackage(admin))
                    && manager.isAlwaysOnVpnLockdownEnabled(admin) && exceptions != null && exceptions.isEmpty();
        } catch (RuntimeException e) { return false; }
    }
    public static void ensure(Context context) {
        if (!isOwner(context)) throw new SecurityException("Pocket не управляет этим рабочим профилем");
        if (!confirmed(context)) {
            try {
                context.getSystemService(DevicePolicyManager.class).setAlwaysOnVpnPackage(
                        new ComponentName(context, ShelterDeviceAdminReceiver.class), context.getPackageName(), true, Collections.emptySet());
            } catch (Exception e) { throw new IllegalStateException("Не удалось включить системную блокировку VPN"); }
        }
        if (!confirmed(context)) throw new IllegalStateException("Системная защита не подтверждена");
    }
    public static void requireConfirmed(Context context) {
        if (!confirmed(context)) throw new IllegalStateException("Сначала настрой защиту в Pocket. Добавление приложений пока недоступно.");
    }
}
