package com.github.sedovalx.deps.analyzer.examples

import com.github.sedovalx.deps.analyzer.model.DepsClient


fun main() {
    val client = DepsClient()

    listOf(
        "com.squareup.okhttp3:okhttp:3.11.0",
        "org.glassfish.jersey.core:jersey-client:2.27",
        "com.google.http-client:google-http-client:1.25.0"
    ).forEach {
        println(client.analyze(it))
    }
}