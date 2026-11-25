package com.weartak.atak.weartak_companion.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.atakmap.android.gui.ImportFileBrowserDialog;
import com.atakmap.coremap.io.IOProviderFactory;
import org.json.JSONArray;
import se.takcampus.voice.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Edit the identity for one explicit Mumble destination before sending a package. */
final class VoiceAuthDialog {
    private final Context context;
    private final JSONArray profiles;
    private final BooleanSupplier current;
    private final Consumer<byte[]> next;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final byte[] mission;
    private final VoiceMission parsed;

    VoiceAuthDialog(Context context, byte[] mission, VoiceMission parsed, JSONArray profiles,
                    BooleanSupplier current, Consumer<byte[]> next) {
        this.context = context;
        this.mission = mission;
        this.parsed = parsed;
        this.profiles = profiles;
        this.current = current;
        this.next = next;
    }

    void open() {
        LinkedHashMap<String, VoiceProfile> endpoints = new LinkedHashMap<>();
        for (VoiceMission.Channel channel : parsed.channels) {
            VoiceProfile p = channel.profile();
            if (!p.multicast()) endpoints.putIfAbsent(p.endpoint(), p);
        }
        List<VoiceProfile> targets = new ArrayList<>(endpoints.values());
        if (targets.isEmpty()) { problem("This package has no Mumble channels."); return; }
        if (targets.size() == 1) { choose(targets.get(0)); return; }
        new AlertDialog.Builder(context).setTitle("Mumble endpoint")
                .setItems(endpoints.keySet().toArray(new String[0]), (d,i) -> choose(targets.get(i)))
                .setNegativeButton("Cancel", null).show();
    }

    private void choose(VoiceProfile target) {
        String[] options = {"Mumble server password", "TAK auto-enrollment", "Import client certificate (.p12)", "Mumble account login"};
        new AlertDialog.Builder(context).setTitle(target.endpoint()).setItems(options, (d,i) -> {
            if (i == 1) chooseEnrollment(target);
            else if (i == 2) chooseSoft(target);
            else password(target, i == 3);
        }).setNegativeButton("Cancel", null).show();
    }

    private EditText field(String hint, boolean secret) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        input.setSaveEnabled(false);
        return input;
    }

    private void password(VoiceProfile target, boolean account) {
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText user = field("Mumble username", false);
        EditText pass = field(account ? "Account password" : "Server password (blank for an open server)", true);
        if (account) fields.addView(user);
        fields.addView(pass);
        VoiceLogin existing = parsed.login(target);
        if (existing != null && existing.mode.equals(account ? VoiceLogin.ACCOUNT : VoiceLogin.SERVER)) {
            user.setText(existing.username);
            pass.setText(existing.password);
        }
        AlertDialog prompt = new AlertDialog.Builder(context).setTitle(target.endpoint()).setView(fields)
                .setNegativeButton("Cancel", null).setPositiveButton("Use login", null).create();
        prompt.setOnShowListener(d -> prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (account && (user.getText().toString().trim().isEmpty() || pass.length() == 0)) {
                pass.setError("Enter both the Mumble username and password."); return;
            }
            try {
                String secret = pass.getText().toString();
                VoiceLogin login = account ? new VoiceLogin(user.getText().toString().trim(), secret) : VoiceLogin.server(secret);
                prompt.dismiss(); apply(target, login);
            } catch (IllegalArgumentException e) { pass.setError("Check the length and format of the login."); }
        }));
        prompt.show();
    }

    private void chooseEnrollment(VoiceProfile target) {
        List<TakServerItem> servers = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (profiles != null) for (int i = 0; i < profiles.length(); i++) {
            TakServerItem item = TakServerItem.fromJson(profiles.optJSONObject(i));
            if (!item.isEnabled || item.isP12Cert || item.serverId == null || item.serverId.isEmpty()) continue;
            servers.add(item);
            labels.add(item.name + " (" + item.address + ":" + item.port + ")");
        }
        // Wi-Fi onboarding can operate without a companion settings snapshot.
        labels.add("Choose the WearTAK profile on the watch");
        new AlertDialog.Builder(context).setTitle("Use enrollment login saved in WearTAK")
                .setItems(labels.toArray(new String[0]), (d,i) ->
                        apply(target, VoiceLogin.enroll(i == servers.size() ? "" : servers.get(i).serverId)))
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseSoft(VoiceProfile target) {
        new Thread(() -> {
            List<AtakServerImport.Connection> servers = new ArrayList<>();
            try {
                for (AtakServerImport.Connection c : AtakServerSource.connections())
                    if (c.supported() && !c.enrollment && c.enabled && AtakServerSource.hasClientCertificate(c)) servers.add(c);
            } catch (RuntimeException | LinkageError ignored) { /* File selection still works. */ }
            main.post(() -> {
                if (!current.getAsBoolean()) return;
                List<String> labels = new ArrayList<>();
                for (AtakServerImport.Connection c : servers) labels.add(c.name + " (" + c.host + ":" + c.port + ")");
                labels.add("Choose a .p12 file on this phone");
                new AlertDialog.Builder(context).setTitle("Client certificate for " + target.endpoint())
                        .setItems(labels.toArray(new String[0]), (d,i) -> {
                            if (i == servers.size()) chooseFile(target);
                            else savedSoft(target, servers.get(i));
                        }).setNegativeButton("Cancel", null).show();
            });
        }, "Voice-soft-profiles").start();
    }

    private void savedSoft(VoiceProfile target, AtakServerImport.Connection connection) {
        new Thread(() -> {
            try (AtakServerTransfer.SoftMaterial material = AtakServerSource.readSoftCertificates(connection);
                 AtakServerTransfer.SoftResult result = AtakServerTransfer.softCertificates(material)) {
                if (!result.ready()) { problem(result.problem); return; }
                apply(target, VoiceLogin.certificate(result.bytes, result.password));
            } catch (Exception | LinkageError e) { problem("Could not load this soft certificate, or it exceeds the package size limit."); }
        }, "Voice-soft-certificate").start();
    }

    private void chooseFile(VoiceProfile target) {
        ImportFileBrowserDialog.show("Choose client certificate", new String[]{"p12", "pfx"},
            new ImportFileBrowserDialog.DialogDismissed() {
                public void onDialogClosed() {}
                public void onFileSelected(java.io.File file) {
                    EditText pass = field("PKCS#12 file password", true);
                    new AlertDialog.Builder(context).setTitle(file.getName()).setView(pass)
                        .setNegativeButton("Cancel", null).setPositiveButton("Use certificate", (d,w) -> {
                            String password = pass.getText().toString();
                            new Thread(() -> {
                                try (InputStream in = IOProviderFactory.getInputStream(file)) {
                                    byte[] bytes = VoicePackageImporter.readBounded(in, 24000);
                                    try {
                                        if (!AtakServerImport.usableClientP12(bytes, password)) {
                                            problem("Check the .p12 file and its password."); return;
                                        }
                                        apply(target, VoiceLogin.certificate(bytes, password));
                                    } finally { java.util.Arrays.fill(bytes, (byte) 0); }
                                } catch (Exception e) { problem("Could not read the .p12 or it exceeds the package size limit."); }
                            }, "Voice-certificate-file").start();
                        }).show();
                }
            }, context);
    }

    private void apply(VoiceProfile target, VoiceLogin login) {
        try {
            byte[] updated = VoicePackageImporter.setLogin(mission, target, login);
            VoiceHandoff.packageForTransfer(updated);
            main.post(() -> { if (current.getAsBoolean()) next.accept(updated); });
        } catch (Exception e) { problem("Could not prepare this login. Check the fields and package size."); }
    }

    private void problem(String message) {
        main.post(() -> {
            if (current.getAsBoolean()) new AlertDialog.Builder(context).setMessage(message).setPositiveButton("OK", null).show();
        });
    }
}
