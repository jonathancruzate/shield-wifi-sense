package com.jonathancruzate.shieldwifisense;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;

/** v1.1: the Activity is now just a launcher + status viewer.
 * Sampling lives in SenseService (foreground service, boot-started).
 * This screen tails the newest JSONL line so a human can see it work. */
public class MainActivity extends Activity {
    private TextView statusTextView;
    private File outputFile;
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        outputFile = new File(dir, "shield-wifi-sense.jsonl");
        setupUi();
        requestLocationPermissionIfNeeded();
        Intent svc = new Intent(this, SenseService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private void setupUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.setPadding(48, 36, 48, 36);
        TextView titleView = new TextView(this);
        titleView.setText("SHIELD Wi-Fi Sense v1.1 (service sampler)");
        titleView.setTextColor(Color.parseColor("#38BDF8"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(titleView);
        TextView subtitleView = new TextView(this);
        subtitleView.setText("Sampling runs in SenseService (foreground, boot-started). Log: "
                + outputFile.getAbsolutePath());
        subtitleView.setTextColor(Color.parseColor("#94A3B8"));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitleView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        subtitleView.setPadding(0, 4, 0, 16);
        root.addView(subtitleView);
        ScrollView scrollView = new ScrollView(this);
        statusTextView = new TextView(this);
        statusTextView.setTextColor(Color.parseColor("#E2E8F0"));
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusTextView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        statusTextView.setText("Waiting for first sample...");
        scrollView.addView(statusTextView);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void requestLocationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1001);
        }
    }

    private void refreshStatus() {
        try {
            if (outputFile == null || !outputFile.exists() || outputFile.length() == 0) {
                statusTextView.setText("No samples yet - service starting...");
                return;
            }
            long len = outputFile.length();
            int tail = (int) Math.min(len, 4096);
            try (RandomAccessFile raf = new RandomAccessFile(outputFile, "r")) {
                raf.seek(len - tail);
                byte[] buf = new byte[tail];
                raf.readFully(buf);
                String text = new String(buf, "UTF-8");
                int lastNl = text.lastIndexOf('\n', text.length() - 2);
                String lastLine = lastNl >= 0
                        ? text.substring(lastNl + 1).trim()
                        : text.trim();
                statusTextView.setText("Log size: " + len + " bytes\n\nLatest sample:\n" + lastLine);
            }
        } catch (Throwable t) {
            statusTextView.setText("Status read error: " + t.getMessage());
        }
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
