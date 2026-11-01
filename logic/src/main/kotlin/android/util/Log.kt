/*
 * Test-only stub. NOT part of the Android app.
 *
 * See android/content/Context.kt: the JVM logic module has no `android.util.Log`, so the two
 * calls GuestRegistry makes are declared here. Logging is intentionally inert.
 */
package android.util

/** Test-only replacement for `android.util.Log`; writes nothing. */
object Log {
    @JvmStatic fun d(tag: String, msg: String) {}

    @JvmStatic fun w(tag: String, msg: String, tr: Throwable) {}
}
