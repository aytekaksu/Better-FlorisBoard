/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.lib.ext

import android.content.Context
import android.net.Uri
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.lib.io.BoundedExtensionArchive
import dev.patrickgold.florisboard.lib.io.FlorisRef
import dev.patrickgold.florisboard.lib.io.ZipUtils
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.coroutines.CancellationException
import org.florisboard.lib.android.conservativeUsableSpace
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.resultErr
import org.florisboard.lib.kotlin.resultOk
import java.util.UUID

/**
 * An extension container holding a parsed config, a working directory file
 * object as well as a reference to the original flex file.
 *
 * @property meta The parsed config of this extension.
 * @property workingDir The working directory, used as a cache and as a staging
 *  area for modifications to extension files.
 * @property sourceRef Optional, defines where the original flex file is stored.
 */
@Polymorphic
@Serializable
abstract class Extension {
    @Transient var workingDir: FsDir? = null
    @Transient var sourceRef: FlorisRef? = null
    @Transient internal var sourceArchiveFingerprint: InstalledExtensionArchiveFingerprint? = null
    @Transient private var ownedRuntimeDir: FsDir? = null
    @Transient private val lifecycleGuard = Any()

    abstract val meta: ExtensionMeta
    abstract val dependencies: List<String>?

    abstract fun serialType(): String

    abstract fun components(): List<ExtensionComponent>

    fun isLoaded() = workingDir != null

    open fun onBeforeLoad(context: Context, cacheDir: FsDir) {
        /* Empty */
    }

    open fun onAfterLoad(context: Context, cacheDir: FsDir) {
        /* Empty */
    }

    fun load(context: Context, force: Boolean = false): Result<Unit> = synchronized(lifecycleGuard) {
        if (!force && workingDir?.isDirectory == true) {
            return@synchronized resultOk()
        }
        if (workingDir != null || ownedRuntimeDir != null) {
            unloadLocked(context)
        }
        val sourceRef = sourceRef ?: return@synchronized resultOk()
        val runtimeRoot = try {
            prepareRuntimeRoot(context)
        } catch (error: Exception) {
            return@synchronized resultErr(error)
        }
        val cacheDir = runtimeRoot.subDir(UUID.randomUUID().toString())
        try {
            check(
                runtimeRoot.conservativeUsableSpace() >=
                    BoundedExtensionArchive.DefaultLimits.maxExpandedBytes + MinFreeSpaceBytes,
            ) {
                "Not enough space to load extension data."
            }
            check(cacheDir.mkdirs()) { "Unable to create extension runtime directory." }
            onBeforeLoad(context, cacheDir)
            ZipUtils.unzip(context, sourceRef, cacheDir).getOrThrow()
            workingDir = cacheDir
            ownedRuntimeDir = cacheDir
            onAfterLoad(context, cacheDir)
            resultOk()
        } catch (error: Throwable) {
            runCatching { onBeforeUnload(context, cacheDir) }
            cacheDir.deleteRecursively()
            workingDir = null
            ownedRuntimeDir = null
            runCatching { onAfterUnload(context, cacheDir) }
            when (error) {
                is InterruptedException -> throw error
                is CancellationException -> throw error
                is Exception -> resultErr(error)
                else -> throw error
            }
        }
    }

    open fun onBeforeUnload(context: Context, cacheDir: FsDir) {
        /* Empty */
    }

    open fun onAfterUnload(context: Context, cacheDir: FsDir) {
        /* Empty */
    }

    fun unload(context: Context) = synchronized(lifecycleGuard) {
        unloadLocked(context)
    }

    private fun unloadLocked(context: Context) {
        val cacheDir = ownedRuntimeDir
        if (cacheDir == null) {
            workingDir = null
            return
        }
        try {
            onBeforeUnload(context, cacheDir)
        } finally {
            cacheDir.deleteRecursively()
            if (workingDir == cacheDir) {
                workingDir = null
            }
            ownedRuntimeDir = null
            onAfterUnload(context, cacheDir)
        }
    }

    abstract fun edit(): ExtensionEditor

    private companion object {
        const val RuntimeRootDirName = "extension-runtime"
        const val MinFreeSpaceBytes = 128L * 1_024 * 1_024
        val RuntimeRootGuard = Any()
        val PreparedRuntimeRoots = mutableSetOf<String>()

        fun prepareRuntimeRoot(context: Context): FsDir = synchronized(RuntimeRootGuard) {
            val root = FsDir(context.cacheDir, RuntimeRootDirName)
            if (PreparedRuntimeRoots.add(root.absolutePath)) {
                check(!root.exists() || root.deleteRecursively()) {
                    "Unable to clean stale extension runtime data."
                }
            }
            check(root.isDirectory || root.mkdirs()) {
                "Unable to create extension runtime directory."
            }
            root
        }
    }
}

/**
 * Generates an update url for [Extension] lists.
 *
 * @param version the version of the api path
 * @param host the host for the addons store
 * @return the Url
 */
internal fun List<Extension>.generateUpdateUrl(
    host: String = BuildConfig.FLADDONS_STORE_URL,
): String {
    return Uri.Builder().run {
        scheme("https")
        authority(host)
        appendPath("check-updates")
        encodedFragment(
            buildString {
                append("data={")
                append(this@generateUpdateUrl.joinToString(",") { it.meta.getUpdateJsonPair() })
                append("}")
            }
        )
    }.build().toString()
}

interface ExtensionEditor {
    var meta: ExtensionMeta
    val dependencies: MutableList<String>

    fun build(): Extension
}
