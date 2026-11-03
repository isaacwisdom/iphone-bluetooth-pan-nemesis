# iPhone Bluetooth PAN for NEMESIS

A small Android 4.4 application and NEMESIS 1.16 package that connects a compatible Hyundai/Kia head unit to an iPhone Personal Hotspot using Bluetooth PAN.

The package uses the Bluetooth PANU implementation already present in the stock firmware. It does not replace `Bluetooth.apk`, patch the Bluetooth stack, or require HMG-API.

## Tested system

- Head unit platform: NEEV23
- Region: USA
- Firmware: 260507
- Android: 4.4.2
- NEMESIS: 1.16.2

The tested firmware is a full NEMESIS compatibility match. Other head-unit
models are shown as partial compatibility because the implementation uses the
standard Gen5W Bluetooth PAN APIs, but they have not yet been tested.

## Features

- Launchable from NEMESIS Manager's **My Packages** screen
- Paired-phone selection
- Manual connect and disconnect
- Bluetooth, DHCP, and assigned-address status
- Optional reconnect when the controller starts, disabled by default
- Automatic enablement and initialization of the stock PAN profile

## Install

### USB drive (recommended)

Download `iphone.bluetooth.pan-<version>-usb.zip` and extract it to the root of
a USB drive. The result should look like this:

```text
NEMESIS.data
README.txt
packages/
└── iphone.bluetooth.pan-<version>.zip
```

Insert the drive after Android has started and accept the NEMESIS installation
prompt.

### Package Store or ADB

The bare `iphone.bluetooth.pan-<version>.zip` is the canonical package archive
used for Package Store submission and direct installation:

```sh
adb push iphone.bluetooth.pan-1.8.1.zip /data/local/tmp/iphone.bluetooth.pan.zip
adb shell '/system/xbin/nemesis install /data/local/tmp/iphone.bluetooth.pan.zip'
adb shell '/system/xbin/nemesis pkg-launch iphone.bluetooth.pan'
```

On the iPhone, enable **Settings → Personal Hotspot → Allow Others to Join**. The phone must already be paired with the vehicle.

## Diagnostics

```sh
adb shell '/system/xbin/nemesis service status iphone.bluetooth.pan/pan-supervisor'
adb shell '/system/xbin/nemesis log service iphone.bluetooth.pan/pan-supervisor'
adb shell 'logcat -v time -s BtPanTest:I AndroidRuntime:E'
```

A successful connection creates `bt-pan`, normally with an address in the iPhone's `172.20.10.0/28` network.

## Build

Requirements:

- JDK 17
- Android SDK platform and build-tools (`aapt2`, `d8`, `apksigner`)
- `zip` and Python 3

Set `ANDROID_HOME`, then run:

```sh
./build.sh
```

The build produces exactly two release artifacts in `dist/`:

- `iphone.bluetooth.pan-<version>-usb.zip` — recommended USB distribution
- `iphone.bluetooth.pan-<version>.zip` — Package Store and direct-install archive

You may provide an external signing key:

```sh
APP_KEY=/secure/path/iphone-pan.p12 APP_KEY_PASS='…' ./build.sh
```

Without `APP_KEY`, the build creates a temporary development key. NEMESIS package
updates uninstall the previous APK before installing its replacement, so APK
signer continuity is not required. This also means an update resets the app's
saved phone selection and reconnect preference. Keys and build output are
ignored by Git and must never be committed.

## Safety and limitations

This is experimental software for an in-vehicle infotainment system. Do not interact with it while driving. Automatic reconnect is opt-in. The current package has only been tested on the system listed above.

## License

GPL-3.0-or-later.
