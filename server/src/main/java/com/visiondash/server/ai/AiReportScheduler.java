package com.visiondash.server.ai;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fires a report when a window has closed, on a thread of its own.
 *
 * The thread is the whole point of this class. Spring's default scheduler is a pool of
 * ONE, shared with ImageFetchWorker.pump() - so a report that sat waiting forty seconds
 * for a language model would be forty seconds in which no defect image was fetched for
 * anybody. The @Scheduled method here therefore does nothing but hand the work to a
 * dedicated single-thread executor and return, which takes microseconds. Whatever the
 * GPU does next, it does it somewhere the dashboard cannot feel.
 *
 * Single-threaded rather than pooled, and guarded by {@link #running}: two reports must
 * never be in flight at once. They would race for the same window row, and on a 10GB
 * card two generations at once is also how you find out what happens when the model no
 * longer fits in video memory.
 */
@Component
public class AiReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(AiReportScheduler.class);

    private final AiReportService reports;

    /**
     * Not merely defensive. A model still generating when the next tick arrives is the
     * normal state of affairs on a busy card, and the tick that finds this set simply
     * goes away again - the window it would have claimed is still there next time.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-report");
        // Daemon: a report half-written must never be the reason the server will not stop.
        t.setDaemon(true);
        return t;
    });

    public AiReportScheduler(AiReportService reports) {
        this.reports = reports;
    }

    /**
     * Every thirty seconds, cheaply, rather than on a cron matched to the interval.
     *
     * The interval is an operator setting. A cron expression would have been fixed at
     * startup and would have quietly kept firing on ten-minute boundaries after somebody
     * changed it to five. Asking "has a window closed that has no report" is one indexed
     * count, and it is correct whatever the interval was changed to, whenever the server
     * was last restarted, and after however long a gap.
     */
    @Scheduled(fixedDelay = 30, initialDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void tick() {
        if (!reports.enabled() || !reports.schemaReady() || running.get()) {
            return;
        }
        LocalDateTime due;
        try {
            due = reports.dueWindow();
        } catch (RuntimeException e) {
            log.warn("could not work out which AI report window is due: {}", e.getMessage());
            return;
        }
        if (due == null) {
            return;
        }
        submit(due, false);
    }

    /** Also used by the manual run on the tab, which is how a fresh Ollama install is proved. */
    public boolean submit(LocalDateTime windowStart, boolean replace) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        worker.submit(() -> {
            try {
                reports.run(windowStart, replace);
            } catch (RuntimeException e) {
                // Swallowed rather than rethrown: an executor task that throws kills
                // nothing here, but it also logs at a level nobody reads. The next tick
                // will offer the same window again.
                log.error("AI report for {} failed outright", windowStart, e);
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public boolean busy() {
        return running.get();
    }

    /**
     * Nightly, alongside the other retention sweeps. 03:40 rather than on the hour so it
     * does not land on the same minute as every other scheduled job on this PC.
     */
    @Scheduled(cron = "0 40 3 * * *")
    public void purge() {
        if (!reports.schemaReady()) {
            return;
        }
        // On the worker thread, not this one: a delete across a month of reports is
        // short, but it is still not something to do on the thread that fetches images.
        worker.submit(() -> {
            try {
                int removed = reports.purge();
                if (removed > 0) {
                    log.info("purged {} AI reports past their retention", removed);
                }
            } catch (RuntimeException e) {
                log.warn("AI report purge failed: {}", e.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
