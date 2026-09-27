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

package dev.patrickgold.florisboard.lib.ext

import dev.patrickgold.florisboard.lib.io.parsePortablePathSegments
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * A bounded, forward-slash-separated path which is safe to resolve below a trusted directory.
 *
 * Parsing rejects platform-specific or ambiguous forms. [resolveWithin] additionally rejects
 * symbolic links in the root or any existing child, and returns a canonical path below the root.
 */
class SafeRelativePath private constructor(private val segments: List<String>) {
    companion object {
        const val MAX_LENGTH = 512
        const val MAX_SEGMENT_LENGTH = 128
        const val MAX_DEPTH = 16

        fun parse(rawPath: String): Result<SafeRelativePath> {
            val segments = parsePortablePathSegments(
                rawPath = rawPath,
                maxPathBytes = MAX_LENGTH,
                maxSegmentBytes = MAX_SEGMENT_LENGTH,
                maxDepth = MAX_DEPTH,
                rejectBlankSegments = true,
            ) ?: return unsafePath()
            return Result.success(SafeRelativePath(segments))
        }
    }

    /** Validated path text for filesystem APIs. Do not include it in diagnostics. */
    val value: String
        get() = segments.joinToString("/")

    /**
     * Resolves this path below an existing, non-symlink directory.
     *
     * Existing children are checked without following links. Missing final segments are allowed so
     * the returned path can also be used as a destination after the caller creates it safely.
     */
    fun resolveWithin(root: Path): Result<Path> = try {
        Result.success(resolveWithinOrThrow(root))
    } catch (_: UnsafeRelativePathException) {
        unsafePath()
    } catch (_: IOException) {
        unsafePath()
    } catch (_: SecurityException) {
        unsafePath()
    }

    private fun resolveWithinOrThrow(root: Path): Path {
        val absoluteRoot = root.toAbsolutePath().normalize()
        rejectUnsafeUnless(
            Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(absoluteRoot),
        )
        val canonicalRoot = absoluteRoot.toRealPath()
        var candidate = canonicalRoot
        var hasMissingSegment = false
        for ((index, segment) in segments.withIndex()) {
            candidate = candidate.resolve(segment).normalize()
            rejectUnsafeUnless(candidate.startsWith(canonicalRoot))
            if (hasMissingSegment) {
                continue
            }
            when {
                Files.isSymbolicLink(candidate) -> throw UnsafeRelativePathException()

                Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) -> {
                    rejectUnsafeUnless(
                        index == segments.lastIndex ||
                            Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS),
                    )
                    candidate = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS)
                    rejectUnsafeUnless(candidate.startsWith(canonicalRoot))
                }

                else -> hasMissingSegment = true
            }
        }
        return candidate
    }

    override fun equals(other: Any?): Boolean = other is SafeRelativePath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = "<relative-path>"
}

class UnsafeRelativePathException internal constructor() : IllegalArgumentException("Unsafe relative path")

private fun <T> unsafePath(): Result<T> = Result.failure(UnsafeRelativePathException())

private fun rejectUnsafeUnless(condition: Boolean) {
    if (!condition) {
        throw UnsafeRelativePathException()
    }
}
