# AVFMux

Android Virtualization Framework Multiplexer — an Apache-2.0 fork of AOSP's
[Linux Terminal app](https://android.googlesource.com/platform/packages/apps/TerminalApp/)
that adds a guest registry and a selection screen while retaining the upstream Debian
install/launch architecture.

## Scope

The fork keeps upstream behavior wherever practical:

- Debian remains the stock guest at `<filesDir>/linux`, with the same `root_part`,
  `vm_config.json`, and `completed` marker.
- `InstalledImage.getDefault()`, the installer, the VM launcher service, the terminal tabs,
  mDNS console discovery, port forwarding, disk resize, recovery, and the settings screen are
  unchanged except for explicit guest-id plumbing.
- AVFMux adds only: a guest definition (`Guest`), a conservative registry (`GuestRegistry`),
  optional `guest.json` metadata, a guest-list activity, and a settings entry pointing to it.

No ISO/IMG provisioning or VM-format conversion is implemented.

## Guest model

| Guest | Storage | Launchable by AVFMux |
|---|---|---|
| Debian (stock) | `<filesDir>/linux` | Yes, when installed |
| AVFMux-registered guests | `<filesDir>/guests/<id>` (must contain `vm_config.json`) | Yes, when installed |
| Arch Linux (declared external) | `/data/local/tmp/termux-arch-v2` (shell-owned) | No — listed with an explicit reason |

Directories under `guests/` without a real `vm_config.json` are skipped, so a stray directory
cannot become a phantom guest. Nothing in the registry writes, moves, or migrates guest data.

## Verified platform limits

These are Android/AVF boundaries, not implementation shortcuts:

- `VirtualMachineManager` has **no enumeration API** — only name-keyed
  `create`/`get`/`getOrCreate`/`delete`/`importFromDescriptor`.
- A normal app cannot read another app's private storage or the shell-owned
  `/data/local/tmp`, and cannot read `/data/misc/virtualizationservice`.
- AVF exposes **no host-side attach** to a VM owned by another requester.
- `MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE` are `signature|privileged`
  permissions. A sideloaded APK compiles but cannot hold them.

Therefore the existing Arch guest is displayed as *declared external* and is not launched by
AVFMux. Launching it requires either a privileged (system/priv-app) install that can hold the
VM permissions and reference the external image, or importing the guest into AVFMux's own
storage. Neither path is implemented here.

Full evidence is in [`docs/discovery-findings.md`](docs/discovery-findings.md).

## Build

AVFMux is built as its upstream ancestor is: an AOSP/Soong `android_app` with
`platform_apis: true` and `privileged: true` (root `Android.bp`). Assembly therefore happens
inside an AOSP source tree; the public SDK and Maven cannot provide the hidden
`android.system.virtualmachine` APIs or AOSP-only libraries (`debian-service-grpclib-lite`,
`framework-virtualization.impl`, `libforwarder_host_jni`, …).

The new registry logic is independently compiled and tested on the JVM:

```sh
./gradlew :logic:test
```

CI runs those tests and checks static invariants: both VM permissions, the registered
`GuestListActivity`, and the Soong flags/libraries in `Android.bp`.

Soong APK assembly and on-device launch are **unverified in this environment**; see
[`docs/build-model.md`](docs/build-model.md) for the verified build-model evidence.

## License

Apache License 2.0. Upstream copyright notices are retained in every copied source file;
new files carry the same license header.
