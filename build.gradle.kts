plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.github.triplet.play") version "3.11.0" apply false
}

// App Crawler configuration
val appCrawlerDir = file("tools/app-crawler")
val appCrawlerJar = file("$appCrawlerDir/crawl_launcher.jar")
val appCrawlerUrl = "https://dl.google.com/appcrawler/beta1/app-crawler.zip"

tasks.register("downloadAppCrawler") {
    description = "Downloads Google App Crawler if not present"
    group = "verification"

    onlyIf { !appCrawlerJar.exists() }

    doLast {
        println("Downloading App Crawler...")
        appCrawlerDir.mkdirs()

        val zipFile = file("$appCrawlerDir/app-crawler.zip")

        // Download
        java.net.URI(appCrawlerUrl).toURL().openStream().use { input ->
            zipFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Extract
        copy {
            from(zipTree(zipFile)) {
                // Flatten the nested directory
                eachFile {
                    relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
                }
                includeEmptyDirs = false
            }
            into(appCrawlerDir)
        }

        zipFile.delete()
        println("App Crawler installed to: $appCrawlerDir")
    }
}

tasks.register<Exec>("runAppCrawler") {
    description = "Runs App Crawler on the release APK to detect crashes"
    group = "verification"

    dependsOn("downloadAppCrawler", ":app:assembleRelease")

    val apkFile = file("app/build/outputs/apk/release/app-release.apk")
    val androidSdk = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "${System.getProperty("user.home")}/Android/Sdk"

    doFirst {
        require(apkFile.exists()) { "APK not found: $apkFile" }
        require(file(androidSdk).exists()) { "Android SDK not found: $androidSdk" }
        println("Running App Crawler on: $apkFile")
        println("Using Android SDK: $androidSdk")
    }

    workingDir = appCrawlerDir
    commandLine(
        "java", "-jar", "crawl_launcher.jar",
        "--apk-file", apkFile.absolutePath,
        "--android-sdk", androidSdk,
        "--crawl-steps", "50"
    )
}
