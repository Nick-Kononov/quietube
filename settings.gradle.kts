pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
// Upstream tools and Media3 still request vulnerable libraries; cover plugin and project resolution.
gradle.beforeProject {
    val patchSecurityDependencies: Configuration.() -> Unit = {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "org.bouncycastle" && requested.name in setOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on") -> {
                    useVersion("1.85")
                    because("Fix CVE-2026-8763 and CVE-2025-14813; keep Bouncy Castle modules aligned")
                }
                requested.group == "io.netty" && requested.name.startsWith("netty-") && !requested.name.startsWith("netty-tcnative") -> {
                    useVersion("4.1.137.Final")
                    because("Fix CVE-2026-75595; keep the existing Netty 4.1 modules aligned")
                }
                requested.group == "com.google.guava" && requested.name == "guava" -> {
                    useVersion(if (requested.version?.endsWith("-android") == true) "33.7.2-android" else "33.7.2-jre")
                    because("Fix CVE-2026-102554 while preserving the Android/JRE variant")
                }
                requested.group == "org.bitbucket.b_c" && requested.name == "jose4j" -> {
                    useVersion("0.9.6")
                    because("Fix CVE-2024-29371 in bundletool's JWE dependency")
                }
                requested.group == "org.jdom" && requested.name == "jdom2" -> {
                    useVersion("2.0.6.1")
                    because("Fix CVE-2021-33813 in Jetifier's XML dependency")
                }
                requested.group == "org.apache.commons" && requested.name == "commons-lang3" -> {
                    useVersion("3.18.0")
                    because("Fix CVE-2025-48924 in Android SDK tooling")
                }
                requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> {
                    useVersion("4.5.14")
                    because("Fix CVE-2020-13956 in Android tooling's HTTP client")
                }
            }
        }
    }
    buildscript.configurations.configureEach(patchSecurityDependencies)
    configurations.configureEach(patchSecurityDependencies)
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "Quietube"
include(":app")
