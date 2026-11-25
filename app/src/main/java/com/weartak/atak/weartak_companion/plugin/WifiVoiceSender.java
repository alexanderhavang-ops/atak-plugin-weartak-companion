package com.weartak.atak.weartak_companion.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.io.IOException;
import java.util.regex.Pattern;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import se.takcampus.voice.WifiPairingIdentity;
import se.takcampus.voice.WifiVoiceTransfer;

/** Foreground DNS-SD picker and single Wi-Fi provisioning transaction for ATAK. */
final class WifiVoiceSender implements AutoCloseable {
    private static final Pattern WATCH_NAME = Pattern.compile(Pattern.quote(WifiVoiceTransfer.SERVICE_NAME_PREFIX)
            + "[0-9A-F]{4}(?: \\([0-9]{1,4}\\))?");
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    void open(Context context, String label, String loginSummary, String envelope) {
        close();
        Network wifi = findWifi(context);
        if (wifi == null) {
            notice(context, "Connect the phone and watch to the same Wi-Fi network.");
            return;
        }
        session = new Session(context, wifi, label, loginSummary, envelope);
        session.browse();
    }

    private static Network findWifi(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return null;
        try {
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return network;
            }
        } catch (RuntimeException unavailable) {
            return null;
        }
        return null;
    }

    @Override public void close() {
        Session current = session;
        session = null;
        if (current != null) current.close();
    }

    private static void notice(Context context, String message) {
        new AlertDialog.Builder(context).setTitle("Voice setup").setMessage(message).setPositiveButton("OK", null).show();
    }

    private final class Session implements NsdManager.DiscoveryListener {
        private final Context host;
        private final Network wifi;
        private final String label;
        private final String loginSummary;
        private final String envelope;
        private final NsdManager nsd;
        private final Map<String, NsdServiceInfo> watches = new LinkedHashMap<>();
        private final Runnable discoveryTimeout = () -> {
            if (this.closed || this.resolving) return;
            String message = watches.isEmpty() ? "No watches found. Open Pair over Wi-Fi on the watch and check the network."
                    : "The Wi-Fi scan timed out. Open Send over Wi-Fi to scan again.";
            finish(message);
        };
        private AlertDialog dialog;
        private ArrayAdapter<String> rows;
        private ListView list;
        private TextView hint;
        private WifiManager.MulticastLock multicast;
        private Socket socket;
        private volatile boolean closed;
        private boolean discovering;
        private boolean resolving;

        Session(Context host, Network wifi, String label, String loginSummary, String envelope) {
            this.host = host;
            this.wifi = wifi;
            this.label = label;
            this.loginSummary = loginSummary;
            this.envelope = envelope;
            nsd = host.getSystemService(NsdManager.class);
        }

        void browse() {
            hint = new TextView(host);
            hint.setText("On your watch, open Voice setup → Pair over Wi-Fi.");
            int padding = (int)(16 * host.getResources().getDisplayMetrics().density);
            hint.setPadding(padding, padding, padding, padding);
            list = new ListView(host);
            rows = new ArrayAdapter<>(host, android.R.layout.simple_list_item_1, new ArrayList<>());
            list.setAdapter(rows);
            list.setOnItemClickListener((parent, view, position, id) -> resolve(rows.getItem(position)));
            LinearLayout panel = new LinearLayout(host);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.addView(hint);
            panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            dialog = new AlertDialog.Builder(host).setTitle("Find a WearTAK watch").setView(panel)
                    .setNegativeButton("Cancel", null).create();
            dialog.setOnDismissListener(ignored -> WifiVoiceSender.this.close());
            dialog.show();
            if (dialog.getWindow() != null) {
                int height = (int)(350 * host.getResources().getDisplayMetrics().density);
                dialog.getWindow().setLayout(-1, Math.min(height, host.getResources().getDisplayMetrics().heightPixels));
            }
            try {
                WifiManager manager = host.getApplicationContext().getSystemService(WifiManager.class);
                if (manager != null) {
                    multicast = manager.createMulticastLock("WearTAK Voice discovery");
                    multicast.setReferenceCounted(false);
                    multicast.acquire();
                }
            } catch (RuntimeException unavailable) {
                multicast = null; // System NSD can still discover on newer Android builds.
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    nsd.discoverServices(WifiVoiceTransfer.SERVICE, NsdManager.PROTOCOL_DNS_SD, wifi, main::post, this);
                } else {
                    nsd.discoverServices(WifiVoiceTransfer.SERVICE, NsdManager.PROTOCOL_DNS_SD, this);
                }
                discovering = true;
                main.postDelayed(discoveryTimeout, 40_000);
            } catch (RuntimeException failure) {
                finish("Could not start Wi-Fi discovery. Check ATAK’s network access.");
            }
        }

        @Override public void onDiscoveryStarted(String type) {}
        @Override public void onDiscoveryStopped(String type) {}
        @Override public void onStartDiscoveryFailed(String type, int error) {
            main.post(() -> finish("Could not start searching for watches over Wi-Fi."));
        }
        @Override public void onStopDiscoveryFailed(String type, int error) {}
        @Override public void onServiceFound(NsdServiceInfo service) {
            main.post(() -> {
                if (closed || !discovering || watches.size() >= 24) return;
                String name = service.getServiceName();
                if (name == null || !WATCH_NAME.matcher(name).matches()) return;
                if (Build.VERSION.SDK_INT >= 33 && service.getNetwork() != null && !wifi.equals(service.getNetwork())) return;
                if (watches.put(name, service) == null) rows.add(name);
            });
        }
        @Override public void onServiceLost(NsdServiceInfo service) {
            main.post(() -> {
                if (closed || !discovering) return;
                if (watches.remove(service.getServiceName()) != null) rows.remove(service.getServiceName());
            });
        }

        private void resolve(String name) {
            NsdServiceInfo service = watches.get(name);
            if (closed || resolving || service == null) return;
            resolving = true;
            list.setEnabled(false);
            hint.setText("Connecting to " + name + "…");
            main.removeCallbacks(discoveryTimeout);
            main.postDelayed(() -> {
                if (resolving) finish("The watch stopped responding. Open Wi-Fi pairing again.");
            }, 8000);
            try {
                nsd.resolveService(service, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo ignored, int error) {
                        main.post(() -> finish("Could not resolve this watch. Open Wi-Fi pairing again."));
                    }
                    @Override public void onServiceResolved(NsdServiceInfo found) {
                        main.post(() -> {
                            if (closed) return;
                            resolving = false;
                            InetAddress address = ipv4(found);
                            byte[] version = found.getAttributes().get("voice");
                            int port = found.getPort();
                            if (address == null || port < 1 || port > 65535 || version == null || version.length != 1 || version[0] != '1'
                                    || (Build.VERSION.SDK_INT >= 33 && found.getNetwork() != null && !wifi.equals(found.getNetwork()))) {
                                finish("This watch’s Wi-Fi setup service is unavailable or incompatible.");
                                return;
                            }
                            stopDiscovery();
                            showCode(name, new InetSocketAddress(address, port));
                        });
                    }
                });
            } catch (RuntimeException failure) {
                finish("Could not look up this watch over Wi-Fi.");
            }
        }

        private InetAddress ipv4(NsdServiceInfo service) {
            if (Build.VERSION.SDK_INT >= 34) {
                for (InetAddress address : service.getHostAddresses()) if (usableAddress(address)) return address;
            }
            InetAddress address = service.getHost();
            return usableAddress(address) ? address : null;
        }

        private boolean usableAddress(InetAddress address) {
            if (!(address instanceof Inet4Address) || address.isAnyLocalAddress()
                    || address.isLoopbackAddress() || address.isMulticastAddress()) return false;
            ConnectivityManager manager = host.getSystemService(ConnectivityManager.class);
            try {
                LinkProperties link = manager == null ? null : manager.getLinkProperties(wifi);
                if (link == null) return false;
                for (LinkAddress local : link.getLinkAddresses()) {
                    if (local.getAddress() instanceof Inet4Address && !local.getAddress().equals(address)
                            && local.getPrefixLength() > 0
                            && new IpPrefix(local.getAddress(), local.getPrefixLength()).contains(address)) return true;
                }
            } catch (RuntimeException unavailable) {
                return false;
            }
            return false;
        }

        private void showCode(String name, InetSocketAddress endpoint) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
            EditText input = new EditText(host);
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            input.setSaveEnabled(false);
            if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(EditText.IMPORTANT_FOR_AUTOFILL_NO);
            input.setHint("8-digit code");
            AlertDialog prompt = new AlertDialog.Builder(host).setTitle(name)
                    .setMessage("Enter the code shown on the watch to send “" + label + "”.\n\n" + loginSummary)
                    .setView(input).setPositiveButton("Send", null).setNegativeButton("Cancel", null).create();
            dialog = prompt;
            prompt.setOnDismissListener(ignored -> { input.setText(""); WifiVoiceSender.this.close(); });
            prompt.show();
            prompt.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                String code = input.getText().toString().trim();
                if (!code.matches("[0-9]{8}")) {
                    input.setError("Enter all eight digits.");
                    return;
                }
                input.setText("");
                input.setEnabled(false);
                button.setEnabled(false);
                prompt.setMessage("Pairing and sending voice channels…");
                send(endpoint, code, prompt, input);
            });
        }

        private void send(InetSocketAddress endpoint, String code, AlertDialog prompt, EditText input) {
            Runnable deadline = () -> finish("Wi-Fi transfer timed out. Open pairing again on the watch.");
            main.postDelayed(deadline, 20_000);
            Thread transfer = new Thread(() -> {
                try (WifiPairingIdentity identity = new WifiPairingIdentity();
                     Socket connection = wifi.getSocketFactory().createSocket()) {
                    synchronized (this) {
                        if (closed) return;
                        socket = connection;
                    }
                    connection.connect(endpoint, 5000);
                    WifiVoiceTransfer.Receipt result = WifiVoiceTransfer.send(connection, code, identity, identity.certificate(), envelope);
                    main.post(() -> finish(switch (result) {
                        case STAGED -> "Channels sent. Review them on the watch.";
                        case BUSY -> "The watch has a package awaiting review.";
                        case QUEUED -> "Package delivered. Open Voice on the watch to check for review.";
                        case RECEIVER_UNAVAILABLE -> "Enable the WearTAK Voice plugin on the watch and retry.";
                        case REJECTED -> "The watch rejected this package.";
                    }));
                } catch (Exception failure) {
                    main.post(() -> {
                        if (closed) return;
                        prompt.setMessage("Pairing failed. Check the code and that the watch’s pairing screen is still open.");
                        input.setEnabled(true);
                        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    });
                } finally {
                    main.removeCallbacks(deadline);
                    synchronized (this) { socket = null; }
                }
            }, "WearTAK-WiFi-Voice-Send");
            transfer.start();
        }

        private void stopDiscovery() {
            main.removeCallbacks(discoveryTimeout);
            if (discovering) {
                discovering = false;
                try { nsd.stopServiceDiscovery(this); } catch (RuntimeException ignored) {}
            }
            if (multicast != null) {
                try { if (multicast.isHeld()) multicast.release(); } catch (RuntimeException ignored) {}
                multicast = null;
            }
        }

        private void finish(String message) {
            if (closed) return;
            WifiVoiceSender.this.close();
            notice(host, message);
        }

        private void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            }
            stopDiscovery();
            if (dialog != null) {
                dialog.setOnDismissListener(null);
                dialog.dismiss();
            }
        }
    }
}
