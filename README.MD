## Description
A java client that can calculate the total size of a Maven/Gradle dependency transitively. 
I did not find a gradle plugin for that and decided to write it by myself.

## Usage
The client accepts a dependency identifier in the Gradle format and produces a result with 
with information about the total size of the dependency.

```kotlin
val client = DepsClient(repositories = setOf("https://repo1.maven.org/maven2"))
val result: DependencyAnalyzeResult = client.analyze("org.glassfish.jersey.core:jersey-client:2.27")
println(result)
```

For the example above, the output would be
```
org.glassfish.jersey.core:jersey-client:2.27 (181563)
  javax.ws.rs:javax.ws.rs-api:2.1 (127509)
  org.glassfish.jersey.core:jersey-common:2.27 (1140395)
    javax.ws.rs:javax.ws.rs-api:2.1 (127509)
    javax.annotation:javax.annotation-api:1.2 (26366)
    org.glassfish.hk2.external:javax.inject:2.5.0-b42 (5951)
      javax.inject:javax.inject:1 (2497)
    org.glassfish.hk2:osgi-resource-locator:1.0.1 (20235)
    org.jmockit:jmockit:1.18 (598339)
      com.google.code.findbugs:jsr305:3.0.0 (33031)
      junit:junit:4.12 (314932)
        org.hamcrest:hamcrest-core:1.3 (45024)
      org.testng:testng:6.8.21 (836806)
        org.apache.ant:ant:1.7.0 (1289806)
          org.apache.ant:ant-launcher:1.7.0 (11734)
          xerces:xercesImpl:2.8.1 (1212965)
            xml-apis:xml-apis:1.3.03 (195119)
            xml-resolver:xml-resolver:1.1 (60047)
          xml-apis:xml-apis:1.3.03 (195119)
        junit:junit:4.10 (253160)
          org.hamcrest:hamcrest-core:1.1 (76643)
            jmock:jmock:1.1.0 (102598)
              junit:junit:3.8.1 (121070)
            junit:junit:4.0 (105601)
            org.easymock:easymock:2.2 (62696)
        org.beanshell:bsh:2.0b4 (281694)
        com.beust:jcommander:1.27 (55585)
        org.yaml:snakeyaml:1.12 (270779)
      javax.persistence:persistence-api:1.0 (52150)
      javax.servlet:servlet-api:2.5 (105112)
      javax:javaee-api:6.0 (979616)
  org.glassfish.hk2.external:javax.inject:2.5.0-b42 (5951)
    javax.inject:javax.inject:1 (2497)
Total size: 8691 Kb (8900099)
``` 

## TODO
- [ ] Support Bintray dependency URL layout
- [ ] Consider only distinct transitive dependencies
- [ ] Create a Gradle plugin to run it as a task
- [ ] Publish to Bintray   