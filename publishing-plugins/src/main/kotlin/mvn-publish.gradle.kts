import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.`maven-publish`
import org.gradle.kotlin.dsl.signing
import java.util.*

plugins {
    `maven-publish`
    signing
}

// Stub secrets to let the project sync and build without the publication values set up
ext["ossrhUsername"] = null
ext["ossrhPassword"] = null

fun loadSecrets(secretPropsFile: File) {
    if (secretPropsFile.exists()) {
        secretPropsFile.reader().use {
            Properties().apply {
                load(it)
            }
        }.onEach { (name, value) ->
            ext[name.toString()] = value
        }
    }
}

// Grabbing secrets from gradle.properties file or from environment variables, which could be used on CI
loadSecrets(project.rootProject.file("gradle.properties"))
loadSecrets(File("${project.gradle.gradleUserHomeDir}/gradle.properties"))

fun getExtraString(name: String) = ext[name]?.toString()

// If not release build add SNAPSHOT suffix
fun getVersionName() =
    if (hasProperty("release"))
        getExtraString("VERSION_NAME")
    else
        getExtraString("VERSION_NAME") + "-SNAPSHOT"

afterEvaluate {
    configure<PublishingExtension> {
        // Configure all publications
        publications.create<MavenPublication>("test") {
            groupId = getExtraString("GROUP")
            artifactId = getExtraString("POM_ARTIFACT_ID")
            version = getVersionName()

            artifact("$buildDir/outputs/aar/${project.getName()}-release.aar")
            artifact(tasks.named<Jar>("withJavadocJar"))
            artifact(tasks.named<Jar>("withSourcesJar"))

            // Provide artifacts information requited by Maven Central
            pom {
                name.set(getExtraString("POM_NAME"))
                description.set(getExtraString("POM_DESCRIPTION"))
                url.set(getExtraString("POM_URL"))

                licenses {
                    license {
                        name.set(getExtraString("POM_LICENCE_NAME"))
                        url.set(getExtraString("POM_LICENCE_URL"))
                        distribution.set(getExtraString("POM_LICENCE_DIST"))
                    }
                }

                developers {
                    developer {
                        id.set(getExtraString("POM_DEVELOPER_ID"))
                        name.set(getExtraString("POM_DEVELOPER_NAME"))
                    }
                }

                scm {
                    url.set(getExtraString("POM_SCM_URL"))
                    connection.set(getExtraString("POM_SCM_CONNECTION"))
                    developerConnection.set(getExtraString("POM_SCM_DEV_CONNECTION"))
                }

                withXml {
                    val dependenciesNode = asNode().appendNode("dependencies")
                    configurations.getByName("implementation") {
                        dependencies.forEach {
                            val dependencyNode = dependenciesNode.appendNode("dependency")
                            dependencyNode.appendNode("groupId", it.group)
                            dependencyNode.appendNode("artifactId", it.name)
                            dependencyNode.appendNode("version", it.version)
                        }
                    }
                }
            }
        }
    }

    signing {
        val pgpKeyContent = System.getenv("SIGNING_PRIVATE_KEY_BASE64")
        if (pgpKeyContent != null) {
            useInMemoryPgpKeys(
                System.getenv("SIGNING_KEY_ID"),
                String(Base64.getDecoder().decode(pgpKeyContent)),
                System.getenv("SIGNING_KEY_PASSWORD")
            )
        }
        sign(publishing.publications)
    }
}


tasks.getByName("publish") {
    dependsOn("build")
}

// NOTE: appsflyer uses mustRunAfter(tasks.matching { it.name.startsWith("sign") }) here, but that
// forces Gradle to realize every task in the project to evaluate the predicate, which here trips
// over AGP's lazily-registered l8DexDesugarLibDebugAndroidTest task (coreLibraryDesugaring has no
// dependencies configured, unrelated to signing). The publication is named "test", so its sign
// task's name is deterministic -- reference it directly to avoid forcing unrelated tasks to realize.
tasks.matching { it.name.startsWith("publish") && it.name.contains("Publication") }.configureEach {
    mustRunAfter("signTestPublication")
}

tasks.getByName("publishToMavenLocal") {
    dependsOn("build")
}

tasks.getByName("publishToSonatype") {
    dependsOn("publish")
}

tasks.whenTaskAdded {
    if (name.startsWith("publishTestPublicationTo")) {
        dependsOn("bundleReleaseAar")
    }
    if (name.startsWith("sign") && name.contains("Publication")) {
        mustRunAfter("bundleReleaseAar")
    }
}

