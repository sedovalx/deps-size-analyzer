package com.github.sedovalx.deps.analyzer.model

import com.fasterxml.jackson.core.JsonProcessingException
import java.lang.RuntimeException


open class DependencyResolutionException(message: String, cause: Exception? = null) : RuntimeException(message, cause)
class DependencyNotFoundException(dependencyId: String) : DependencyResolutionException("Dependency $dependencyId is not found")
class DependencyDownloadException(dependencyId: String, cause: Exception) : DependencyResolutionException("Failed to download $dependencyId", cause)
class NoVersionDependencyException(projectId: String, dependencyId: String) : DependencyResolutionException("Dependency management of $projectId has no version of $dependencyId")
class IllegalDependencyFormatException(dependency: String) : DependencyResolutionException("Format of $dependency is invalid. Please use the Gradle notation.")
class DependencyPomParsingException(dependencyId: String, cause: JsonProcessingException) : DependencyResolutionException("Can't parse $dependencyId POM", cause)
class DependencyPomEmptyException(dependencyId: String) : DependencyResolutionException("Downloaded $dependencyId POM is empty")
