/*
 * Copyright 2026 Pigeon contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
// Icône de l'app Pigeon. Remplace le module :appicon:element (voir app/build.gradle.kts).
plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.appicon.pigeon"

    buildTypes {
        register("nightly")
    }
}
