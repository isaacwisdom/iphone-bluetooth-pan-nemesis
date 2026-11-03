#!/system/bin/sh
echo "iPhone PAN: enabling stock PAN profile"
pm enable --user 0 com.android.bluetooth/.pan.PanService
echo "iPhone PAN: initializing stock PAN profile"
am startservice -n com.android.bluetooth/.pan.PanService \
  --es action com.android.bluetooth.btservice.action.STATE_CHANGED \
  --ei android.bluetooth.adapter.extra.STATE 12
echo "iPhone PAN: starting controller"
am startservice -n org.egmp.bluetoothpan.nemesis/org.egmp.bluetoothpan.PanControllerService
echo "iPhone PAN: controller started; following log"
exec logcat -v time -s BtPanTest:I AndroidRuntime:E
