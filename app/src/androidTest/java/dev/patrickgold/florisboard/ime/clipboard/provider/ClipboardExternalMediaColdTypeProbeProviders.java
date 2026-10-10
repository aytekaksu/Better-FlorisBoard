/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard.provider;

/**
 * One-shot providers in separate processes. Each process can perform only a
 * getType-first probe, so another resolver operation cannot warm its target
 * provider handle.
 */
public final class ClipboardExternalMediaColdTypeProbeProviders {
    private ClipboardExternalMediaColdTypeProbeProviders() {
    }

    public static final class BeforeGrant extends ClipboardExternalMediaTestControlProvider {
        @Override
        protected boolean coldTypeProbeOnly() {
            return true;
        }
    }

    public static final class WhileGranted extends ClipboardExternalMediaTestControlProvider {
        @Override
        protected boolean coldTypeProbeOnly() {
            return true;
        }
    }

    public static final class AfterRevoke extends ClipboardExternalMediaTestControlProvider {
        @Override
        protected boolean coldTypeProbeOnly() {
            return true;
        }
    }
}
