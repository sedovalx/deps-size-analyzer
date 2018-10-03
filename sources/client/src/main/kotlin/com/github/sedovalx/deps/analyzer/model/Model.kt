package com.github.sedovalx.deps.analyzer.model

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import java.io.StringWriter
import java.io.Writer

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

