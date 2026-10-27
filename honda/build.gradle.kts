plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    api("com.google.protobuf:protobuf-java:3.25.5")
    api("io.grpc:grpc-api:1.69.1")
    implementation("io.grpc:grpc-protobuf:1.69.1")
    implementation("io.grpc:grpc-stub:1.69.1")
    implementation("io.grpc:grpc-okhttp:1.69.1")
}
protobuf { protoc { artifact = "com.google.protobuf:protoc:3.25.5" } }
tasks.register<JavaExec>("contractCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.openridesync.honda.ContractCheck")
}
tasks.register<JavaExec>("vehicleStoreCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.openridesync.honda.VehicleStoreCheck")
}
tasks.register<JavaExec>("tripCacheCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.openridesync.honda.TripCacheCheck")
}

tasks.register<JavaExec>("pairedVehicleStoreCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.openridesync.honda.PairedVehicleStoreCheck")
}
tasks.check { dependsOn("contractCheck", "vehicleStoreCheck", "tripCacheCheck", "pairedVehicleStoreCheck") }
// The wire checks use a standalone Java main instead of a test framework.
tasks.test { failOnNoDiscoveredTests = false; dependsOn("contractCheck", "vehicleStoreCheck", "tripCacheCheck", "pairedVehicleStoreCheck") }
