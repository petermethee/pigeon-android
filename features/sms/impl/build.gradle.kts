/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// SMS natifs : Pigeon peut devenir l'app SMS par défaut d'Android.
// Ce module est ajouté automatiquement à l'app (tous les modules features/*/impl le sont).
plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.features.sms.impl"
}

dependencies {
    // NotificationCompat, vérification des permissions.
    implementation(libs.androidx.corektx)
    // Chargement des SMS en arrière-plan.
    implementation(libs.coroutines.core)
    // Thème, couleurs, icônes et composants d'Element (design system "Compound"), pour un rendu homogène.
    implementation(projects.libraries.compound)
    implementation(projects.libraries.designsystem)
    // Textes communs traduits (Envoyer, Annuler…).
    implementation(projects.libraries.uiStrings)
}
