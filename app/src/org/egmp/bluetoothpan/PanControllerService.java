package org.egmp.bluetoothpan;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
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
    private static final long BLUETOOTH_STABLE_MS = 5000L;
    private static final long PHONE_GRACE_MS = 15000L;
    private static final long PROXY_TIMEOUT_MS = 30000L;
    private static final long CONNECT_TIMEOUT_MS = 30000L;
    private static final long NETWORK_TIMEOUT_MS = 20000L;
    private static final long DISCONNECT_TIMEOUT_MS = 15000L;
    private static final long RECONNECT_COOLDOWN_MS = 30000L;

    private enum Phase {
        INITIALIZING, WAITING_FOR_BLUETOOTH, BLUETOOTH_SETTLING,
        WAITING_FOR_PHONE, STARTING_PAN, READY_DISCONNECTED, CONNECTING,
        CONFIGURING_NETWORK, CONNECTED, DISCONNECTING, RECONNECT_COOLDOWN,
        RECOVERING_PAN, ERROR
    }

    private volatile BluetoothProfile pan;
    private volatile boolean stop;
    private BluetoothAdapter adapter;
    private BluetoothDevice selected;
    private Thread worker;
    private SharedPreferences prefs;
    private Phase phase = Phase.INITIALIZING;
    private long adapterStableSince;
    private long phoneMissingSince;
    private long proxyRequestAt;
    private long proxyWaitStarted;
    private long phaseDeadline;
    private long nextReconnectAt;
    private int handledSequence;
    private int activeRequestId;
    private int lastLink = Integer.MIN_VALUE;
    private boolean lastNetwork;
    private boolean connectArmed;
    private boolean disconnectIssued;
    private int disconnectedConnectPolls;
    private int immediateFailureCount;
    private int recoveryCount;

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            pan = proxy;
            proxyRequestAt = 0L;
            proxyWaitStarted = 0L;
            log("event=PAN_PROXY_READY class=" + proxy.getClass().getName());
        }
        @Override public void onServiceDisconnected(int profile) {
            pan = null;
            proxyRequestAt = 0L;
            log("event=PAN_PROXY_LOST");
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        adapter = BluetoothAdapter.getDefaultAdapter();
        handledSequence = prefs.getInt("handled_sequence", 0);
        connectArmed = prefs.getBoolean("requested_connect", false);
        log("controller=created package=" + getPackageName());
        if (adapter == null) {
            transition(Phase.ERROR, "NO_ADAPTER", "No Bluetooth adapter found");
            stopSelf();
            return;
        }
        worker = new Thread("pan-state-machine") {
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
        closeProxy();
        log("controller=destroyed");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void controlLoop() {
        transition(Phase.WAITING_FOR_BLUETOOTH, "SERVICE_START", "Checking Bluetooth");
        while (!stop) {
            try { tick(); } catch (Throwable t) {
                log("tick failed: " + rootMessage(t));
                transition(Phase.ERROR, "TICK_FAILED", "Bluetooth PAN controller error");
            }
            sleep(1000L);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        consumeUserIntent(now);
        if (adapter.getState() != BluetoothAdapter.STATE_ON) {
            adapterStableSince = 0L;
            phoneMissingSince = 0L;
            proxyWaitStarted = 0L;
            selected = null;
            closeProxy();
            transition(Phase.WAITING_FOR_BLUETOOTH, "ADAPTER_NOT_ON", "Bluetooth is off");
            return;
        }
        if (adapterStableSince == 0L) {
            adapterStableSince = now;
            transition(Phase.BLUETOOTH_SETTLING, "ADAPTER_ON", "Bluetooth is starting");
            return;
        }
        if (now - adapterStableSince < BLUETOOTH_STABLE_MS) {
            transition(Phase.BLUETOOTH_SETTLING, "STABILITY_WINDOW", "Bluetooth is starting");
            return;
        }
        selected = resolveSelected();
        if (selected == null) {
            if (phoneMissingSince == 0L) phoneMissingSince = now;
            transition(Phase.WAITING_FOR_PHONE, "PHONE_NOT_VISIBLE",
                    now - phoneMissingSince < PHONE_GRACE_MS
                            ? "Waiting for paired phone" : "Paired phone unavailable");
            return;
        }
        phoneMissingSince = 0L;
        if (pan == null) {
            if (proxyWaitStarted == 0L) proxyWaitStarted = now;
            requestProfileProxy(now);
            if (now - proxyWaitStarted > PROXY_TIMEOUT_MS) {
                proxyRequestAt = 0L;
                transition(Phase.ERROR, "PROXY_TIMEOUT", "Bluetooth PAN service unavailable");
            } else {
                transition(phase == Phase.RECOVERING_PAN ? Phase.RECOVERING_PAN : Phase.STARTING_PAN,
                        "PROXY_PENDING", phase == Phase.RECOVERING_PAN
                                ? "Repairing Bluetooth PAN" : "Starting Bluetooth PAN");
            }
            return;
        }

        int link = connectionState(selected, false);
        boolean network = networkConnected();
        logObservation(link, network);
        if (network) {
            proxyWaitStarted = 0L;
            immediateFailureCount = 0;
            recoveryCount = 0;
            connectArmed = false;
            disconnectIssued = false;
            if (!desiredConnected()) beginDisconnect(now, link);
            else transition(Phase.CONNECTED, "NETWORK_CONNECTED", connectedMessage());
            return;
        }
        if (!desiredConnected()) {
            reconcileDisconnectedIntent(now, link);
            return;
        }
        reconcileConnectedIntent(now, link);
    }

    private void consumeUserIntent(long now) {
        int sequence = prefs.getInt("request_sequence", 0);
        if (sequence == handledSequence) return;
        handledSequence = sequence;
        activeRequestId = sequence;
        prefs.edit().putInt("handled_sequence", sequence).commit();
        boolean connect = desiredConnected();
        log("event=USER_INTENT request=" + sequence + " desired=" + (connect ? "CONNECTED" : "DISCONNECTED"));
        connectArmed = connect;
        disconnectIssued = false;
        disconnectedConnectPolls = 0;
        phaseDeadline = 0L;
        nextReconnectAt = now;
        if (connect && pan == null) proxyWaitStarted = now;
    }

    private void reconcileConnectedIntent(long now, int link) {
        if (link == CONNECTED) {
            boolean retryRequested = connectArmed;
            connectArmed = false;
            if (phase == Phase.ERROR && !retryRequested) return;
            if (phase != Phase.CONFIGURING_NETWORK) {
                phaseDeadline = now + NETWORK_TIMEOUT_MS;
                transition(Phase.CONFIGURING_NETWORK, "PAN_LINK_CONNECTED",
                        "Bluetooth connected; configuring network");
            } else if (now >= phaseDeadline) {
                transition(Phase.ERROR, "NETWORK_TIMEOUT", "Connected to iPhone, but no network");
            }
            return;
        }
        if (link == CONNECTING) {
            disconnectedConnectPolls = 0;
            transition(Phase.CONNECTING, "PAN_LINK_CONNECTING", "Connecting to " + name(selected));
            if (phaseDeadline == 0L) phaseDeadline = now + CONNECT_TIMEOUT_MS;
            if (now >= phaseDeadline) finishConnectFailure(now, "CONNECT_TIMEOUT", "iPhone did not respond");
            return;
        }
        if (link == DISCONNECTING) {
            if (phaseDeadline == 0L) phaseDeadline = now + DISCONNECT_TIMEOUT_MS;
            if (now >= phaseDeadline) {
                transition(Phase.ERROR, "STALE_DISCONNECT", "Previous disconnect did not finish");
                return;
            }
            transition(Phase.DISCONNECTING, "PAN_LINK_DISCONNECTING", "Waiting for previous disconnect");
            return;
        }
        if (phase == Phase.CONNECTING) {
            disconnectedConnectPolls++;
            if (disconnectedConnectPolls >= 3) {
                immediateFailureCount++;
                if (immediateFailureCount >= 2 && recoveryCount == 0) beginPanRecovery(now);
                else finishConnectFailure(now, "IMMEDIATE_DROP", "PAN rejected; check Personal Hotspot");
            } else if (now >= phaseDeadline) {
                finishConnectFailure(now, "CONNECT_TIMEOUT", "iPhone did not respond");
            }
            return;
        }
        if (phase == Phase.RECOVERING_PAN) {
            if (now >= phaseDeadline) {
                connectArmed = true;
                transition(Phase.READY_DISCONNECTED, "RECOVERY_COMPLETE", "Bluetooth PAN repaired");
            }
            return;
        }
        if (connectArmed) {
            beginConnect(now, link);
            return;
        }
        if (phase == Phase.CONNECTED || phase == Phase.CONFIGURING_NETWORK) {
            nextReconnectAt = now + RECONNECT_COOLDOWN_MS;
            transition(prefs.getBoolean("automatic", false) ? Phase.RECONNECT_COOLDOWN : Phase.READY_DISCONNECTED,
                    "CONNECTION_LOST", prefs.getBoolean("automatic", false)
                            ? "Connection lost; retrying shortly" : "Connection lost");
            return;
        }
        if (prefs.getBoolean("automatic", false) && now >= nextReconnectAt) {
            connectArmed = true;
            beginConnect(now, link);
            return;
        }
        if (phase == Phase.RECONNECT_COOLDOWN) {
            if (prefs.getBoolean("automatic", false))
                transition(Phase.RECONNECT_COOLDOWN, "COOLDOWN", "Connection lost; retrying shortly");
            else transition(Phase.READY_DISCONNECTED, "AUTO_RECONNECT_DISABLED", "Connection lost");
        } else if (phase != Phase.ERROR && phase != Phase.READY_DISCONNECTED) {
            transition(Phase.READY_DISCONNECTED, "IDLE", "Disconnected");
        }
    }

    private void reconcileDisconnectedIntent(long now, int link) {
        connectArmed = false;
        nextReconnectAt = 0L;
        if (link == DISCONNECTED || link == UNKNOWN) {
            disconnectIssued = false;
            phaseDeadline = 0L;
            transition(Phase.READY_DISCONNECTED, "DISCONNECT_CONFIRMED", "Disconnected");
            return;
        }
        if (phaseDeadline != 0L && now >= phaseDeadline) {
            transition(Phase.ERROR, "DISCONNECT_TIMEOUT", "Disconnect failed or timed out");
            return;
        }
        beginDisconnect(now, link);
    }

    private void beginConnect(long now, int link) {
        if (link != DISCONNECTED && link != UNKNOWN) return;
        Object accepted = invoke("connect", selected, true);
        log("action=PAN_CONNECT request=" + activeRequestId + " accepted=" + accepted);
        connectArmed = false;
        disconnectedConnectPolls = 0;
        phaseDeadline = now + CONNECT_TIMEOUT_MS;
        if (Boolean.TRUE.equals(accepted))
            transition(Phase.CONNECTING, "CONNECT_ACCEPTED", "Connecting to " + name(selected));
        else {
            immediateFailureCount++;
            if (immediateFailureCount >= 2 && recoveryCount == 0) beginPanRecovery(now);
            else finishConnectFailure(now, "CONNECT_REFUSED", "PAN service refused connection");
        }
    }

    private void finishConnectFailure(long now, String reason, String message) {
        connectArmed = false;
        phaseDeadline = 0L;
        nextReconnectAt = prefs.getBoolean("automatic", false) ? now + RECONNECT_COOLDOWN_MS : Long.MAX_VALUE;
        transition(prefs.getBoolean("automatic", false) ? Phase.RECONNECT_COOLDOWN : Phase.ERROR, reason, message);
    }

    private void beginDisconnect(long now, int link) {
        if (phaseDeadline == 0L) phaseDeadline = now + DISCONNECT_TIMEOUT_MS;
        if (!disconnectIssued && link != DISCONNECTING) {
            Object accepted = invoke("disconnect", selected, true);
            log("action=PAN_DISCONNECT request=" + activeRequestId + " accepted=" + accepted);
            disconnectIssued = true;
        }
        transition(Phase.DISCONNECTING, "DISCONNECT_REQUESTED", "Disconnecting from " + name(selected));
    }

    private void beginPanRecovery(long now) {
        if (networkConnected()) return;
        int link = connectionState(selected, false);
        if (link != DISCONNECTED && link != UNKNOWN) return;
        recoveryCount++;
        log("action=PAN_RECOVERY attempt=" + recoveryCount + " reason=repeated-immediate-drop");
        closeProxy();
        try {
            Intent start = new Intent();
            start.setComponent(new ComponentName("com.android.bluetooth", "com.android.bluetooth.pan.PanService"));
            start.putExtra("action", "com.android.bluetooth.btservice.action.STATE_CHANGED");
            start.putExtra("android.bluetooth.adapter.extra.STATE", BluetoothAdapter.STATE_ON);
            startService(start);
        } catch (Throwable t) {
            log("PAN recovery start failed: " + rootMessage(t));
        }
        proxyRequestAt = 0L;
        proxyWaitStarted = now;
        phaseDeadline = now + 3000L;
        transition(Phase.RECOVERING_PAN, "PAN_RECOVERY", "Repairing Bluetooth PAN");
    }

    private BluetoothDevice resolveSelected() {
        Set<BluetoothDevice> bonded;
        try { bonded = adapter.getBondedDevices(); } catch (Throwable t) { return null; }
        String address = prefs.getString("device", "");
        if (bonded != null) for (BluetoothDevice d : bonded)
            if (d.getAddress().equalsIgnoreCase(address)) return d;
        if (address.length() == 0 && pan != null) {
            try {
                List<BluetoothDevice> connected = pan.getConnectedDevices();
                if (connected != null && connected.size() == 1)
                    return remember(connected.get(0), "connected PAN device");
            } catch (Throwable ignored) {}
        }
        BluetoothDevice only = null;
        int phones = 0;
        if (address.length() == 0 && bonded != null) for (BluetoothDevice d : bonded) {
            BluetoothClass type = d.getBluetoothClass();
            if (type != null && type.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE) {
                only = d;
                phones++;
            }
        }
        return phones == 1 ? remember(only, "only bonded phone") : null;
    }

    private BluetoothDevice remember(BluetoothDevice d, String reason) {
        prefs.edit().putString("device", d.getAddress()).commit();
        log("selected=" + label(d) + " reason=" + reason);
        return d;
    }

    private synchronized void requestProfileProxy(long now) {
        if (adapter == null || pan != null || stop) return;
        if (proxyRequestAt != 0L && now - proxyRequestAt < 5000L) return;
        try {
            boolean requested = adapter.getProfileProxy(this, listener, PROFILE_PAN);
            log("action=REQUEST_PAN_PROXY accepted=" + requested);
            proxyRequestAt = requested ? now : 0L;
        } catch (Throwable t) {
            proxyRequestAt = 0L;
            log("PAN profile request failed: " + rootMessage(t));
        }
    }

    private void closeProxy() {
        BluetoothProfile current = pan;
        pan = null;
        proxyRequestAt = 0L;
        try { if (current != null && adapter != null) adapter.closeProfileProxy(PROFILE_PAN, current); }
        catch (Throwable ignored) {}
    }

    private int connectionState(BluetoothDevice d, boolean report) {
        Object value = invoke("getConnectionState", d, report);
        return value instanceof Integer ? ((Integer) value).intValue() : UNKNOWN;
    }

    private Object invoke(String name, BluetoothDevice d, boolean report) {
        BluetoothProfile profile = pan;
        if (profile == null || d == null) return null;
        try {
            Method method = profile.getClass().getMethod(name, BluetoothDevice.class);
            method.setAccessible(true);
            Object result = method.invoke(profile, d);
            if (report) log("call=" + name + " device=" + label(d) + " result=" + result);
            return result;
        } catch (Throwable t) {
            log("call=" + name + " failed=" + rootMessage(t));
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
            return info == null ? "MISSING" : info.getState() + "/" + info.getDetailedState();
        } catch (Throwable t) { return "QUERY_FAILED"; }
    }

    private void logObservation(int link, boolean network) {
        if (link == lastLink && network == lastNetwork) return;
        lastLink = link;
        lastNetwork = network;
        log("observation adapter=ON_STABLE phone=BONDED proxy=READY link=" + link
                + " network=" + networkSummary() + " desired="
                + (desiredConnected() ? "CONNECTED" : "DISCONNECTED"));
    }

    private void transition(Phase next, String event, String message) {
        String prior = phase.name();
        if (phase != next) {
            phase = next;
            log("transition from=" + prior + " to=" + next.name() + " event=" + event
                    + " request=" + activeRequestId);
        }
        String state = next.name();
        if (!state.equals(prefs.getString("controller_state", ""))
                || !message.equals(prefs.getString("status", ""))) {
            prefs.edit().putString("controller_state", state).putString("status", message).commit();
            log("ui state=" + state + " message=" + message.replace('\n', ' '));
        }
    }

    private String connectedMessage() {
        String address = interfaceAddress("bt-pan");
        return "Connected through iPhone" + (address.length() == 0 ? "" : "\nAddress: " + address);
    }

    private boolean desiredConnected() { return prefs.getBoolean("requested_connect", false); }

    private void queue(boolean connect) {
        int sequence = prefs.getInt("request_sequence", 0) + 1;
        prefs.edit().putBoolean("requested_connect", connect).putInt("request_sequence", sequence).commit();
    }

    private static String interfaceAddress(String wanted) {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface n : Collections.list(all)) if (wanted.equals(n.getName()))
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    String value = a.getHostAddress();
                    if (value.indexOf(':') < 0) return value;
                }
        } catch (Throwable ignored) {}
        return "";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
    private static String name(BluetoothDevice d) { return d.getName() == null ? "iPhone" : d.getName(); }
    private static String label(BluetoothDevice d) { return name(d) + "(" + d.getAddress() + ")"; }
    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getName() + ": " + t.getMessage();
    }
    private static void log(String value) { Log.i(TAG, value); }
}
