# Build model — why Soong, and what CI can verify

## Verified finding

Upstream TerminalApp is not a Gradle app. Its `Android.bp` declares:

```
platform_apis: true
privileged: true
static_libs: [
  "android.system.virtualizationservice_internal-java",
  "avf_aconfig_flags_java",
  "debian-service-grpclib-lite",
  "libcrosvm_android_display_service-java",
  "MicrodroidTestHelper",
  ...
]
libs: [
  "androidx.annotation_annotation",
  "framework-virtualization.impl",
  "framework-annotations-lib",
]
```

The Kotlin sources import hidden/SystemApi and AOSP-only symbols across roughly twenty files:

- `android.system.virtualmachine.*` (`VirtualMachineManager`, `VirtualMachine`,
  `VirtualMachineConfig`, `VirtualMachineCustomImageConfig`, `VirtualMachineException`,
  `VirtualMachineCallback`)
- `android.os.SystemProperties` and `android.system.Os.deleteContentsAndDir`
- `android.system.virtualizationservice_internal.IVirtualizationServiceInternal`
- `io.grpc.*` plus AOSP's generated `com.android.virtualization.terminal.proto.DebianServiceGrpc`
- `avf_aconfig_flags_java` (`Flags`), `libcrosvm_android_display_service-java`, and
  `MicrodroidTestHelper`'s `DeviceProperties`

None of these exist in the public Android SDK or on Maven Central. When the repo was briefly
built as a Gradle Android app with `compileSdk 37.2`, CI failed with unresolved references for
exactly this surface (`gh run 41305962243`):

```
VmLauncherService.kt: Unresolved reference 'virtualmachine', 'SystemProperties', 'io.grpc' ...
ConfigJson.kt:       Cannot infer type for VirtualMachineConfig builders
Runner.kt:           Unresolved reference 'VirtualMachineCallback'
...
```

A standalone APK cannot be produced by stubbing these and calling it validated: missing
AOSP-native pieces (`libforwarder_host_jni`, the internal Binder/AIDL stubs, and the generated
Debian gRPC library) are functional code, not compile-time decoration. Patching them would
rewrite upstream rather than fork it.

## Chosen model

- **App assembly**: native AOSP/Soong via root `Android.bp`, adapted from upstream. Module name
  `AvfMux`, package remains `com.android.virtualization.terminal`, `platform_apis: true`,
  `privileged: true`, `apex_available: ["com.android.virt"]`. It is intended to replace the
  upstream `VmTerminalApp` module in an AOSP tree.
- **JVM verification**: a `:logic` Gradle module compiles only the new pure files
  (`Guest.kt`, `GuestRegistry.kt`, `GuestManifest.kt`) against package-correct test stubs
  (`android.content.Context`, `android.util.Log`, `InstalledImage`) and runs
  `GuestRegistryTest` with JUnit 4.

CI runs `./gradlew :logic:test` and checks static invariants: both VM permissions remain in
the manifest, `GuestListActivity` is registered, and `Android.bp` keeps `platform_apis`,
`privileged`, the framework virtualization lib, the Debian gRPC lib, and apex availability.

## Verified vs. unverified

| Item | Status |
|---|---|
| Registry logic compilation | **Verified locally** (Gradle 9.6.0, JDK 21; JDK 17 in CI) |
| 11 registry unit tests | **Verified locally**: 11 tests, 0 failures |
| Manifest/blueprint invariants | **Verified statically** |
| Soong APK assembly | **UNVERIFIED** — requires an AOSP source tree |
| APK install/launch on device | **UNVERIFIED** — requires a privileged install holding `MANAGE_VIRTUAL_MACHINE` |
| Arch guest launch | **Not implemented** — blocked by AVF/AIDL/storage boundaries (see discovery findings) |

The unit tests caught a real recursion bug (`GuestRegistry.listOf` calling itself instead of
`kotlin.collections.listOf`), which is why the JVM harness is kept rather than asserted only
by inspection.

## Deployment boundary

`MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE` are `signature|privileged`
permissions. A sideloaded build cannot hold them, so even a correctly assembled APK would not
launch Debian. Deploying AVFMux as a privileged system/priv-app app (or replacing the
preinstalled TerminalApp in the virt apex) is required. That deployment is outside this
repository and is not verified here.
