package com.github.sedovalx.deps.analyzer.examples

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.sedovalx.deps.analyzer.model.DepsClient


fun main() {
    val client = DepsClient()
    val (url, project) = client.downloadPom("com.squareup.okhttp3:okhttp:3.11.0")
    println(project)

    val flattened = client.flattenParents(project)
    println(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(flattened))
}