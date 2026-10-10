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

package dev.patrickgold.florisboard.ime.nlp.plugin

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import kotlinx.coroutines.runBlocking
import org.florisboard.autocorrect.api.AutocorrectCapsMode
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import org.florisboard.autocorrect.host.core.BindingState
import org.florisboard.autocorrect.host.core.DiscoveryState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowContextImpl
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Checks the real manager's ownership against Robolectric's independent binding registry. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = Application::class, shadows = [HeldBindContextImpl::class])
class AutocorrectPluginBindingTest {
    private val prefs by FlorisPreferenceStore
    private val providerA = ComponentName("binding.fixture", "binding.fixture.ProviderA")
    private val providerB = ComponentName("binding.fixture", "binding.fixture.ProviderB")
    private lateinit var application: FlorisApplication
    private lateinit var platform: ShadowApplication
    private lateinit var manager: AutocorrectPluginManager
    private var preexistingReceivers = emptyList<BroadcastReceiver>()
    private var oldSelection: String? = null
    private var savedSelection = false

    @Before
    fun prepareHost() {
        HeldBindContextImpl.gate = null
        application = FlorisApplication()
        platform = shadowOf(application)
        platform.callAttach(RuntimeEnvironment.getApplication().baseContext)
        platform.setUnbindServiceCallsOnServiceDisconnected(false)
        oldSelection = prefs.suggestion.autocorrectPluginComponent.getOrNull()
        savedSelection = true
        installProvider(providerA)
        installProvider(providerB)
        preexistingReceivers = platform.registeredReceivers.map { it.broadcastReceiver }
        manager = AutocorrectPluginManager(
            context = application,
            keyboardTraits = { AutocorrectKeyboardTraits(false, AutocorrectCapsMode.UNSHIFTED) },
            currentEditorInfo = { FlorisEditorInfo.Unspecified },
            currentEditorContent = { EditorContent.Unspecified },
        )
        assertEquals("manager must register one package receiver", 1, ownedReceivers().size)
        select(providerA)
        manager.refreshProviders()
        await("synthetic providers were not discovered") {
            manager.hostStateSnapshot().discovery is DiscoveryState.Ready &&
                manager.providers.value.map { it.componentName }.containsAll(listOf(providerA, providerB))
        }
    }

    @After
    fun releaseFixture() {
        val held = HeldBindContextImpl.gate
        held?.release?.countDown()
        try {
            if (::manager.isInitialized) destroyAndDrain()
            assertFalse("platform fixture exceeded its hold deadline", held?.timedOut == true)
        } finally {
            HeldBindContextImpl.gate = null
            try {
                // A baseline failure deliberately leaves registration; release it only after assertions.
                if (::platform.isInitialized) bound().forEach(application::unbindService)
                shadowOf(Looper.getMainLooper()).idle()
            } finally {
                if (savedSelection) {
                    runBlocking {
                        val preference = prefs.suggestion.autocorrectPluginComponent
                        oldSelection?.let { preference.set(it) }?.getOrThrow()
                            ?: preference.reset().getOrThrow()
                    }
                }
            }
        }
    }

    @Test
    fun falseBindReleasesItsAttemptedConnectionOnce() {
        platform.declareComponentUnbindable(providerA)
        assertFailedAttemptReleased()
    }

    @Test
    fun deniedBindReleasesItsAttemptedConnectionOnce() {
        platform.setThrowInBindService(SecurityException("synthetic bind denial"))
        assertFailedAttemptReleased()
    }

    @Test
    fun staleFailedBindReleasesOnlyTheOldAttempt() {
        val held = BindReturnGate(providerA)
        HeldBindContextImpl.gate = held
        platform.declareComponentUnbindable(providerA)
        platform.setComponentNameAndServiceForBindServiceForIntent(
            providerIntent(providerB),
            providerB,
            Messenger(Handler(Looper.getMainLooper())).binder,
        )
        manager.acquirePluginUi()
        assertTrue(
            "fixture did not reach the platform bind",
            held.entered.await(BINDING_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        assertEquals("fixture must hold an actual false return", false, held.result)
        val old = checkNotNull(held.connection)
        assertTrue("old attempt was not registered by the platform", bound().any { it === old })

        select(providerB)
        val replacementState = manager.hostStateSnapshot().binding
        assertTrue(
            "replacement lease was not installed while bind was held",
            replacementState is BindingState.Connecting,
        )
        assertEquals(providerB.flattenToString(), (replacementState as BindingState.Connecting).lease.providerId.value)
        held.release.countDown()
        // Do not wait for A to disappear: the baseline must reach the release assertion with A retained.
        await("replacement bind never reached the platform") { bound().any { it !== old } }
        val replacement = bound().single { it !== old }
        assertEquals("old failed attempt must be released once", 1, released().count { it === old })
        assertEquals("only replacement tracking may remain", 1, bound().size)
        assertTrue(bound().single() === replacement)
        assertEquals(0, released().count { it === replacement })

        old.onServiceConnected(providerA, Messenger(Handler(Looper.getMainLooper())).binder)
        old.onServiceDisconnected(providerA)
        old.onNullBinding(providerA)
        old.onBindingDied(providerA)
        assertEquals(
            "old callbacks changed the replacement lease",
            replacementState,
            manager.hostStateSnapshot().binding,
        )
        assertTrue(bound().single() === replacement)
        assertEquals(0, released().count { it === replacement })

        destroyAndDrain()
        assertEquals(1, released().count { it === old })
        assertEquals(1, released().count { it === replacement })
        assertTrue("destroy left framework registration", bound().isEmpty())
    }

    @Test
    fun cancelledQueuedBindDoesNotReleaseAnUnattemptedConnection() {
        synchronized(manager) {
            manager.acquirePluginUi()
            val binding = manager.hostStateSnapshot().binding
            assertTrue("fixture must first queue a valid bind", binding is BindingState.Connecting)
            assertEquals(providerA.flattenToString(), (binding as BindingState.Connecting).lease.providerId.value)
            manager.releasePluginUi()
            assertEquals(BindingState.Unbound, manager.hostStateSnapshot().binding)
        }
        destroyAndDrain()
        assertTrue("cancelled queued bind reached the platform", bound().isEmpty())
        assertTrue("an unattempted connection was released", released().isEmpty())
    }

    private fun assertFailedAttemptReleased() {
        manager.acquirePluginUi()
        await("current bind failure was not reported") { manager.pluginUiError.value && !manager.pluginUiLoading.value }
        destroyAndDrain()
        val active = bound()
        val retired = released()
        val attempts = Collections.newSetFromMap(IdentityHashMap<ServiceConnection, Boolean>())
            .apply {
                addAll(active)
                addAll(retired)
            }
        assertEquals("fixture must make one actual platform bind attempt", 1, attempts.size)
        val attempt = attempts.single()
        assertEquals("failed bind must release its attempted connection once", 1, retired.count { it === attempt })
        assertTrue("failed bind left framework registration", active.isEmpty())
    }

    private fun installProvider(component: ComponentName) {
        val packageManager = shadowOf(application.packageManager)
        packageManager.addOrUpdateService(
            ServiceInfo().apply {
                packageName = component.packageName
                name = component.className
                applicationInfo = ApplicationInfo().apply {
                    packageName = component.packageName
                    uid = 17_001
                }
                exported = true
                nonLocalizedLabel = component.className
                metaData = Bundle().apply {
                    putInt(AutocorrectPluginContract.META_PROTOCOL_VERSION, AutocorrectPluginContract.PROTOCOL_VERSION)
                }
            },
        )
        packageManager.addIntentFilterForService(
            component,
            IntentFilter(AutocorrectPluginContract.ACTION_BIND_PROVIDER),
        )
    }

    private fun select(component: ComponentName) {
        runBlocking { prefs.suggestion.autocorrectPluginComponent.set(component.flattenToString()).getOrThrow() }
        manager.onSelectedProviderChanged()
    }

    private fun destroyAndDrain() {
        runBlocking { manager.destroy() }
        // Resource release is ordered after transport cleanup; do not refresh discovery as a fence.
        await("host package receiver was not released") {
            ownedReceivers().isEmpty()
        }
    }

    private fun ownedReceivers() = platform.registeredReceivers.filter { wrapper ->
        preexistingReceivers.none { it === wrapper.broadcastReceiver }
    }

    private fun bound() = platform.boundServiceConnections.let { synchronized(it) { it.toList() } }

    private fun released() = platform.unboundServiceConnections.let { synchronized(it) { it.toList() } }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BINDING_TIMEOUT_MS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, condition())
    }
}

@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
class HeldBindContextImpl : ShadowContextImpl() {
    companion object {
        @Volatile var gate: BindReturnGate? = null
    }

    @Implementation
    override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        val result = super.bindService(intent, connection, flags)
        val held = gate?.takeIf { it.component == intent.component } ?: return result
        held.connection = connection
        held.result = result
        held.entered.countDown()
        held.timedOut = !held.release.await(BINDING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result
    }
}

class BindReturnGate(val component: ComponentName) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Volatile var connection: ServiceConnection? = null

    @Volatile var result: Boolean? = null

    @Volatile var timedOut = false
}

private fun providerIntent(component: ComponentName) =
    Intent(AutocorrectPluginContract.ACTION_BIND_PROVIDER).setComponent(component)

private const val BINDING_TIMEOUT_MS = 5_000L
