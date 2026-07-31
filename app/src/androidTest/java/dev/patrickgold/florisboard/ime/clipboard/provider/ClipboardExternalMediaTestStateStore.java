/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.clipboard.provider;

import android.content.Context;
import android.os.Bundle;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Small file-backed state shared by the fixture's separate provider processes.
 */
final class ClipboardExternalMediaTestStateStore {
    private static final int RETAINED_GENERATION_COUNT = 4;
    private static final String KEY_BLOCKING_ENTERED = "blocking_entered";
    private static final String KEY_PREFIX_WRITTEN = "prefix_written";
    private static final String KEY_PREFIX_COMPLETED = "prefix_completed";
    private static final String KEY_DELAYED_ACTIVE = "delayed_active";
    private static final String KEY_CANCELLATION_OBSERVED = "cancellation_observed";
    private static final String KEY_OPEN_COUNT = "open_count";
    private static final String KEY_MIME_TYPE_QUERY_COUNT = "mime_type_query_count";
    private static final String KEY_DISPLAY_NAME_QUERY_COUNT = "display_name_query_count";
    private static final String KEY_BLOCKING_CALLER_PID = "blocking_caller_pid";
    private static final String KEY_BLOCKING_CALLER_UID = "blocking_caller_uid";
    private static final String KEY_HEALTHY_CALLER_PID = "healthy_caller_pid";
    private static final String KEY_HEALTHY_CALLER_UID = "healthy_caller_uid";

    private static final String BLOCKING_ENTERED = "blocking-entered";
    private static final String PREFIX_WRITTEN = "prefix-written";
    private static final String PREFIX_COMPLETED = "prefix-completed";
    private static final String DELAYED_COMPLETED = "delayed-completed";
    private static final String CANCELLATION_OBSERVED = "cancellation-observed";
    private static final String RELEASED = "released";
    private static final String OPEN_COUNT = "open-count";
    private static final String MIME_TYPE_QUERY_COUNT = "mime-type-query-count";
    private static final String DISPLAY_NAME_QUERY_COUNT = "display-name-query-count";
    private static final String BLOCKING_CALLER_PID = "blocking-caller-pid";
    private static final String BLOCKING_CALLER_UID = "blocking-caller-uid";
    private static final String HEALTHY_CALLER_PID = "healthy-caller-pid";
    private static final String HEALTHY_CALLER_UID = "healthy-caller-uid";
    private static final Object PROCESS_IO_LOCK = new Object();

    private final File root;
    private final File currentGenerationFile;

    ClipboardExternalMediaTestStateStore(Context context) {
        root = new File(context.getFilesDir(), "clipboard-external-media-test-state");
        currentGenerationFile = new File(root, "current-generation");
    }

    long currentGeneration() {
        synchronized (PROCESS_IO_LOCK) {
            ensureDirectory(root);
            try (
                RandomAccessFile current = new RandomAccessFile(currentGenerationFile, "rw");
                FileChannel channel = current.getChannel();
                FileLock ignored = channel.lock()
            ) {
                return readOrInitializeGeneration(current, channel);
            } catch (IOException error) {
                throw unavailable();
            }
        }
    }

    long reset() {
        synchronized (PROCESS_IO_LOCK) {
            ensureDirectory(root);
            try (
                RandomAccessFile current = new RandomAccessFile(currentGenerationFile, "rw");
                FileChannel channel = current.getChannel();
                FileLock ignored = channel.lock()
            ) {
                long previous = readOrInitializeGeneration(current, channel);
                long next = previous == Long.MAX_VALUE ? 1L : previous + 1L;
                ensureDirectory(generationDirectory(next));
                writeLong(current, channel, next);
                pruneOldGenerationDirectories(next);
                return next;
            } catch (IOException error) {
                throw unavailable();
            }
        }
    }

    void releaseCurrent() {
        synchronized (PROCESS_IO_LOCK) {
            ensureDirectory(root);
            try (
                RandomAccessFile current = new RandomAccessFile(currentGenerationFile, "rw");
                FileChannel channel = current.getChannel();
                FileLock ignored = channel.lock()
            ) {
                long generation = readOrInitializeGeneration(current, channel);
                createMarker(generation, RELEASED);
            } catch (IOException error) {
                throw unavailable();
            }
        }
    }

    Bundle currentStatus() {
        synchronized (PROCESS_IO_LOCK) {
            ensureDirectory(root);
            try (
                RandomAccessFile current = new RandomAccessFile(currentGenerationFile, "rw");
                FileChannel channel = current.getChannel();
                FileLock ignored = channel.lock()
            ) {
                long generation = readOrInitializeGeneration(current, channel);
                Bundle status = new Bundle();
                status.putBoolean(
                    KEY_BLOCKING_ENTERED,
                    markerFile(generation, BLOCKING_ENTERED).isFile()
                );
                status.putBoolean(
                    KEY_PREFIX_WRITTEN,
                    markerFile(generation, PREFIX_WRITTEN).isFile()
                );
                status.putBoolean(
                    KEY_PREFIX_COMPLETED,
                    markerFile(generation, PREFIX_COMPLETED).isFile()
                );
                status.putBoolean(
                    KEY_DELAYED_ACTIVE,
                    markerFile(generation, BLOCKING_ENTERED).isFile()
                        && !markerFile(generation, DELAYED_COMPLETED).isFile()
                );
                status.putBoolean(
                    KEY_CANCELLATION_OBSERVED,
                    markerFile(generation, CANCELLATION_OBSERVED).isFile()
                );
                status.putInt(KEY_OPEN_COUNT, readCounter(generation, OPEN_COUNT));
                status.putInt(
                    KEY_MIME_TYPE_QUERY_COUNT,
                    readCounter(generation, MIME_TYPE_QUERY_COUNT)
                );
                status.putInt(
                    KEY_DISPLAY_NAME_QUERY_COUNT,
                    readCounter(generation, DISPLAY_NAME_QUERY_COUNT)
                );
                status.putInt(
                    KEY_BLOCKING_CALLER_PID,
                    readObservation(generation, BLOCKING_CALLER_PID)
                );
                status.putInt(
                    KEY_BLOCKING_CALLER_UID,
                    readObservation(generation, BLOCKING_CALLER_UID)
                );
                status.putInt(
                    KEY_HEALTHY_CALLER_PID,
                    readObservation(generation, HEALTHY_CALLER_PID)
                );
                status.putInt(
                    KEY_HEALTHY_CALLER_UID,
                    readObservation(generation, HEALTHY_CALLER_UID)
                );
                return status;
            } catch (IOException error) {
                throw unavailable();
            }
        }
    }

    void markBlockingEntered(long generation) {
        mark(generation, BLOCKING_ENTERED);
    }

    void markPrefixWritten(long generation) {
        mark(generation, PREFIX_WRITTEN);
    }

    void markPrefixCompleted(long generation) {
        mark(generation, PREFIX_COMPLETED);
    }

    void markCancellationObserved(long generation) {
        mark(generation, CANCELLATION_OBSERVED);
    }

    void markDelayedCompleted(long generation) {
        mark(generation, DELAYED_COMPLETED);
    }

    boolean shouldRelease(long generation) {
        return markerFile(generation, RELEASED).isFile()
            || currentGeneration() != generation;
    }

    void incrementOpenCount(long generation) {
        incrementCounter(generation, OPEN_COUNT);
    }

    void incrementMimeTypeQueryCount(long generation) {
        incrementCounter(generation, MIME_TYPE_QUERY_COUNT);
    }

    void incrementDisplayNameQueryCount(long generation) {
        incrementCounter(generation, DISPLAY_NAME_QUERY_COUNT);
    }

    void recordBlockingCaller(long generation, int pid, int uid) {
        synchronized (PROCESS_IO_LOCK) {
            recordObservation(generation, BLOCKING_CALLER_PID, pid, true);
            recordObservation(generation, BLOCKING_CALLER_UID, uid, true);
        }
    }

    void recordHealthyCaller(long generation, int pid, int uid) {
        synchronized (PROCESS_IO_LOCK) {
            recordObservation(generation, HEALTHY_CALLER_PID, pid, false);
            recordObservation(generation, HEALTHY_CALLER_UID, uid, false);
        }
    }

    private void mark(long generation, String name) {
        synchronized (PROCESS_IO_LOCK) {
            try {
                createMarker(generation, name);
            } catch (IOException error) {
                throw unavailable();
            }
        }
    }

    private void createMarker(long generation, String name) throws IOException {
        File marker = markerFile(generation, name);
        ensureDirectory(marker.getParentFile());
        if (!marker.createNewFile() && !marker.isFile()) {
            throw new IOException();
        }
    }

    private void incrementCounter(long generation, String name) {
        synchronized (PROCESS_IO_LOCK) {
            File counter = counterFile(generation, name);
            ensureDirectory(counter.getParentFile());
            try (
                RandomAccessFile value = new RandomAccessFile(counter, "rw");
                FileChannel channel = value.getChannel();
                FileLock ignored = channel.lock()
            ) {
                long previous = readLong(value, 0L);
                if (previous >= Integer.MAX_VALUE) {
                    throw unavailable();
                }
                writeLong(value, channel, previous + 1L);
            } catch (IOException error) {
                throw unavailable();
            }
        }
    }

    private int readCounter(long generation, String name) throws IOException {
        File counter = counterFile(generation, name);
        ensureDirectory(counter.getParentFile());
        try (
            RandomAccessFile value = new RandomAccessFile(counter, "rw");
            FileChannel channel = value.getChannel();
            FileLock ignored = channel.lock()
        ) {
            long count = readLong(value, 0L);
            if (count < 0L || count > Integer.MAX_VALUE) {
                throw new IOException();
            }
            return (int) count;
        }
    }

    private void recordObservation(
        long generation,
        String name,
        int observation,
        boolean keepFirst
    ) {
        if (observation <= 0) {
            throw unavailable();
        }
        synchronized (PROCESS_IO_LOCK) {
            File valueFile = counterFile(generation, name);
            ensureDirectory(valueFile.getParentFile());
            try (
                RandomAccessFile value = new RandomAccessFile(valueFile, "rw");
                FileChannel channel = value.getChannel();
                FileLock ignored = channel.lock()
            ) {
                if (keepFirst && readLong(value, 0L) > 0L) {
                    return;
                }
                writeLong(value, channel, observation);
            } catch (IOException error) {
                throw unavailable();
            }
        }
    }

    private int readObservation(long generation, String name) throws IOException {
        File observation = counterFile(generation, name);
        ensureDirectory(observation.getParentFile());
        try (
            RandomAccessFile value = new RandomAccessFile(observation, "rw");
            FileChannel channel = value.getChannel();
            FileLock ignored = channel.lock()
        ) {
            long recorded = readLong(value, -1L);
            if (recorded < -1L || recorded > Integer.MAX_VALUE) {
                throw new IOException();
            }
            return (int) recorded;
        }
    }

    private long readOrInitializeGeneration(
        RandomAccessFile current,
        FileChannel channel
    ) throws IOException {
        long generation = readLong(current, 0L);
        if (generation <= 0L) {
            generation = 1L;
            ensureDirectory(generationDirectory(generation));
            writeLong(current, channel, generation);
        } else {
            ensureDirectory(generationDirectory(generation));
        }
        return generation;
    }

    private static long readLong(RandomAccessFile file, long fallback) throws IOException {
        if (file.length() != Long.BYTES) {
            return fallback;
        }
        file.seek(0L);
        return file.readLong();
    }

    private static void writeLong(
        RandomAccessFile file,
        FileChannel channel,
        long value
    ) throws IOException {
        file.seek(0L);
        file.writeLong(value);
        file.setLength(Long.BYTES);
        channel.force(true);
    }

    private File markerFile(long generation, String name) {
        return new File(generationDirectory(generation), name + ".marker");
    }

    private File counterFile(long generation, String name) {
        return new File(generationDirectory(generation), name + ".counter");
    }

    private File generationDirectory(long generation) {
        return new File(root, "generation-" + generation);
    }

    private void pruneOldGenerationDirectories(long currentGeneration) {
        File[] entries;
        try {
            entries = root.listFiles();
        } catch (SecurityException ignored) {
            return;
        }
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            long generation = parseGenerationDirectory(entry.getName());
            if (
                generation <= 0L
                    || generationAge(generation, currentGeneration) < RETAINED_GENERATION_COUNT
            ) {
                continue;
            }
            deleteTreeBestEffort(entry.toPath());
        }
    }

    private static long parseGenerationDirectory(String name) {
        String prefix = "generation-";
        if (!name.startsWith(prefix)) {
            return -1L;
        }
        try {
            long generation = Long.parseLong(name.substring(prefix.length()));
            return generation > 0L && name.equals(prefix + generation) ? generation : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static long generationAge(long generation, long currentGeneration) {
        return currentGeneration >= generation
            ? currentGeneration - generation
            : Long.MAX_VALUE - generation + currentGeneration;
    }

    private static void deleteTreeBestEffort(Path root) {
        try {
            Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes
                    ) {
                        deleteBestEffort(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(
                        Path directory,
                        IOException error
                    ) {
                        deleteBestEffort(directory);
                        return FileVisitResult.CONTINUE;
                    }
                }
            );
        } catch (IOException | SecurityException ignored) {
            // Fixture cleanup must not mask the behavior under test.
        }
    }

    private static void deleteBestEffort(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException ignored) {
            // A later reset can retry files still in use by another fixture process.
        }
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Clipboard test state is unavailable.");
    }
}
