package io.github.nekontritce11123.pocketandroid.probe;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One network worker; the independent deadline can abort blocked I/O. */
final class ProbeRunner {
    static final long DEFAULT_RUN_MILLIS = 5 * 60 * 1000;
    static final long MAX_RUN_MILLIS = 30 * 60 * 1000;
    interface Check { String run(Session session); }

    static final class Session {
        final long id;
        private final long deadline;
        private boolean stopped;
        private Runnable abort;

        Session(long id, long durationMillis) {
            this.id = id;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
        }

        synchronized boolean cancelled() {
            return stopped || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted();
        }

        synchronized boolean register(Runnable abort) {
            if (cancelled()) {
                abort.run();
                return false;
            }
            this.abort = abort;
            return true;
        }

        synchronized void clearAbort() { abort = null; }

        void cancel() {
            Runnable close;
            synchronized (this) {
                stopped = true;
                close = abort;
                abort = null;
            }
            if (close != null) close.run();
        }
    }

    private final ScheduledThreadPoolExecutor worker = scheduler();
    private final ScheduledThreadPoolExecutor timer = scheduler();
    private final long durationMillis;
    private Session session;
    private ScheduledFuture<?> pending, deadline;
    private long nextId;
    private boolean running;
    private String lastResult = "", status = "";
    private Runnable listener;

    ProbeRunner() { this(DEFAULT_RUN_MILLIS); }

    // Short deadlines are used only by the plain JVM self-check.
    ProbeRunner(long durationMillis) {
        if (durationMillis <= 0 || durationMillis > MAX_RUN_MILLIS) throw new IllegalArgumentException();
        this.durationMillis = durationMillis;
    }

    private static ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(30, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    synchronized void setListener(Runnable listener) { this.listener = listener; }
    synchronized boolean isRunning() { return running; }
    synchronized boolean hasResult() { return !status.isEmpty(); }
    synchronized String snapshot() {
        return status + (lastResult.isEmpty() ? "" : "\n\nПоследний завершённый результат:\n" + lastResult);
    }

    synchronized void start(Check check, boolean repeat, int intervalSeconds) {
        start(check, repeat, intervalSeconds, durationMillis);
    }

    synchronized void start(Check check, boolean repeat, int intervalSeconds, long runMillis) {
        TargetValidation.interval(intervalSeconds);
        if (runMillis <= 0 || runMillis > MAX_RUN_MILLIS) throw new IllegalArgumentException("Недопустимая длительность.");
        cancelCurrent();
        Session current = new Session(++nextId, runMillis);
        session = current;
        running = true;
        long minutes = Math.max(1, runMillis / 60000);
        status = repeat ? "Повторные проверки работают (не более " + minutes + " мин)." : "Проверка…";
        changed();
        deadline = timer.schedule(() -> finish(current, "Лимит " + minutes + " мин: проверки остановлены."),
                runMillis, TimeUnit.MILLISECONDS);
        Runnable task = () -> {
            if (current.cancelled()) return;
            String result;
            try {
                result = check.run(current);
            } catch (RuntimeException e) {
                result = "Ошибка проверки: " + e.getClass().getSimpleName();
            }
            synchronized (ProbeRunner.this) {
                if (session != current || !running || current.cancelled()) return;
                lastResult = result;
                if (!repeat) finish(current, "Проверка завершена.");
                else changed();
            }
        };
        pending = repeat ? worker.scheduleWithFixedDelay(task, 0, intervalSeconds, TimeUnit.SECONDS)
                : worker.schedule(task, 0, TimeUnit.SECONDS);
    }

    synchronized void stop(String reason) {
        cancelCurrent();
        status = reason;
        changed();
    }

    private synchronized void finish(Session current, String reason) {
        if (session != current || !running) return;
        stop(reason);
    }

    private void cancelCurrent() {
        running = false;
        if (session != null) session.cancel();
        if (pending != null) pending.cancel(true);
        if (deadline != null) deadline.cancel(false);
        pending = deadline = null;
    }

    private void changed() { if (listener != null) listener.run(); }

    synchronized void shutdownForTest() {
        cancelCurrent();
        worker.shutdownNow();
        timer.shutdownNow();
    }
}
