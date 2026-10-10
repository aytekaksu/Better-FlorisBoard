/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Runs backup URI probes as the test APK's separate UID, never as the target app. */
public final class BackupShareProbeProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.patrickgold.florisboard.test.backup-share-probe";
    public static final String METHOD_PROBE = "probe";
    public static final String TYPE = "type";
    public static final String NAME = "name";
    public static final String SIZE = "size";
    public static final String READ_SIZE = "read_size";
    public static final String READ_SHA256 = "read_sha256";
    public static final String TYPED_READ_SIZE = "typed_read_size";
    public static final String TYPED_READ_SHA256 = "typed_read_sha256";
    public static final String WRITABLE = "writable";

    private static final String TARGET_PACKAGE = "dev.patrickgold.florisboard.debug";
    private static final String BACKUP_AUTHORITY = TARGET_PACKAGE + ".provider.backup-share";
    private static final long MAX_PROBE_BYTES = 1024L * 1024L;

    @Override
    public boolean onCreate() {
        return getContext() != null;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_PROBE.equals(method) || extras != null || arg == null) {
            throw new IllegalArgumentException("Unsupported backup probe request.");
        }
        Context context = providerContext();
        enforceTargetCaller(context);
        Uri uri = Uri.parse(arg);
        if (!"content".equals(uri.getScheme()) || !BACKUP_AUTHORITY.equals(uri.getAuthority())) {
            throw new IllegalArgumentException("Unknown backup authority.");
        }

        long identity = Binder.clearCallingIdentity();
        try {
            return probe(context.getContentResolver(), uri);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle probe(ContentResolver resolver, Uri uri) {
        Bundle result = new Bundle();
        try {
            result.putString(TYPE, resolver.getType(uri));
        } catch (Exception ignored) {
            // An inaccessible URI has no MIME metadata.
        }
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                result.putString(NAME, cursor.getString(
                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                ));
                result.putLong(SIZE, cursor.getLong(
                    cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
                ));
            }
        } catch (Exception ignored) {
            // An inaccessible URI has no display metadata.
        }
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input != null) {
                addDigest(result, READ_SIZE, READ_SHA256, input);
            }
        } catch (Exception ignored) {
            // An inaccessible URI has no readable bytes.
        }
        try (AssetFileDescriptor descriptor = resolver.openTypedAssetFileDescriptor(
            uri, "application/zip", null
        )) {
            if (descriptor != null) {
                try (InputStream input = descriptor.createInputStream()) {
                    addDigest(result, TYPED_READ_SIZE, TYPED_READ_SHA256, input);
                }
            }
        } catch (Exception ignored) {
            // Typed reads follow the same URI grant and expiry rules.
        }
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw")) {
            result.putBoolean(WRITABLE, descriptor != null);
        } catch (Exception ignored) {
            result.putBoolean(WRITABLE, false);
        }
        return result;
    }

    private static void addDigest(Bundle result, String sizeKey, String digestKey, InputStream input)
        throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[4096];
        long size = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            size += read;
            if (size > MAX_PROBE_BYTES) {
                throw new IOException("Probe input exceeds synthetic fixture limit.");
            }
            digest.update(buffer, 0, read);
        }
        result.putLong(sizeKey, size);
        result.putByteArray(digestKey, digest.digest());
    }

    private static void enforceTargetCaller(Context context) {
        try {
            int targetUid = context.getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0).uid;
            if (Binder.getCallingUid() != targetUid) {
                throw new SecurityException("Only the fixture target may call this probe.");
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new SecurityException("Fixture target is unavailable.");
        }
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Backup probe provider is unavailable.");
        }
        return context;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
