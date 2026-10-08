// Versions match Haven's Android build, which is known to build on this machine.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// ── Line coverage (JaCoCo) for the JVM unit tests of :app, :wear and :shared ─────────────────────
// Each module gets `coverageReport` (XML + HTML under build/reports/coverage/) and `coverageSummary`,
// which prints the module's line coverage as one `soren-coverage: {…}` line — Soren's `android` and
// `android-shared` suites run it after the tests and show the figure (docs/testing.md). Compiler-made
// classes (R, BuildConfig, Compose's lambda singletons) are left out; nothing else is.
val coverageExcludes = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.class", "**/Manifest*.class",
    "**/ComposableSingletons*.class",
)
subprojects {
    if (name !in setOf("app", "wear", "shared")) return@subprojects
    apply(plugin = "jacoco")
    extensions.configure<JacocoPluginExtension> { toolVersion = "0.8.12" }
    // Robolectric loads the app's classes through its own class loader: without this their lines
    // count as missed even when a test ran them.
    tasks.withType<Test>().configureEach {
        extensions.configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
    val module = name
    val android = module != "shared"
    val testTask = if (android) "testDebugUnitTest" else "test"
    val report = tasks.register<JacocoReport>("coverageReport") {
        group = "verification"
        description = "Line coverage of the $testTask run (JaCoCo)."
        dependsOn(testTask)
        val classes = if (android) layout.buildDirectory.dir("tmp/kotlin-classes/debug") else layout.buildDirectory.dir("classes/kotlin/main")
        classDirectories.setFrom(files(classes).asFileTree.matching { exclude(coverageExcludes) })
        sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
        // AGP's debug unit-test coverage (enableUnitTestCoverage) writes under outputs/; the JVM
        // module's jacoco plugin under jacoco/.
        executionData.setFrom(fileTree(layout.buildDirectory) {
            include("jacoco/$testTask.exec", "outputs/unit_test_code_coverage/**/$testTask.exec")
        })
        reports {
            xml.required.set(true)
            html.required.set(true)
            xml.outputLocation.set(layout.buildDirectory.file("reports/coverage/coverage.xml"))
            html.outputLocation.set(layout.buildDirectory.dir("reports/coverage/html"))
        }
    }
    // :shared is plain Kotlin the phone's and the watch's tests exercise too (the package reader, the
    // canon, verse numbering): when their unit tests run in the same build, their execution counts.
    if (!android) {
        val users = listOf(":app:testDebugUnitTest", ":wear:testDebugUnitTest")
        report.configure { mustRunAfter(users) }
        gradle.taskGraph.whenReady {
            val ran = users.filter { hasTask(it) }
            if (ran.isNotEmpty()) report.configure {
                for (task in ran) {
                    val dir = rootProject.project(task.substringBeforeLast(":")).layout.buildDirectory
                    executionData(fileTree(dir) { include("outputs/unit_test_code_coverage/**/testDebugUnitTest.exec") })
                }
            }
        }
    }
    tasks.register("coverageSummary") {
        group = "verification"
        description = "Prints this module's line coverage as a soren-coverage line."
        dependsOn(report)
        val xml = layout.buildDirectory.file("reports/coverage/coverage.xml")
        doLast {
            val text = xml.get().asFile.readText()
            // The report's own totals are the last LINE counter, after every package's.
            val m = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""").findAll(text).lastOrNull()
                ?: error("no LINE counter in ${xml.get().asFile}")
            val missed = m.groupValues[1].toLong()
            val covered = m.groupValues[2].toLong()
            val executable = missed + covered
            val pct = if (executable == 0L) 0.0 else Math.round(covered * 1000.0 / executable) / 10.0
            println("soren-coverage: {\"covered\":$covered,\"executable\":$executable,\"targets\":[{\"name\":\":$module\",\"covered\":$covered,\"executable\":$executable}]}")
            println(":$module line coverage: $pct% ($covered/$executable)")
        }
    }
}
