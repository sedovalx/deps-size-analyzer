package com.github.sedovalx.deps.analyzer.model

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.lang.IllegalArgumentException
import java.lang.RuntimeException

enum class DependencyScope {
    COMPILE,
    PROVIDED,
    RUNTIME,
    TEST,
    SYSTEM,
    IMPORT
}

data class MavenDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val scope: DependencyScope?
) {
    companion object {
        private val VERSION_PLACEHOLDER = "\\$\\{([^}]+)}".toRegex()
    }

    override fun toString(): String {
        return gradleId + if (scope != null) " ($scope)" else ""
    }

    val gradleId: String get() = "$artifactName:$version"

    val artifactName: String get() = "$groupId:$artifactId"

    val versionPlaceholder: String?
        get() {
            if (version == null) return null

            val result = VERSION_PLACEHOLDER.matchEntire(version)
            return if (result != null) {
                val (placeholder) = result.destructured
                placeholder
            } else {
                null
            }
        }
}

data class DependencyManagement(val dependencies: Iterable<MavenDependency> = emptyList())

@JacksonXmlRootElement(localName = "project")
data class MavenProject(
    val groupId: String?,
    val artifactId: String,
    val version: String?,
    val parent: MavenDependency?,
    val properties: Map<String, String> = emptyMap(),
    val dependencies: Iterable<MavenDependency> = emptyList(),
    val dependencyManagement: DependencyManagement?
) {
    val inheritedGroup: String? get() = groupId ?: parent?.groupId
    val inheritedVersion: String? get() = version ?: parent?.version

    val gradleId: String get() = "$inheritedGroup:$artifactId:$inheritedVersion"
}

open class DependencyResolutionException(message: String, cause: Exception? = null) : RuntimeException(message, cause)
class DependencyNotFoundException(dependencyId: String) : DependencyResolutionException("Dependency $dependencyId is not found")
class DependencyDownloadException(dependencyId: String, cause: Exception) : DependencyResolutionException("Failed to download $dependencyId", cause)
class UnresolvedDependencyException(projectId: String, dependencyId: String) : DependencyResolutionException("Dependency management of $projectId doesn't contain version of $dependencyId")
class NoVersionDependencyException(projectId: String, dependencyId: String) : DependencyResolutionException("Dependency management of $projectId has no version of $dependencyId")

class DepsClient(
    private val repositories: Set<String> = LinkedHashSet<String>(1).apply { add("https://repo1.maven.org/maven2") },
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val mapper: ObjectMapper = createDefaultXmlMapper()
) {
    companion object : KLogging() {
        private val DEPENDENCY_REGEX = "([^:]+):([^:]+):([^:]+)".toRegex()


        private fun createDefaultXmlMapper() = XmlMapper()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(KotlinModule())
    }

    fun flattenParents(project: MavenProject): MavenProject {
        return if (project.parent == null) {
            project
        } else {
            logger.trace { "Downloading the parent of ${project.gradleId}" }
            val (_, projectParent) = try {
                downloadPom(project.parent.gradleId)
            } catch (ex: Exception) {
                logger.trace { "Failed to download the parent of ${project.gradleId}: ${ex.message}" }
                throw DependencyDownloadException(project.parent.gradleId, ex)
            }

            val flattenedParent = flattenParents(projectParent)
            MavenProject(
                project.groupId ?: flattenedParent.groupId,
                project.artifactId,
                project.version ?: flattenedParent.version,
                null,
                flattenedParent.properties.plus(project.properties),
                project.dependencies + flattenedParent.dependencies,
                project.dependencyManagement + flattenedParent.dependencyManagement
            )
        }
    }

    private fun resolveDependencyManagement(project: MavenProject): DependencyManagement {
        if (project.dependencyManagement == null) {
            return DependencyManagement(emptyList())
        }

        val resolved = project.dependencyManagement.dependencies.map { dependency ->
            val placeholder = dependency.versionPlaceholder
            when {
                dependency.version == null -> {
                    logger.trace { "Can't find a version info of $dependency dependency" }
                    throw NoVersionDependencyException(project.gradleId, dependency.gradleId)
                }
                placeholder != null -> {
                    val version = project.properties[placeholder]
                    if (version != null) {
                        dependency.copy(version = version)
                    } else {
                        logger.trace { "Can't find a version info of $dependency dependency" }
                        throw NoVersionDependencyException(project.gradleId, dependency.gradleId)
                    }
                }
                else -> dependency
            }
        }

        return DependencyManagement(resolved)
    }

    fun resolveDependencyVersions(project: MavenProject): MavenProject {
        val (unresolved, resolved) = project.dependencies.partition { it.version == null || it.versionPlaceholder != null }
        return if (unresolved.isEmpty()) {
            project.copy(dependencyManagement = null)
        } else {
            val dependencyManagement = resolveDependencyManagement(project)
            val dependencyMap = dependencyManagement.dependencies.associateBy { it.artifactName }
            val found = unresolved.map {
                dependencyMap[it.artifactName] ?: throw UnresolvedDependencyException(project.gradleId, it.toString())
            }

            project.copy(dependencies = resolved + found, dependencyManagement = null)
        }
    }

//    fun collectDependencies(project: MavenProject): List<MavenDependency> {
//        val (unresolved, resolved) = project.dependencies.partition { it.version == null || it.versionPlaceholder != null }
//        return if (unresolved.isEmpty()) {
//            resolved
//        } else {
//
//        }
//    }
//
//    fun getManagedDependencies(project: MavenProject): List<MavenDependency> {
//        val managedDependencies = mutableListOf<MavenDependency>()
//        if (project.parent != null) {
//            val (_, parentProject) = try {
//                downloadPom(project.parent.dependencyGradleId)
//            } catch (ex: Exception) {
//                logger.error(ex) { "Failed to download parent project ${project.parent.dependencyGradleId}" }
//                throw ex
//            }
//
//            managedDependencies.addAll(getManagedDependencies(parentProject))
//        }
//
//        if (project.dependencyManagement != null) {
//            managedDependencies.addAll(
//                resolveDependencyVersions(project.identity, project.dependencyManagement.dependencies, project.properties)
//            )
//        }
//
//        return managedDependencies
//    }
//
//    fun resolveDependencyVersions(projectId: String, dependencies: Iterable<MavenDependency>, properties: Map<String, String>): List<MavenDependency> {
//        return dependencies.mapNotNull {
//            val placeholder = it.versionPlaceholder
//            when {
//                it.version == null -> {
//                    logger.warn { "Can't resolve version of $projectId dependency management item $it" }
//                    null
//                }
//                placeholder != null -> {
//                    val version = properties[placeholder]
//                    if (version != null) {
//                        it.copy(version = version)
//                    } else {
//                        logger.warn { "Can't resolve version of $projectId dependency management item $it. " +
//                                "Version placeholder is not found in the POM properties" }
//                        null
//                    }
//                }
//                else -> it
//            }
//        }
//    }

    fun getDependencySize(dependencyUrl: String): Long? {
        logger.trace { "Getting size of $dependencyUrl" }

        val request = Request.Builder()
            .url(dependencyUrl)
            .method("HEAD", null)
            .build()

        httpClient.newCall(request).execute().use {
            val size = it.header("Content-Length")?.toLongOrNull()
            logger.trace { "Got size of $dependencyUrl: $size bytes" }
            return size
        }
    }

    fun downloadPom(dependency: String): Pair<String, MavenProject> {
        logger.debug { "Downloading $dependency POM" }
        for (url in repositories) {
            val dependencyUrl = buildDependencyUrl(url, dependency)
            logger.trace { "Getting $dependencyUrl" }
            val response = try {
                val request = Request.Builder().url(dependencyUrl).build()
                httpClient.newCall(request).execute()
            } catch (ex: IOException) {
                logger.info(ex) { "Failed to fetch a pom from $dependencyUrl" }
                continue
            }

            if (response.isSuccessful) {
                val project = response.use {
                    it.body()?.byteStream()?.let { stream -> mapper.readValue<MavenProject>(stream) }
                }

                if (project != null) {
                    return dependencyUrl to project
                } else {
                    logger.trace { "Response on $dependencyUrl has an empty body" }
                }
            } else {
                logger.trace { "Request to $dependencyUrl resolved as ${response.code()}: ${response.message()}" }
            }
        }

        throw DependencyNotFoundException(dependency)
    }

    private fun buildDependencyUrl(repositoryUrl: String, dependency: String): String {
        val dependencyPath = DEPENDENCY_REGEX.matchEntire(dependency)?.let { matchResult ->
            val (group, artifact, version) = matchResult.destructured
            "/${group.replace(".", "/")}/$artifact/$version/$artifact-$version.pom"
        } ?: throw IllegalArgumentException("$dependency string doesn't comply the pattern $DEPENDENCY_REGEX")

        return repositoryUrl.trimEnd('/') + dependencyPath
    }

    private operator fun DependencyManagement?.plus(other: DependencyManagement?): DependencyManagement? {
        return when {
            this == null && other == null -> null
            this != null && other != null -> DependencyManagement(this.dependencies + other.dependencies)
            else -> this ?: other
        }
    }
}