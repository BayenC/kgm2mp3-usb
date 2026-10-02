plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }

tasks.register<JavaExec>("validateRealSample") {
    group = "verification"
    description = "Decrypt an explicitly supplied KGM_SAMPLE into KGM_OUTPUT (never an embedded fixture)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.kgx2mp3.core.RealSampleValidation")
}
