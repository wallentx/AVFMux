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
| Debian (stock) | `<filesDir>/linux` | Yes when installed; selecting it when absent runs the upstream installer |
| AVFMux-registered guest | `<filesDir>/guests/<installDirName>` | Yes only when `guest.json`, `vm_config.json`, `completed`, and `root_part` all exist |
| External descriptor (e.g. Arch) | any app-visible `installDir`, declared by the user | No — listed with the exact reason it cannot launch |

A directory under `guests/` is ignored unless all four files above are present and its
`guest.json` names a safe single-component install directory. A stray or partial directory
therefore cannot become a phantom guest. Nothing in the registry writes, moves, deletes,
imports, or migrates guest data.

External guests are declared, never discovered silently. AVFMux reads descriptors from
`Download/AVFMux/guests/*.json`:

```json
{
  "id": "arch",
  "displayName": "Arch Linux",
  "distribution": "Arch Linux",
  "installDir": "/data/local/tmp/termux-arch-v2",
  "note": "created by Termux:API"
}
```

The registry probes that path only to explain the result. On this device the Arch install dir
is shell-owned, so the row reads "install dir not accessible to this app (app sandbox)". If a
descriptor points at a readable bundle, it still says "external bundle is outside app-private
storage and is not imported or launched by this build": AVFMux does not attach to or import
another owner's VM. There is no hard-coded guest path in the source.

## Verified platform limits

These are Android/AVF boundaries, not implementation shortcuts:

- `VirtualMachineManager` has **no enumeration API** — only name-keyed
  `create`/`get`/`getOrCreate`/`delete`/`importFromDescriptor`.
- A normal app cannot read another app's private storage or the shell-owned
  `/data/local/tmp`, and cannot read `/data/misc/virtualizationservice`.
- The upstream console attach is a guest-side TCP (mDNS `ttyd`) connection to the VM this app
  started; AVF exposes no host-side attach to a VM owned by another requester.
- `MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE` are `signature|privileged`
  permissions. A sideloaded APK compiles but cannot hold them.
- Whether a privileged system install can read the shell-owned Arch bundle or take over the
  existing VM is **unverified** and not implemented here. AVFMux neither destroys nor migrates
  either existing guest.

Therefore the existing Arch guest appears only when a user descriptor declares it, and it is
not launched by AVFMux. Launching it would require an ownership/import mechanism that AVF does
not expose to this app; that is deliberately out of scope for this slice.

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
