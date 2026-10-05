package com.visiondash.server.images;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Copies one image off an inspection PC's SMB share.
 *
 * The shares are open to Everyone and named after the drive letter, so a plain file copy
 * over the UNC path is all this needs - no SMB library, no credentials.
 *
 * Two things here exist because their absence caused real outages. A copy to an
 * unreachable share can block for well over a minute inside Windows' own SMB retries, so
 * a short TCP probe runs first and fails in seconds instead. And the copy itself is given
 * a hard deadline, because a share that goes unresponsive *after* the probe passes has no
 * timeout of its own and will otherwise hold the calling thread indefinitely.
 */
@Component
public class SmbImageSource {
    private static final Logger log = LoggerFactory.getLogger(SmbImageSource.class);
    private static final int SMB_PORT = 445;

    /** What went wrong, in the only two categories the retry policy cares about. */
    public sealed interface Result {
        record Copied(long bytes) implements Result {
        }

        /** Worth trying again: the PC is rebooting, the network blipped, or the vision
         *  software has written the CSV row but not yet flushed the image. */
        record Transient(String reason) implements Result {
        }

        /** No retry can fix this: the path itself is unusable. */
        record Permanent(String reason) implements Result {
        }
    }

    public Result copy(String sourcePath, String hostIp, Path destination, int probeTimeoutMs, int copyTimeoutMs) {
        String unc;
        try {
            unc = toUncPath(sourcePath, hostIp);
        } catch (IllegalArgumentException e) {
            return new Result.Permanent(e.getMessage());
        }

        if (!isReachable(hostIp, probeTimeoutMs)) {
            return new Result.Transient("port " + SMB_PORT + " unreachable on " + hostIp);
        }

        Thread worker = null;
        try {
            Files.createDirectories(destination.getParent());
            CopyTask task = new CopyTask(Paths.get(unc), destination);
            worker = new Thread(task, "image-copy");
            worker.setDaemon(true);
            worker.start();
            worker.join(copyTimeoutMs);

            if (worker.isAlive()) {
                // Java cannot abort a thread stuck in a blocking file read, so this bounds
                // *our* wait rather than the copy. The abandoned thread finishes or dies
                // with the process; what matters is that the worker pool is not held.
                return new Result.Transient("copy exceeded " + copyTimeoutMs + "ms");
            }
            if (task.failure instanceof NoSuchFileException) {
                // Not permanent: the inspection PC often writes the CSV row a moment before
                // the image lands. Treating this as fatal is what used to lose images that
                // would have arrived seconds later.
                return new Result.Transient("not on the share yet");
            }
            if (task.failure != null) {
                return new Result.Transient(task.failure.getClass().getSimpleName() + ": " + task.failure.getMessage());
            }
            return new Result.Copied(task.bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Transient("interrupted");
        } catch (IOException e) {
            return new Result.Transient("cannot prepare " + destination + ": " + e.getMessage());
        } finally {
            if (worker != null && worker.isAlive()) {
                log.warn("abandoned a stuck copy of {}", unc);
            }
        }
    }

    /** {@code F:\Files\Image\x.jpg} on 10.0.0.1 becomes {@code \\10.0.0.1\F\Files\Image\x.jpg}. */
    public static String toUncPath(String localPath, String hostIp) {
        if (localPath == null || localPath.isBlank()) {
            throw new IllegalArgumentException("empty image path");
        }
        int colon = localPath.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("image path has no drive letter: " + localPath);
        }
        String drive = localPath.substring(0, colon);
        String rest = localPath.substring(colon + 1);
        return "\\\\" + hostIp + "\\" + drive + rest;
    }

    private boolean isReachable(String hostIp, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostIp, SMB_PORT), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static final class CopyTask implements Runnable {
        private final Path source;
        private final Path destination;
        volatile Exception failure;
        volatile long bytes;

        CopyTask(Path source, Path destination) {
            this.source = source;
            this.destination = destination;
        }

        @Override
        public void run() {
            try {
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                bytes = Files.size(destination);
            } catch (Exception e) {
                failure = e;
            }
        }
    }
}
