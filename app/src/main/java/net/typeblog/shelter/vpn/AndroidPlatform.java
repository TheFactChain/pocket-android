package net.typeblog.shelter.vpn;

import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InterfaceAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.nekohasekai.libbox.*;

/**
 * Android resources for one core lifetime; stop the core before closing this adapter.
 * Without a VpnService (the server check process) core sockets are bound to the physical network instead of
 * protected, and no TUN can be opened.
 */
public final class AndroidPlatform implements PlatformInterface, AutoCloseable {
    private final VpnService service;
    private final ConnectivityManager connectivity;
    private final Object lock = new Object();
    private final ConnectivityManager.NetworkCallback callback;
    private Network underlyingNetwork;
    private InterfaceUpdateListener interfaceListener;
    private Runnable networkChangeListener;
    private ParcelFileDescriptor tun;
    private volatile boolean closed;

    public AndroidPlatform(VpnService service) { this(service, service); }

    public AndroidPlatform(android.content.Context context, VpnService service) {
        this.service = service;
        connectivity = Objects.requireNonNull(context.getSystemService(ConnectivityManager.class));
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { refreshNetwork(null); }
            @Override public void onLost(Network network) { refreshNetwork(network); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                refreshNetwork(null);
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                refreshNetwork(null);
            }
        };
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
        refreshNetwork(null);
    }

    public Network getUnderlyingNetwork() {
        synchronized (lock) { return closed ? null : underlyingNetwork; }
    }

    /** The listener must enqueue controller work, rather than synchronously stop the core. */
    public void setNetworkChangeListener(Runnable listener) {
        synchronized (lock) {
            requireOpen();
            networkChangeListener = listener;
        }
    }

    private boolean isPhysical(NetworkCapabilities caps) {
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
    }

    private Network selectNetwork(Network lost) {
        Network active = connectivity.getActiveNetwork();
        Network best = null;
        int bestScore = -1;
        for (Network network : connectivity.getAllNetworks()) {
            if (network.equals(lost)) continue;
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (!isPhysical(caps) || properties == null || properties.getInterfaceName() == null) continue;
            int score = network.equals(active) ? 100 : 0;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 20;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 4;
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 3;
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 2;
            if (score > bestScore || (score == bestScore && network.equals(underlyingNetwork))) {
                best = network;
                bestScore = score;
            }
        }
        return best;
    }

    private void refreshNetwork(Network lost) {
        synchronized (lock) {
            if (closed) return;
            Network selected = selectNetwork(lost);
            boolean changed = !Objects.equals(selected, underlyingNetwork);
            underlyingNetwork = selected;
            updateInterfaceListener();
            if (changed && networkChangeListener != null) networkChangeListener.run();
        }
    }

    private void updateInterfaceListener() {
        if (interfaceListener == null) return;
        LinkProperties properties = underlyingNetwork == null ? null
                : connectivity.getLinkProperties(underlyingNetwork);
        NetworkCapabilities caps = underlyingNetwork == null ? null
                : connectivity.getNetworkCapabilities(underlyingNetwork);
        String name = properties == null ? null : properties.getInterfaceName();
        int index = -1;
        if (name != null) {
            try {
                java.net.NetworkInterface networkInterface = java.net.NetworkInterface.getByName(name);
                if (networkInterface != null) index = networkInterface.getIndex();
            } catch (IOException ignored) {
                // An interface may disappear between the connectivity event and this lookup.
            }
        }
        if (index < 0) name = "";
        boolean expensive = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        interfaceListener.updateDefaultInterface(name == null ? "" : name, index, expensive, false);
    }

    @Override public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        synchronized (lock) {
            requireOpen();
            if (interfaceListener != null && interfaceListener != listener) {
                throw new IllegalStateException("Interface monitor already registered");
            }
            interfaceListener = Objects.requireNonNull(listener);
            updateInterfaceListener();
        }
    }

    @Override public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        synchronized (lock) {
            if (interfaceListener == listener) interfaceListener = null;
        }
    }

    @Override public boolean usePlatformAutoDetectInterfaceControl() { return true; }

    @Override public void autoDetectInterfaceControl(int fd) throws IOException {
        requireOpen();
        if (service != null) {
            if (!service.protect(fd)) throw new IOException("Could not protect core socket");
            return;
        }
        Network network = getUnderlyingNetwork();
        if (network == null) throw new IOException("No physical Internet network");
        // The duplicate shares the socket, so binding it binds the core's socket; only the duplicate is closed.
        try (ParcelFileDescriptor socket = ParcelFileDescriptor.fromFd(fd)) { network.bindSocket(socket.getFileDescriptor()); }
    }

    @Override public int openTun(TunOptions options) throws Exception {
        synchronized (lock) {
            requireOpen();
            if (service == null) throw new IllegalStateException("No VPN service for a TUN");
            if (tun != null) throw new IllegalStateException("TUN already established for this core");
            if (underlyingNetwork == null) throw new IOException("No physical Internet network");
            if (options.getMTU() < 1280) throw new IllegalArgumentException("IPv6 TUN requires MTU >= 1280");
            // Other HTTP proxy getters dereference a nil Go pointer when the proxy is disabled.
            if (!options.getAutoRoute() || hasNext(options.getIncludePackage())
                    || hasNext(options.getExcludePackage())
                    || hasNext(options.getInet4RouteExcludeAddress())
                    || hasNext(options.getInet6RouteExcludeAddress())
                    || options.isHTTPProxyEnabled()) {
                throw new IllegalArgumentException("Unsupported TUN bypass or proxy options");
            }
            VpnService.Builder builder = service.new Builder().setSession("Pocket")
                    .setMtu(options.getMTU()).setMetered(false)
                    .setUnderlyingNetworks(new Network[]{underlyingNetwork});
            if (!addAddresses(builder, options.getInet4Address(), false)
                    || !addAddresses(builder, options.getInet6Address(), true)) {
                throw new IllegalArgumentException("TUN requires IPv4 and IPv6 addresses");
            }
            builder.addRoute("0.0.0.0", 0).addRoute("::", 0);
            StringIterator dns = options.getDNSServerAddress();
            boolean hasDNS = false;
            while (dns != null && dns.hasNext()) {
                builder.addDnsServer(dns.next());
                hasDNS = true;
            }
            if (!hasDNS) builder.addDnsServer("172.19.0.2");
            ParcelFileDescriptor established = builder.establish();
            if (established == null) throw new IOException("VPN permission revoked or TUN unavailable");
            tun = established;
            return tun.getFd();
        }
    }

    private static boolean hasNext(StringIterator iterator) { return iterator != null && iterator.hasNext(); }
    private static boolean hasNext(RoutePrefixIterator iterator) { return iterator != null && iterator.hasNext(); }

    private static boolean addAddresses(VpnService.Builder builder, RoutePrefixIterator iterator, boolean ipv6) {
        boolean added = false;
        while (iterator != null && iterator.hasNext()) {
            RoutePrefix prefix = iterator.next();
            InetAddress address = InetAddresses.parseNumericAddress(prefix.address());
            if ((address instanceof Inet6Address) != ipv6) {
                throw new IllegalArgumentException("Unexpected TUN address family");
            }
            builder.addAddress(address, prefix.prefix());
            added = true;
        }
        return added;
    }

    @Override public NetworkInterfaceIterator getInterfaces() throws Exception {
        // Go may call this back on another thread while updateDefaultInterface is waiting.
        requireOpen();
        List<io.nekohasekai.libbox.NetworkInterface> interfaces = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (!isPhysical(caps) || properties == null) continue;
            String name = properties.getInterfaceName();
            if (name == null || !names.add(name)) continue;
            java.net.NetworkInterface source = java.net.NetworkInterface.getByName(name);
            if (source == null) continue;
            io.nekohasekai.libbox.NetworkInterface result = new io.nekohasekai.libbox.NetworkInterface();
            result.setName(name);
            result.setIndex(source.getIndex());
            result.setMTU(source.getMTU());
            int flags = (source.isUp() ? OsConstants.IFF_UP | OsConstants.IFF_RUNNING : 0)
                    | (source.isLoopback() ? OsConstants.IFF_LOOPBACK : 0)
                    | (source.isPointToPoint() ? OsConstants.IFF_POINTOPOINT : 0)
                    | (source.supportsMulticast() ? OsConstants.IFF_MULTICAST : 0);
            List<String> addresses = new ArrayList<>();
            for (InterfaceAddress address : source.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) flags |= OsConstants.IFF_BROADCAST;
                // Go netip prefixes cannot contain the Java IPv6 scope suffix.
                addresses.add(address.getAddress().getHostAddress().split("%", 2)[0]
                        + "/" + address.getNetworkPrefixLength());
            }
            result.setFlags(flags);
            result.setAddresses(strings(addresses));
            int type = Libbox.InterfaceTypeOther;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = Libbox.InterfaceTypeWIFI;
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = Libbox.InterfaceTypeCellular;
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = Libbox.InterfaceTypeEthernet;
            result.setType(type);
            result.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            List<String> dns = new ArrayList<>();
            for (InetAddress address : properties.getDnsServers()) dns.add(address.getHostAddress());
            result.setDNSServer(strings(dns));
            List<String> gateways = new ArrayList<>();
            for (RouteInfo route : properties.getRoutes()) {
                if (route.hasGateway()) gateways.add(route.getGateway().getHostAddress());
            }
            result.setGateway(strings(gateways));
            interfaces.add(result);
        }
        return new NetworkInterfaceIterator() {
            private int index;
            @Override public boolean hasNext() { return index < interfaces.size(); }
            @Override public io.nekohasekai.libbox.NetworkInterface next() { return interfaces.get(index++); }
        };
    }

    private static StringIterator strings(List<String> values) {
        return new StringIterator() {
            private int index;
            @Override public boolean hasNext() { return index < values.size(); }
            @Override public int len() { return values.size() - index; }
            @Override public String next() { return values.get(index++); }
        };
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Platform is closed");
    }

    @Override public void close() throws IOException {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            interfaceListener = null;
            networkChangeListener = null;
            underlyingNetwork = null;
        }
        try {
            connectivity.unregisterNetworkCallback(callback);
        } finally {
            synchronized (lock) {
                if (tun != null) {
                    try { tun.close(); } finally { tun = null; }
                }
            }
        }
    }

    @Override public LocalDNSTransport localDNSTransport() { return null; }
    @Override public WIFIState readWIFIState() { return null; }
    @Override public boolean includeAllNetworks() { return true; }
    @Override public boolean underNetworkExtension() { return false; }
    @Override public boolean usePlatformShell() { return false; }
    @Override public boolean usePlatformBridge() { return false; }
    @Override public void clearDNSCache() { }
    @Override public void registerMyInterface(String name) { }
    @Override public String tailscaleHostname() { throw unsupported(); }
    @Override public void checkPlatformShell() { throw unsupported(); }
    @Override public String lookupSFTPServer() { throw unsupported(); }
    @Override public PlatformUser lookupUser(String username) { throw unsupported(); }
    @Override public String readSystemSSHHostKey() { throw unsupported(); }
    @Override public BridgeSession createBridge(BridgeOptions options) { throw unsupported(); }
    @Override public ShellSession openShellSession(PlatformUser user, String command, StringIterator environ,
            String term, int rows, int cols) { throw unsupported(); }
    @Override public ConnectionOwner findConnectionOwner(int protocol, String source, int sourcePort,
            String destination, int destinationPort) { throw unsupported(); }
    @Override public void sendNotification(io.nekohasekai.libbox.Notification notification) { throw unsupported(); }
    @Override public void cancelNotification(String identifier, int typeID) { throw unsupported(); }
    @Override public void startNeighborMonitor(NeighborUpdateListener listener) { throw unsupported(); }
    @Override public void closeNeighborMonitor(NeighborUpdateListener listener) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Platform feature is unavailable in Pocket");
    }
}
