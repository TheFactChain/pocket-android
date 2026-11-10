package io.github.nekontritce11123.pocketandroid.probe;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Run with javac/java; no Android runtime, network or credentials. */
public final class ProbeRunnerSelfTest {
    public static void main(String[] args) throws Exception {
        singleResultAndStop();
        replaceWaitsForOldWorker();
        deadlineAbortsAndDoesNotRepeat();
        cancelledSessionRejectsResources();
        try {
            new ProbeRunner(ProbeRunner.MAX_RUN_MILLIS + 1);
            throw new AssertionError("Allowed a run longer than five minutes");
        } catch (IllegalArgumentException expected) { }
        System.out.println("ProbeRunnerSelfTest PASS (single result, stop, replace, deadline, resource cancellation, maximum)");
    }

    private static void singleResultAndStop() throws Exception {
        ProbeRunner runner = new ProbeRunner();
        try {
            AtomicInteger calls = new AtomicInteger();
            runner.start(session -> { calls.incrementAndGet(); return "single-result"; }, false, 1);
            await(() -> !runner.isRunning());
            check(runner.snapshot().contains("single-result"), "Lost single result");
            check(calls.get() == 1, "Single request repeated");
            runner.start(session -> { calls.incrementAndGet(); return "repeat-result"; }, true, 1);
            await(() -> runner.snapshot().contains("repeat-result"));
            runner.stop("explicit-stop");
            int stoppedCalls = calls.get();
            Thread.sleep(1100);
            check(calls.get() == stoppedCalls, "Request after explicit stop");
            check(runner.snapshot().contains("explicit-stop") && runner.snapshot().contains("repeat-result"),
                    "Stop lost the last real result");
        } finally { runner.shutdownForTest(); }
    }

    private static void replaceWaitsForOldWorker() throws Exception {
        ProbeRunner runner = new ProbeRunner();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger(), newCalls = new AtomicInteger();
        try {
            runner.start(session -> {
                session.register(closes::incrementAndGet);
                entered.countDown();
                // Model platform DNS that takes time to unwind after cancellation.
                boolean done = false;
                while (!done) {
                    try { release.await(); done = true; }
                    catch (InterruptedException ignored) { }
                }
                return "stale-result";
            }, true, 1);
            check(entered.await(2, TimeUnit.SECONDS), "Old worker did not start");
            runner.start(session -> { newCalls.incrementAndGet(); return "new-result"; }, false, 1);
            check(closes.get() == 1, "Replace did not close active resource");
            Thread.sleep(50);
            check(newCalls.get() == 0, "Overlapping network workers");
            release.countDown();
            await(() -> !runner.isRunning());
            check(runner.snapshot().contains("new-result") && !runner.snapshot().contains("stale-result"),
                    "Stale worker overwrote new result");
        } finally { release.countDown(); runner.shutdownForTest(); }
    }

    private static void deadlineAbortsAndDoesNotRepeat() throws Exception {
        ProbeRunner runner = new ProbeRunner(150);
        AtomicInteger calls = new AtomicInteger(), closes = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            runner.start(session -> {
                calls.incrementAndGet();
                session.register(() -> { closes.incrementAndGet(); release.countDown(); });
                entered.countDown();
                try { release.await(); } catch (InterruptedException ignored) { }
                return "late-result";
            }, true, 1);
            check(entered.await(2, TimeUnit.SECONDS), "Deadline worker did not start");
            await(() -> !runner.isRunning());
            await(() -> closes.get() == 1);
            Thread.sleep(1100);
            check(calls.get() == 1, "Request after deadline");
            check(!runner.snapshot().contains("late-result"), "Deadline published late result");
            check(runner.snapshot().contains("Лимит"), "No timeout status");
        } finally { release.countDown(); runner.shutdownForTest(); }
    }

    private static void cancelledSessionRejectsResources() throws Exception {
        ProbeRunner.Session session = new ProbeRunner.Session(1, 1000);
        session.cancel();
        AtomicInteger closes = new AtomicInteger();
        check(!session.register(closes::incrementAndGet), "Cancelled session accepted I/O");
        check(closes.get() == 1 && session.cancelled(), "Cancelled I/O was not closed");
        ProbeRunner.Session expired = new ProbeRunner.Session(2, 1);
        Thread.sleep(10);
        check(!expired.register(closes::incrementAndGet), "Expired session accepted I/O");
        check(closes.get() == 2, "Expired I/O was not closed");
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        check(condition.getAsBoolean(), "Timed out waiting for test condition");
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
