package org.egmp.bluetoothpan;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
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
import java.util.List;
import java.util.Set;

public final class PanControllerService extends Service {
    private static final String TAG = "BtPanTest";
    private static final int PROFILE_PAN = 5;
    private static final int DISCONNECTED = 0;
    private static final int CONNECTING = 1;
    private static final int CONNECTED = 2;
    private static final int DISCONNECTING = 3;
    private static final int UNKNOWN = -1;
    private volatile BluetoothProfile pan;
    private volatile boolean stop;
    private BluetoothAdapter adapter;
    private BluetoothDevice owned;
    private Thread worker;
    private SharedPreferences prefs;
    private volatile long proxyRequestAt;
    private long nextAutomaticAttemptAt;
    private int lastObservedState = Integer.MIN_VALUE;
    private boolean lastObservedNetwork;
    private String lastObservedDevice = "";

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            pan = proxy;
            proxyRequestAt = 0L;
            lastObservedState = Integer.MIN_VALUE;
            log("PAN profile ready: " + proxy.getClass().getName());
            setStatus("Bluetooth PAN profile ready");
        }
        @Override public void onServiceDisconnected(int profile) {
            pan = null;
            proxyRequestAt = 0L;
            lastObservedState = Integer.MIN_VALUE;
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
        while (!stop) {
            int sequence = prefs.getInt("request_sequence", 0);
            if (sequence != handled) {
                if (prefs.getBoolean("requested_connect", false)) {
                    nextAutomaticAttemptAt = System.currentTimeMillis() + 30000L;
                    connectSelected();
                } else {
                    disconnectSelected();
                }
                handled = sequence;
                prefs.edit().putInt("handled_sequence", handled).commit();
            } else {
                int state = observeSelected();
                long now = System.currentTimeMillis();
                if (state == DISCONNECTED && prefs.getBoolean("automatic", false)
                        && prefs.getBoolean("requested_connect", false)
                        && now >= nextAutomaticAttemptAt) {
                    nextAutomaticAttemptAt = now + 30000L;
                    log("automatic reconnect after confirmed disconnection");
                    connectSelected();
                }
            }
            sleep(1000L);
        }
    }

    private void queue(boolean connect) {
        int sequence = prefs.getInt("request_sequence", 0) + 1;
        prefs.edit().putBoolean("requested_connect", connect).putInt("request_sequence", sequence).commit();
    }

    private void connectSelected() {
        BluetoothDevice d = selected(true);
        if (d == null || !waitForProxy()) return;
        int before = connectionState(d, true);
        log("connect request for " + label(d) + "; state before=" + before);
        if (panNetworkReady()) {
            owned = d;
            log("usable bt-pan network already exists; skipping connect call");
            reportConnected();
            return;
        }
        if (before == DISCONNECTING) {
            setStatus("Waiting for the previous disconnect…");
            for (int i = 0; i < 10 && !stop && before == DISCONNECTING; i++) {
                sleep(1000L);
                before = connectionState(d, false);
            }
        }
        if (before == DISCONNECTED || before == UNKNOWN) {
            setStatus("Connecting to " + name(d) + "…");
            Object accepted = invoke("connect", d, true);
            if (!Boolean.TRUE.equals(accepted)) {
                setStatus("Connection request was refused by the PAN service");
                return;
            }
        } else if (before == CONNECTING) {
            setStatus("Connection already in progress…");
        } else if (before == CONNECTED) {
            setStatus("Bluetooth connected; waiting for an IP address");
        } else {
            setStatus("PAN is busy; try again shortly");
            return;
        }
        owned = d;
        for (int i = 0; i < 30 && !stop; i++) {
            sleep(1000L);
            if (!prefs.getBoolean("requested_connect", true)) {
                log("connection wait cancelled by disconnect request");
                return;
            }
            int state = connectionState(d, false);
            log("connection state=" + state + "; " + networkSummary());
            if (panNetworkReady()) { reportConnected(); return; }
        }
        int state = connectionState(d, true);
        if (state == CONNECTED) setStatus("Bluetooth connected; waiting for an IP address");
        else setStatus("Connection failed or timed out");
    }

    private void disconnectSelected() {
        BluetoothDevice d = owned != null ? owned : selected(true);
        if (d == null || !waitForProxy()) return;
        int state = connectionState(d, true);
        log("disconnect request for " + label(d) + "; state before=" + state);
        if (state == DISCONNECTED && !panNetworkReady()) {
            owned = null;
            setStatus("Disconnected");
            return;
        }
        setStatus("Disconnecting from " + name(d) + "…");
        if (state != DISCONNECTING) {
            Object accepted = invoke("disconnect", d, true);
            if (!Boolean.TRUE.equals(accepted))
                log("disconnect was not accepted; waiting for actual state before reporting failure");
        }
        for (int i = 0; i < 15 && !stop; i++) {
            sleep(1000L);
            if (prefs.getBoolean("requested_connect", false)) {
                log("disconnect wait cancelled by connect request");
                return;
            }
            state = connectionState(d, false);
            boolean network = panNetworkReady();
            log("disconnect state=" + state + "; " + networkSummary());
            if (state == DISCONNECTED && !network) {
                owned = null;
                setStatus("Disconnected");
                return;
            }
        }
        state = connectionState(d, true);
        if (state == DISCONNECTED)
            setStatus("Disconnect incomplete — PAN network still active");
        else
            setStatus("Disconnect failed or timed out");
    }

    private BluetoothDevice selected(boolean reportMissing) {
        String address = prefs.getString("device", "");
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) for (BluetoothDevice d : bonded) {
            if (d.getAddress().equalsIgnoreCase(address)) return d;
        }
        if (address.length() == 0 && pan != null) {
            try {
                List<BluetoothDevice> connected = pan.getConnectedDevices();
                if (connected != null && connected.size() == 1)
                    return rememberDiscovered(connected.get(0), "connected PAN device");
            } catch (Throwable t) {
                log("connected PAN device discovery failed: " + rootMessage(t));
            }
        }
        BluetoothDevice onlyPhone = null;
        int phoneCount = 0;
        if (address.length() == 0 && bonded != null) for (BluetoothDevice d : bonded) {
            BluetoothClass type = d.getBluetoothClass();
            if (type != null && type.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE) {
                onlyPhone = d;
                phoneCount++;
            }
        }
        if (phoneCount == 1) return rememberDiscovered(onlyPhone, "only bonded phone");
        if (reportMissing)
            setStatus(address.length() == 0 ? "Select a paired iPhone" : "Selected phone is no longer paired");
        return null;
    }

    private BluetoothDevice rememberDiscovered(BluetoothDevice d, String reason) {
        prefs.edit().putString("device", d.getAddress()).commit();
        log("selected " + label(d) + " as " + reason);
        return d;
    }

    private int observeSelected() {
        BluetoothDevice d = selected(true);
        if (d == null) return UNKNOWN;
        if (pan == null) {
            requestProfileProxy();
            return UNKNOWN;
        }
        int state = connectionState(d, false);
        boolean network = panNetworkReady();
        String address = d.getAddress();
        if (!address.equals(lastObservedDevice) || state != lastObservedState || network != lastObservedNetwork) {
            log("observed " + label(d) + ": state=" + state + "; " + networkSummary());
            lastObservedDevice = address;
            lastObservedState = state;
            lastObservedNetwork = network;
            if (network) {
                owned = d;
                reportConnected();
            } else if (state == CONNECTED) {
                owned = d;
                setStatus("Bluetooth connected; waiting for an IP address");
            } else if (state == CONNECTING) {
                setStatus("Connecting to " + name(d) + "…");
            } else if (state == DISCONNECTING) {
                setStatus("Disconnecting from " + name(d) + "…");
            } else if (state == DISCONNECTED) {
                owned = null;
                setStatus("Disconnected");
            } else {
                setStatus("Bluetooth PAN state unavailable");
            }
        }
        return network ? CONNECTED : state;
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

    private int connectionState(BluetoothDevice d, boolean report) {
        Object value = invoke("getConnectionState", d, report);
        return value instanceof Integer ? ((Integer) value).intValue() : -1;
    }

    private Object invoke(String name, BluetoothDevice d, boolean report) {
        BluetoothProfile profile = pan;
        if (profile == null) return null;
        try {
            Method method = profile.getClass().getMethod(name, BluetoothDevice.class);
            method.setAccessible(true);
            Object result = method.invoke(profile, d);
            if (report) log(name + "(" + label(d) + ") returned " + result);
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

    private boolean panNetworkReady() {
        // ConnectivityManager's Bluetooth tracker is reliable on this unit,
        // while NetworkInterface enumeration may omit bt-pan or its IPv4
        // address even when traffic is already routed through it.
        return networkConnected();
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
        if (value.equals(prefs.getString("status", ""))) return;
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
