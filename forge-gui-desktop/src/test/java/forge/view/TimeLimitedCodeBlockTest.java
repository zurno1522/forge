package forge.view;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TimeLimitedCodeBlockTest {
    @Test(groups = { "UnitTest", "fast" })
    public void normalTasksReturnResultsWithoutCancellation() throws Exception {
        final AtomicInteger cancellations = new AtomicInteger();
        Assert.assertEquals(TimeLimitedCodeBlock.runWithTimeout((Callable<String>) () -> "result",
                5, TimeUnit.SECONDS, cancellations::incrementAndGet), "result");
        final AtomicBoolean ran = new AtomicBoolean();
        TimeLimitedCodeBlock.runWithTimeout((Runnable) () -> ran.set(true),
                5, TimeUnit.SECONDS, cancellations::incrementAndGet);
        Assert.assertTrue(ran.get());
        Assert.assertEquals(cancellations.get(), 0);
    }

    @Test(groups = { "UnitTest", "fast" })
    public void originalExceptionsAndErrorsAreUnwrapped() {
        final IllegalStateException failure = new IllegalStateException("task failed");
        final IllegalStateException thrown = Assert.expectThrows(IllegalStateException.class,
                () -> TimeLimitedCodeBlock.runWithTimeout((Callable<Void>) () -> {
                    throw failure;
                }, 5, TimeUnit.SECONDS));
        Assert.assertSame(thrown, failure);

        final AssertionError error = new AssertionError("task error");
        final AssertionError thrownError = Assert.expectThrows(AssertionError.class,
                () -> TimeLimitedCodeBlock.runWithTimeout((Runnable) () -> {
                    throw error;
                }, 5, TimeUnit.SECONDS));
        Assert.assertSame(thrownError, error);
    }

    @Test(groups = { "UnitTest", "fast" })
    public void timeoutWaitsForWorkerCleanupAndRunsCallbackBeforeInterrupt() throws Exception {
        final CancellationProbe probe = new CancellationProbe(100, TimeUnit.MILLISECONDS, null);
        try {
            probe.start();
            probe.awaitCleanup();
            probe.assertCallerStillWaiting();
            Assert.assertTrue(probe.callbackSeenByWorker.get());
            probe.releaseCleanup.countDown();

            Assert.assertTrue(probe.result.get(5, TimeUnit.SECONDS) instanceof TimeoutException);
            Assert.assertTrue(probe.workerFinished.get());
            Assert.assertEquals(probe.cancellations.get(), 1);
            Assert.assertFalse(probe.callerInterrupted.get());
        } finally {
            probe.close();
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void callerInterruptionCancelsAndWaitsForWorkerCleanup() throws Exception {
        final CancellationProbe probe = new CancellationProbe(1, TimeUnit.DAYS, null);
        try {
            probe.start();
            Assert.assertTrue(probe.workerStarted.await(5, TimeUnit.SECONDS));
            probe.caller.interrupt();
            probe.awaitCleanup();
            probe.assertCallerStillWaiting();
            probe.releaseCleanup.countDown();

            Assert.assertTrue(probe.result.get(5, TimeUnit.SECONDS) instanceof InterruptedException);
            Assert.assertTrue(probe.workerFinished.get());
            Assert.assertTrue(probe.callerInterrupted.get());
            Assert.assertTrue(probe.callbackSeenByWorker.get());
            Assert.assertEquals(probe.cancellations.get(), 1);
        } finally {
            probe.close();
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void interruptionWhileJoiningPreservesTimeoutAndInterruptFlag() throws Exception {
        final CancellationProbe probe = new CancellationProbe(100, TimeUnit.MILLISECONDS, null);
        try {
            probe.start();
            probe.awaitCleanup();
            probe.caller.interrupt();
            probe.assertCallerStillWaiting();
            probe.releaseCleanup.countDown();

            Assert.assertTrue(probe.result.get(5, TimeUnit.SECONDS) instanceof TimeoutException);
            Assert.assertTrue(probe.callerInterrupted.get());
            Assert.assertTrue(probe.workerFinished.get());
        } finally {
            probe.close();
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void failingCancellationCallbackStillCancelsAndJoinsWorker() throws Exception {
        final IllegalStateException cleanupFailure = new IllegalStateException("callback failed");
        final CancellationProbe probe = new CancellationProbe(100, TimeUnit.MILLISECONDS, cleanupFailure);
        try {
            probe.start();
            probe.awaitCleanup();
            probe.assertCallerStillWaiting();
            probe.releaseCleanup.countDown();

            final Throwable thrown = probe.result.get(5, TimeUnit.SECONDS);
            Assert.assertTrue(thrown instanceof TimeoutException);
            Assert.assertEquals(thrown.getSuppressed().length, 1);
            Assert.assertSame(thrown.getSuppressed()[0], cleanupFailure);
            Assert.assertTrue(probe.workerFinished.get());
            Assert.assertEquals(probe.cancellations.get(), 1);
        } finally {
            probe.close();
        }
    }

    private static final class CancellationProbe implements AutoCloseable {
        private final CountDownLatch workerStarted = new CountDownLatch(1);
        private final CountDownLatch cleanupStarted = new CountDownLatch(1);
        private final CountDownLatch releaseCleanup = new CountDownLatch(1);
        private final AtomicBoolean workerFinished = new AtomicBoolean();
        private final AtomicBoolean callbackSeenByWorker = new AtomicBoolean();
        private final AtomicBoolean callerInterrupted = new AtomicBoolean();
        private final AtomicInteger cancellations = new AtomicInteger();
        private final CompletableFuture<Throwable> result = new CompletableFuture<>();
        private final Thread caller;

        private CancellationProbe(final long timeout, final TimeUnit unit, final RuntimeException callbackFailure) {
            caller = new Thread(() -> {
                Throwable thrown = null;
                try {
                    TimeLimitedCodeBlock.runWithTimeout((Callable<Void>) () -> {
                        workerStarted.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException e) {
                            callbackSeenByWorker.set(cancellations.get() == 1);
                            cleanupStarted.countDown();
                            releaseCleanup.await();
                        } finally {
                            workerFinished.set(true);
                        }
                        return null;
                    }, timeout, unit, () -> {
                        cancellations.incrementAndGet();
                        if (callbackFailure != null) {
                            throw callbackFailure;
                        }
                    });
                } catch (Throwable failure) {
                    thrown = failure;
                } finally {
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                    result.complete(thrown);
                }
            }, "time-limited-caller-test");
        }

        private void start() {
            caller.start();
        }

        private void awaitCleanup() throws InterruptedException {
            Assert.assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS), "Worker did not begin cancellation cleanup");
        }

        private void assertCallerStillWaiting() {
            Assert.expectThrows(TimeoutException.class, () -> result.get(50, TimeUnit.MILLISECONDS));
        }

        @Override
        public void close() throws InterruptedException {
            releaseCleanup.countDown();
            caller.interrupt();
            caller.join(5000);
            Assert.assertFalse(caller.isAlive(), "Caller failed to stop after worker cleanup was released");
        }
    }
}
