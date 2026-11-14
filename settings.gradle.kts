pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
// AGP, lint and device-test tooling still bring vulnerable crypto/TLS transitive libraries.
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
