# Read this first

SongateOS changes the software of your HiBy R4 at the lowest level: its bootloader must be unlocked, its partitions
are written with EDL, fastboot and a custom recovery. Done carefully, following the guides, this is safe and fully
reversible. Done in a hurry, it can leave a player that doesn't start.

## You do this at your own risk

**SongateOS is provided as it is, with no warranty of any kind. Its author is not responsible for any damage, data loss
or unusable ("bricked") player that results from installing, using or removing it.** Unlocking the bootloader and
installing another system may also end your warranty with HiBy.

A player that stops booting is almost never caused by SongateOS itself. It happens when steps are skipped, run in a
different order, run on the wrong model, or "sped up". Every guide here was written step by step, on a real R4, for a
reason.

## Before you start

- **Read every guide fully before you run its first command.** Then follow it exactly as written, one step at a time.
- **Make your own EDL backup first** ([guide 1](01-backup-edl.md)) and check its checksums. It is the only thing that
  brings your own player back, whatever happens.
- **Only the HiBy R4 standard model.** Never the R4 EVA, and never another player.
- **Never flash anything made for another device**, and never someone else's backup.
- **Stay on slot a**, never relock the bootloader, and never write the partitions the guides tell you to leave alone.
- **If something looks different from what a guide describes, stop.** Don't improvise. Ask in
  [Discussions](https://github.com/AndryTheBeast/SongateOS-HiBy_R4/discussions) and include what you saw.

If you're not comfortable with a command line, EDL and fastboot, it's fine to wait, or to ask someone experienced to
help you through it.
