package com.github.sedovalx.deps.analyzer.examples;

import com.github.sedovalx.deps.analyzer.model.DependencyAnalyzeResult;
import com.github.sedovalx.deps.analyzer.model.DepsClient;

import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class JavaExample {
    public static void main(String[] args) throws InterruptedException, ExecutionException, TimeoutException {
        LinkedHashSet<String> repositories = new LinkedHashSet<>();
        repositories.add("https://repo1.maven.org/maven2");
        DepsClient client = new DepsClient(repositories);
        CompletableFuture<DependencyAnalyzeResult> result = client.analyzeJava("org.glassfish.jersey.core:jersey-client:2.27");
        System.out.println(result.get(3, TimeUnit.SECONDS));
    }
}
