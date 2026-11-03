#!/system/bin/sh

# This is the long-lived workload supervised by NEMESIS. It prepares the
# disabled stock PAN profile, starts the APK controller, and periodically
# restores either service if Android kills it. Its output is the NEMESIS
# service log; logcat is deliberately not used as a keepalive process.

PAN_COMPONENT=com.android.bluetooth/.pan.PanService
APP_COMPONENT=org.egmp.bluetoothpan.nemesis/org.egmp.bluetoothpan.PanControllerService

start_pan() {
    pm enable --user 0 "$PAN_COMPONENT"
    am startservice -n "$PAN_COMPONENT" \
        --es action com.android.bluetooth.btservice.action.STATE_CHANGED \
        --ei android.bluetooth.adapter.extra.STATE 12
}

start_controller() {
    am startservice -n "$APP_COMPONENT"
}

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

echo "iPhone PAN: supervisor started"
start_pan
start_controller

while true; do
    sleep 15
    # Re-sending the adapter-state start request is harmless when PanService is
    # healthy and repairs the state where its ServiceRecord remains registered
    # but the profile binder stopped after a disconnect.
    start_pan >/dev/null 2>&1
    if ! dumpsys activity services "$APP_COMPONENT" 2>/dev/null \
        | grep -q 'ServiceRecord'; then
        echo "iPhone PAN: controller stopped; starting it"
        start_controller
    fi
done
