import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

compose.desktop {
    application {
        mainClass = "dev.netmtr.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "SpectrIT-NetMTR"
            packageVersion = "1.0.0"
            description = "Spectr IT NetMTR"
            vendor = "Spectr IT"
            windows {
                menuGroup = "Spectr IT"
                upgradeUuid = "7c2a1e4b-6d8f-4a1c-9b3e-2f5a8c1d4e70"
            }
        }
    }
}
