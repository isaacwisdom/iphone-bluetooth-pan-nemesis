#!/system/bin/sh

# This is the long-lived workload supervised by NEMESIS. It prepares the
# disabled stock PAN profile, starts the APK controller, and periodically
# restores either service if Android kills it. Its output is the NEMESIS
# service log. A filtered logcat child feeds APK and Bluetooth diagnostics into
# that same log; the supervisor loop remains the managed workload.

PAN_COMPONENT=com.android.bluetooth/.pan.PanService
APP_COMPONENT=org.egmp.bluetoothpan.nemesis/org.egmp.bluetoothpan.PanControllerService
LOGCAT_PID=

cleanup() {
    if [ -n "$LOGCAT_PID" ]; then
        kill "$LOGCAT_PID" >/dev/null 2>&1
        wait "$LOGCAT_PID" >/dev/null 2>&1
    fi
}

trap 'exit 0' 1 2 15
trap cleanup 0

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

echo "===== iPhone Bluetooth PAN 1.9.1 diagnostic session $(date) ====="
echo "iPhone PAN: supervisor started"
logcat -v time -s BtPanTest:I BluetoothPan:D PanService:D AndroidRuntime:E '*:S' &
LOGCAT_PID=$!
echo "iPhone PAN: diagnostic logcat pid=$LOGCAT_PID"
start_pan
start_controller

while true; do
    sleep 15
    # Do not re-send the adapter-state initialization while PanService is
    # healthy: this stock implementation can lose its per-device state when it
    # receives that start request during an active connection.
    if ! dumpsys activity services com.android.bluetooth/.pan.PanService 2>/dev/null \
        | grep -q 'ServiceRecord'; then
        echo "iPhone PAN: stock PAN service stopped; starting it"
        start_pan
    fi
    if ! dumpsys activity services "$APP_COMPONENT" 2>/dev/null \
        | grep -q 'ServiceRecord'; then
        echo "iPhone PAN: controller stopped; starting it"
        start_controller
    fi
done
