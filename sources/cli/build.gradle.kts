dependencies {
    implementation(project(":sources:client"))
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:2.11.1")
}

tasks {
    named<Jar>("jar") {
//        configurations.forEach { conf ->
//            try {
//                println(conf.name)
//                conf.files.forEach {
//                    println("\t${it.absolutePath}")
//                }
//            } catch (ex: Exception) {
//                println(ex.message)
//            }
//        }
        from(configurations["runtimeClasspath"].map { if (it.isDirectory) it else zipTree(it) })

        manifest {
            attributes(mapOf("Main-Class" to "com.github.sedovalx.deps.analyzer.cli.CliKt"))
        }
    }
}