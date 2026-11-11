package forge.view;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a task with a deadline and waits for its worker to stop before returning. */
public class TimeLimitedCodeBlock {

    public static void runWithTimeout(final Runnable runnable, long timeout, TimeUnit timeUnit) throws Exception {
        runWithTimeout(runnable, timeout, timeUnit, () -> { });
    }

    public static void runWithTimeout(final Runnable runnable, long timeout, TimeUnit timeUnit,
            final Runnable onCancellation) throws Exception {
        Objects.requireNonNull(runnable);
        runWithTimeout(() -> {
            runnable.run();
            return null;
        }, timeout, timeUnit, onCancellation);
    }

    public static <T> T runWithTimeout(Callable<T> callable, long timeout, TimeUnit timeUnit) throws Exception {
        return runWithTimeout(callable, timeout, timeUnit, () -> { });
    }

    /**
     * The cancellation callback runs on the caller before interrupting a timed-out task,
     * or when the caller is interrupted. It should make the task stop cooperatively.
     * Cancellation waits for the worker's cleanup, even beyond the deadline; cancelling
     * a Future alone does not mean that its task has stopped using shared state.
     */
    public static <T> T runWithTimeout(Callable<T> callable, long timeout, TimeUnit timeUnit,
            final Runnable onCancellation) throws Exception {
        Objects.requireNonNull(callable);
        Objects.requireNonNull(timeUnit);
        Objects.requireNonNull(onCancellation);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<T> future = executor.submit(callable);
        executor.shutdown(); // This does not cancel the already-scheduled task.
        boolean interrupted = false;
        try {
            return future.get(timeout, timeUnit);
        }
        catch (TimeoutException | InterruptedException e) {
            interrupted = e instanceof InterruptedException;
            try {
                onCancellation.run();
            } catch (RuntimeException | Error cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            } finally {
                future.cancel(true);
            }
            throw e;
        }
        catch (ExecutionException e) {
            //unwrap the root cause
            Throwable t = e.getCause();
            if (t instanceof Error) {
                throw (Error) t;
            } else if (t instanceof Exception) {
                throw (Exception) t;
            } else {
                throw new IllegalStateException(t);
            }
        }
        finally {
            boolean terminated = false;
            while (!terminated) {
                try {
                    terminated = executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    // Finish joining before allowing callers to finalize shared state.
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
