import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

/** Telegram bot credentials live in app/telegram.properties (git-ignored). */
val telegramProps: Properties = Properties().apply {
    val f = file("telegram.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val telegramBotToken: String = telegramProps.getProperty("botToken") ?: ""
val telegramChatId: String = telegramProps.getProperty("chatId") ?: ""

android {
    namespace = "ua.ukrainedrones"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.ukrainedrones"
        minSdk = 26
        targetSdk = 35
        versionCode = (readVersionProps().getProperty("versionCode") ?: "1").toIntOrNull() ?: 1
        versionName = readVersionProps().getProperty("versionName") ?: "0.1.0"
        buildConfigField("String", "CARTO_API_KEY", "\"$cartoApiKey\"")
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"$telegramBotToken\"")
        buildConfigField("String", "TELEGRAM_CHAT_ID", "\"$telegramChatId\"")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            res.srcDirs("src/main/res", "src/main/iconpacks/classic", "src/main/iconpacks/photo", "src/main/iconpacks/army", "src/main/iconpacks/comic", "src/main/iconpacks/russian")
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

configurations.all {
    resolutionStrategy {
        // coil 2.7.0 asks for kotlin-stdlib 2.0.0, but we compile with Kotlin 1.9.24;
        // force keeps the stdlib on the compiler's own version.
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // OSMdroid — free, no API key, no Google account needed
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Image loading (Wikimedia Commons photos in the threat popup)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // JSON parsing
    implementation("org.json:json:20240303")

    // Local prefs for zone toggles
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Home-screen widget
    implementation("androidx.glance:glance-appwidget:1.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.register("bumpVersion") {
    group = "versioning"
    description = "Increments versionCode in app/version.properties and auto-bumps the patch of versionName (e.g. 0.3.8 -> 0.3.9). Optional: -PnewVersion=X.Y.Z overrides the name."
    doLast {
        val props = Properties()
        if (versionPropsFile.exists()) versionPropsFile.inputStream().use { props.load(it) }
        val currentCode = (props.getProperty("versionCode") ?: "0").toIntOrNull() ?: 0
        val currentName = props.getProperty("versionName") ?: ""
        val requested = project.findProperty("newVersion")?.toString()?.takeIf { it.isNotBlank() }
        val newName = requested ?: autoBumpPatch(currentName)
        props.setProperty("versionCode", (currentCode + 1).toString())
        props.setProperty("versionName", newName)
        versionPropsFile.outputStream().use { props.store(it, "Bumped via gradlew bumpVersion") }
        println("versionCode: $currentCode -> ${currentCode + 1}")
        println("versionName: ${currentName.ifEmpty { "(unset)" }} -> $newName")
    }
}

private fun autoBumpPatch(name: String): String {
    val parts = name.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size < 3) return name
    return "${parts[0]}.${parts[1]}.${parts[2] + 1}"
}

tasks.register<GradleBuild>("release") {
    group = "versioning"
    description = "Bumps the version, then builds the release APK and uploads it in a fresh Gradle run so the APK and version.json both carry the new version. Release notes come from notes_en.txt / notes_ua.txt."
    dependsOn("bumpVersion")
    dir = rootProject.projectDir
    tasks = listOf(":app:uploadRelease")
}

tasks.register("uploadRelease") {
    group = "versioning"
    description = "Builds the release APK, generates version.json from version.properties + notes files, and uploads both to the FTP server."
    dependsOn("assembleRelease")
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

        val apk = file("build/outputs/apk/release/app-release.apk")
        if (!apk.exists()) throw GradleException("Release APK not found: $apk")

        val vProps = Properties().apply { versionPropsFile.inputStream().use { load(it) } }
        val vc = vProps.getProperty("versionCode") ?: "0"
        val vn = vProps.getProperty("versionName") ?: "0.0.0"
        val notesEn = file("notes_en.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim().orEmpty()
        val notesUa = file("notes_ua.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim().orEmpty()

        val versionJson = buildString {
            appendLine("{")
            append("  \"versionCode\": ").append(vc).appendLine(",")
            append("  \"versionName\": \"").append(escapeJson(vn)).appendLine("\",")
            append("  \"apkUrl\": \"https://").append(host).append("/other_apps/ukrainedrones/app-release.apk\",").appendLine()
            appendLine("  \"notes\": {")
            append("    \"en\": \"").append(escapeJson(notesEn)).appendLine("\",")
            append("    \"ua\": \"").append(escapeJson(notesUa)).appendLine("\"")
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
        println("Done. https://$host/other_apps/ukrainedrones/version.json")
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
