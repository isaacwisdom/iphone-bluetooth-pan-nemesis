#!/system/bin/sh
pm enable --user 0 com.android.bluetooth/.pan.PanService
am startservice -n com.android.bluetooth/.pan.PanService \
  --es action com.android.bluetooth.btservice.action.STATE_CHANGED \
  --ei android.bluetooth.adapter.extra.STATE 12
am startservice -n org.egmp.bluetoothpan.nemesis/org.egmp.bluetoothpan.PanControllerService
exit 0
