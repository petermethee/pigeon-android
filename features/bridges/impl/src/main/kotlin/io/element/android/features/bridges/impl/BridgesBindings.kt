/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.bridges.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.libraries.sessionstorage.api.SessionStore

/** Ce que l'écran des comptes connectés prend à Element : la session Matrix ouverte. */
@ContributesTo(AppScope::class)
interface BridgesBindings {
    fun sessionStore(): SessionStore
}
