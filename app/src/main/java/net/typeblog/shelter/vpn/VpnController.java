package net.typeblog.shelter.vpn;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import net.typeblog.shelter.services.IPocketVpnService;
import java.util.concurrent.TimeUnit;

/** Work-profile-only connection between the existing DPC service and the VPN process. */
public final class VpnController {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private IPocketVpnService service;
    private volatile boolean closed;
    // Only the main thread owns binding attempts, timers and backoff.
    private Attempt current;
    private long retryDelay = 2000;
    private final Runnable retry = this::bind;
    private final Runnable stable = () -> {
        if (current != null && current.connected && !closed) retryDelay = 2000;
    };
    private final Runnable timeout = () -> {
        if (current != null && !current.connected) reconnect(current);
    };

    public VpnController(Context context) {
        this.context = context.getApplicationContext();
        onMain(this::bind);
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run();
        else main.post(action);
    }

    private void bind() {
        if (closed || current != null) return;
        main.removeCallbacks(retry);
        Attempt attempt = new Attempt();
        current = attempt;
        try {
            boolean bound = context.bindService(new Intent(context, PocketVpnService.class)
                    .setAction(PocketVpnService.ACTION_CONTROL), attempt, Context.BIND_AUTO_CREATE);
            if (bound) main.postDelayed(timeout, 10000);
            else reconnect(attempt);
        } catch (IllegalStateException | SecurityException e) {
            reconnect(attempt);
        }
    }

    private void clearService() {
        synchronized (lock) {
            service = null;
            lock.notifyAll();
        }
    }

    private void unbind() {
        Attempt old = current;
        current = null;
        if (old != null) {
            try { context.unbindService(old); }
            catch (IllegalArgumentException | SecurityException ignored) { }
        }
    }

    private void reconnect(Attempt attempt) {
        if (current != attempt) return;
        main.removeCallbacks(timeout);
        main.removeCallbacks(stable);
        clearService();
        unbind();
        main.removeCallbacks(retry);
        if (!closed) {
            main.postDelayed(retry, retryDelay);
            retryDelay = Math.min(30000, retryDelay * 2);
        }
    }

    private final class Attempt implements ServiceConnection {
        boolean connected;

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            onMain(() -> {
                if (closed || current != this) return;
                IPocketVpnService next = IPocketVpnService.Stub.asInterface(binder);
                if (next == null) { reconnect(this); return; }
                main.removeCallbacks(timeout);
                connected = true;
                synchronized (lock) {
                    if (closed) return;
                    service = next;
                    lock.notifyAll();
                }
                main.removeCallbacks(stable);
                main.postDelayed(stable, 60000);
                try { PocketVpnService.start(context); }
                catch (IllegalStateException | SecurityException ignored) {
                    // Android may require a foreground action; keep control available without claiming a tunnel.
                }
            });
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            onMain(() -> reconnect(this));
        }

        @Override public void onBindingDied(ComponentName name) {
            onMain(() -> reconnect(this));
        }

        @Override public void onNullBinding(ComponentName name) {
            onMain(() -> reconnect(this));
        }
    }

    public IPocketVpnService get() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        synchronized (lock) {
            while (service == null && !closed && Looper.myLooper() != main.getLooper()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(lock, remaining); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Операция прервана");
                }
            }
            if (closed || service == null) throw new IllegalStateException("Служба VPN пока недоступна");
            return service;
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            service = null;
            lock.notifyAll();
        }
        onMain(() -> {
            main.removeCallbacksAndMessages(null);
            unbind();
        });
    }
}
