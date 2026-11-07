package com.jonathancruzate.shieldwifisense;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String TAG = "ShieldWifiSense";
    private static final String DEFAULT_GATEWAY = "192.168.1.1";
    private static final long SAMPLE_INTERVAL_MS = 500L;
    private static final long PING_INTERVAL_MS = 1000L;
    private static final long SCAN_INTERVAL_MS = 30000L;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private boolean hasWifiRtt;
    private boolean hasWifiAware;
    private File outputFile;

    private ScheduledExecutorService executor;
    private final AtomicLong sampleSeq = new AtomicLong(0);

    private volatile double lastPingRttMs = -1.0;
    private volatile long lastPingTimestampMs = 0L;
    private volatile String currentGateway = DEFAULT_GATEWAY;

    private volatile long lastScanRequestMs = 0L;
    private volatile int lastScanCount = 0;
    private volatile List<ApEntry> lastTopAps = Collections.emptyList();

    private TextView statusTextView;

    private static class ApEntry {
        final String ssid;
        final String bssid;
        final int rssi;
        final int frequency;

        ApEntry(String ssid, String bssid, int rssi, int frequency) {
            this.ssid = ssid != null ? ssid : "";
            this.bssid = bssid != null ? bssid : "";
            this.rssi = rssi;
            this.frequency = frequency;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Context appContext = getApplicationContext();
        wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        PackageManager pm = getPackageManager();
        hasWifiRtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT);
        hasWifiAware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE);

        File externalDir = getExternalFilesDir(null);
        if (externalDir == null) {
            externalDir = getFilesDir();
        }
        if (!externalDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            externalDir.mkdirs();
        }
        outputFile = new File(externalDir, "shield-wifi-sense.jsonl");

        setupUi();
        requestLocationPermissionIfNeeded();
        startSamplers();
    }

    private void setupUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.setPadding(48, 36, 48, 36);

        TextView titleView = new TextView(this);
        titleView.setText("SHIELD Wi-Fi Sense (v0 RSSI Telemetry)");
        titleView.setTextColor(Color.parseColor("#38BDF8"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText("Package: com.jonathancruzate.shieldwifisense | Log: " + outputFile.getAbsolutePath());
        subtitleView.setTextColor(Color.parseColor("#94A3B8"));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitleView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        subtitleView.setPadding(0, 4, 0, 16);
        root.addView(subtitleView);

        ScrollView scrollView = new ScrollView(this);
        statusTextView = new TextView(this);
        statusTextView.setTextColor(Color.parseColor("#E2E8F0"));
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusTextView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        statusTextView.setGravity(Gravity.TOP | Gravity.START);
        statusTextView.setText("Initializing 2 Hz sampler...");
        scrollView.addView(statusTextView);

        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void requestLocationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1001);
        }
    }

    private void startSamplers() {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newScheduledThreadPool(2);

        // 1 Hz gateway ping task
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    String gw = resolveGatewayIp();
                    currentGateway = gw;
                    lastPingRttMs = pingHostOnce(gw);
                    lastPingTimestampMs = System.currentTimeMillis();
                } catch (Throwable t) {
                    Log.w(TAG, "Ping worker error: " + t.getMessage());
                }
            }
        }, 0L, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 2 Hz (every 500ms) telemetry & Wi-Fi sampler
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    collectAndLogSample();
                } catch (Throwable t) {
                    Log.e(TAG, "Sample worker error", t);
                }
            }
        }, 100L, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void collectAndLogSample() {
        long nowMs = System.currentTimeMillis();
        long seq = sampleSeq.incrementAndGet();

        String activeNetType = resolveActiveNetworkType();
        String activeIp = resolveActiveIpAddress();
        String gateway = resolveGatewayIp();
        currentGateway = gateway;

        boolean wifiEnabled = wifiManager != null && wifiManager.isWifiEnabled();
        WifiInfo info = (wifiManager != null) ? wifiManager.getConnectionInfo() : null;

        int rssi = (info != null) ? info.getRssi() : -127;
        String ssid = (info != null && info.getSSID() != null) ? info.getSSID() : "<unknown ssid>";
        String bssid = (info != null && info.getBSSID() != null) ? info.getBSSID() : "<none>";
        int frequency = (info != null) ? info.getFrequency() : -1;
        int linkSpeed = (info != null) ? info.getLinkSpeed() : -1;
        int rawWifiIp = (info != null) ? info.getIpAddress() : 0;
        String wifiIp = formatIpv4Int(rawWifiIp);
        String effectiveIp = (!"0.0.0.0".equals(wifiIp)) ? wifiIp : activeIp;
        SupplicantState supplicantState = (info != null) ? info.getSupplicantState() : SupplicantState.UNINITIALIZED;

        boolean scanTriggered = false;
        if (wifiManager != null && (nowMs - lastScanRequestMs >= SCAN_INTERVAL_MS)) {
            lastScanRequestMs = nowMs;
            try {
                //noinspection deprecation
                scanTriggered = wifiManager.startScan();
            } catch (SecurityException se) {
                Log.w(TAG, "startScan SecurityException: " + se.getMessage());
            }
        }
        updateScanResultsSnapshot();

        List<ApEntry> topAps = lastTopAps;
        int scanCount = lastScanCount;
        double pingRtt = lastPingRttMs;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String isoTs = sdf.format(new Date(nowMs));

        try {
            JSONObject root = new JSONObject();
            root.put("seq", seq);
            root.put("ts_ms", nowMs);
            root.put("iso_ts", isoTs);
            root.put("active_network_type", activeNetType);
            root.put("wifi_enabled", wifiEnabled);

            // Top-level WifiInfo fields per spec
            root.put("rssi", rssi);
            root.put("ssid", ssid);
            root.put("bssid", bssid);
            root.put("frequency", frequency);
            root.put("linkSpeed", linkSpeed);
            root.put("ip", effectiveIp);
            root.put("wifi_ip", wifiIp);
            root.put("supplicant_state", String.valueOf(supplicantState));

            // Nested WifiInfo object for structured consumers
            JSONObject wifiObj = new JSONObject();
            wifiObj.put("rssi", rssi);
            wifiObj.put("ssid", ssid);
            wifiObj.put("bssid", bssid);
            wifiObj.put("frequency", frequency);
            wifiObj.put("linkSpeed", linkSpeed);
            wifiObj.put("ip", wifiIp);
            wifiObj.put("supplicantState", String.valueOf(supplicantState));
            root.put("wifi_info", wifiObj);

            // Feature flags
            root.put("wifi_rtt", hasWifiRtt);
            root.put("wifi_aware", hasWifiAware);
            JSONObject featuresObj = new JSONObject();
            featuresObj.put("android.hardware.wifi.rtt", hasWifiRtt);
            featuresObj.put("android.hardware.wifi.aware", hasWifiAware);
            root.put("features", featuresObj);

            // Gateway Ping RTT
            root.put("gateway", gateway);
            if (pingRtt >= 0.0) {
                root.put("ping_rtt_ms", Math.round(pingRtt * 1000.0) / 1000.0);
            } else {
                root.put("ping_rtt_ms", JSONObject.NULL);
            }

            // Wi-Fi Scan Results (strongest 5 APs)
            root.put("scan_triggered", scanTriggered);
            root.put("scan_result_count", scanCount);
            JSONArray topApsArr = new JSONArray();
            for (ApEntry ap : topAps) {
                JSONObject apObj = new JSONObject();
                apObj.put("ssid", ap.ssid);
                apObj.put("bssid", ap.bssid);
                apObj.put("rssi", ap.rssi);
                apObj.put("frequency", ap.frequency);
                topApsArr.put(apObj);
            }
            root.put("strongest_5_aps", topApsArr);

            String jsonLine = root.toString();
            Log.i(TAG, jsonLine);
            appendJsonLine(jsonLine);

            final String uiText = buildDashboardText(
                    seq, isoTs, activeNetType, effectiveIp, wifiIp, wifiEnabled,
                    ssid, bssid, rssi, frequency, linkSpeed, String.valueOf(supplicantState),
                    gateway, pingRtt, scanCount, topAps, jsonLine);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (statusTextView != null) {
                        statusTextView.setText(uiText);
                    }
                }
            });
        } catch ( Exception e) {
            Log.e(TAG, "Failed to serialize sample", e);
        }
    }

    private void updateScanResultsSnapshot() {
        if (wifiManager == null) {
            return;
        }
        try {
            List<ScanResult> results = wifiManager.getScanResults();
            if (results == null) {
                return;
            }
            lastScanCount = results.size();
            List<ScanResult> copy = new ArrayList<>(results);
            Collections.sort(copy, new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult a, ScanResult b) {
                    return Integer.compare(b.level, a.level);
                }
            });
            List<ApEntry> top5 = new ArrayList<>(Math.min(5, copy.size()));
            for (int i = 0; i < Math.min(5, copy.size()); i++) {
                ScanResult sr = copy.get(i);
                top5.add(new ApEntry(sr.SSID, sr.BSSID, sr.level, sr.frequency));
            }
            lastTopAps = top5;
        } catch (SecurityException se) {
            Log.w(TAG, "getScanResults SecurityException (location permission missing?): " + se.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "getScanResults error: " + t.getMessage());
        }
    }

    private synchronized void appendJsonLine(String jsonLine) {
        try (FileOutputStream fos = new FileOutputStream(outputFile, true)) {
            byte[] bytes = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
            fos.write(bytes);
            fos.flush();
        } catch (Exception e) {
            Log.e(TAG, "Error writing JSONL to " + outputFile, e);
        }
    }

    private String resolveActiveNetworkType() {
        if (connectivityManager == null) {
            return "UNKNOWN";
        }
        Network lanNet = findPhysicalLanNetwork();
        if (lanNet == null) {
            lanNet = connectivityManager.getActiveNetwork();
        }
        if (lanNet == null) {
            return "NONE";
        }
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(lanNet);
        if (caps == null) {
            return "NONE";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "WIFI";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "ETHERNET";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "CELLULAR";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "VPN";
        }
        return "OTHER";
    }

    private Network findPhysicalLanNetwork() {
        if (connectivityManager == null) {
            return null;
        }
        try {
            Network[] networks = connectivityManager.getAllNetworks();
            Network ethCandidate = null;
            for (Network net : networks) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    continue;
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return net;
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    ethCandidate = net;
                }
            }
            if (ethCandidate != null) {
                return ethCandidate;
            }
        } catch (Throwable ignored) {
        }
        return connectivityManager.getActiveNetwork();
    }

    private String resolveActiveIpAddress() {
        if (connectivityManager == null) {
            return "0.0.0.0";
        }
        try {
            Network net = findPhysicalLanNetwork();
            if (net != null) {
                LinkProperties lp = connectivityManager.getLinkProperties(net);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la.getAddress();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "0.0.0.0";
    }

    private String resolveGatewayIp() {
        if (connectivityManager != null) {
            try {
                Network net = findPhysicalLanNetwork();
                if (net != null) {
                    LinkProperties lp = connectivityManager.getLinkProperties(net);
                    if (lp != null) {
                        for (RouteInfo route : lp.getRoutes()) {
                            if (route.isDefaultRoute() && route.hasGateway()) {
                                InetAddress gw = route.getGateway();
                                if (gw instanceof Inet4Address) {
                                    String host = gw.getHostAddress();
                                    if (host != null && !host.isEmpty() && !"0.0.0.0".equals(host)) {
                                        return host;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (wifiManager != null) {
            try {
                DhcpInfo dhcp = wifiManager.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    String gw = formatIpv4Int(dhcp.gateway);
                    if (!"0.0.0.0".equals(gw)) {
                        return gw;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return DEFAULT_GATEWAY;
    }

    private double pingHostOnce(String host) {
        try {
            Network lanNet = findPhysicalLanNetwork();
            if (lanNet != null && connectivityManager != null) {
                connectivityManager.bindProcessToNetwork(lanNet);
            }
        } catch (Throwable ignored) {
        }
        Process proc = null;
        try {
            proc = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", host)
                    .redirectErrorStream(true)
                    .start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            Pattern pattern = Pattern.compile("time=([0-9.]+)\\s*ms");
            String line;
            Double rtt = null;
            while ((line = reader.readLine()) != null) {
                Matcher m = pattern.matcher(line);
                if (m.find()) {
                    rtt = Double.parseDouble(m.group(1));
                }
            }
            proc.waitFor(1500, TimeUnit.MILLISECONDS);
            if (rtt != null) {
                return rtt;
            }
        } catch (Throwable ignored) {
        } finally {
            if (proc != null) {
                proc.destroy();
            }
        }
        // Fallback TCP SYN RTT to gateway port 80/53 over physical LAN if ICMP is restricted
        long t0 = System.nanoTime();
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, 53), 1000);
            return (System.nanoTime() - t0) / 1_000_000.0;
        } catch (java.net.ConnectException ce) {
            // RST from gateway still measures true LAN round-trip time
            return (System.nanoTime() - t0) / 1_000_000.0;
        } catch (Throwable t) {
            return -1.0;
        }
    }

    private static String formatIpv4Int(int ip) {
        if (ip == 0) {
            return "0.0.0.0";
        }
        return String.format(Locale.US, "%d.%d.%d.%d",
                (ip & 0xff),
                (ip >> 8 & 0xff),
                (ip >> 16 & 0xff),
                (ip >> 24 & 0xff));
    }

    private String buildDashboardText(
            long seq, String isoTs, String activeNetType, String effectiveIp, String wifiIp,
            boolean wifiEnabled, String ssid, String bssid, int rssi, int frequency, int linkSpeed,
            String supplicantState, String gateway, double pingRtt, int scanCount,
            List<ApEntry> topAps, String lastJson) {

        StringBuilder sb = new StringBuilder();
        sb.append("Sample #").append(seq).append("  |  UTC: ").append(isoTs).append("\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("Active Network Type : ").append(activeNetType)
          .append("   (Active IP: ").append(effectiveIp).append(")\n");
        sb.append("Wi-Fi Radio Enabled : ").append(wifiEnabled)
          .append("   (Supplicant: ").append(supplicantState).append(")\n");
        sb.append("WifiInfo            : RSSI=").append(rssi).append(" dBm")
          .append(" | SSID=").append(ssid)
          .append(" | BSSID=").append(bssid).append("\n");
        sb.append("                      Freq=").append(frequency).append(" MHz")
          .append(" | LinkSpeed=").append(linkSpeed).append(" Mbps")
          .append(" | WifiIP=").append(wifiIp).append("\n");
        sb.append("Wi-Fi Features      : wifi.rtt=").append(hasWifiRtt)
          .append(" | wifi.aware=").append(hasWifiAware).append("\n");
        sb.append("Gateway Ping (1Hz)  : gw=").append(gateway)
          .append(" | RTT=").append(pingRtt >= 0 ? String.format(Locale.US, "%.3f ms", pingRtt) : "N/A")
          .append("\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("Wi-Fi Scan (<=1/30s): total_aps=").append(scanCount).append(" | Strongest 5 APs:\n");
        if (topAps.isEmpty()) {
            sb.append("  (no scan results available or location permission pending)\n");
        } else {
            for (int i = 0; i < topAps.size(); i++) {
                ApEntry ap = topAps.get(i);
                sb.append(String.format(Locale.US, "  #%d  %4d dBm | %4d MHz | %s | %s\n",
                        i + 1, ap.rssi, ap.frequency, ap.bssid,
                        ap.ssid.isEmpty() ? "<hidden>" : ap.ssid));
            }
        }
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("Latest JSONL line:\n").append(lastJson).append("\n");
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        super.onDestroy();
    }
}
