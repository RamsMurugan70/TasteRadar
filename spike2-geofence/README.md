# Spike 2 — do the alerts fire

```bash
cd spike2-geofence
# Android Studio: File > Open, let it generate the Gradle wrapper and sync.
# It will likely offer to upgrade AGP and Kotlin — accept.
```

Install on the phone you actually carry, then:

1. **Grant location permissions.** Two separate requests. On Android 11+ the
   background one opens Settings rather than a dialog — pick *Allow all the time*.
2. **Drop a fence here**, at ten or fifteen places you pass in a normal week:
   home, office, the usual junction, a couple of restaurants. No coordinates to
   type; it uses your current fix.
3. **Register all fences.**
4. Then forget about it for a week. Swipe the app away. Reboot the phone at
   least once — that is the test that matters most.

Afterwards:

```bash
adb pull /sdcard/Android/data/com.tasteradar.spike/files/fence-log.csv
```

Columns: `time, fence_id, fence_name, transition, report_delay_ms, accuracy_m, lat, lng`.

## What to look for

- **Did anything fire at all after the reboot?** If not, the `BootReceiver` is
  being killed by the OEM battery manager. That is the single most common way a
  geofencing app dies, and the fix is the battery-optimisation exemption.
- **`report_delay_ms`** is the gap between when the OS located you and when the
  app heard about it. Responsiveness is set to 0 here, i.e. as fast as Android
  will go. Whatever you see is the *best* case; production at 30–60s will be worse.
- **Missed crossings.** You know where you went. Count the places you passed that
  produced no row.
- **Duplicate ENTERs** on the same visit mean the radius is too tight for the GPS
  accuracy in that spot. 150m is the default here; central Chennai may need more.

## Deliberate choices worth knowing

- `PendingIntent` is **MUTABLE**. With `FLAG_IMMUTABLE` every event arrives empty
  and the app looks broken for no visible reason.
- `setInitialTrigger(0)` — no alert for fences you are already standing in.
- ENTER, EXIT and DWELL are all logged; DWELL after 60s. The real app will mostly
  care about DWELL, since passing a place at speed is not an opportunity.
