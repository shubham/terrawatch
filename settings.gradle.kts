rootProject.name = "terrawatch"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
    }
}

include(":composeApp")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:data")
include(":core:ui")
// Plan 4 Task 6: RevenueCat entitlements + AdMob banner — spec §5.1's own module list.
// com.revenuecat.purchases:purchases-kmp-core resolves via mavenCentral() (no restrictive
// mavenContent filter on that repository above); com.google.android.gms:play-services-ads
// resolves via google()'s existing "com.google" group-prefix allowance — no repository changes
// needed for either.
include(":core:monetization")
include(":core:ads")
