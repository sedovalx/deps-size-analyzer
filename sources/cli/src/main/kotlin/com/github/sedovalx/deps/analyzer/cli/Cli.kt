package com.github.sedovalx.deps.analyzer.cli

import com.github.sedovalx.deps.analyzer.model.DepsClient

suspend fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("A maven dependency notation is expected as a first argument. Example: com.squareup.okhttp3:okhttp:3.12.0")
        return
    }

    val client = DepsClient()
    val result = client.analyze(args.first())
    println(result)
}