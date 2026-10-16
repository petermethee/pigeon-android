/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// SMS natifs : Pigeon peut devenir l'app SMS par défaut d'Android.
// Ce module est ajouté automatiquement à l'app (tous les modules features/*/impl le sont).
plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.features.sms.impl"
}

dependencies {
    // NotificationCompat : notifications compatibles avec toutes les versions d'Android.
    implementation(libs.androidx.corektx)
}
