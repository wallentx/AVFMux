# AVF guest discovery — verified findings

All claims below were checked against this Pixel 11 Pro XL (Android 17, `DP11.261016.004`)
using a uid-2000 (`u:r:shell:s0`) shell obtained through Shizuku. Items marked
**UNVERIFIED** were not directly observed.

## 1. How upstream TerminalApp manages its guest

| Concern | Finding | Source |
|---|---|---|
| Guest identity | Exactly **one** guest, hardcoded to `<filesDir>/linux` | `InstalledImage.kt` — `INSTALL_DIRNAME="linux"`, `getDefault()` |
| Installed state | Presence of the `completed` marker file | `InstalledImage.isInstalled()` |
| Config | `vm_config.json`, parsed by `ConfigJson` (Gson) | `InstalledImage.CONFIG_FILENAME`, `ConfigJson.from()` |
| Disk | `root_part` (ext4 rootfs, not a separate `disk.img`) | `InstalledImage.ROOTFS_FILENAME` |
| VM API | `VirtualMachineManager.getOrCreate(name, config)` → `vm.run()` | `Runner.kt` |
| VM name | `customImageConfig.name` from `vm_config.json` | `ConfigJson.toCustomImageConfigBuilder()` |
| Console | Guest TCP endpoint discovered via **mDNS** (`_http._tcp`, service name `ttyd`), not vsock host-side | `VmLauncherService.getTerminalServiceInfo()` |
| Provisioning | `images.tar.gz` from `https://dl.google.com/android/ferrochrome/<SDK_FULL>/<abi>/` or external storage if debuggable | `ImageArchive.fromInternet()` / `fromSdCard()` |

There is **no registry, no `listFiles()` enumeration, and no multi-guest concept** anywhere
in the upstream Kotlin sources.

## 2. Platform API surface (decompiled from `/apex/com.android.virt/javalib/framework-virtualization.jar`)

`android.system.virtualmachine.VirtualMachineManager` public methods:

```
create(String, VirtualMachineConfig) -> VirtualMachine
get(String) -> VirtualMachine
getOrCreate(String, VirtualMachineConfig) -> VirtualMachine
delete(String)
importFromDescriptor(String, VirtualMachineDescriptor) -> VirtualMachine
getCapabilities() -> int
getSupportedOSList() -> List
isFeatureEnabled(String) / isUpdatableVmSupported() / isRemoteAttestationSupported()
```

**There is no enumeration/list method.** Every access is keyed by a caller-supplied *name*.
`mVmsByName` is a per-instance cache, not a queryable registry.

`VirtualMachineDescriptor` is a Binder handoff (ParcelFileDescriptors for config, instance,
encrypted store) — it is the *result* of importing/creating, not a discovery mechanism.

Persistence constants (hidden API, `BLOCKED`):
- `VM_DIR = "vm"`
- `CONFIG_FILE = "config.xml"`
- `INSTANCE_ID_FILE = "instance_id"`, `INSTANCE_IMAGE_FILE = "instance.img"`

Public SDK/test-API constants:
- `MANAGE_VIRTUAL_MACHINE_PERMISSION = "android.permission.MANAGE_VIRTUAL_MACHINE"`
- `USE_CUSTOM_VIRTUAL_MACHINE_PERMISSION = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"`
- `STATUS_RUNNING = 1`, `STATUS_STOPPED = 0`, `STATUS_DELETED = 2`

`getVmDir(context, name)` resolves to `/data/misc/virtualizationservice/vm/<name>/`.
That path exists but is **inaccessible to shell** (permission denied on `stat`).

## 3. Device-wide VM enumeration (works, but limited)

`/apex/com.android.virt/bin/vm list vms` lists registered VMs with name, cid, requesterUid,
requesterPid, instanceId. Live output on this device:

```
name: "isolated_storage_service_vm"  cid: 2048  requesterUid: 10332
name: "7777777719369372"             cid: -1    requesterUid: 1000
name: "777777774fc2fddc"             cid: -1    requesterUid: 10186
name: "77777777bb8d70f5"             cid: -1    requesterUid: 10309   <-- TerminalApp
name: "7777777713d7abd1"             cid: -1    requesterUid: 1010186
```

`uid 10309` is `com.android.virtualization.terminal` (`pm list packages --uid 10309`).

Key limitation: `vm list` yields **instance metadata only** — opaque names, requester uid,
cid. It exposes **no config, no disk paths, and no kernel**. Configs live in
`/data/misc/virtualizationservice/vm/<name>/config.xml`, unreadable by shell.

## 4. TerminalApp identity and permissions

```
package:/apex/com.android.virt/priv-app/VmTerminalAppGoogle@DP11.261016.004/VmTerminalAppGoogle.apk
flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP KILL_AFTER_RESTORE ]
versionCode=37  minSdk=37  targetSdk=37
declared permission: com.android.virtualization.terminal.permission.MANAGE_TERMINAL
                     prot=signature|preinstalled
requested: MANAGE_VIRTUAL_MACHINE, USE_CUSTOM_VIRTUAL_MACHINE, MANAGE_EXTERNAL_STORAGE, ...
```

`MANAGE_VIRTUAL_MACHINE` / `USE_CUSTOM_VIRTUAL_MACHINE` are `signature|privileged` platform
permissions. A normally-installed fork **cannot** hold them — therefore a fork cannot call
`VirtualizationService`/`VirtualMachineManager` as its own uid.

## 5. The two environments on this device

### Debian (stock TerminalApp)
- VM instance registered under requesterUid **10309**, `cid: -1` (currently **stopped**).
- Config/disk inside `/data/data/com.android.virtualization.terminal/files/linux/`.
- **`files/` is denied to shell** (`ls`/`stat` → permission denied).
- The app process is running (`ps` → uid `u0_a309`), but no crosvm child, so the VM is not
  booted.

### Arch Linux (`termux-arch-v2`)
- Intact under `/data/local/tmp/termux-arch-v2/` (owned by `shell`, `u:r:shell_data_file:s0`).
- Contents verified:
  - `arch-rootfs.img` — **64 GiB** (68719476736 bytes), RW ext4 root
  - `Image` 66 MiB, `Image-gfxstream` 84 MiB, `Image.before-landlock` 66 MiB
  - `authorized-key.bin` 4 KiB, `init-mode` = `systemd`, `memory-mib` = `8192`
  - `shared-storage-path` = `/storage/emulated/0/Download/AetherShared`
  - `owner.lock`, prior rootfs backups (6 GiB each)
- It has **no `config.xml`/`vm_config.json`** on disk: the Aether service builds the config
  in memory and calls `IVirtualizationService.createVm` each time it starts.
- It is **not present** in `vm list` at the moment (no registered instance with that name).

## 6. Security boundaries (verified)

| Boundary | Result |
|---|---|
| shell → TerminalApp `files/` | **denied** |
| shell → `/data/misc/virtualizationservice` | **denied** (even `stat`) |
| shell → `/data/local/tmp/termux-arch-v2` | **allowed** (read/write) |
| app (untrusted_app) → AVF manager | permission-gated; fork cannot hold `MANAGE_VIRTUAL_MACHINE` |
| Cross-app VM reuse | no platform API exists to attach to another uid's VM |

## 7. Consequences for AVFMux

1. **Discovery of registered VMs is possible only via privileged context** (shell/Shizuku or
   a platform-signed app). Plain app code has no listing API.
2. **Discovery of configs/disks across apps is impossible** without root/platform trust —
   neither shell nor untrusted apps can read either guest's config store.
3. Therefore the fork's viable model is a **guest registry owned by AVFMux itself**:
   guest *definitions* (name, distribution, disk path, kernel, params, memory) stored in
   AVFMux's own `filesDir`, separate from VM runtime state.
4. The **Arch environment can be reused without recreation** only if AVFMux can point a VM
   config at `/data/local/tmp/termux-arch-v2/arch-rootfs.img` + `Image` — which requires
   either (a) AVFMux running with shell privileges, or (b) an explicit handoff of those
   artifacts into an AVFMux-owned location. Plain-uid AVFMux cannot read that directory.
