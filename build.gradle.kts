// The library build. Versions are pinned here rather than in a version catalog
// because this project is ONE module — a catalog would be indirection with
// nothing to share.
plugins {
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
