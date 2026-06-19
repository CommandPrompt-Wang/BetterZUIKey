// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}
//val javaVersion by extra(JavaVersion.VERSION_11)

gradle.projectsEvaluated {
    allprojects {
        tasks.withType<JavaCompile> {
            options.compilerArgs.add("-Xlint:deprecation")
        }
    }
}
