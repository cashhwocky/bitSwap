# bitSwap

Switch a Pixel to a different ROM **from the phone itself**. No PC, no second phone.
Pick a ROM zip, tap **Switch**: bitSwap installs it on the other slot, formats data and reboots into it.

Made by Alex Desrosier for bitDroid.

## Phones
Pixel 10 (`frankel`), Pixel 10 Pro (`blazer`), Pixel 10 Pro XL (`mustang`), Pixel 6a (`bluejay`).

## Needs root (Magisk)
Android only lets rooted apps write partitions. No root? Use **bitFlash** → *Root this Pixel* (one tap, nothing wiped).

## What it does
1. **Check root**: starts a small root helper inside bitSwap's own APK through `su` (no extra binaries).
2. **Check this phone**: supported device, which slot it runs on, no Android update pending, other slot not in use.
3. **Check the ROM zip**: `pre-device` must match, `post-sdk-level` ≥ 37 (Android 17), full A/B ROM only (no incremental or partial OTAs, only REPLACE / REPLACE_XZ / REPLACE_BZ / ZSTD / ZERO / DISCARD).
4. **Check firmware on both slots** (read only): bootloader, modem and other firmware partitions must be identical on both slots, otherwise it stops (booting the other slot could run older firmware: the anti-rollback brick).
5. **Make room in super**: builds the other slot's partition table the way update_engine does, using only free space next to the running ROM. Not saved yet.
6. **Mark the other slot as not ready** so a half-written slot can never boot.
7. **Write** boot images (`/dev/block/by-name/<part>_<other>`) and system partitions (into super), build the dm-verity hash tree and FEC on the phone when the ROM leaves them out, then **check every partition's SHA-256** against the ROM.
8. **Save the new partition table** (other slot's metadata only; the running slot's is never written).
9. **Switch** the active slot (`bootctl`, or the boot HAL through `service call`).
10. **Schedule format data** (`--wipe_data` in misc's bootloader message, first 2 KB only). If this fails it switches back.
11. **Reboot** to recovery: it formats data and starts the new ROM's setup.

## Never, hard-coded (the root helper enforces these, not just the UI)
- Never touches the bootloader, modem/radio, firmware, pvmfw, or bootloader lock state. Never locks the bootloader.
- Never writes the slot the phone is running on, or space in super used by the running ROM.
- Never flashes a ROM for another device or older than Android 17.
- If anything fails before the slot switch, the phone still boots the ROM it's on.

## Build
GitHub Actions builds a release APK on every push to `main` and publishes `bitSwap-v0.1.N.apk`.
Optional signing secrets: `BITSWAP_KEYSTORE_BASE64`, `BITSWAP_KEYSTORE_PASSWORD`, `BITSWAP_KEY_ALIAS`, `BITSWAP_KEY_PASSWORD`.

Payload and zip reading started from bitFlash.
