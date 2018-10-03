package com.github.sedovalx.deps.analyzer.examples;

import com.github.sedovalx.deps.analyzer.model.DependencyAnalyzeResult;
import com.github.sedovalx.deps.analyzer.model.DepsClient;

import java.util.LinkedHashSet;

public class JavaExample {
    public static void main(String[] args) {
        LinkedHashSet<String> repositories = new LinkedHashSet<>();
        repositories.add("https://repo1.maven.org/maven2");
        DepsClient client = new DepsClient(repositories);
        DependencyAnalyzeResult result = client.analyze("org.glassfish.jersey.core:jersey-client:2.27");
        System.out.println(result);
    }
}
