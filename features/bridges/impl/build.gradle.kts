import extension.setupDependencyInjection

/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// Comptes connectés : connexion de WhatsApp et Messenger aux bridges du serveur, depuis l'app.
// Ce module est ajouté automatiquement à l'app (tous les modules features/*/impl le sont).
plugins {
    id("io.element.android-compose-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.features.bridges.impl"
}

// Accès à la session Matrix ouverte (jeton de connexion), fournie par l'injection de dépendances d'Element.
setupDependencyInjection()

dependencies {
    implementation(libs.coroutines.core)
    // Demandes à l'API de connexion des bridges.
    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp)
    implementation(libs.serialization.json)
    // Session Matrix ouverte : serveur, identifiant et jeton de connexion.
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.sessionStorage.api)
    // Thème, couleurs, icônes et composants d'Element.
    implementation(projects.libraries.compound)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.qrcode)
    implementation(projects.libraries.uiStrings)
}
