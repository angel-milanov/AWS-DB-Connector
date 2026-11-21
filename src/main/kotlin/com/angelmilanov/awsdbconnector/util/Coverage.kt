package com.angelmilanov.awsdbconnector.util

/**
 * Marks the few places that no test can run: they need the real desktop (a system tray, a browser, a file manager)
 * or end the process. Everything else is covered by the tests, line by line (see the jacoco tasks in build.gradle.kts).
 *
 * JaCoCo leaves out classes and functions that carry an annotation with "Generated" in its name, hence the name.
 * Code marked with it has to stay free of logic: it only passes calls on. Say why it cannot be tested in [reason].
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FILE)
annotation class ExcludeFromJacocoGeneratedReport(val reason: String)
