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

package dev.patrickgold.florisboard.app

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

internal enum class OwnedRoutePopResult {
    NOT_CURRENT,
    POPPED,
    FAILED,
}

internal inline fun <T : Any> popOwnedRoute(
    current: T?,
    owner: T,
    ownerIsResumed: Boolean,
    pop: () -> Boolean,
): OwnedRoutePopResult {
    if (current !== owner || !ownerIsResumed) return OwnedRoutePopResult.NOT_CURRENT
    return if (pop()) OwnedRoutePopResult.POPPED else OwnedRoutePopResult.FAILED
}

internal suspend inline fun <T : Any> popOwnedRouteWhenResumed(
    crossinline current: () -> T?,
    owner: T,
    ownerStates: Flow<Lifecycle.State>,
    pop: () -> Boolean,
): OwnedRoutePopResult {
    val terminalState = ownerStates.first { state ->
        state == Lifecycle.State.RESUMED || state == Lifecycle.State.DESTROYED
    }
    return popOwnedRoute(
        current = current(),
        owner = owner,
        ownerIsResumed = terminalState == Lifecycle.State.RESUMED,
        pop = pop,
    )
}

internal inline fun NavController.runOwnedNavigationAction(
    owner: NavBackStackEntry,
    action: NavController.() -> Boolean,
): OwnedRoutePopResult =
    popOwnedRoute(
        current = currentBackStackEntry,
        owner = owner,
        ownerIsResumed = owner.lifecycle.currentState == Lifecycle.State.RESUMED,
        pop = { action() },
    )

internal fun NavController.popOwnedRoute(owner: NavBackStackEntry): OwnedRoutePopResult =
    runOwnedNavigationAction(owner, NavController::popBackStack)

internal suspend inline fun NavController.runOwnedNavigationActionWhenResumed(
    owner: NavBackStackEntry,
    crossinline action: NavController.() -> Boolean,
): OwnedRoutePopResult = popOwnedRouteWhenResumed(
    current = { currentBackStackEntry },
    owner = owner,
    ownerStates = owner.lifecycle.currentStateFlow,
    pop = { action() },
)

internal suspend fun NavController.popOwnedRouteWhenResumed(
    owner: NavBackStackEntry,
): OwnedRoutePopResult =
    runOwnedNavigationActionWhenResumed(owner, NavController::popBackStack)
