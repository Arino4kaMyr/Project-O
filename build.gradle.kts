plugins {
    kotlin("jvm") version "2.2.10"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(24)
}

application {
    mainClass.set("MainKt")
}

tasks.register("compileJasmin") {
    group = "compilation"
    description = "Compiles generated Jasmin files to Java bytecode"
    
    val jasminDir = file("jasmin/generated")
    val jasminJar = file("jasmin/generated/jasmin.jar")
    
    doFirst {
        if (!jasminJar.exists()) {
            throw GradleException("jasmin.jar not found at ${jasminJar.absolutePath}")
        }
        
        val jasminFiles = jasminDir.listFiles { _, name -> name.endsWith(".j") }
        if (jasminFiles == null || jasminFiles.isEmpty()) {
            throw GradleException("No .j files found in ${jasminDir.absolutePath}. Run 'gradlew run' first to generate them.")
        }
    }
    
    doLast {
        val jasminFiles = jasminDir.listFiles { _, name -> name.endsWith(".j") } ?: return@doLast
        
        jasminFiles.forEach { jasminFile ->
            exec {
                workingDir = jasminDir
                commandLine("java", "-jar", jasminJar.absolutePath, jasminFile.name)
            }
            println("✓ Compiled: ${jasminFile.name}")
        }
    }
}

tasks.register<JavaExec>("runGenerated") {
    group = "execution"
    description = "Runs the compiled Program class from generated Jasmin files"
    dependsOn("compileJasmin")
    
    val jasminDir = file("jasmin/generated")
    
    workingDir = jasminDir
    classpath = files(jasminDir)
    mainClass.set("Program")
    
    standardOutput = System.out
    errorOutput = System.err
}

tasks.register("buildAndRun") {
    group = "build"
    description = "Full pipeline: compile Kotlin, generate Jasmin, compile Jasmin, and run program"
    
    dependsOn("run")
    dependsOn("compileJasmin")
    finalizedBy("runGenerated")
}