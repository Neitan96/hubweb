import java.util.Properties

// Assinatura de release: lida de keystore.properties (fora do controle de versão).
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Build da empresa (-Pempresa): lê empresa.properties (fora do controle de versão) com configUrl, token e sufixo do nome
// do APK. O app grava URL e token nas Configurações na primeira abertura (e a autoatualização continua ligada: o APK
// público atualiza por cima e os dados ficam). Sem -Pempresa, o build é o público, sem URL nem token.
val empresa = project.hasProperty("empresa")
val empProps = Properties().apply {
    val f = rootProject.file("empresa.properties")
    if (empresa) {
        require(f.exists()) { "Falta empresa.properties (veja empresa.properties.example)" }
        f.inputStream().use { load(it) }
    }
}
fun String.paraBuildConfig() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Documentação exibida no app (Configurações → Documentação / Exemplo de JSON): copia o README e o
// exemplo do projeto para os assets, para o app mostrar sempre o mesmo texto do repositório.
val copyDocs by tasks.registering(Copy::class) {
    from(rootProject.file("README.md"), rootProject.file("exemplo-apps.json"), rootProject.file("exemplo-dicas.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
// Os assets só são montados depois da cópia.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(copyDocs) }
// O lint (inclusive o do release) também lê a pasta de assets.
tasks.matching { it.name.contains("Lint", ignoreCase = true) }.configureEach { dependsOn(copyDocs) }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "br.com.hubweb"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.hubweb"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "1.0.14"
        buildConfigField("String", "EMPRESA_URL", (empProps.getProperty("configUrl") ?: "").trim().paraBuildConfig())
        buildConfigField("String", "EMPRESA_TOKEN", (empProps.getProperty("token") ?: "").trim().paraBuildConfig())
    }

    // Nome do arquivo: "HubWeb <versão>.apk" (release); nos demais tipos acrescenta o nome do tipo.
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                listOfNotNull(
                    "HubWeb ${variant.versionName}",
                    empProps.getProperty("sufixo")?.trim()?.takeIf { empresa && it.isNotEmpty() },
                    variant.buildType.name.takeIf { it != "release" },
                ).joinToString(" ") + ".apk"
        }
    }

    signingConfigs {
        create("release") {
            if (ksProps.isNotEmpty()) {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sem R8: não consigo testar a versão ofuscada em aparelho aqui; o APK fica maior, porém previsível.
            isMinifyEnabled = false
            if (ksProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    // Leitor de códigos de barras e QR pela câmera (ZXing, Apache 2.0; funciona offline)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
