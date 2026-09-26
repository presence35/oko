import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionPropsFile = file("version.properties")

fun readVersionProps(): Properties = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}

/** Release signing credentials live in app/keystore.properties (git-ignored). */
fun readKeystoreProps(): Properties = Properties().apply {
    val f = file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** CARTO basemap API key lives in app/carto.properties (git-ignored). */
val cartoApiKey: String = Properties().apply {
    val f = file("carto.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("cartoApiKey") ?: ""

android {
    namespace = "com.odesaplay.oko"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.odesaplay.oko"
        minSdk = 26
        targetSdk = 36
        versionCode = (readVersionProps().getProperty("versionCode") ?: "1").toIntOrNull() ?: 1
        versionName = readVersionProps().getProperty("versionName") ?: "0.1.0"
        buildConfigField("String", "CARTO_API_KEY", "\"$cartoApiKey\"")
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
        resourceConfigurations += listOf("en", "uk", "ru")
    }

    // Two distribution channels from one codebase:
    //  - play     → Google Play. No self-update: the APK-download/install path and its
    //               REQUEST_INSTALL_PACKAGES permission are not compiled in (R8 strips the
    //               dead branches; the permission/provider live only in the sideload manifest).
    //  - sideload → the beta APK feed on odesaplay.com.ua, with in-app self-update intact.
    flavorDimensions += "channel"
    productFlavors {
        create("play") {
            dimension = "channel"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
        create("sideload") {
            dimension = "channel"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val ksPropsFile = file("keystore.properties")
            if (!ksPropsFile.exists()) {
                throw GradleException("Missing $ksPropsFile — create it with storeFile, storePassword, keyAlias, keyPassword (it is git-ignored).")
            }
            val ks = Properties().apply { ksPropsFile.inputStream().use { load(it) } }
            storeFile = file(ks.getProperty("storeFile") ?: throw GradleException("keystore.properties: missing 'storeFile'"))
            storePassword = ks.getProperty("storePassword") ?: throw GradleException("keystore.properties: missing 'storePassword'")
            keyAlias = ks.getProperty("keyAlias") ?: throw GradleException("keystore.properties: missing 'keyAlias'")
            keyPassword = ks.getProperty("keyPassword") ?: throw GradleException("keystore.properties: missing 'keyPassword'")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            res.srcDirs("src/main/res", "src/main/iconpacks/classic", "src/main/iconpacks/photo", "src/main/iconpacks/army", "src/main/iconpacks/comic", "src/main/iconpacks/russian")
        }
    }

    splits {
        abi {
            isEnable = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")

    // MapLibre GL Native
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    // WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20240303")

    // Local prefs for zone toggles
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager — periodic watchdog for process-kill resilience
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Home-screen widget
    implementation("androidx.glance:glance-appwidget:1.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.register("bumpVersion") {
    group = "versioning"
    description = "Increments versionCode and derives versionName as major.minor.versionCode. Optional: -PnewVersion=X.Y.Z overrides the name."
    doLast {
        val props = Properties()
        if (versionPropsFile.exists()) versionPropsFile.inputStream().use { props.load(it) }
        val currentCode = (props.getProperty("versionCode") ?: "0").toIntOrNull() ?: 0
        val currentName = props.getProperty("versionName") ?: ""
        val requested = project.findProperty("newVersion")?.toString()?.takeIf { it.isNotBlank() }
        val newCode = currentCode + 1
        val newName = requested ?: deriveVersionName(currentName, newCode)
        props.setProperty("versionCode", newCode.toString())
        props.setProperty("versionName", newName)
        versionPropsFile.outputStream().use { props.store(it, "Bumped via gradlew bumpVersion") }
        println("versionCode: $currentCode -> $newCode")
        println("versionName: ${currentName.ifEmpty { "(unset)" }} -> $newName")
    }
}

private fun deriveVersionName(current: String, newCode: Int): String {
    val parts = current.split('.')
    val major = parts.getOrNull(0) ?: "0"
    val minor = parts.getOrNull(1) ?: "6"
    return "$major.$minor.$newCode"
}

tasks.register<GradleBuild>("releaseDirect") {
    group = "release"
    description = "Bumps the version, then builds the sideload (direct/beta) APK and uploads it + version.json to the FTP server, in a fresh Gradle run so both carry the new version."
    dependsOn("bumpVersion")
    dir = rootProject.projectDir
    tasks = listOf(":app:uploadRelease")
}

tasks.register<GradleBuild>("releasePlay") {
    group = "release"
    description = "Bumps the version, then builds the Google Play App Bundle (play flavor — no self-update). Does not upload."
    dependsOn("bumpVersion")
    dir = rootProject.projectDir
    tasks = listOf(":app:bundlePlayRelease")
}

tasks.register("uploadRelease") {
    group = "versioning"
    description = "Builds the beta-release APK (sideload flavor), generates version.json from version.properties + CHANGELOG.md, and uploads both to the FTP server."
    dependsOn("assembleSideloadRelease")
    doLast {
        val uploadPropsFile = file("upload.properties")
        if (!uploadPropsFile.exists()) {
            throw GradleException("Missing $uploadPropsFile — create it with host, user, password, remoteDir (it is git-ignored).")
        }
        val up = Properties().apply { uploadPropsFile.inputStream().use { load(it) } }
        val host = up.getProperty("host") ?: throw GradleException("upload.properties: missing 'host'")
        val user = up.getProperty("user") ?: throw GradleException("upload.properties: missing 'user'")
        val pass = up.getProperty("password") ?: throw GradleException("upload.properties: missing 'password'")
        val remoteDir = up.getProperty("remoteDir").orEmpty().trim().trim('/')

        val apk = file("build/outputs/apk/sideload/release/app-sideload-release.apk")
        if (!apk.exists()) throw GradleException("Sideload release APK not found: $apk")

        val vProps = Properties().apply { versionPropsFile.inputStream().use { load(it) } }
        val vc = vProps.getProperty("versionCode") ?: "0"
        val vn = vProps.getProperty("versionName") ?: "0.0.0"
        val (notesEn, notesUa, notesRu) = buildNotesFromChangelog()

        val versionJson = buildString {
            appendLine("{")
            append("  \"versionCode\": ").append(vc).appendLine(",")
            append("  \"versionName\": \"").append(escapeJson(vn)).appendLine("\",")
            append("  \"apkUrl\": \"https://").append(host).append("/other_apps/ukrainedrones/app-release.apk\",").appendLine()
            appendLine("  \"notes\": {")
            append("    \"en\": \"").append(escapeJson(notesEn)).appendLine("\",")
            append("    \"ua\": \"").append(escapeJson(notesUa)).appendLine("\",")
            append("    \"ru\": \"").append(escapeJson(notesRu)).appendLine("\"")
            appendLine("  }")
            appendLine("}")
        }
        val jsonFile = file("build/release/version.json")
        jsonFile.parentFile.mkdirs()
        jsonFile.writeText(versionJson, Charsets.UTF_8)

        fun ftpPath(fileName: String): String =
            if (remoteDir.isEmpty()) "ftp://$host/$fileName" else "ftp://$host/$remoteDir/$fileName"

        fun upload(local: File, remoteName: String) {
            val cmd = listOf(
                "curl", "-sS", "--ftp-create-dirs",
                "-T", local.absolutePath,
                ftpPath(remoteName),
                "--user", "$user:$pass"
            )
            val result = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = result.inputStream.readBytes().toString(Charsets.UTF_8)
            val code = result.waitFor()
            println("upload $remoteName -> exit $code")
            if (output.isNotBlank()) println(output)
            if (code != 0) throw GradleException("FTP upload of $remoteName failed (exit $code)")
        }

        upload(apk, "app-release.apk")
        upload(jsonFile, "version.json")
        val privacyFile = rootProject.file("privacy.html")
        if (privacyFile.exists()) upload(privacyFile, "privacy.html")
        println("Done. https://$host/other_apps/ukrainedrones/version.json")
    }
}

tasks.register("uploadPrivacy") {
    group = "versioning"
    description = "Uploads the privacy policy (privacy.html) to the FTP server."
    doLast {
        val uploadPropsFile = file("upload.properties")
        if (!uploadPropsFile.exists()) {
            throw GradleException("Missing $uploadPropsFile — create it with host, user, password, remoteDir (it is git-ignored).")
        }
        val up = Properties().apply { uploadPropsFile.inputStream().use { load(it) } }
        val host = up.getProperty("host") ?: throw GradleException("upload.properties: missing 'host'")
        val user = up.getProperty("user") ?: throw GradleException("upload.properties: missing 'user'")
        val pass = up.getProperty("password") ?: throw GradleException("upload.properties: missing 'password'")
        val remoteDir = up.getProperty("remoteDir").orEmpty().trim().trim('/')

        val local = rootProject.file("privacy.html")
        if (!local.exists()) throw GradleException("privacy.html not found: $local")
        val path = if (remoteDir.isEmpty()) "ftp://$host/privacy.html" else "ftp://$host/$remoteDir/privacy.html"

        val result = ProcessBuilder(
            listOf("curl", "-sS", "--ftp-create-dirs", "-T", local.absolutePath, path, "--user", "$user:$pass")
        ).redirectErrorStream(true).start()
        val output = result.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = result.waitFor()
        if (output.isNotBlank()) println(output)
        if (code != 0) throw GradleException("FTP upload of privacy.html failed (exit $code)")
        println("Privacy policy: https://$host/other_apps/ukrainedrones/privacy.html")
    }
}

private fun escapeJson(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

private fun buildNotesFromChangelog(): Triple<String, String, String> {
    // The changelog lives at the repo root; this script runs in the :app project dir,
    // so a bare file("CHANGELOG.md") would resolve to app/CHANGELOG.md (absent) and
    // silently fall back to empty notes on every release.
    val changelog = rootProject.file("CHANGELOG.md").takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
    val lines = changelog.lines()
    val start = lines.indexOfFirst { it.trim() == "## [Unreleased]" }
    if (start < 0) return fallbackNotes()
    val bullets = lines.drop(start + 1)
        .takeWhile { !it.trim().startsWith("## ") }
        .map { it.trim() }
        .filter { it.startsWith("- ") }
    // Entries are EN-only. UA/RU fall back to EN until real translations land.
    val notes = bullets.map { bullet ->
        bullet.removePrefix("- ").trim()
            .replace(Regex("\\s+\\d{2}-\\d{2}_\\d{2}:\\d{2}:\\d{2}$"), "")
            .trim()
    }
    return if (notes.any { it.isNotBlank() }) {
        val joined = notes.joinToString("\n")
        Triple(joined, joined, joined)
    } else fallbackNotes()
}

private fun fallbackNotes(): Triple<String, String, String> = Triple(
    file("notes_en.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim().orEmpty(),
    file("notes_ua.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim().orEmpty(),
    file("notes_ru.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim().orEmpty()
)
