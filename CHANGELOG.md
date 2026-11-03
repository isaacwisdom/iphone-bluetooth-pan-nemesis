# Changelog

## 1.8.1

- Reacquire the Bluetooth PAN profile proxy when the stock service disconnects,
  allowing subsequent connections without restarting the app.
- Periodically reassert stock PAN initialization to repair a stale registered
  service whose profile binder is no longer available.
- Document the canonical NEMESIS update behavior: the previous APK is
  uninstalled before its replacement is installed, so signer continuity is not
  required and app preferences reset on update.

## 1.8.0

- Use the NEMESIS firmware identifier for compatibility and advertise untested
  head-unit models as partial matches.
- Replace the duplicate post-install startup and logcat keepalive with one
  genuine NEMESIS-supervised PAN/controller service.
- Present the canonical USB bundle as the primary user download and keep build
  output limited to the USB bundle and bare Package Store archive.

## 1.7.0

- Crop transparent icon padding so the artwork fills the NEMESIS launcher slot.

## 1.6.0

- Use a calmer teal-blue tile and enlarge/thicken the globe and Bluetooth glyph for launcher-size legibility.

## 1.5.0

- Refine the icon to a full wireframe globe with a smaller centered Bluetooth rune.

## 1.4.0

- Simplify the launcher artwork to a dark globe/Bluetooth glyph on a cyan tile matching NEMESIS's Precondition icon.

## 1.3.0

- Add a purpose-built launcher icon matching the dark dashboard.

## 1.2.0

- Scale the dashboard to the head unit's actual display resolution.
- Increase secondary-label and footer text sizes.

## 1.1.0

- Replace stock Holo controls with a dark, touch-friendly dashboard.

## 1.0.0

- Initial standalone NEMESIS 1.16 release.
- Manual connect and disconnect, connection status, IP display, and optional startup reconnect.
