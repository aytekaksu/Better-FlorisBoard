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
import android.os.FileObserver
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.keyboard.KeyboardExtension
import dev.patrickgold.florisboard.ime.nlp.LanguagePackExtension
import dev.patrickgold.florisboard.ime.text.composing.Appender
import dev.patrickgold.florisboard.ime.text.composing.Composer
import dev.patrickgold.florisboard.ime.text.composing.HangulUnicode
import dev.patrickgold.florisboard.ime.text.composing.KanaUnicode
import dev.patrickgold.florisboard.ime.text.composing.WithRules
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.io.BoundedExtensionArchive
import dev.patrickgold.florisboard.lib.io.FlorisRef
import dev.patrickgold.florisboard.lib.io.ZipUtils
import dev.patrickgold.florisboard.lib.io.delete
import dev.patrickgold.florisboard.lib.io.listDirs
import dev.patrickgold.florisboard.lib.io.listFiles
import dev.patrickgold.florisboard.lib.io.loadTextAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import org.florisboard.lib.android.FileObserver
import org.florisboard.lib.android.writeFromFile
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.throwOnFailure
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

@OptIn(ExperimentalSerializationApi::class)
val ExtensionJsonConfig = Json {
    classDiscriminator = "$"
    encodeDefaults = false
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    prettyPrintIndent = "  "
    serializersModule = SerializersModule {
        polymorphic(Extension::class) {
            subclass(KeyboardExtension::class, KeyboardExtension.serializer())
            subclass(ThemeExtension::class, ThemeExtension.serializer())
            subclass(LanguagePackExtension::class, LanguagePackExtension.serializer())
        }
        polymorphic(Composer::class) {
            subclass(Appender::class, Appender.serializer())
            subclass(HangulUnicode::class, HangulUnicode.serializer())
            subclass(KanaUnicode::class, KanaUnicode.serializer())
            subclass(WithRules::class, WithRules.serializer())
            defaultDeserializer { Appender.serializer() }
        }
    }
}

internal fun <T> decodeExtensionManifest(
    manifestJson: String,
    serializer: KSerializer<T>,
): Result<T> = runCatching {
    check(manifestJson.hasBoundedExtensionManifestJsonShape()) {
        "Extension manifest exceeds structural limits."
    }
    ExtensionJsonConfig.decodeFromString(serializer, manifestJson)
}

internal fun decodeExtensionManifest(manifestJson: String): Result<Extension> {
    return decodeExtensionManifest(manifestJson, serializer<Extension>())
}

internal data class InstalledExtensionArchiveFingerprint(
    val extensionId: String,
    val serialType: String,
    val sha256: String,
)

private fun ByteArray.toHexString(): String = buildString(size * 2) {
    for (byte in this@toHexString) {
        val value = byte.toInt() and 0xff
        append("0123456789abcdef"[value ushr 4])
        append("0123456789abcdef"[value and 0x0f])
    }
}

class ExtensionManager(context: Context) {
    companion object {
        const val IME_KEYBOARD_PATH = "ime/keyboard"
        const val IME_KEYBOARD3_PATH = "ime/keyboard3"
        const val IME_THEME_PATH = "ime/theme"
        const val IME_LANGUAGEPACK_PATH = "ime/languagepack"

        private const val EXTENSION_ARCHIVE_SUFFIX = ".${ExtensionDefaults.FILE_EXTENSION}"
        private val INTERNAL_EXTENSION_PATHS = listOf(
            IME_KEYBOARD_PATH,
            IME_THEME_PATH,
            IME_LANGUAGEPACK_PATH,
        )
        private const val FILE_OBSERVER_MASK =
            FileObserver.CLOSE_WRITE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO
        private val storageMutationGuard = Mutex()

        internal suspend fun <T> withStorageMutation(block: suspend () -> T): T =
            storageMutationGuard.withLock { block() }
    }

    private val appContext by context.appContext()
    private val defaultScope = CoroutineScope(Dispatchers.Default)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val importGuard = Mutex()

    val keyboardExtensions = ExtensionIndex(KeyboardExtension.serializer(), IME_KEYBOARD_PATH)
    val themes = ExtensionIndex(ThemeExtension.serializer(), IME_THEME_PATH)
    val languagePacks = ExtensionIndex(LanguagePackExtension.serializer(), IME_LANGUAGEPACK_PATH)

    val extensions = combine(
        keyboardExtensions,
        themes,
        languagePacks,
    ) { lists -> lists.flatMap { it } }.stateIn(defaultScope, SharingStarted.Eagerly, emptyList())

    fun init() {
        ioScope.launch {
            keyboardExtensions.init()
            themes.init()
            languagePacks.init()
        }
    }

    suspend fun import(ext: Extension) = importGuard.withLock {
        withStorageMutation {
            runInterruptible(Dispatchers.IO) {
                val stagingDir = requireNotNull(ext.workingDir) { "No working dir specified" }
                installArchive(ext, stagingDir, replaceExisting = true)
            }
        }
    }

    internal suspend fun installNew(ext: Extension, stagingDir: FsDir) = importGuard.withLock {
        withStorageMutation {
            runInterruptible(Dispatchers.IO) {
                installArchive(ext, stagingDir, replaceExisting = false)
            }
        }
    }

    internal suspend fun replace(
        ext: Extension,
        stagingDir: FsDir,
        expected: InstalledExtensionArchiveFingerprint,
    ) = importGuard.withLock {
        withStorageMutation {
            runInterruptible(Dispatchers.IO) {
                installArchive(
                    ext = ext,
                    stagingDir = stagingDir,
                    replaceExisting = true,
                    expected = expected,
                )
            }
        }
    }

    internal suspend fun materializeForEditor(
        ext: Extension,
        destination: FsDir,
    ): InstalledExtensionArchiveFingerprint? = importGuard.withLock {
        withStorageMutation {
            runInterruptible(Dispatchers.IO) {
                val sourceRef = requireNotNull(ext.sourceRef) { "Extension source is unavailable." }
                val fingerprint = if (sourceRef.isInternal) {
                    requireUnchangedInternalArchive(ext)
                } else {
                    check(sourceRef.isAssets) { "Unsupported extension source." }
                    null
                }
                ZipUtils.unzip(appContext, sourceRef, destination).throwOnFailure()
                fingerprint
            }
        }
    }

    private fun installArchive(
        ext: Extension,
        stagingDir: FsDir,
        replaceExisting: Boolean,
        expected: InstalledExtensionArchiveFingerprint? = null,
    ) {
        val destination = requireInstallDestination(ext, replaceExisting, expected)
        val result = if (replaceExisting) {
            ZipUtils.zip(appContext, stagingDir, destination)
        } else {
            ZipUtils.zipNew(appContext, stagingDir, destination)
        }
        result.throwOnFailure()
        ext.sourceRef = destination
        ext.sourceArchiveFingerprint = fingerprint(
            ext = ext,
            archive = destination.absoluteFile(appContext).toPath(),
        )
    }

    private fun requireInstallDestination(
        ext: Extension,
        replaceExisting: Boolean,
        expected: InstalledExtensionArchiveFingerprint?,
    ): FlorisRef {
        check(ext.validateForImport().isValid) { "Extension manifest is invalid." }
        check(!isBundledExtensionId(ext.meta.id)) { "Bundled extensions cannot be replaced." }
        if (expected != null) {
            check(replaceExisting) { "An editor replacement must replace an archive." }
            check(
                ext.meta.id == expected.extensionId &&
                    ext.serialType() == expected.serialType,
            ) {
                "The edited extension identity changed."
            }
        }
        val installed = getExtensionById(ext.meta.id)
        if (replaceExisting) {
            installed?.let {
                check(it.sourceRef?.isAssets != true) { "Bundled extensions cannot be replaced." }
                check(it.serialType() == ext.serialType()) {
                    "Extension type does not match installed package."
                }
            }
        } else {
            check(installed == null) { "Extension ID is already installed." }
        }

        val archiveName = ExtensionDefaults.createFlexName(ext.meta.id)
        val extensionPath = extensionPath(ext)
        for (path in INTERNAL_EXTENSION_PATHS) {
            val candidate = FlorisRef.internal(path)
                .subRef(archiveName)
                .absoluteFile(appContext)
                .toPath()
            if (replaceExisting && path == extensionPath) {
                check(
                    Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS),
                ) {
                    "Extension destination is invalid."
                }
            } else {
                check(Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    "Extension ID is already installed."
                }
            }
        }
        val destination = FlorisRef.internal(extensionPath).subRef(archiveName)
        if (expected != null) {
            check(
                fingerprint(ext, destination.absoluteFile(appContext).toPath()) == expected,
            ) {
                "The installed extension changed while it was being edited."
            }
        }
        return destination
    }

    private fun internalArchiveRef(ext: Extension): FlorisRef =
        FlorisRef.internal(extensionPath(ext))
            .subRef(ExtensionDefaults.createFlexName(ext.meta.id))

    private fun requireUnchangedInternalArchive(
        ext: Extension,
    ): InstalledExtensionArchiveFingerprint {
        val sourceRef = requireNotNull(ext.sourceRef) { "Extension source is unavailable." }
        val expectedRef = internalArchiveRef(ext)
        check(sourceRef.isInternal)
        check(
            sourceRef.absoluteFile(appContext).toPath().toAbsolutePath().normalize() ==
                expectedRef.absoluteFile(appContext).toPath().toAbsolutePath().normalize(),
        ) {
            "Installed extension source is invalid."
        }
        val expected = requireNotNull(ext.sourceArchiveFingerprint) {
            "Installed extension identity is unavailable."
        }
        check(fingerprint(ext, expectedRef.absoluteFile(appContext).toPath()) == expected) {
            "The installed extension changed."
        }
        return expected
    }

    private fun extensionPath(ext: Extension): String = when (ext) {
        is KeyboardExtension -> IME_KEYBOARD_PATH
        is ThemeExtension -> IME_THEME_PATH
        is LanguagePackExtension -> IME_LANGUAGEPACK_PATH
        else -> error("Unknown extension type")
    }

    private fun fingerprint(
        ext: Extension,
        archive: Path,
    ): InstalledExtensionArchiveFingerprint {
        val attributes = Files.readAttributes(
            archive,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        check(
            !attributes.isSymbolicLink &&
                attributes.isRegularFile &&
                attributes.size() in 1L..BoundedExtensionArchive.DefaultLimits.maxArchiveBytes,
        ) {
            "Installed extension archive is invalid."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        Files.newInputStream(
            archive,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { input ->
            val buffer = ByteArray(16 * 1_024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val readBytes = input.read(buffer)
                if (readBytes < 0) break
                check(readBytes > 0 && readBytes.toLong() <= attributes.size() - copiedBytes) {
                    "Installed extension archive changed."
                }
                digest.update(buffer, 0, readBytes)
                copiedBytes += readBytes
            }
        }
        check(copiedBytes == attributes.size()) { "Installed extension archive changed." }
        return InstalledExtensionArchiveFingerprint(
            extensionId = ext.meta.id,
            serialType = ext.serialType(),
            sha256 = digest.digest().toHexString(),
        )
    }

    private fun isBundledExtensionId(id: String): Boolean = INTERNAL_EXTENSION_PATHS.any { path ->
        appContext.assets.list("$path/$id")
            ?.contains(ExtensionDefaults.MANIFEST_FILE_NAME)
            ?: throw IllegalStateException("Unable to inspect bundled extensions.")
    }

    suspend fun export(ext: Extension, uri: Uri) {
        val sourceRef = requireNotNull(ext.sourceRef) { "No source ref specified" }
        val workspace = Files.createTempDirectory(
            appContext.cacheDir.toPath(),
            "extension-export-",
        ).toFile()
        try {
            val snapshot = workspace.resolve("extension.${ExtensionDefaults.FILE_EXTENSION}")
            when {
                sourceRef.isInternal -> {
                    withStorageMutation {
                        runInterruptible(Dispatchers.IO) {
                            ZipUtils.copyFileNoFollow(
                                srcFile = sourceRef.absoluteFile(appContext),
                                dstFile = snapshot,
                            )
                        }
                    }
                    runInterruptible(Dispatchers.IO) {
                        appContext.contentResolver.writeFromFile(uri, snapshot)
                    }
                }
                sourceRef.isAssets -> runInterruptible(Dispatchers.IO) {
                    ZipUtils.unzip(appContext, sourceRef, workspace).throwOnFailure()
                    ZipUtils.zip(appContext, workspace, uri).throwOnFailure()
                }
                else -> error("Unsupported extension source")
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    fun getExtensionById(id: String): Extension? {
        return keyboardExtensions.value.find { it.meta.id == id }
            ?: themes.value.find { it.meta.id == id }
            ?: languagePacks.value.find { it.meta.id == id }
    }

    fun canDelete(ext: Extension): Boolean {
        return ext.sourceRef?.isInternal == true
    }

    suspend fun delete(ext: Extension) = withStorageMutation {
        runInterruptible(Dispatchers.IO) {
            check(canDelete(ext)) { "Cannot delete extension!" }
            requireUnchangedInternalArchive(ext)
            ext.unload(appContext)
            ext.sourceRef!!.delete(appContext)
        }
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    inner class ExtensionIndex<T : Extension>(
        private val serializer: KSerializer<T>,
        modulePath: String,
        private val flow: MutableStateFlow<List<T>> = MutableStateFlow(emptyList()),
    ) : StateFlow<List<T>> by flow {
        private val assetsModuleRef = FlorisRef.assets(modulePath)
        private val internalModuleRef = FlorisRef.internal(modulePath)
        var internalModuleDir = internalModuleRef.absoluteFile(appContext)

        private var staticExtensions = listOf<T>()
        private var fileObserver: FileObserver? = null
        private val initGuard = Mutex()
        private val refreshGuard = Mutex()
        private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

        init {
            ioScope.launch {
                while (true) {
                    refreshRequests.receive()
                    refreshGuard.withLock {
                        refresh()
                    }
                }
            }
        }

        suspend fun init() {
            initGuard.withLock {
                // Update internal module dir to actual path and make directory if not exists
                internalModuleDir = internalModuleRef.absoluteFile(appContext)
                internalModuleDir.mkdirs()

                // Refresh index to new state
                refreshGuard.withLock {
                    staticExtensions = indexAssetsModule()
                    refresh()
                }

                // Stop watching on old file observer if one exists and start new observer on new path
                fileObserver?.stopWatching()
                fileObserver = FileObserver(internalModuleDir, FILE_OBSERVER_MASK) { _, path ->
                    if (path?.substringAfterLast('/')?.endsWith(EXTENSION_ARCHIVE_SUFFIX) != true) {
                        return@FileObserver
                    }
                    refreshRequests.trySend(Unit)
                }.also { it.startWatching() }
            }
        }

        private suspend fun refresh() = withStorageMutation {
            flow.value = staticExtensions + indexInternalModule()
        }

        private fun indexAssetsModule(): List<T> {
            val list = mutableListOf<T>()
            assetsModuleRef.listDirs(appContext).fold(
                onSuccess = { extRefs ->
                    for (extRef in extRefs) {
                        val fileRef = extRef.subRef(ExtensionDefaults.MANIFEST_FILE_NAME)
                        fileRef.loadTextAsset(appContext)
                            .mapCatching { decodeExtensionManifest(it, serializer).getOrThrow() }
                            .fold(
                            onSuccess = { ext ->
                                if (ext.validateForImport().isValid) {
                                    ext.sourceRef = extRef
                                    list.add(ext)
                                } else {
                                    flogError { "Bundled extension manifest validation failed" }
                                }
                            },
                            onFailure = { error ->
                                flogError {
                                    "Failed to parse bundled extension manifest: error=${error.javaClass.simpleName}"
                                }
                            },
                            )
                    }
                },
                onFailure = { error ->
                    flogError { "Failed to list bundled extensions: error=${error.javaClass.simpleName}" }
                },
            )
            return list.toList()
        }

        private fun indexInternalModule(): List<T> {
            val list = mutableListOf<T>()
            internalModuleRef.listFiles(appContext).fold(
                onSuccess = { extRefs ->
                    for (extRef in extRefs) {
                        val fileRef = extRef.absoluteFile(appContext)
                        if (!fileRef.name.endsWith(EXTENSION_ARCHIVE_SUFFIX)) {
                            continue
                        }
                        ZipUtils.readFileFromArchive(appContext, extRef, ExtensionDefaults.MANIFEST_FILE_NAME).fold(
                            onSuccess = { metaStr ->
                                decodeExtensionManifest(metaStr, serializer).fold(
                                    onSuccess = { ext ->
                                        val isCanonicalFile = fileRef.name ==
                                            ExtensionDefaults.createFlexName(ext.meta.id)
                                        val conflictsWithBundled = staticExtensions.any {
                                            it.meta.id == ext.meta.id
                                        }
                                        if (
                                            ext.validateForImport().isValid &&
                                            isCanonicalFile &&
                                            !conflictsWithBundled
                                        ) {
                                            ext.sourceRef = extRef
                                            ext.sourceArchiveFingerprint = fingerprint(ext, fileRef.toPath())
                                            list.add(ext)
                                        } else {
                                            flogError { "Installed extension package validation failed" }
                                        }
                                    },
                                    onFailure = { error ->
                                        flogError {
                                            "Installed manifest parse failed: error=${error.javaClass.simpleName}"
                                        }
                                    },
                                )
                            },
                            onFailure = { error ->
                                flogError {
                                    "Failed to read installed extension manifest: error=${error.javaClass.simpleName}"
                                }
                            },
                        )
                    }
                },
                onFailure = { error ->
                    flogError { "Failed to list installed extensions: error=${error.javaClass.simpleName}" }
                },
            )
            return list.toList()
        }
    }
}
