/*
 * Test-only stub. NOT part of the Android app.
 *
 * The Android app is built by Soong from Android.bp inside the AOSP tree. This module
 * recompiles the pure registry sources on a plain JVM, where `android.*` classes do not
 * exist, so the handful of Android types they touch are declared here. These stubs must
 * never be shipped in an APK; the real framework classes provide them at runtime.
 */
package android.content

import java.io.File

/** Test-only replacement for `android.content.Context`, narrowed to `getFilesDir()`. */
open class Context {
    /** Stand-in for the app-private files dir; tests override it. */
    open fun getFilesDir(): File = throw UnsupportedOperationException("stub Context: override getFilesDir()")
}
