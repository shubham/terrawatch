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
// Plan 4 Task 6: RevenueCat ad-revenue tracking + AdMob banner — spec §5.1's own module list.
// com.revenuecat.purchases:purchases-kmp-core resolves via mavenCentral() (no restrictive
// mavenContent filter on that repository above); com.google.android.gms:play-services-ads
// resolves via google()'s existing "com.google" group-prefix allowance — no repository changes
// needed for either. core:monetization (the purchase/entitlement module) was deleted by the
// 2026-09-21 ads-only-monetization plan; purchases-kmp-core now lives in core:ads alone.
include(":core:ads")
