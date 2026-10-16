/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Écran de rédaction de SMS, ouvert par les autres apps (ex. "Envoyer un SMS" depuis les contacts).
 *
 * Pas encore réalisé (étape envoi) : affiche un message et se ferme.
 */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, R.string.sms_compose_not_available, Toast.LENGTH_LONG).show()
        finish()
    }
}
