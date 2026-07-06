plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "com.grimrg"
version = "0.9.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        rider("2026.1.2") {
            useInstaller = false
        }
        bundledPlugin("com.jetbrains.rider-cpp")
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
        changeNotes = """
            <b>0.9.1</b>
            <ul>
              <li>Fix: Several issues when multiple projects are opened.</li>
            </ul>
            <b>0.9.0</b> - initial release for closed testing.
            <ul>
              <li>Main-toolbar widget and roll-out editor for Unreal Engine commandline presets.</li>
              <li>Groups with drag-and-drop reordering of options within and across groups.</li>
              <li>Tick-order concatenation of enabled entries.</li>
            </ul>
        """.trimIndent()
    }
}
