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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Framework-only provider loaded before the instrumentation Kotlin classloader.
 */
public final class ClipboardExternalMediaTestProvider extends ContentProvider {
    private static final long DELAYED_OPEN_TIMEOUT_MS = 5_000L;
    private static final long POLL_INTERVAL_MS = 10L;
    private static final String GENERATION_PARAMETER = "generation";
    private static final byte[] HEALTHY_BYTES = new byte[] {1, 3, 3, 7};
    private static final byte[] BLOCKING_PREFIX_BYTES = new byte[] {1, 3};
    private static final byte[] SVG_BYTES = (
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"2\" height=\"2\">"
            + "<rect width=\"2\" height=\"2\"/></svg>"
    ).getBytes(StandardCharsets.UTF_8);
    private static final byte[] ORIENTED_JPEG_BYTES = Base64.decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAcAAAAA"
            + "AAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCA"
            + "sICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoK"
            + "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAUACgDAREAAhEBA"
            + "xEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQ"
            + "AAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJico"
            + "KSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKT"
            + "lJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo"
            + "6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAA"
            + "gECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRCh"
            + "YkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eH"
            + "l6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1d"
            + "bX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5rr+az/bgKAPZv2Rv+Z"
            + "h/7dP/AGtXwvGv/Lj/ALe/9tP8e/2sH/NHf91H/wB0T2avhT/HsKAPy4/4XT8TP+hl/"
            + "wDJOH/4iv8Aot/4lD+jv/0Jf/LnF/8Ay8/2I/4mk8dv+hv/AOW+F/8AlAf8Lp+Jn/Qy/"
            + "wDknD/8RR/xKH9Hf/oS/wDlzi//AJeH/E0njt/0N/8Ay3wv/wAoP0G/4IR+H9I+Pn/C"
            + "1P8AhbVp/a39k/2H/Z/7xoPK83+0PM/1JTdny065xjjGTX+WP7S/w44M8FP9VP8AUzC"
            + "/VPrf172vv1KvP7L6n7P+NOpy8vtJ/Da9/evZW8DPMZiPpFez/wCIhS+vfUb+w0VHk9"
            + "tb2v8Au/sebm9jT+Pm5eX3bXlf9B/+GVvgL/0In/lUuv8A47X+WP8ArfxF/wA//wDyWH"
            + "/yJ4H/ABLl4M/9Cv8A8r4j/wCXB/wyt8Bf+hE/8ql1/wDHaP8AW/iL/n//AOSw/wDkQ"
            + "/4ly8Gf+hX/AOV8R/8ALj+cmv8AsvPz0KAP08/4Nwv+ay/9y7/7k6/xV/bBf80T/wB1"
            + "L/3QP1jwv/5i/wDuH/7efp5X+Kp+sBQB/9k=",
        Base64.DEFAULT
    );
    private ClipboardExternalMediaTestStateStore stateStore;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        stateStore = new ClipboardExternalMediaTestStateStore(context);
        stateStore.currentGeneration();
        return true;
    }

    @Override
    public String getType(Uri uri) {
        ClipboardExternalMediaTestStateStore store = stateStore();
        long generation = requireGeneration(uri);
        store.incrementMimeTypeQueryCount(generation);
        String path = uri.getPath();
        if ("/blocking-mime-type".equals(path)) {
            // getType can use one-way IPC, where Binder reports no caller PID.
            // Synchronous query/open fixtures verify process isolation instead.
            store.markBlockingEntered(generation);
            awaitReleaseIgnoringInterrupts(store, generation);
            return "application/octet-stream";
        }
        if (
            "/svg".equals(path)
                || "/blocking".equals(path)
                || "/delayed".equals(path)
        ) {
            return "image/svg+xml";
        }
        if ("/oriented-jpeg".equals(path)) {
            return "image/jpeg";
        }
        if (
            "/healthy".equals(path)
                || "/cancellation-aware".equals(path)
                || "/prefix-then-block".equals(path)
                || "/blocking-display-name".equals(path)
                || "/cancellation-aware-display-name".equals(path)
        ) {
            return "application/octet-stream";
        }
        return null;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode)
        throws FileNotFoundException {
        return openSource(uri, mode, null);
    }

    @Override
    public AssetFileDescriptor openAssetFile(
        Uri uri,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        return openSource(uri, mode, signal);
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(
        Uri uri,
        String mimeTypeFilter,
        Bundle options
    ) throws FileNotFoundException {
        requireTypedOpen(mimeTypeFilter, options);
        return openSource(uri, "r", null);
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(
        Uri uri,
        String mimeTypeFilter,
        Bundle options,
        CancellationSignal signal
    ) throws FileNotFoundException {
        requireTypedOpen(mimeTypeFilter, options);
        return openSource(uri, "r", signal);
    }

    private static void requireTypedOpen(String mimeTypeFilter, Bundle options) {
        if (!"*/*".equals(mimeTypeFilter) || (options != null && !options.isEmpty())) {
            throw new IllegalArgumentException("Unexpected typed-open request.");
        }
    }

    private AssetFileDescriptor openSource(
        Uri uri,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Read-only test source.");
        }
        if ("/prefix-then-block".equals(uri.getPath())) {
            return openPrefixThenBlock(uri);
        }
        byte[] bytes = bytesForOpen(uri, signal);
        File source;
        try {
            source = File.createTempFile(
                "clipboard-import-source-",
                ".bin",
                getContext().getCacheDir()
            );
            try (FileOutputStream output = new FileOutputStream(source)) {
                output.write(bytes);
            }
        } catch (IOException error) {
            throw new FileNotFoundException("Test source unavailable.");
        }
        ParcelFileDescriptor descriptor =
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        source.delete();
        return new AssetFileDescriptor(descriptor, 0L, bytes.length);
    }

    private byte[] bytesForOpen(Uri uri, CancellationSignal signal) {
        ClipboardExternalMediaTestStateStore store = stateStore();
        long generation = requireGeneration(uri);
        store.incrementOpenCount(generation);
        String path = uri.getPath();
        if ("/healthy".equals(path)) {
            store.recordHealthyCaller(
                generation,
                Binder.getCallingPid(),
                Binder.getCallingUid()
            );
            return HEALTHY_BYTES;
        }
        if ("/svg".equals(path)) {
            return SVG_BYTES;
        }
        if ("/oriented-jpeg".equals(path)) {
            return ORIENTED_JPEG_BYTES;
        }
        if (
            "/blocking-mime-type".equals(path)
                || "/blocking-display-name".equals(path)
                || "/cancellation-aware-display-name".equals(path)
        ) {
            return HEALTHY_BYTES;
        }
        if ("/blocking".equals(path)) {
            store.recordBlockingCaller(
                generation,
                Binder.getCallingPid(),
                Binder.getCallingUid()
            );
            store.markBlockingEntered(generation);
            awaitReleaseIgnoringInterrupts(store, generation);
            return SVG_BYTES;
        }
        if ("/delayed".equals(path)) {
            store.recordBlockingCaller(
                generation,
                Binder.getCallingPid(),
                Binder.getCallingUid()
            );
            store.markBlockingEntered(generation);
            try {
                awaitReleaseOrDelay(
                    store,
                    generation,
                    DELAYED_OPEN_TIMEOUT_MS
                );
            } finally {
                store.markDelayedCompleted(generation);
            }
            return SVG_BYTES;
        }
        if ("/cancellation-aware".equals(path)) {
            store.recordBlockingCaller(
                generation,
                Binder.getCallingPid(),
                Binder.getCallingUid()
            );
            store.markBlockingEntered(generation);
            while (signal == null || !signal.isCanceled()) {
                if (store.shouldRelease(generation)) {
                    return HEALTHY_BYTES;
                }
                pauseIgnoringInterrupts();
            }
            store.markCancellationObserved(generation);
            throw new OperationCanceledException();
        }
        throw new IllegalArgumentException("Unknown test source.");
    }

    private AssetFileDescriptor openPrefixThenBlock(Uri uri) throws FileNotFoundException {
        ClipboardExternalMediaTestStateStore store = stateStore();
        long generation = requireGeneration(uri);
        store.incrementOpenCount(generation);
        store.recordBlockingCaller(
            generation,
            Binder.getCallingPid(),
            Binder.getCallingUid()
        );
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException error) {
            throw new FileNotFoundException("Test source unavailable.");
        }
        Thread writer = new Thread(
            () -> {
                try (
                    ParcelFileDescriptor.AutoCloseOutputStream output =
                        new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                ) {
                    output.write(BLOCKING_PREFIX_BYTES);
                    output.flush();
                    store.markPrefixWritten(generation);
                    store.markBlockingEntered(generation);
                    awaitReleaseIgnoringInterrupts(store, generation);
                } catch (IOException ignored) {
                    // A killed import process closes the read end of the hostile pipe.
                } finally {
                    store.markPrefixCompleted(generation);
                }
            },
            "clipboard-test-hostile-stream"
        );
        writer.setDaemon(true);
        try {
            writer.start();
        } catch (RuntimeException error) {
            closeQuietly(pipe[0]);
            closeQuietly(pipe[1]);
            throw new FileNotFoundException("Test source unavailable.");
        }
        return new AssetFileDescriptor(
            pipe[0],
            0L,
            AssetFileDescriptor.UNKNOWN_LENGTH
        );
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // The fixture is already unavailable.
        }
    }

    private static void awaitReleaseIgnoringInterrupts(
        ClipboardExternalMediaTestStateStore store,
        long generation
    ) {
        while (!store.shouldRelease(generation)) {
            pauseIgnoringInterrupts();
        }
    }

    private static void pauseIgnoringInterrupts() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException ignored) {
            // Hostile endpoints intentionally ignore thread interruption.
        }
    }

    private static void awaitReleaseOrDelay(
        ClipboardExternalMediaTestStateStore store,
        long generation,
        long delayMs
    ) {
        long remainingNanos = delayMs * 1_000_000L;
        long lastNanos = System.nanoTime();
        while (remainingNanos > 0L && !store.shouldRelease(generation)) {
            pauseIgnoringInterrupts();
            long nowNanos = System.nanoTime();
            remainingNanos -= Math.max(0L, nowNanos - lastNanos);
            lastNanos = nowNanos;
        }
    }

    private ClipboardExternalMediaTestStateStore stateStore() {
        ClipboardExternalMediaTestStateStore current = stateStore;
        if (current == null) {
            throw new IllegalStateException("Clipboard test provider is unavailable.");
        }
        return current;
    }

    private static long requireGeneration(Uri uri) {
        String generationValue = uri.getQueryParameter(GENERATION_PARAMETER);
        if (generationValue == null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid test source URI.");
        }
        try {
            long generation = Long.parseLong(generationValue);
            String expectedQuery = GENERATION_PARAMETER + "=" + generation;
            if (
                generation <= 0L
                    || !Long.toString(generation).equals(generationValue)
                    || !expectedQuery.equals(uri.getEncodedQuery())
            ) {
                throw new IllegalArgumentException("Invalid test source URI.");
            }
            return generation;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid test source URI.");
        }
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder,
        CancellationSignal signal
    ) {
        return queryDisplayName(uri, projection, signal);
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        return queryDisplayName(uri, projection, null);
    }

    private Cursor queryDisplayName(
        Uri uri,
        String[] projection,
        CancellationSignal signal
    ) {
        ClipboardExternalMediaTestStateStore store = stateStore();
        long generation = requireGeneration(uri);
        store.incrementDisplayNameQueryCount(generation);
        String path = uri.getPath();
        if ("/blocking-display-name".equals(path)) {
            store.recordBlockingCaller(
                generation,
                Binder.getCallingPid(),
                Binder.getCallingUid()
            );
            store.markBlockingEntered(generation);
            awaitReleaseIgnoringInterrupts(store, generation);
        } else if ("/cancellation-aware-display-name".equals(path)) {
            store.recordBlockingCaller(
                generation,
                Binder.getCallingPid(),
                Binder.getCallingUid()
            );
            store.markBlockingEntered(generation);
            awaitCancellationOrRelease(store, generation, signal);
        } else if (signal != null) {
            signal.throwIfCanceled();
        }
        if (!Arrays.equals(projection, new String[] {OpenableColumns.DISPLAY_NAME})) {
            throw new IllegalArgumentException("Unexpected projection.");
        }
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME});
        String displayName = "/oriented-jpeg".equals(uri.getPath())
            ? "oriented.jpg"
            : " \u0000vector.svg ";
        cursor.addRow(new Object[] {displayName});
        return cursor;
    }

    private static void awaitCancellationOrRelease(
        ClipboardExternalMediaTestStateStore store,
        long generation,
        CancellationSignal signal
    ) {
        while (signal == null || !signal.isCanceled()) {
            if (store.shouldRelease(generation)) {
                return;
            }
            pauseIgnoringInterrupts();
        }
        store.markCancellationObserved(generation);
        throw new OperationCanceledException();
    }

    public static byte[] orientedJpegBytes() {
        return ORIENTED_JPEG_BYTES.clone();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
        Uri uri,
        ContentValues values,
        String selection,
        String[] selectionArgs
    ) {
        return 0;
    }
}
