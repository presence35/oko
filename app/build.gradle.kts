import java.util.Properties
import java.security.MessageDigest

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

/** A build with no app/keystore.properties is only ever correct for a source build that the
 *  distributor signs itself — F-Droid compiles the store flavor this way. Everywhere else it
 *  is a mistake, so it must be asked for explicitly rather than happening silently. */
val hasReleaseKeystore = file("keystore.properties").exists()
val unsignedReleaseAllowed = project.hasProperty("allowUnsignedRelease")

android {
    namespace = "com.odesaplay.oko"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.odesaplay.oko"
        minSdk = 26
        targetSdk = 36
        versionCode = (readVersionProps().getProperty("versionCode") ?: "1").toIntOrNull() ?: 1
        versionName = readVersionProps().getProperty("versionName") ?: "0.1.0"
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
        if (hasReleaseKeystore) {
            create("release") {
                val ks = readKeystoreProps()
                storeFile = file(ks.getProperty("storeFile") ?: throw GradleException("keystore.properties: missing 'storeFile'"))
                storePassword = ks.getProperty("storePassword") ?: throw GradleException("keystore.properties: missing 'storePassword'")
                keyAlias = ks.getProperty("keyAlias") ?: throw GradleException("keystore.properties: missing 'keyAlias'")
                keyPassword = ks.getProperty("keyPassword") ?: throw GradleException("keystore.properties: missing 'keyPassword'")
            }
        } else if (!unsignedReleaseAllowed) {
            throw GradleException(
                "Missing ${file("keystore.properties")} — create it with storeFile, storePassword, keyAlias, keyPassword (it is git-ignored). " +
                    "Pass -PallowUnsignedRelease only for a source build that the distributor signs itself (F-Droid)."
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Null on an unsigned source build (see hasReleaseKeystore) — AGP then emits
            // app-<flavor>-release-unsigned.apk for the distributor to sign.
            signingConfig = signingConfigs.findByName("release")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    // 2.10+ ships R8-full-mode-correct consumer rules; 2.9.1 relied on AGP<9's
    // implicit constructor retention and crashed on startup under AGP 9.
    implementation("androidx.work:work-runtime-ktx:2.11.0")

    // Home-screen widget
    implementation("androidx.glance:glance-appwidget:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // Robolectric lets Android-bound units (SharedPreferences, Context, system services) run as
    // plain JVM tests. SDK is pinned in src/test/resources/robolectric.properties.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Compose UI tests, run on the JVM through Robolectric: the intensity picker and the wizard
    // pages are pure UI logic that no amount of domain testing can reach. Versionless — the
    // compose-bom platform above aligns them.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.work:work-testing:2.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Unit tests gate every build: a change that breaks them fails assembly — and therefore the
// release tasks — instead of surfacing later. ~20s warm; Robolectric's first run downloads its
// android-all jars once. Hooked onto assemble* rather than preBuild: preBuild is an ancestor of
// the compile tasks, so a dependency there would be a cycle.
tasks.matching { it.name == "assemble" || (it.name.startsWith("assemble") && !it.name.startsWith("assembleAndroidTest")) }
    .configureEach {
        dependsOn("testSideloadDebugUnitTest")
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

/** Publishing is always our own signature. The unsigned path exists only for a distributor
 *  building from source, so it must never reach the release tasks. */
tasks.register("requireReleaseKeystore") {
    group = "versioning"
    description = "Fails unless app/keystore.properties is present. Guards every publishing path against the unsigned source-build mode."
    doLast {
        if (!hasReleaseKeystore) throw GradleException(
            "app/keystore.properties is required to publish. -PallowUnsignedRelease is only for a distributor building and signing from source."
        )
    }
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
    dependsOn("bumpVersion", "requireReleaseKeystore")
    dir = rootProject.projectDir
    tasks = listOf(":app:bundlePlayRelease")
}

tasks.register("uploadRelease") {
    group = "versioning"
    description = "Builds the beta-release APK (sideload flavor), generates version.json (version + sha256 read from the built APK, notes from CHANGELOG.md), uploads both, then verifies the live copy."
    dependsOn("requireReleaseKeystore", "assembleSideloadRelease")
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
        val ftpHost = up.getProperty("ftpHost")?.trim()
            ?: throw GradleException("upload.properties: missing 'ftpHost' — the FTPS server host whose certificate is valid (e.g. athena.ukrhost.biz).")

        // The APK is the source of truth for versionCode/versionName: version.properties is
        // bumped before the build and could describe a different artifact than the one we are
        // about to publish. AGP writes the built values into output-metadata.json.
        val metadataFile = file("build/outputs/apk/sideload/release/output-metadata.json")
        if (!metadataFile.exists()) throw GradleException("Missing build metadata: $metadataFile")
        @Suppress("UNCHECKED_CAST")
        val element = ((groovy.json.JsonSlurper().parse(metadataFile) as Map<String, Any>)["elements"] as? List<Any>)
            ?.firstOrNull() as? Map<String, Any>
            ?: throw GradleException("No APK entry in $metadataFile")
        val vc = (element["versionCode"] as Number).toInt()
        val vn = element["versionName"].toString()
        val builtApk = file("build/outputs/apk/sideload/release/${element["outputFile"]}")
        if (!builtApk.exists()) throw GradleException("Built APK not found: $builtApk")

        val vProps = readVersionProps()
        val propsCode = (vProps.getProperty("versionCode") ?: "").toIntOrNull()
        val propsName = vProps.getProperty("versionName")
        if (propsCode != vc || propsName != vn) {
            throw GradleException(
                "Stale build: version.properties says $propsName ($propsCode) but the APK is $vn ($vc) — rebuild before releasing."
            )
        }
        val sha256 = sha256Of(builtApk)
        val (notesEn, notesUa, notesRu) = buildNotesFromChangelog()

        val publicDir = if (remoteDir.isEmpty()) "" else "$remoteDir/"
        // Derived from the upload target so the URL the client verifies against cannot drift
        // from where the file actually lands.
        val apkUrl = "https://$host/${publicDir}app-release.apk"

        val versionJson = buildString {
            appendLine("{")
            append("  \"versionCode\": ").append(vc).appendLine(",")
            append("  \"versionName\": \"").append(escapeJson(vn)).appendLine("\",")
            append("  \"apkUrl\": \"").append(apkUrl).appendLine("\",")
            append("  \"sha256\": \"").append(sha256).appendLine("\",")
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

        // APK first, manifest second: a client must never read a new versionCode while the old
        // file is still what that URL serves. The reverse order is unsafe.
        ftpUpload(ftpHost, remoteDir, user, pass, builtApk, "app-release.apk")
        ftpUpload(ftpHost, remoteDir, user, pass, jsonFile, "version.json")
        val privacyFile = rootProject.file("privacy.html")
        if (privacyFile.exists()) ftpUpload(ftpHost, remoteDir, user, pass, privacyFile, "privacy.html")

        // Prove the server serves what we just built. Without this, a bad upload only shows up
        // as an app that silently refuses to install, forever. FTP→HTTPS propagation can lag,
        // so retry before calling it broken.
        //
        // A mismatch is re-uploaded, not just reported. The manifest is the file every client
        // reads to decide whether it is up to date, and a failed manifest upload leaves the server
        // holding a newer APK behind an older manifest — every tester sees "up to date" and stays
        // on the old build until the *next* release repairs it. Re-sending the file we already
        // have locally costs one upload and closes that window instead of leaving it open.
        val liveUrl = "https://$host/${publicDir}version.json"
        var live: Map<*, *>? = null
        for (attempt in 0 until 3) {
            live = fetchLiveJson(liveUrl)
            val ok = live != null &&
                live["sha256"]?.toString() == sha256 &&
                (live["versionCode"] as? Number)?.toInt() == vc
            if (ok || attempt == 2) break
            Thread.sleep(3_000)
            if (attempt == 0) {
                println("Live manifest does not match the build — re-uploading version.json")
                runCatching { ftpUpload(ftpHost, remoteDir, user, pass, jsonFile, "version.json") }
                    .onFailure { println("Re-upload failed: ${it.message}") }
            }
        }
        val liveSha = live?.get("sha256")?.toString()
        val liveVc = (live?.get("versionCode") as? Number)?.toInt()
        if (liveSha != sha256 || liveVc != vc) {
            throw GradleException("Post-upload check FAILED at $liveUrl: serves versionCode=$liveVc sha256=$liveSha, expected $vc / $sha256")
        }
        println("Verified live: $liveUrl (versionCode=$vc)")
        println("Done. $liveUrl")
    }
}

private fun fetchLiveJson(url: String): Map<*, *>? = runCatching {
    val proc = ProcessBuilder("curl", "-sS", "--fail", url).redirectErrorStream(true).start()
    val body = proc.inputStream.readBytes().toString(Charsets.UTF_8)
    if (proc.waitFor() != 0) return@runCatching null
    groovy.json.JsonSlurper().parseText(body) as? Map<*, *>
}.getOrNull()

private fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            digest.update(buf, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// Explicit FTPS (AUTH TLS) against the host whose certificate is valid; implicit FTPS
// (curl's ftps:// scheme, port 990) is not exposed by the server. Credentials travel
// through curl's --config on stdin so the password never appears in the process argv.
private fun ftpUpload(ftpHost: String, remoteDir: String, user: String, pass: String, local: File, remoteName: String) {
    val path = if (remoteDir.isEmpty()) "ftp://$ftpHost/$remoteName" else "ftp://$ftpHost/$remoteDir/$remoteName"
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    val config = "user = \"${esc(user)}:${esc(pass)}\"\n"
    val proc = ProcessBuilder(
        "curl", "-sS", "--ssl-reqd", "--ftp-create-dirs", "--config", "-",
        "-T", local.absolutePath, path
    ).redirectErrorStream(true).start()
    proc.outputStream.use { it.write(config.toByteArray(Charsets.UTF_8)) }
    val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
    val code = proc.waitFor()
    println("upload $remoteName -> exit $code")
    if (output.isNotBlank()) println(output)
    if (code != 0) throw GradleException("FTP upload of $remoteName failed (exit $code)")
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
        val ftpHost = up.getProperty("ftpHost")?.trim()
            ?: throw GradleException("upload.properties: missing 'ftpHost' — the FTPS server host whose certificate is valid (e.g. athena.ukrhost.biz).")

        val local = rootProject.file("privacy.html")
        if (!local.exists()) throw GradleException("privacy.html not found: $local")

        ftpUpload(ftpHost, remoteDir, user, pass, local, "privacy.html")
        println("Privacy policy: https://$host/other_apps/oko/privacy.html")
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
