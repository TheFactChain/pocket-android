package net.typeblog.shelter.services;
interface IPocketVpnService {
    String getPocketState();
    String listConnections();
    String readConnection(String id);
    String saveConnection(String json);
    void activateConnection(String id);
    void deleteConnection(String id);
    void setDirectRu(boolean enabled);
    void checkNow();
    String listSubscriptions();
    String addSubscription(String url, boolean allowDevice);
    String refreshSubscription(String id, boolean allowDevice);
    void deleteSubscription(String id);
    void refreshDueSubscriptions();
    String pingConnections(String subscriptionId, boolean force);
}
