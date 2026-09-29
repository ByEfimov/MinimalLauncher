// Appended to a COPY of app/build.gradle.kts by shots.sh — the real build (and CI) stays untouched.
android {
    testOptions { unitTests { isIncludeAndroidResources = true; all { it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/"); it.maxHeapSize = "2g" } } }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
tasks.withType<Test>().configureEach { forkEvery = 1; maxParallelForks = 2; systemProperty("shots.dir", System.getenv("SHOTS_DIR") ?: "/tmp/shots") }
