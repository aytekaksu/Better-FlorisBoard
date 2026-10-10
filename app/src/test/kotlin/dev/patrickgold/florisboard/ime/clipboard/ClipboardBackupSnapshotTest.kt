/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class ClipboardBackupSnapshotTest :
    FunSpec({
        test("release is idempotent after the lease is released") {
            runTest {
                var releases = 0
                val snapshot = ClipboardBackupSnapshot(emptyList()) {
                    releases++
                }

                snapshot.release()
                snapshot.release()

                releases shouldBe 1
            }
        }

        test("a failed release remains retryable") {
            runTest {
                var attempts = 0
                val snapshot = ClipboardBackupSnapshot(emptyList()) {
                    attempts++
                    if (attempts == 1) {
                        throw IOException("synthetic release failure")
                    }
                }

                shouldThrow<IOException> {
                    snapshot.release()
                }
                snapshot.release()

                attempts shouldBe 2
            }
        }

        test("a cancelled release remains retryable") {
            runTest {
                var attempts = 0
                val snapshot = ClipboardBackupSnapshot(emptyList()) {
                    attempts++
                    if (attempts == 1) {
                        throw CancellationException("synthetic cancellation")
                    }
                }

                shouldThrow<CancellationException> {
                    snapshot.release()
                }
                snapshot.release()

                attempts shouldBe 2
            }
        }
    })
