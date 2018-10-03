package com.github.sedovalx.deps.analyzer

sealed class DependencyResult

data class DependencyInfo(val dependency: String, val ownSize: Long, val totalSize: Long, val children: Set<DependencyResult>) : DependencyResult()
data class DependencyError(val ex: Exception) : DependencyResult()

//fun analyzeSize(dependency: String, failFast: Boolean = false, vararg dependencyExclusions: String): DependencyResult {
//
//}
//
//private fun getDependencyPom(dependency: String) {
//
//}