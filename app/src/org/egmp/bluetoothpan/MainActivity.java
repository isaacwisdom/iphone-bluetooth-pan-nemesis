package org.egmp.bluetoothpan;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    static final String PREFS = "pan";
    private final Handler handler = new Handler();
    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private SharedPreferences prefs;
    private Dashboard dashboard;
    private int selected;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            dashboard.invalidate();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadPhones();
        dashboard = new Dashboard();
        setContentView(dashboard);
        startService(new Intent(this, PanControllerService.class));
    }

    private void loadPhones() {
        devices.clear();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        Set<BluetoothDevice> bonded = adapter == null ? null : adapter.getBondedDevices();
        if (bonded != null) for (BluetoothDevice device : bonded) {
            BluetoothClass type = device.getBluetoothClass();
            if (type != null && type.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE) devices.add(device);
        }
        String saved = prefs.getString("device", "");
        selected = 0;
        for (int i = 0; i < devices.size(); i++) if (devices.get(i).getAddress().equals(saved)) selected = i;
    }

    private void selectNextPhone() {
        if (devices.isEmpty()) return;
        selected = (selected + 1) % devices.size();
        prefs.edit().putString("device", devices.get(selected).getAddress()).commit();
        dashboard.invalidate();
    }

    private void request(boolean connect) {
        if (devices.isEmpty()) {
            prefs.edit().putString("status", "No paired phones found").commit();
            dashboard.invalidate();
            return;
        }
        BluetoothDevice device = devices.get(selected);
        int sequence = prefs.getInt("request_sequence", 0) + 1;
        prefs.edit().putString("device", device.getAddress()).putBoolean("requested_connect", connect)
            .putInt("request_sequence", sequence)
            .putString("status", connect ? "Connection requested…" : "Disconnection requested…").commit();
        startService(new Intent(this, PanControllerService.class));
        dashboard.invalidate();
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private final class Dashboard extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF phoneCard = new RectF();
        private final RectF connectButton = new RectF();
        private final RectF disconnectButton = new RectF();
        private final RectF autoButton = new RectF();
        private float drawScale = 1;

        Dashboard() { super(MainActivity.this); }

        @Override protected void onDraw(Canvas canvas) {
            drawScale = Math.min(getWidth() / 800f, getHeight() / 400f);
            if (drawScale <= 0) drawScale = 1;
            canvas.save();
            canvas.scale(drawScale, drawScale);
            int width = (int) (getWidth() / drawScale);
            int height = (int) (getHeight() / drawScale);
            canvas.drawColor(Color.rgb(13, 19, 26));

            text(canvas, "iPhone Hotspot", 36, 46, 28, Color.WHITE, true);
            text(canvas, "BLUETOOTH PAN", width - 36, 43, 13, Color.rgb(111, 205, 255), false, Paint.Align.RIGHT);

            float top = 68;
            phoneCard.set(32, top, width * .52f, top + 86);
            card(canvas, phoneCard, Color.rgb(25, 34, 44));
            text(canvas, "PAIRED PHONE", phoneCard.left + 20, phoneCard.top + 27, 14, Color.rgb(139, 156, 173), true);
            String phoneName = devices.isEmpty() ? "No paired phone" : name(devices.get(selected));
            text(canvas, phoneName, phoneCard.left + 20, phoneCard.top + 59, 23, Color.WHITE, true);
            text(canvas, devices.size() > 1 ? "Tap to change  ›" : "Paired and ready", phoneCard.right - 18,
                phoneCard.top + 59, 14, Color.rgb(111, 205, 255), false, Paint.Align.RIGHT);

            RectF statusCard = new RectF(width * .55f, top, width - 32, top + 86);
            card(canvas, statusCard, Color.rgb(25, 34, 44));
            String status = prefs.getString("status", "Ready");
            boolean connected = status.startsWith("Connected through");
            boolean working = status.startsWith("Connecting") || status.startsWith("Disconnecting")
                || status.startsWith("Waiting") || status.startsWith("Bluetooth connected")
                || status.startsWith("Bluetooth PAN profile ready")
                || status.startsWith("Connection requested")
                || status.startsWith("Disconnection requested")
                || status.startsWith("Connection already in progress");
            int statusColor = connected ? Color.rgb(53, 214, 139)
                : working ? Color.rgb(255, 181, 71) : Color.rgb(235, 86, 86);
            paint.setColor(statusColor);
            canvas.drawCircle(statusCard.left + 22, statusCard.top + 25, 6, paint);
            text(canvas, connected ? "ONLINE" : working ? "WORKING" : "OFFLINE",
                statusCard.left + 37, statusCard.top + 30, 14, statusColor, true);
            String[] lines = status.split("\\n");
            text(canvas, lines[0], statusCard.left + 20, statusCard.top + 59, 17, Color.WHITE, true);
            if (lines.length > 1) text(canvas, lines[1], statusCard.left + 20, statusCard.top + 78, 12, Color.rgb(160, 176, 191), true);

            float buttonTop = top + 110;
            connectButton.set(32, buttonTop, width * .62f, buttonTop + 76);
            round(canvas, connectButton, Color.rgb(0, 132, 203), 10);
            text(canvas, "CONNECT", connectButton.centerX(), connectButton.centerY() + 8, 22, Color.WHITE, true, Paint.Align.CENTER);

            disconnectButton.set(width * .64f, buttonTop, width - 32, buttonTop + 76);
            round(canvas, disconnectButton, Color.rgb(43, 54, 66), 10);
            text(canvas, "DISCONNECT", disconnectButton.centerX(), disconnectButton.centerY() + 7, 18,
                Color.rgb(224, 231, 237), true, Paint.Align.CENTER);

            autoButton.set(32, buttonTop + 99, width - 32, buttonTop + 155);
            card(canvas, autoButton, Color.rgb(20, 28, 37));
            boolean automatic = prefs.getBoolean("automatic", false);
            text(canvas, "Reconnect automatically", autoButton.left + 20, autoButton.centerY() + 6, 17, Color.rgb(220, 229, 237), true);
            float switchRight = autoButton.right - 18;
            RectF track = new RectF(switchRight - 56, autoButton.centerY() - 13, switchRight, autoButton.centerY() + 13);
            round(canvas, track, automatic ? Color.rgb(0, 132, 203) : Color.rgb(66, 77, 88), 13);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(automatic ? track.right - 13 : track.left + 13, track.centerY(), 10, paint);

            text(canvas, "Uses the paired iPhone’s Personal Hotspot. Automatic reconnect is off by default.",
                36, height - 19, 14, Color.rgb(112, 130, 147), false);
            canvas.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            float x = event.getX() / drawScale, y = event.getY() / drawScale;
            if (phoneCard.contains(x, y)) selectNextPhone();
            else if (connectButton.contains(x, y)) request(true);
            else if (disconnectButton.contains(x, y)) request(false);
            else if (autoButton.contains(x, y)) {
                boolean value = !prefs.getBoolean("automatic", false);
                prefs.edit().putBoolean("automatic", value).commit();
                invalidate();
            }
            return true;
        }

        private void card(Canvas canvas, RectF bounds, int color) { round(canvas, bounds, color, 8); }
        private void round(Canvas canvas, RectF bounds, int color, float radius) {
            paint.setColor(color); paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(bounds, radius, radius, paint);
        }
        private void text(Canvas canvas, String value, float x, float y, float size, int color, boolean bold) {
            text(canvas, value, x, y, size, color, bold, Paint.Align.LEFT);
        }
        private void text(Canvas canvas, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
            paint.setColor(color); paint.setTextSize(size); paint.setTextAlign(align);
            paint.setTypeface(android.graphics.Typeface.create("sans", bold ? 1 : 0));
            canvas.drawText(value, x, y, paint);
        }
    }

    private static String name(BluetoothDevice device) {
        return device.getName() == null ? "Unnamed iPhone" : device.getName();
    }
}
