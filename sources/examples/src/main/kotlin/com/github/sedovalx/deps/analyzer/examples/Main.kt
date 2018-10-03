package com.github.sedovalx.deps.analyzer.examples

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.sedovalx.deps.analyzer.model.DepsClient


fun main() {
    val client = DepsClient()
    val (_, project) = client.downloadPom("com.squareup.okhttp3:okhttp:3.11.0")
    println(project)

    project
        .let { client.flattenParents(it) }
        .also {
            println(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(it))
        }
        .let { client.resolveDependencyVersions(it) }
        .also {
            println(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(it))
        }
}