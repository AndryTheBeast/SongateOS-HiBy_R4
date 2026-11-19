# Security

Please report security problems privately, not in a public issue: use **Report a vulnerability** on this repository's
[Security](https://github.com/AndryTheBeast/SongateOS-HiBy_R4/security) tab. You'll get an answer there.

Things that count:

- a way to get an update, recovery package or Google apps zip accepted that isn't signed with the SongateOS release key
  or doesn't match its checksum;
- a way for an app without permission to use the HiBy service's system API (settings, updates, recovery switch, root);
- anything that exposes personal data (Wi-Fi, Bluetooth devices, accounts) to other apps or to the network.

Two things are known and written down, so they don't need a report:

- **Beta builds are signed with public test keys.** LineageOS's Trust warns about it. Builds for the public release
  are signed with SongateOS's own keys.
- **The recoveries can't decrypt internal storage** and offer no encryption themselves; they're for installing and
  repairing, and they are as open as any custom recovery.
