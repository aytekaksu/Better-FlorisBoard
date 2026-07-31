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
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Test-only control channel for the private external-media fixture.
 */
public class ClipboardExternalMediaTestControlProvider extends ContentProvider {
    private static final String TARGET_PACKAGE = "dev.patrickgold.florisboard.debug";
    private static final String METHOD_RESET = "reset";
    private static final String METHOD_RELEASE = "release";
    private static final String METHOD_STATUS = "status";
    private static final String METHOD_GRANT = "grant";
    private static final String METHOD_REVOKE = "revoke";
    private static final String METHOD_PROBE_TARGET_MEDIA = "probe_target_media";
    private static final String METHOD_PROBE_TARGET_IMPORT_WORKER =
        "probe_target_import_worker";
    private static final String METHOD_PROBE_COLD_TARGET_TYPE = "probe_cold_target_type";
    private static final String GENERATION_PARAMETER = "generation";
    private static final String KEY_FIXTURE_GENERATION = "fixture_generation";
    private static final String KEY_QUERY_VISIBLE = "query_visible";
    private static final String KEY_TYPE_VISIBLE = "type_visible";
    private static final String KEY_STREAM_TYPES_VISIBLE = "stream_types_visible";
    private static final String KEY_OPEN_VISIBLE = "open_visible";
    private static final String KEY_TYPED_OPEN_VISIBLE = "typed_open_visible";
    private static final String KEY_TYPED_OPEN_READABLE = "typed_open_readable";
    private static final String KEY_TYPED_MISMATCH_REJECTED = "typed_mismatch_rejected";
    private static final String KEY_TYPED_OPTIONS_REJECTED = "typed_options_rejected";
    private static final String KEY_TYPED_CANCELLATION_OBSERVED =
        "typed_cancellation_observed";
    private static final String KEY_COLD_TYPE_VALUE = "cold_type_value";
    private static final String KEY_IMPORT_WORKER_ACQUIRED = "import_worker_acquired";
    private static final byte[] TARGET_MEDIA_BYTES = new byte[] {1, 3, 3, 7};
    private static final String TARGET_IMPORT_WORKER_AUTHORITY =
        TARGET_PACKAGE + ".provider.clipboard-import-worker";
    private static final String[] FIXTURE_URIS = new String[] {
        "content://dev.patrickgold.florisboard.test.clipboard-source/healthy",
        "content://dev.patrickgold.florisboard.test.clipboard-source/empty",
        "content://dev.patrickgold.florisboard.test.clipboard-source/svg",
        "content://dev.patrickgold.florisboard.test.clipboard-source/oriented-jpeg",
        "content://dev.patrickgold.florisboard.test.clipboard-source/blocking",
        "content://dev.patrickgold.florisboard.test.clipboard-source/delayed",
        "content://dev.patrickgold.florisboard.test.clipboard-source/cancellation-aware",
        "content://dev.patrickgold.florisboard.test.clipboard-source/prefix-then-block",
        "content://dev.patrickgold.florisboard.test.clipboard-source/blocking-mime-type",
        "content://dev.patrickgold.florisboard.test.clipboard-source/blocking-display-name",
        "content://dev.patrickgold.florisboard.test.clipboard-source/"
            + "cancellation-aware-display-name",
    };
    private ClipboardExternalMediaTestStateStore stateStore;
    private boolean coldTypeProbeUsed;

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
    public Bundle call(String method, String arg, Bundle extras) {
        enforceTargetCaller();
        if (extras != null && !extras.isEmpty()) {
            throw new IllegalArgumentException("Control extras are not supported.");
        }
        if (coldTypeProbeOnly()) {
            if (!METHOD_PROBE_COLD_TARGET_TYPE.equals(method)) {
                throw new IllegalArgumentException("Cold type probe supports one method only.");
            }
            synchronized (this) {
                if (coldTypeProbeUsed) {
                    throw new IllegalStateException("Cold type probe was already used.");
                }
                coldTypeProbeUsed = true;
            }
            return probeColdTargetType(requireTargetMediaUri(arg));
        }
        if (METHOD_RESET.equals(method)) {
            requireNoArgument(arg);
            long previousGeneration = stateStore().currentGeneration();
            updateEveryGrant(previousGeneration, false);
            revokeLegacyGrants();
            long generation = stateStore().reset();
            Bundle result = new Bundle();
            result.putLong(KEY_FIXTURE_GENERATION, generation);
            return result;
        }
        if (METHOD_RELEASE.equals(method)) {
            requireNoArgument(arg);
            stateStore().releaseCurrent();
            return Bundle.EMPTY;
        }
        if (METHOD_STATUS.equals(method)) {
            requireNoArgument(arg);
            return stateStore().currentStatus();
        }
        if (METHOD_GRANT.equals(method)) {
            updateGrant(requireFixtureUri(arg), true);
            return Bundle.EMPTY;
        }
        if (METHOD_REVOKE.equals(method)) {
            updateGrant(requireFixtureUri(arg), false);
            return Bundle.EMPTY;
        }
        if (METHOD_PROBE_TARGET_MEDIA.equals(method)) {
            return probeTargetMedia(requireTargetMediaUri(arg));
        }
        if (METHOD_PROBE_TARGET_IMPORT_WORKER.equals(method)) {
            requireNoArgument(arg);
            return probeTargetImportWorker();
        }
        throw new IllegalArgumentException("Unknown control method.");
    }

    protected boolean coldTypeProbeOnly() {
        return false;
    }

    private Bundle probeColdTargetType(Uri uri) {
        Context context = providerContext();
        Bundle result = new Bundle();
        long identity = Binder.clearCallingIdentity();
        try {
            result.putString(KEY_COLD_TYPE_VALUE, context.getContentResolver().getType(uri));
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle probeTargetMedia(Uri uri) {
        Context context = providerContext();
        Bundle result = new Bundle();
        long identity = Binder.clearCallingIdentity();
        try {
            try (Cursor cursor = context.getContentResolver().query(
                uri,
                null,
                null,
                null,
                null
            )) {
                result.putBoolean(KEY_QUERY_VISIBLE, cursor != null);
            } catch (Exception ignored) {
                result.putBoolean(KEY_QUERY_VISIBLE, false);
            }
            try {
                result.putBoolean(
                    KEY_TYPE_VISIBLE,
                    context.getContentResolver().getType(uri) != null
                );
            } catch (Exception ignored) {
                result.putBoolean(KEY_TYPE_VISIBLE, false);
            }
            try {
                String[] streamTypes = context.getContentResolver().getStreamTypes(uri, "*/*");
                result.putBoolean(
                    KEY_STREAM_TYPES_VISIBLE,
                    streamTypes != null && streamTypes.length > 0
                );
            } catch (Exception ignored) {
                result.putBoolean(KEY_STREAM_TYPES_VISIBLE, false);
            }
            try (
                AssetFileDescriptor descriptor =
                    context.getContentResolver().openAssetFileDescriptor(uri, "r")
            ) {
                result.putBoolean(KEY_OPEN_VISIBLE, descriptor != null);
            } catch (Exception ignored) {
                result.putBoolean(KEY_OPEN_VISIBLE, false);
            }
            boolean typedOpenVisible = false;
            boolean typedOpenReadable = false;
            try (
                AssetFileDescriptor descriptor =
                    context.getContentResolver().openTypedAssetFileDescriptor(
                        uri,
                        "image/*",
                        null
                    )
            ) {
                typedOpenVisible = descriptor != null;
                if (descriptor != null) {
                    typedOpenReadable = hasExpectedTargetBytes(descriptor);
                }
            } catch (Exception ignored) {
                // The visibility booleans remain false.
            }
            result.putBoolean(KEY_TYPED_OPEN_VISIBLE, typedOpenVisible);
            result.putBoolean(KEY_TYPED_OPEN_READABLE, typedOpenReadable);
            if (typedOpenVisible) {
                result.putBoolean(
                    KEY_TYPED_MISMATCH_REJECTED,
                    typedOpenIsRejected(context, uri, "video/*", null)
                );
                Bundle unsupportedOptions = new Bundle();
                unsupportedOptions.putBoolean("unsupported", true);
                result.putBoolean(
                    KEY_TYPED_OPTIONS_REJECTED,
                    typedOpenIsRejected(context, uri, "image/*", unsupportedOptions)
                );
                result.putBoolean(
                    KEY_TYPED_CANCELLATION_OBSERVED,
                    typedOpenObservesCancellation(context, uri)
                );
            }
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle probeTargetImportWorker() {
        Context context = providerContext();
        Bundle result = new Bundle();
        long identity = Binder.clearCallingIdentity();
        ContentProviderClient client = null;
        try {
            try {
                client = context.getContentResolver()
                    .acquireUnstableContentProviderClient(TARGET_IMPORT_WORKER_AUTHORITY);
                result.putBoolean(KEY_IMPORT_WORKER_ACQUIRED, client != null);
            } catch (Exception ignored) {
                result.putBoolean(KEY_IMPORT_WORKER_ACQUIRED, false);
            }
            return result;
        } finally {
            try {
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception ignored) {
                        // Acquisition itself is the security result under test.
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static boolean hasExpectedTargetBytes(AssetFileDescriptor descriptor)
        throws IOException {
        try (InputStream input = descriptor.createInputStream()) {
            for (byte expected : TARGET_MEDIA_BYTES) {
                if (input.read() != (expected & 0xff)) {
                    return false;
                }
            }
            return input.read() == -1;
        }
    }

    private static boolean typedOpenIsRejected(
        Context context,
        Uri uri,
        String mimeTypeFilter,
        Bundle options
    ) {
        try (
            AssetFileDescriptor descriptor =
                context.getContentResolver().openTypedAssetFileDescriptor(
                    uri,
                    mimeTypeFilter,
                    options
                )
        ) {
            return descriptor == null;
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean typedOpenObservesCancellation(Context context, Uri uri) {
        CancellationSignal signal = new CancellationSignal();
        signal.cancel();
        try (
            AssetFileDescriptor descriptor =
                context.getContentResolver().openTypedAssetFileDescriptor(
                    uri,
                    "image/*",
                    null,
                    signal
                )
        ) {
            return false;
        } catch (OperationCanceledException ignored) {
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void enforceTargetCaller() {
        Context context = providerContext();
        try {
            int targetUid = context.getPackageManager()
                .getApplicationInfo(TARGET_PACKAGE, 0)
                .uid;
            if (Binder.getCallingUid() != targetUid) {
                throw new SecurityException("Only the fixture target may use the control channel.");
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new SecurityException("Fixture target is unavailable.");
        }
    }

    private void updateEveryGrant(long generation, boolean grant) {
        for (String rawUri : FIXTURE_URIS) {
            updateGrant(generationUri(rawUri, generation), grant);
        }
    }

    private void revokeLegacyGrants() {
        for (String rawUri : FIXTURE_URIS) {
            updateGrant(Uri.parse(rawUri), false);
        }
    }

    private void updateGrant(Uri uri, boolean grant) {
        Context context = providerContext();
        long identity = Binder.clearCallingIdentity();
        try {
            if (grant) {
                context.grantUriPermission(
                    TARGET_PACKAGE,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } else {
                context.revokeUriPermission(
                    TARGET_PACKAGE,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Uri requireFixtureUri(String rawUri) {
        long generation = stateStore().currentGeneration();
        for (String fixtureUri : FIXTURE_URIS) {
            Uri expected = generationUri(fixtureUri, generation);
            if (expected.toString().equals(rawUri)) {
                return expected;
            }
        }
        throw new IllegalArgumentException("Unknown fixture URI.");
    }

    private static Uri generationUri(String rawUri, long generation) {
        return Uri.parse(rawUri)
            .buildUpon()
            .appendQueryParameter(GENERATION_PARAMETER, Long.toString(generation))
            .build();
    }

    private static Uri requireTargetMediaUri(String rawUri) {
        String authority = TARGET_PACKAGE + ".provider.clipboard";
        String imagePrefix = "content://" + authority + "/clips/images/";
        String videoPrefix = "content://" + authority + "/clips/videos/";
        String id = null;
        if (rawUri != null && rawUri.startsWith(imagePrefix)) {
            id = rawUri.substring(imagePrefix.length());
        } else if (rawUri != null && rawUri.startsWith(videoPrefix)) {
            id = rawUri.substring(videoPrefix.length());
        }
        if (
            id == null
                || id.isEmpty()
                || id.length() > 19
                || (id.length() > 1 && id.charAt(0) == '0')
        ) {
            throw new IllegalArgumentException("Unknown target media URI.");
        }
        for (int index = 0; index < id.length(); index++) {
            if (id.charAt(index) < '0' || id.charAt(index) > '9') {
                throw new IllegalArgumentException("Unknown target media URI.");
            }
        }
        try {
            if (Long.parseLong(id) <= 0L) {
                throw new IllegalArgumentException("Unknown target media URI.");
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Unknown target media URI.");
        }
        return Uri.parse(rawUri);
    }

    private static void requireNoArgument(String arg) {
        if (arg != null) {
            throw new IllegalArgumentException("Control argument is not supported.");
        }
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Control provider is unavailable.");
        }
        return context;
    }

    private ClipboardExternalMediaTestStateStore stateStore() {
        ClipboardExternalMediaTestStateStore current = stateStore;
        if (current == null) {
            throw new IllegalStateException("Control provider is unavailable.");
        }
        return current;
    }

    @Override
    public String getType(Uri uri) {
        throw unsupported();
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        throw unsupported();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw unsupported();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw unsupported();
    }

    @Override
    public int update(
        Uri uri,
        ContentValues values,
        String selection,
        String[] selectionArgs
    ) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Control provider supports call only.");
    }
}
