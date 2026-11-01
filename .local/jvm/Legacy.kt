package com.android.virtualization.terminal
// Legacy minimal JVM harness for the pure guest logic.
fun main() {
    var pass = 0; var fail = 0
    fun check(name: String, ok: Boolean) { if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name") } }

    check("debian id", Guest.DEBIAN.id == "debian")
    check("debian dirname", Guest.DEBIAN.installDirName == "linux")
    check("debian launchable-by-storage", Guest.DEBIAN.launchableByStorage)

    check("resolve null -> debian", GuestRegistry.resolve(null, listOf(Guest.DEBIAN)) == Guest.DEBIAN)
    check("resolve empty -> debian", GuestRegistry.resolve("", listOf(Guest.DEBIAN)) == Guest.DEBIAN)
    check("resolve unknown -> debian", GuestRegistry.resolve("zzz", listOf(Guest.DEBIAN)) == Guest.DEBIAN)
    val arch = Guest("arch", "Arch Linux", "Arch Linux", "arch")
    check("resolve known", GuestRegistry.resolve("arch", listOf(Guest.DEBIAN, arch)).id == "arch")

    check("external not launchable", !GuestRegistry.isLaunchable(Guest("a","A","A","a",true,"r"), installed = true))
    check("installed local launchable", GuestRegistry.isLaunchable(Guest.DEBIAN, installed = true))
    check("not installed not launchable", !GuestRegistry.isLaunchable(Guest.DEBIAN, installed = false))

    val m = GuestManifest.parse("""{"displayName":"Arch Linux","distribution":"Arch"}""")
    check("manifest name", m.displayName == "Arch Linux")
    check("manifest dist", m.distribution == "Arch")
    check("manifest default dist", GuestManifest.parse("""{"displayName":"X"}""").distribution == "Unknown")
    var rejected = false
    try { GuestManifest.parse("""{"distribution":"Arch"}""") } catch (e: IllegalArgumentException) { rejected = true }
    check("manifest rejects missing name", rejected)

    println("RESULT pass=$pass fail=$fail")
    if (fail > 0) throw IllegalStateException("$fail test(s) failed")
}
