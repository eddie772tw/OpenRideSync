plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.register<JavaExec>("regression") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.openridesync.diagnostics.DiagnosticsRegression")
}
// Regression tests are executable Java mains, not JUnit-discovered tests.
tasks.test { failOnNoDiscoveredTests = false; dependsOn("regression") }
tasks.check { dependsOn("regression") }
