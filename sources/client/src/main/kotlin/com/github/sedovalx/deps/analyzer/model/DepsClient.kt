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
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import mu.KLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class DepsClient @JvmOverloads constructor(
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

    suspend fun analyze(dependency: String): DependencyAnalyzeResult {
        return analyze(dependency, ConcurrentHashMap(), ConcurrentHashMap())
    }

    private suspend fun analyze(
        dependency: String,
        dependencyCache: MutableMap<String, Pair<MavenDependency, Long>>,
        projectCache: MutableMap<String, Pair<String, MavenProject>>
    ): DependencyAnalyzeResult {
        val (pomUrl, project) = projectCache.getOrPut(dependency) {
            downloadPom(dependency)
        }

        val current = dependencyCache.getOrPut(dependency) {
            MavenDependency(
                project.inheritedGroup!!,
                project.artifactId,
                project.inheritedVersion!!,
                null
            ) to (getDependencySize(pomUrl.removeSuffix("pom") + "jar") ?: 0)
        }

        val dependencies = project.flattenParents(projectCache)
            .resolveDependencyVersions()
            .dependencies
            ?.toList()
            ?.filter { it.gradleId != dependency && it.scope !in IGNORED_SCOPES }
            ?: emptyList()

        return DependencyAnalyzeResult(
            current.first,
            pomUrl,
            dependencies.map { analyze(it.gradleId, dependencyCache, projectCache) }.toSet(),
            current.second
        )
    }

    fun analyzeJava(dependency: String): CompletableFuture<DependencyAnalyzeResult> {
        return GlobalScope.future { analyze(dependency) }
    }

    private suspend fun MavenProject.flattenParents(projectCache: MutableMap<String, Pair<String, MavenProject>>): MavenProject {
        return if (parent == null) {
            this
        } else {
            logger.trace { "Downloading the parent of $gradleId" }
            val (_, projectParent) = try {
                projectCache.getOrPut(parent.gradleId) {
                    downloadPom(parent.gradleId)
                }
            } catch (ex: Exception) {
                logger.trace { "Failed to download the parent of $gradleId: ${ex.message}" }
                throw DependencyDownloadException(parent.gradleId, ex)
            }

            val flattenedParent = projectParent.flattenParents(projectCache)
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
                throw NoVersionDependencyException(
                    project.gradleId,
                    dependency.gradleId
                )
            }
            placeholder == "project.version" -> dependency.copy(version = project.inheritedVersion)
            placeholder != null -> {
                val version = properties?.get(placeholder)
                if (version != null) {
                    dependency.copy(version = version)
                } else {
                    logger.trace { "Can't find a version info of $dependency dependency" }
                    throw NoVersionDependencyException(
                        project.gradleId,
                        dependency.gradleId
                    )
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

    suspend fun getDependencySize(dependencyUrl: String): Long? {
        logger.trace { "Getting size of $dependencyUrl" }

        val request = Request.Builder()
            .url(dependencyUrl)
            .method("HEAD", null)
            .build()

        httpClient.newCall(request).async().use {
            val size = it.header("Content-Length")?.toLongOrNull()
            if (size != null) {
                logger.trace { "Got size of $dependencyUrl: $size bytes" }
            } else {
                logger.warn { "The size of $dependencyUrl is undefined" }
            }

            return size
        }
    }

    private suspend fun downloadPom(dependency: String): Pair<String, MavenProject> {
        logger.debug { "Downloading $dependency POM" }
        for (url in repositories) {
            val dependencyUrl = buildDependencyUrl(url, dependency)
            logger.trace { "Getting $dependencyUrl" }
            val response = try {
                val request = Request.Builder().url(dependencyUrl).build()
                httpClient.newCall(request).async()
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