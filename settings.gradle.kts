pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only these libraries come from JitPack.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.termux.termux-app")
                // libadb-android (Apache-2.0 / GPL-3.0) and its SPAKE2 (LGPL-3.0): "Install on my watch" over wireless debugging.
                includeGroup("com.github.MuntashirAkon")
                includeGroup("com.github.MuntashirAkon.spake2-java")
            }
        }
    }
}
rootProject.name = "Ultimate"
include(":app")
// Ultimate+ for Wear OS: wrist gestures and buttons that control the phone (same package and key as Ultimate+, so they pair).
include(":wear")
