package com.github.sedovalx.deps.analyzer.model

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.OkHttpClient
import okhttp3.Request

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
    val version: String,
    val scope: DependencyScope = DependencyScope.COMPILE
)

data class DependencyManagement(val dependencies: Iterable<MavenDependency> = emptyList())

@JacksonXmlRootElement(localName = "project")
data class MavenProject(
    val parent: MavenDependency?,
    val properties: Map<String, Any> = emptyMap(),
    val dependencies: Iterable<MavenDependency> = emptyList(),
    val dependencyManagement: DependencyManagement?
)

fun main() {
    val mapper = XmlMapper()
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(KotlinModule())

    val request = Request.Builder()
        .url("https://repo1.maven.org/maven2/com/squareup/okhttp3/parent/3.11.0/parent-3.11.0.pom")
        .build()

    val client = OkHttpClient()
    val response = client.newCall(request).execute()
    response.body()?.also { body ->
        val project = body.byteStream().use {
            mapper.readValue<MavenProject>(it)
        }

        println(project)
    }


}