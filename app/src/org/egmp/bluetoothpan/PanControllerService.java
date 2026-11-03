package org.egmp.bluetoothpan;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

public final class PanControllerService extends Service {
    private static final String TAG = "BtPanTest";
    private static final int PROFILE_PAN = 5;
    private static final int DISCONNECTED = 0;
    private static final int CONNECTED = 2;
    private volatile BluetoothProfile pan;
    private volatile boolean stop;
    private BluetoothAdapter adapter;
    private BluetoothDevice owned;
    private Thread worker;
    private SharedPreferences prefs;
    private volatile long proxyRequestAt;

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            pan = proxy;
            proxyRequestAt = 0L;
            log("PAN profile ready: " + proxy.getClass().getName());
            setStatus("Bluetooth PAN profile ready");
        }
        @Override public void onServiceDisconnected(int profile) {
            pan = null;
            proxyRequestAt = 0L;
            log("PAN profile disconnected");
            setStatus("Bluetooth PAN profile unavailable");
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        adapter = BluetoothAdapter.getDefaultAdapter();
        log("Android service created; package=" + getPackageName());
        if (adapter == null) { setStatus("No Bluetooth adapter found"); stopSelf(); return; }
        requestProfileProxy();
        worker = new Thread("pan-control") {
            @Override public void run() { controlLoop(); }
        };
        worker.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "disconnect".equals(intent.getStringExtra("command"))) queue(false);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stop = true;
        if (worker != null) worker.interrupt();
        try { if (pan != null) adapter.closeProfileProxy(PROFILE_PAN, pan); } catch (Throwable ignored) {}
        log("Android service destroyed");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void controlLoop() {
        int handled = prefs.getInt("handled_sequence", 0);
        if (prefs.getBoolean("automatic", false) && prefs.getBoolean("requested_connect", false)) {
            connectSelected();
        }
        while (!stop) {
            int sequence = prefs.getInt("request_sequence", 0);
            if (sequence != handled) {
                if (prefs.getBoolean("requested_connect", false)) connectSelected(); else disconnectSelected();
                handled = sequence;
                prefs.edit().putInt("handled_sequence", handled).commit();
            }
            sleep(1000L);
        }
    }

    private void queue(boolean connect) {
        int sequence = prefs.getInt("request_sequence", 0) + 1;
        prefs.edit().putBoolean("requested_connect", connect).putInt("request_sequence", sequence).commit();
    }

    private void connectSelected() {
        BluetoothDevice d = selected();
        if (d == null || !waitForProxy()) return;
        int before = connectionState(d);
        log("connect request for " + label(d) + "; state before=" + before);
        if (before == CONNECTED) { owned = d; reportConnected(); return; }
        setStatus("Connecting to " + name(d) + "…");
        Object accepted = invoke("connect", d);
        if (!Boolean.TRUE.equals(accepted)) {
            setStatus("Connection request was refused by the PAN service");
            return;
        }
        owned = d;
        for (int i = 0; i < 30 && !stop; i++) {
            sleep(1000L);
            int state = connectionState(d);
            log("connection state=" + state + "; " + networkSummary());
            if (state == DISCONNECTED) break;
            if (state == CONNECTED && networkConnected()) { reportConnected(); return; }
        }
        int state = connectionState(d);
        if (state == CONNECTED) setStatus("Bluetooth connected; waiting for an IP address");
        else setStatus("Connection failed or timed out");
    }

    private void disconnectSelected() {
        BluetoothDevice d = owned != null ? owned : selected();
        if (d == null || !waitForProxy()) return;
        int state = connectionState(d);
        log("disconnect request for " + label(d) + "; state before=" + state);
        if (state != DISCONNECTED) invoke("disconnect", d);
        owned = null;
        setStatus("Disconnected");
    }

    private BluetoothDevice selected() {
        String address = prefs.getString("device", "");
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) for (BluetoothDevice d : bonded) {
            if (d.getAddress().equalsIgnoreCase(address)) return d;
        }
        setStatus(address.length() == 0 ? "Select a paired iPhone" : "Selected phone is no longer paired");
        return null;
    }

    private boolean waitForProxy() {
        for (int i = 0; pan == null && i < 60 && !stop; i++) {
            requestProfileProxy();
            sleep(500L);
        }
        if (pan == null) setStatus("Bluetooth PAN profile did not become ready");
        return pan != null;
    }

    private synchronized void requestProfileProxy() {
        if (adapter == null || pan != null || stop) return;
        long now = System.currentTimeMillis();
        if (proxyRequestAt != 0L && now - proxyRequestAt < 5000L) return;
        proxyRequestAt = now;
        try {
            boolean requested = adapter.getProfileProxy(this, listener, PROFILE_PAN);
            log("PAN profile requested=" + requested);
            if (!requested) proxyRequestAt = 0L;
        } catch (Throwable t) {
            proxyRequestAt = 0L;
            setStatus("PAN profile request failed: " + rootMessage(t));
        }
    }

    private int connectionState(BluetoothDevice d) {
        Object value = invoke("getConnectionState", d);
        return value instanceof Integer ? ((Integer) value).intValue() : -1;
    }

    private Object invoke(String name, BluetoothDevice d) {
        BluetoothProfile profile = pan;
        if (profile == null) return null;
        try {
            Method method = profile.getClass().getMethod(name, BluetoothDevice.class);
            method.setAccessible(true);
            Object result = method.invoke(profile, d);
            log(name + "(" + label(d) + ") returned " + result);
            return result;
        } catch (Throwable t) {
            log(name + " failed: " + rootMessage(t));
            return null;
        }
    }

    private boolean networkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getNetworkInfo(7);
            return info != null && info.isConnected();
        } catch (Throwable ignored) { return false; }
    }

    private String networkSummary() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getNetworkInfo(7);
            return info == null ? "network missing" : info.getState() + "/" + info.getDetailedState();
        } catch (Throwable t) { return "network query failed"; }
    }

    private void reportConnected() {
        String address = interfaceAddress("bt-pan");
        setStatus("Connected through iPhone" + (address.length() == 0 ? "" : "\nAddress: " + address));
    }

    private static String interfaceAddress(String wanted) {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface n : Collections.list(all)) if (wanted.equals(n.getName())) {
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    String value = a.getHostAddress();
                    if (value.indexOf(':') < 0) return value;
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void setStatus(String value) {
        prefs.edit().putString("status", value).commit();
        log(value.replace('\n', ' '));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
    private static String name(BluetoothDevice d) { return d.getName() == null ? "iPhone" : d.getName(); }
    private static String label(BluetoothDevice d) { return name(d) + " (" + d.getAddress() + ")"; }
    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getName() + ": " + t.getMessage();
    }
    private static void log(String value) { Log.i(TAG, value); }
}
