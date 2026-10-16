/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.sms.impl

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * "Envoyer un SMS" depuis une autre app (contacts, composeur…) : ouvre la conversation dans Pigeon,
 * avec le texte éventuellement proposé par l'autre app déjà saisi.
 */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent.data?.smsAddress()
        val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        startActivity(SmsActivity.intent(this, address = address, draft = body))
        finish()
    }
}
