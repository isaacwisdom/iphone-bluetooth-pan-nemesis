# iPhone Bluetooth PAN for NEMESIS

A small Android 4.4 application and NEMESIS 1.16 package that connects a compatible Hyundai/Kia head unit to an iPhone Personal Hotspot using Bluetooth PAN.

The package uses the Bluetooth PANU implementation already present in the stock firmware. It does not replace `Bluetooth.apk`, patch the Bluetooth stack, or require HMG-API.

## Tested system

- Head unit platform: NEEV23
- Region: USA
- Firmware: 260507
- Android: 4.4.2
- NEMESIS: 1.16.2

Other systems are not currently declared compatible.

## Features

- Launchable from NEMESIS Manager's **My Packages** screen
- Paired-phone selection
- Manual connect and disconnect
- Bluetooth, DHCP, and assigned-address status
- Optional reconnect when the controller starts, disabled by default
- Automatic enablement and initialization of the stock PAN profile

## Install

Download `iphone.bluetooth.pan-<version>.zip`, then:

```sh
adb push iphone.bluetooth.pan-1.7.0.zip /data/local/tmp/iphone.bluetooth.pan.zip
adb shell '/system/xbin/nemesis install /data/local/tmp/iphone.bluetooth.pan.zip'
adb shell '/system/xbin/nemesis pkg-launch iphone.bluetooth.pan'
```

For USB installation, extract the `-usb.zip` archive to the root of a USB drive and insert it after Android has started.

On the iPhone, enable **Settings → Personal Hotspot → Allow Others to Join**. The phone must already be paired with the vehicle.

## Diagnostics

```sh
adb shell '/system/xbin/nemesis service status iphone.bluetooth.pan/pan-control'
adb shell '/system/xbin/nemesis log service iphone.bluetooth.pan/pan-control'
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

For release-compatible APK updates, provide the same external signing key each time:

```sh
APP_KEY=/secure/path/iphone-pan.p12 APP_KEY_PASS='…' ./build.sh
```

Without `APP_KEY`, the build creates a local development key under `.build/`. Keys and build output are ignored by Git and must never be committed.

## Safety and limitations

This is experimental software for an in-vehicle infotainment system. Do not interact with it while driving. Automatic reconnect is opt-in. The current package has only been tested on the system listed above.

## License

GPL-3.0-or-later.
