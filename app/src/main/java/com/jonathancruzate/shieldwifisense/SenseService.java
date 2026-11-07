package com.jonathancruzate.shieldwifisense;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.os.IBinder;
import android.util.Log;

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

/** v1.1 (2026-11-07): sampling moved from the Activity into this
 * foreground service so it survives the UI being closed and can be
 * started at boot by BootReceiver. Same JSONL schema as v1, plus a
 * full `scan_aps` vector (v1 logged only the strongest 5) and
 * in-app log rotation at ~3 MB (rollover to shield-wifi-sense.1.jsonl).
 */
public class SenseService extends Service {
    private static final String TAG = "ShieldWifiSense";
    private static final String DEFAULT_GATEWAY = "192.168.1.1";
    private static final long SAMPLE_INTERVAL_MS = 500L;
    private static final long PING_INTERVAL_MS = 1000L;
    private static final long SCAN_INTERVAL_MS = 30000L;
    private static final long ROTATE_BYTES = 3000000L;
    private static final String CHANNEL_ID = "sense";
    private static final int NOTIF_ID = 42;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private boolean hasWifiRtt;
    private boolean hasWifiAware;
    private File outputFile;

    private ScheduledExecutorService executor;
    private final AtomicLong sampleSeq = new AtomicLong(0);
    private volatile double lastPingRttMs = -1.0;
    private volatile String currentGateway = DEFAULT_GATEWAY;
    private volatile long lastScanRequestMs = 0L;
    private volatile int lastScanCount = 0;
    private volatile List<ApEntry> lastScanAps = Collections.emptyList();

    static class ApEntry {
        final String ssid; final String bssid; final int rssi; final int frequency;
        ApEntry(String ssid, String bssid, int rssi, int frequency) {
            this.ssid = ssid != null ? ssid : "";
            this.bssid = bssid != null ? bssid : "";
            this.rssi = rssi; this.frequency = frequency;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Context appContext = getApplicationContext();
        wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        hasWifiRtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT);
        hasWifiAware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE);
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        outputFile = new File(dir, "shield-wifi-sense.jsonl");
        startForeground(NOTIF_ID, buildNotification());
        startSamplers();
        Log.i(TAG, "SenseService started; log=" + outputFile.getAbsolutePath());
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Wi-Fi Sense sampling",
                    NotificationManager.IMPORTANCE_MIN));
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Shield Wi-Fi Sense")
                .setContentText("Sampling Wi-Fi scan/link state at 2 Hz")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private void startSamplers() {
        if (executor != null && !executor.isShutdown()) return;
        executor = Executors.newScheduledThreadPool(2);
        executor.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                try {
                    String gw = resolveGatewayIp();
                    currentGateway = gw;
                    lastPingRttMs = pingHostOnce(gw);
                } catch (Throwable t) {
                    Log.w(TAG, "Ping worker error: " + t.getMessage());
                }
            }
        }, 0L, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                try { collectAndLogSample(); }
                catch (Throwable t) { Log.e(TAG, "Sample worker error", t); }
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
        String wifiIp = formatIpv4Int((info != null) ? info.getIpAddress() : 0);
        String effectiveIp = (!"0.0.0.0".equals(wifiIp)) ? wifiIp : activeIp;
        SupplicantState supplicantState = (info != null) ? info.getSupplicantState() : SupplicantState.UNINITIALIZED;

        boolean scanTriggered = false;
        if (wifiManager != null && (nowMs - lastScanRequestMs >= SCAN_INTERVAL_MS)) {
            lastScanRequestMs = nowMs;
            try { scanTriggered = wifiManager.startScan(); }
            catch (SecurityException se) { Log.w(TAG, "startScan SecurityException: " + se.getMessage()); }
        }
        updateScanResultsSnapshot();
        List<ApEntry> scanAps = lastScanAps;
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
            root.put("app_version", "1.1.0");
            root.put("active_network_type", activeNetType);
            root.put("wifi_enabled", wifiEnabled);
            root.put("rssi", rssi);
            root.put("ssid", ssid);
            root.put("bssid", bssid);
            root.put("frequency", frequency);
            root.put("linkSpeed", linkSpeed);
            root.put("ip", effectiveIp);
            root.put("wifi_ip", wifiIp);
            root.put("supplicant_state", String.valueOf(supplicantState));
            JSONObject wifiObj = new JSONObject();
            wifiObj.put("rssi", rssi); wifiObj.put("ssid", ssid);
            wifiObj.put("bssid", bssid); wifiObj.put("frequency", frequency);
            wifiObj.put("linkSpeed", linkSpeed); wifiObj.put("ip", wifiIp);
            wifiObj.put("supplicantState", String.valueOf(supplicantState));
            root.put("wifi_info", wifiObj);
            root.put("wifi_rtt", hasWifiRtt);
            root.put("wifi_aware", hasWifiAware);
            JSONObject featuresObj = new JSONObject();
            featuresObj.put("android.hardware.wifi.rtt", hasWifiRtt);
            featuresObj.put("android.hardware.wifi.aware", hasWifiAware);
            root.put("features", featuresObj);
            root.put("gateway", gateway);
            if (pingRtt >= 0.0) root.put("ping_rtt_ms", Math.round(pingRtt * 1000.0) / 1000.0);
            else root.put("ping_rtt_ms", JSONObject.NULL);
            root.put("scan_triggered", scanTriggered);
            root.put("scan_result_count", scanCount);
            JSONArray topArr = new JSONArray();
            JSONArray fullArr = new JSONArray();
            for (int i = 0; i < scanAps.size(); i++) {
                ApEntry ap = scanAps.get(i);
                JSONObject apObj = new JSONObject();
                apObj.put("ssid", ap.ssid); apObj.put("bssid", ap.bssid);
                apObj.put("rssi", ap.rssi); apObj.put("frequency", ap.frequency);
                fullArr.put(apObj);
                if (i < 5) topArr.put(apObj);
            }
            root.put("strongest_5_aps", topArr);
            root.put("scan_aps", fullArr);
            appendJsonLine(root.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to serialize sample", e);
        }
    }

    private void updateScanResultsSnapshot() {
        if (wifiManager == null) return;
        try {
            List<ScanResult> results = wifiManager.getScanResults();
            if (results == null) return;
            lastScanCount = results.size();
            List<ScanResult> copy = new ArrayList<>(results);
            Collections.sort(copy, new Comparator<ScanResult>() {
                @Override public int compare(ScanResult a, ScanResult b) {
                    return Integer.compare(b.level, a.level);
                }
            });
            List<ApEntry> all = new ArrayList<>(copy.size());
            for (ScanResult sr : copy) all.add(new ApEntry(sr.SSID, sr.BSSID, sr.level, sr.frequency));
            lastScanAps = all;
        } catch (SecurityException se) {
            Log.w(TAG, "getScanResults SecurityException: " + se.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "getScanResults error: " + t.getMessage());
        }
    }

    private synchronized void appendJsonLine(String jsonLine) {
        try {
            if (outputFile.exists() && outputFile.length() > ROTATE_BYTES) {
                File rolled = new File(outputFile.getParentFile(), "shield-wifi-sense.1.jsonl");
                if (rolled.exists()) rolled.delete();
                outputFile.renameTo(rolled);
                Log.i(TAG, "rotated log at " + ROTATE_BYTES + " bytes");
            }
            try (FileOutputStream fos = new FileOutputStream(outputFile, true)) {
                fos.write((jsonLine + "\n").getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing JSONL", e);
        }
    }

    private String resolveActiveNetworkType() {
        if (connectivityManager == null) return "UNKNOWN";
        Network lanNet = findPhysicalLanNetwork();
        if (lanNet == null) lanNet = connectivityManager.getActiveNetwork();
        if (lanNet == null) return "NONE";
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(lanNet);
        if (caps == null) return "NONE";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ETHERNET";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "CELLULAR";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        return "OTHER";
    }

    private Network findPhysicalLanNetwork() {
        if (connectivityManager == null) return null;
        try {
            Network[] networks = connectivityManager.getAllNetworks();
            Network ethCandidate = null;
            for (Network net : networks) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return net;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethCandidate = net;
            }
            if (ethCandidate != null) return ethCandidate;
        } catch (Throwable ignored) {}
        return connectivityManager.getActiveNetwork();
    }

    private String resolveActiveIpAddress() {
        if (connectivityManager == null) return "0.0.0.0";
        try {
            Network net = findPhysicalLanNetwork();
            if (net != null) {
                LinkProperties lp = connectivityManager.getLinkProperties(net);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la.getAddress();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress())
                            return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
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
                                    if (host != null && !host.isEmpty() && !"0.0.0.0".equals(host)) return host;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (wifiManager != null) {
            try {
                DhcpInfo dhcp = wifiManager.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    String gw = formatIpv4Int(dhcp.gateway);
                    if (!"0.0.0.0".equals(gw)) return gw;
                }
            } catch (Throwable ignored) {}
        }
        return DEFAULT_GATEWAY;
    }

    private double pingHostOnce(String host) {
        try {
            Network lanNet = findPhysicalLanNetwork();
            if (lanNet != null && connectivityManager != null)
                connectivityManager.bindProcessToNetwork(lanNet);
        } catch (Throwable ignored) {}
        Process proc = null;
        try {
            proc = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", host)
                    .redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            Pattern pattern = Pattern.compile("time=([0-9.]+)\\s*ms");
            String line; Double rtt = null;
            while ((line = reader.readLine()) != null) {
                Matcher m = pattern.matcher(line);
                if (m.find()) rtt = Double.parseDouble(m.group(1));
            }
            proc.waitFor(1500, TimeUnit.MILLISECONDS);
            if (rtt != null) return rtt;
        } catch (Throwable ignored) {
        } finally { if (proc != null) proc.destroy(); }
        long t0 = System.nanoTime();
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, 53), 1000);
            return (System.nanoTime() - t0) / 1_000_000.0;
        } catch (java.net.ConnectException ce) {
            return (System.nanoTime() - t0) / 1_000_000.0;
        } catch (Throwable t) { return -1.0; }
    }

    private static String formatIpv4Int(int ip) {
        if (ip == 0) return "0.0.0.0";
        return String.format(Locale.US, "%d.%d.%d.%d",
                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }
}
