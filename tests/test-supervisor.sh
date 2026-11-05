#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d "${TMPDIR:-/tmp}/iphone-pan-supervisor.XXXXXX")
MOCK="$TMP/bin"
mkdir -p "$MOCK"
trap 'rm -rf "$TMP"' 0

make_mock() {
    name=$1
    shift
    printf '%s\n' '#!/bin/sh' "$@" > "$MOCK/$name"
    chmod 755 "$MOCK/$name"
}

make_mock getprop 'printf "%s\n" 1'
make_mock pm 'printf "pm %s\n" "$*" >> "$TEST_TMP/calls"'
make_mock am 'printf "am %s\n" "$*" >> "$TEST_TMP/calls"'
make_mock dumpsys 'printf "%s\n" "ServiceRecord{test org.egmp.bluetoothpan.nemesis/.PanControllerService}"'
make_mock logcat \
    'printf "%s\n" "$*" > "$TEST_TMP/logcat-args"' \
    'printf "%s\n" "$$" > "$TEST_TMP/logcat-pid"' \
    'printf "%s\n" "11-03 12:00:00.000 I/BtPanTest( 123): captured diagnostic"' \
    'trap "exit 0" 1 2 15' \
    'while true; do /bin/sleep 1; done'
make_mock sleep \
    'if [ "${1:-}" = 15 ]; then' \
    '    target=$PPID' \
    '    (/bin/sleep 0.2; kill -TERM "$target") &' \
    '    /bin/sleep 1' \
    'else' \
    '    /bin/sleep 0.01' \
    'fi'

TEST_TMP="$TMP" PATH="$MOCK:$PATH" /bin/sh "$ROOT/nemesis/svc/pan-control.sh" > "$TMP/output" 2>&1

grep -q 'iPhone Bluetooth PAN 2.0.1 diagnostic session' "$TMP/output"
grep -q 'I/BtPanTest.*captured diagnostic' "$TMP/output"
grep -q 'pm enable --user 0 com.android.bluetooth/.pan.PanService' "$TMP/calls"
grep -q 'am startservice -n com.android.bluetooth/.pan.PanService' "$TMP/calls"
grep -q 'am startservice -n org.egmp.bluetoothpan.nemesis/org.egmp.bluetoothpan.PanControllerService' "$TMP/calls"
grep -q 'BtPanTest:I BluetoothPan:D PanService:D AndroidRuntime:E \*:S' "$TMP/logcat-args"

logcat_pid=$(cat "$TMP/logcat-pid")
if kill -0 "$logcat_pid" 2>/dev/null; then
    echo "logcat child was not stopped" >&2
    exit 1
fi

echo "supervisor logging test: PASS"
