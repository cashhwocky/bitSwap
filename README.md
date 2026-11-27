# bitSwap

Switch a Pixel to a different ROM **from the phone itself**. No PC, no second phone.
Pick a ROM zip, tap **Switch**: bitSwap installs it, formats data and reboots into it.

Made by Alex Desrosier for bitDroid.

## Phones
Pixel 10 (`frankel`), Pixel 10 Pro (`blazer`), Pixel 10 Pro XL (`mustang`), Pixel 6a (`bluejay`).

## Needs root (Magisk)
Android only lets rooted apps do this. No root? Use **bitFlash** → *Root this Pixel* (one tap, nothing wiped).

## How it installs (experimental)
A Pixel's `super` partition only holds one full ROM, so bitSwap can't just write a second copy. Instead it
drives Android's **own** installer, `update_engine` — the same thing OTAs use — which installs the other slot
as a compressed snapshot, builds verity, and switches slots.

The catch with a *different* ROM is that `update_engine` verifies the payload against the running ROM's
signing key. So bitSwap pulls the new ROM's own signing certificate out of the zip and bind-mounts its public
key over the verify path (in init's mount namespace, so the daemon sees it). Then `update_engine` accepts the
new ROM's payload. The mount is undone by the reboot.

Steps:
1. **Check root** — small root helper inside bitSwap's own APK via `su`.
2. **Check this phone** — supported device, which slot, `update_engine` present and idle, free space.
3. **Check the ROM zip** — `pre-device` matches, `post-sdk-level` ≥ 37 (Android 17), full A/B ROM, has `payload.bin`, `payload_properties.txt`, signing cert.
4. **Stage the ROM + trust its signing key** — copy `payload.bin` to `/data/ota_package`, overlay the ROM's public key.
5. **Install with Android's updater** — `update_engine` writes the other slot and marks it active.
6. **Root the new ROM** (optional) — patch the new slot's `init_boot` with Magisk so it boots rooted.
7. **Schedule format data** — `--wipe_data` in misc's bootloader message (first 2 KB only).
8. **Reboot** — recovery formats data, first boot finishes the install, new ROM starts.

## Safety (enforced in the root helper, not just the UI)
- Only `update_engine` writes partitions; a full OTA has no bootloader/modem/firmware, so those are never touched.
- The one partition bitSwap writes by hand is `init_boot` on the **inactive** slot (for Magisk); the running slot is refused.
- `misc` only ever gets the standard recovery wipe message. The bootloader is never locked.
- Nothing is installed until step 5 succeeds. If it fails, your phone still boots the ROM you're on.

## Known limits (untested on a device)
- If the ROM signs its payload with a different key than its zip certificate, `update_engine` still rejects it → use bitFlash.
- `update_engine` enforces anti-rollback by build timestamp; a ROM older than what's installed may be refused.
- If staging runs `/data` low on space, it stops before installing.

## Build
GitHub Actions builds a release APK on every push to `main` and publishes `bitSwap-v0.1.N.apk`.
It bundles Magisk (for rooting the new ROM). Optional signing secrets: `BITSWAP_KEYSTORE_BASE64`, `BITSWAP_KEYSTORE_PASSWORD`, `BITSWAP_KEY_ALIAS`, `BITSWAP_KEY_PASSWORD`.

Payload and zip reading started from bitFlash.
