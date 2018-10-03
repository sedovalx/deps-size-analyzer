package com.github.sedovalx.deps.analyzer.model

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.StringWriter
import java.io.Writer
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
    val properties: Map<String, String>?,
    val dependencies: Iterable<MavenDependency>?,
    val dependencyManagement: DependencyManagement?
) {
    val inheritedGroup: String? get() = groupId ?: parent?.groupId
    val inheritedVersion: String? get() = version ?: parent?.version

    val gradleId: String get() = "$inheritedGroup:$artifactId:$inheritedVersion"
}

open class DependencyResolutionException(message: String, cause: Exception? = null) : RuntimeException(message, cause)
class DependencyNotFoundException(dependencyId: String) : DependencyResolutionException("Dependency $dependencyId is not found")
class DependencyDownloadException(dependencyId: String, cause: Exception) : DependencyResolutionException("Failed to download $dependencyId", cause)
class NoVersionDependencyException(projectId: String, dependencyId: String) : DependencyResolutionException("Dependency management of $projectId has no version of $dependencyId")
class IllegalDependencyFormatException(dependency: String) : DependencyResolutionException("Format of $dependency is invalid. Please use the Gradle notation.")
class DependencyPomParsingException(dependencyId: String, cause: JsonProcessingException) : DependencyResolutionException("Can't parse $dependencyId POM", cause)
class DependencyPomEmptyException(dependencyId: String) : DependencyResolutionException("Downloaded $dependencyId POM is empty")

data class DependencyAnalyzeResult(
    val dependency: MavenDependency,
    val pomUrl: String,
    val children: Set<DependencyAnalyzeResult>,
    val size: Long
) {
    val totalSize: Long by lazy {
        children.fold(size) { sum, item -> sum + item.totalSize }
    }

    fun print(writer: Writer, printTotals: Boolean = false, tabulation: String = "") {
        writer.write(tabulation + dependency.gradleId + " ($size)\n")
        children.forEach {
            it.print(writer, false, "$tabulation  ")
        }

        if (printTotals) {
            writer.write(tabulation + "Total size: ${totalSize / 1024} Kb ($totalSize)")
        }
    }

    override fun toString(): String {
        return StringWriter().use {
            print(it, true)
            it.toString()
        }
    }
}

class DepsClient(
    private val repositories: Set<String> = LinkedHashSet<String>(1).apply { add("https://repo1.maven.org/maven2") },
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val mapper: ObjectMapper = createDefaultXmlMapper()
) {
    companion object : KLogging() {
        private val DEPENDENCY_REGEX = "([^:]+):([^:]+):([^:]+)".toRegex()
        private val IGNORED_SCOPES = setOf(
            DependencyScope.TEST,
            DependencyScope.PROVIDED,
            DependencyScope.SYSTEM
        )

        fun createDefaultXmlMapper() = XmlMapper()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(KotlinModule())
            .addHandler(object : DeserializationProblemHandler() {
                override fun handleUnexpectedToken(
                    ctxt: DeserializationContext,
                    targetType: Class<*>,
                    t: JsonToken,
                    p: JsonParser,
                    failureMsg: String?
                ): Any {
                    return if (p.parsingContext.currentName == MavenProject::dependencies.name && t == JsonToken.VALUE_STRING) {
                        ArrayList<MavenDependency>(0)
                    } else {
                        super.handleUnexpectedToken(ctxt, targetType, t, p, failureMsg)
                    }
                }
            })
    }

    fun analyze(dependency: String): DependencyAnalyzeResult {
        val (pomUrl, project) = downloadPom(dependency)
        val dependencies = project.flattenParents().resolveDependencyVersions().dependencies?.toList() ?: emptyList()

        return DependencyAnalyzeResult(
            MavenDependency(project.inheritedGroup!!, project.artifactId, project.inheritedVersion!!, null),
            pomUrl,
            dependencies.filter { it.scope !in IGNORED_SCOPES }.map { analyze(it.gradleId) }.toSet(),
            getDependencySize(pomUrl.removeSuffix("pom") + "jar") ?: 0
        )
    }

    private fun MavenProject.flattenParents(): MavenProject {
        return if (parent == null) {
            this
        } else {
            logger.trace { "Downloading the parent of $gradleId" }
            val (_, projectParent) = try {
                downloadPom(parent.gradleId)
            } catch (ex: Exception) {
                logger.trace { "Failed to download the parent of $gradleId: ${ex.message}" }
                throw DependencyDownloadException(parent.gradleId, ex)
            }

            val flattenedParent = projectParent.flattenParents()
            MavenProject(
                groupId ?: flattenedParent.groupId,
                artifactId,
                version ?: flattenedParent.version,
                null,
                flattenedParent.properties.plus(properties) { p1, p2 -> p1 + p2 },
                dependencies.plus(flattenedParent.dependencies) { l1, l2 -> l1 + l2 },
                dependencyManagement + flattenedParent.dependencyManagement
            )
        }
    }

    private fun interpolateDependency(project: MavenProject, dependency: MavenDependency, properties: Map<String, String>?): MavenDependency {
        val placeholder = dependency.versionPlaceholder
        return when {
            dependency.version == null -> {
                logger.trace { "Can't find a version info of $dependency dependency" }
                throw NoVersionDependencyException(project.gradleId, dependency.gradleId)
            }
            placeholder == "project.version" -> dependency.copy(version = project.inheritedVersion)
            placeholder != null -> {
                val version = properties?.get(placeholder)
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

    private fun interpolateVersions(project: MavenProject, dependencies: Iterable<MavenDependency>, properties: Map<String, String>?): List<MavenDependency> {
        return dependencies.map { interpolateDependency(project, it, properties) }
    }

    private fun resolveDependencyManagement(project: MavenProject): DependencyManagement {
        if (project.dependencyManagement == null) {
            return DependencyManagement(emptyList())
        }

        val resolved = interpolateVersions(
            project,
            project.dependencyManagement.dependencies,
            project.properties
        )
        return DependencyManagement(resolved)
    }

    private fun MavenProject.resolveDependencyVersions(): MavenProject {
        if (dependencies == null || !dependencies.any()) {
            return this.copy(dependencyManagement = null)
        }

        val (unresolved, resolved) = dependencies.partition { it.version == null || it.versionPlaceholder != null }
        return if (unresolved.isEmpty()) {
            this.copy(dependencyManagement = null)
        } else {
            val dependencyManagement = resolveDependencyManagement(this)
            val dependencyMap = dependencyManagement.dependencies.associateBy { it.artifactName }
            val found = unresolved.map {
                val managed = dependencyMap[it.artifactName]
                if (managed != null) {
                    it.copy(version = managed.version)
                } else {
                    interpolateDependency(this, it, this.properties)
                }
            }

            copy(dependencies = resolved + found, dependencyManagement = null)
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
            if (size != null) {
                logger.trace { "Got size of $dependencyUrl: $size bytes" }
            } else {
                logger.warn { "The size of $dependencyUrl is undefined" }
            }

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
                val responseBody = response.body()?.string()
                val project = if (responseBody != null) {
                    try {
                        mapper.readValue<MavenProject>(responseBody)
                    } catch (ex: JsonProcessingException) {
                        logger.debug { "Can't deserialize response body with error: ${ex.message}\n$responseBody" }
                        throw DependencyPomParsingException(dependency, ex)
                    }
                } else {
                    logger.trace { "Dependency $dependency POM is empty" }
                    throw DependencyPomEmptyException(dependency)
                }


                return dependencyUrl to project
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
        } ?: throw IllegalDependencyFormatException(dependency)

        return repositoryUrl.trimEnd('/') + dependencyPath
    }

    private operator fun DependencyManagement?.plus(other: DependencyManagement?): DependencyManagement? {
        return when {
            this == null && other == null -> null
            this != null && other != null -> DependencyManagement(this.dependencies + other.dependencies)
            else -> this ?: other
        }
    }

    private fun <T> T?.plus(other: T?, sum: (T, T) -> T): T? {
        return when {
            this == null && other == null -> null
            this != null && other != null -> sum(this, other)
            else -> this ?: other
        }
    }
}