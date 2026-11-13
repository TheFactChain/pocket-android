// IShelterService.aidl
package net.typeblog.shelter.services;

import android.content.pm.ApplicationInfo;

import net.typeblog.shelter.services.IAppInstallCallback;
import net.typeblog.shelter.services.IGetAppsCallback;
import net.typeblog.shelter.services.ILoadIconCallback;
import net.typeblog.shelter.services.IStartActivityProxy;
import net.typeblog.shelter.util.ApplicationInfoWrapper;
import net.typeblog.shelter.util.UriForwardProxy;

interface IShelterService {
    void ping();
    String attest(String nonce);
    void stopShelterService(boolean kill);
    void getApps(IGetAppsCallback callback, boolean showAll);
    void loadIcon(in ApplicationInfoWrapper info, ILoadIconCallback callback);
    void installApp(in ApplicationInfoWrapper app, IAppInstallCallback callback);
    void installApk(in UriForwardProxy uri, IAppInstallCallback callback);
    void uninstallApp(in ApplicationInfoWrapper app, IAppInstallCallback callback);
    void freezeApp(in ApplicationInfoWrapper app);
    void unfreezeApp(in ApplicationInfoWrapper app);
    boolean hasUsageStatsPermission();
    boolean hasSystemAlertPermission();
    boolean hasAllFileAccessPermission();
    List<String> getCrossProfileWidgetProviders();
    boolean setCrossProfileWidgetProviderEnabled(String pkgName, boolean enabled);
    void setStartActivityProxy(in IStartActivityProxy proxy);
    List<String> getCrossProfilePackages();
    void setCrossProfilePackages(in List<String> packages);
    String getPocketState();
    String listConnections();
    String readConnection(String id);
    String saveConnection(String json);
    void activateConnection(String id);
    void deleteConnection(String id);
    void setDirectRu(boolean enabled);
    void setProviderRules(boolean enabled);
    void setIpControl(boolean enabled, int minutes);
    void confirmIp();
    void setAppConnection(String packageName, String connectionId);
    String exportSettings(String password);
    String importSettings(String file, String password);
    String diagnose();
    void checkExitIp();
    void checkConnectionNow();
    String listSubscriptions();
    String addSubscription(String url, boolean allowDevice);
    String refreshSubscription(String id, boolean allowDevice);
    void deleteSubscription(String id);
    void refreshDueSubscriptions();
    String pingConnections(String subscriptionId, boolean force);
}
