plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.github.triplet.play") version "3.11.0" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
}

// Firebase Test Lab task
// Usage: ./gradlew firebaseTestLab [-Pdevice=Pixel2.arm] [-PapiLevel=33] [-Ptimeout=120s]
tasks.register<Exec>("firebaseTestLab") {
    description = "Runs Robo test on Firebase Test Lab"
    group = "verification"

    dependsOn(":app:assembleRelease")

    val apkFile = file("app/build/outputs/apk/release/app-release.apk")
    val device = project.findProperty("device") as String? ?: "MediumPhone.arm"
    val apiLevel = project.findProperty("apiLevel") as String? ?: "34"
    val timeout = project.findProperty("timeout") as String? ?: "120s"

    doFirst {
        require(apkFile.exists()) { "APK not found: $apkFile" }
        println("Running Firebase Test Lab: device=$device, API=$apiLevel, timeout=$timeout")
    }

    commandLine(
        "gcloud", "firebase", "test", "android", "run",
        "--type", "robo",
        "--app", apkFile.absolutePath,
        "--device", "model=$device,version=$apiLevel",
        "--timeout", timeout,
        "--project", "nbrs-243915"
    )
}
