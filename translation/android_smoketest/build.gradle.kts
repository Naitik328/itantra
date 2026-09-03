// Root build script for the standalone MT on-device smoke test.
// Not the real app -- see README.md for why this exists separately.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
