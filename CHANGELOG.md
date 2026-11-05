# Changelog

## 2.0.1

- Apply the automatic-reconnect cooldown immediately after a live connection
  drops instead of making one premature retry first.
- Skip the transient connected UI state when a disconnect request arrives at
  the same moment that PAN network setup completes.
- Leave reconnect cooldown immediately if automatic reconnect is switched off.

## 2.0.0

- Replace blocking connect/disconnect routines with one serialized state
  machine driven by persistent user intent and live adapter, phone, PAN-link,
  and network observations.
- Wait for Bluetooth and its bonded-device database to stabilize before PAN
  initialization, avoiding the boot race that left PanService with no
  `bt-pan` interface.
- Keep desired connection state separate from observed state so opposite
  requests, external connections, service restarts, and reconnect cooldowns
  reconcile predictably.
- Add bounded PAN proxy recovery after repeated immediate connection drops,
  while refusing recovery whenever a PAN link or network is active.
- Derive dashboard color and status from explicit controller states and emit
  structured transition, observation, command, and result logs.

## 1.9.2

- Detect a PAN request that the remote endpoint immediately drops instead of
  leaving the dashboard in a misleading 30-second connecting state.
- Report the fast rejection within three seconds with a Personal Hotspot hint,
  while preserving the full timeout for connections that remain in progress.

## 1.9.1

- Reconcile the selected phone's real PAN and network state at startup and
  continuously, including connections initiated outside the app and drops.
- Discover an already-connected PAN device at startup, or automatically select
  the sole bonded phone when no saved selection exists.
- Make Connect idempotent for already-connected and already-connecting phones.
- Confirm both PAN disconnection and network teardown before reporting a
  successful Disconnect, with explicit timeout states otherwise.
- Retry automatic reconnect only after confirmed disconnection, with a
  30-second cooldown between attempts.
- Log only observed state transitions during idle monitoring.
- Avoid reinitializing a healthy PanService, which can erase its active-device
  state while the PAN network is still connected.
- Let an opposite button request cancel an in-progress connect/disconnect wait
  instead of sitting behind its timeout.
- Treat Android's live Bluetooth network as connected even when a restarted
  PanService has forgotten its device-state entry; do not require Java's
  unreliable `bt-pan` address enumeration.
- Use green for online, amber for transitional states, and red for offline or
  failed states in the dashboard.
- Replace the indefinite "waiting for network cleanup" message with an explicit
  disconnect-incomplete error when the PAN network remains active.

## 1.9.0

- Capture filtered application, Bluetooth PAN, and Android runtime messages in
  the NEMESIS `pan-supervisor` service log.
- Add timestamped diagnostic-session markers and stop the logcat child cleanly
  with the supervisor.
- Keep waiting after transient disconnected readings during an accepted PAN
  request, rather than reporting failure before the stock service finishes.
- Request the KitKat `GET_TASKS` permission required by this head unit when the
  activity closes, preventing the back-button crash seen in captured logs.

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
