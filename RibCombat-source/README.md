# RibCombat (Paper 1.21.11)

Combat tagging with safe zones, a red glass barrier + knockback, container/command blocking, and combat-log punishment.

## Setup
1. Build: push to GitHub (Actions builds it) or run `mvn package`. Jar: `target/RibCombat.jar`.
2. Put the jar in `plugins/` and restart.
3. Make your spawn a safe zone (stand on each corner):
   - `/ribcombat pos1`
   - `/ribcombat pos2`
   - `/ribcombat create spawn`
   (It automatically covers bedrock to sky. Add `exact` to only use your corners' heights.)
4. Edit `plugins/RibCombat/config.yml` (messages, timer, blocked commands...) then `/ribcombat reload`.

## Commands
- `/combat` - see your combat timer
- `/ribcombat pos1|pos2|create|delete|list|tag|untag|reload` - admin (`ribcombat.admin`)
- `ribcombat.bypass` - never gets tagged
