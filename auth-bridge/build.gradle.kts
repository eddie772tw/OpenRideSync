plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
tasks.register<JavaExec>("regression") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.openridesync.authbridge.LoopbackTest")
}
tasks.register<JavaExec>("cancellationRegression") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.openridesync.authbridge.LoopbackCancellationTest")
}
tasks.check { dependsOn("regression", "cancellationRegression") }
// Boundary checks are a standalone Java main instead of a test framework.
tasks.test { failOnNoDiscoveredTests = false; dependsOn("regression", "cancellationRegression") }
