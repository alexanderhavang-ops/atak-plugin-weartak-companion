package com.weartak.atak.weartak_companion.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import se.takcampus.voice.AudioLinkEncoder;
import se.takcampus.voice.AudioSetupPayload;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Plays WTK1 setup sounds independently of the BLE connection. */
final class VoiceSetupSound {
    private static final String CARD_PATH = "/api/onboarding/cards/";
    private static final String WATCH_PATH = CARD_PATH + "watch-voice";
    private static final int MAX_RESPONSE = 4096;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private AudioTrack track;
    private ViewTreeObserver.OnWindowFocusChangeListener focusListener;
    private int generation;

    static URI ticketEndpoint(String value) {
        URI card = URI.create(value.trim());
        String path = card.getRawPath();
        if (!validOrigin(card) || card.getRawFragment() != null || path == null
                || !path.matches(CARD_PATH + "[A-Za-z0-9_-]{16,160}")) {
            throw new IllegalArgumentException("Paste an HTTPS TAKS onboarding card link.");
        }
        // Keep the request on the card's origin and strip browser-only query parameters.
        return URI.create("https://" + card.getRawAuthority() + path + "/watch-voice/ticket");
    }

    static String validateSetupLink(URI endpoint, String value) {
        URI setup = URI.create(value);
        if (!validOrigin(setup) || !setup.getHost().equalsIgnoreCase(endpoint.getHost())
                || !WATCH_PATH.equals(setup.getRawPath()) || setup.getRawQuery() != null
                || setup.getRawFragment() == null || !setup.getRawFragment().matches("[A-Za-z0-9_-]{43}")
                || value.getBytes(StandardCharsets.UTF_8).length > AudioLinkEncoder.MAX_LINK) {
            throw new IllegalArgumentException("TAKS returned an invalid voice setup link.");
        }
        return value;
    }

    private static boolean validOrigin(URI uri) {
        return "https".equals(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    @FunctionalInterface
    private interface SoundSource { String load() throws Exception; }

    void open(Context context, boolean taks) {
        close();
        EditText input = new EditText(context);
        input.setHint(taks ? "TAKS onboarding card link" : "HTTPS link to a voice ZIP");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) {
            input.setImportantForAutofill(EditText.IMPORTANT_FOR_AUTOFILL_NO);
        }
        AlertDialog entry = new AlertDialog.Builder(context).setTitle(taks ? "TAKS card link" : "Voice ZIP link")
                .setMessage("On the watch, open Voice setup and choose Listen for setup sound.")
                .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Next", null).create();
        dialog = entry;
        entry.setOnDismissListener(ignored -> input.setText(""));
        entry.setOnShowListener(ignored -> entry.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            URI endpoint;
            try { endpoint = taks ? ticketEndpoint(input.getText().toString()) : AudioSetupPayload.zipUri(input.getText().toString().trim()); }
            catch (IllegalArgumentException invalid) {
                input.setError(taks ? "Paste an HTTPS TAKS onboarding card link." : "Paste a short HTTPS link to the voice ZIP.");
                return;
            }
            entry.dismiss();
            showPlayer(context, endpoint.getHost(), taks ? () -> requestSetupLink(endpoint) : endpoint::toString, true);
        }));
        entry.show();
    }

    void playChannel(Context context, se.takcampus.voice.VoiceProfile channel) throws Exception {
        String payload = AudioSetupPayload.encodeChannel(channel);
        close();
        showPlayer(context, channel.label(), () -> payload, false);
    }

    private void showPlayer(Context context, String title, SoundSource source, boolean link) {
        TextView status = new TextView(context);
        int inset = (int) (20 * context.getResources().getDisplayMetrics().density);
        status.setPadding(inset, inset, inset, inset);
        status.setText(link ? "Hold the phone speaker near the watch. Play the link in private."
                : "Hold the phone speaker near the watch. This sound contains channel settings only.");
        AlertDialog player = new AlertDialog.Builder(context).setTitle(title)
                .setView(status).setNegativeButton("Close", null).setPositiveButton("Play sound", null).create();
        dialog = player;
        player.setOnDismissListener(ignored -> stopPlayback(player));
        player.setOnShowListener(ignored -> player.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                view -> startPlayback(context, source, player, status)));
        player.show();
        focusListener = hasFocus -> {
            if (!hasFocus && dialog == player) {
                stopAudio();
                generation++;
                status.setText("Playback stopped. Tap Play sound for a fresh code.");
                player.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            }
        };
        player.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
    }

    private void startPlayback(Context context, SoundSource source, AlertDialog player, TextView status) {
        int attempt = ++generation;
        player.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        status.setText("Preparing setup sound…");
        new Thread(() -> {
            try {
                short[] pcm = AudioLinkEncoder.encode(source.load());
                main.post(() -> {
                    try {
                        if (dialog != player || generation != attempt || !player.isShowing()) return;
                        AudioTrack audio = new AudioTrack.Builder()
                                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                                .setAudioFormat(new AudioFormat.Builder().setSampleRate(AudioLinkEncoder.RATE)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
                        track = audio;
                        AudioManager manager = context.getSystemService(AudioManager.class);
                        if (manager != null) {
                            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                    audio.setPreferredDevice(device);
                                    break;
                                }
                            }
                        }
                        if (audio.write(pcm, 0, pcm.length) != pcm.length) throw new IllegalStateException("Audio unavailable");
                        audio.play();
                        status.setText("Playing. Hold the speaker near the watch.");
                        main.postDelayed(() -> {
                            if (dialog == player && generation == attempt) {
                                stopAudio();
                                status.setText("Finished. Review the setup on your watch.");
                                player.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            }
                        }, (pcm.length * 1000L + AudioLinkEncoder.RATE - 1) / AudioLinkEncoder.RATE + 400);
                    } catch (RuntimeException unavailable) {
                        stopAudio();
                        status.setText("Could not play the code. Check the phone’s media audio.");
                        player.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    } finally {
                        Arrays.fill(pcm, (short) 0);
                    }
                });
            } catch (Exception failure) {
                String message = failure instanceof TicketError ? failure.getMessage()
                        : failure instanceof SSLException ? "The phone could not verify the TAKS certificate."
                        : "Could not prepare the setup sound. Check the link and phone network.";
                main.post(() -> {
                    if (dialog != player || generation != attempt) return;
                    status.setText(message);
                    player.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                });
            }
        }, "voice-setup-ticket").start();
    }

    private static String requestSetupLink(URI endpoint) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) endpoint.toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Origin", "https://" + endpoint.getRawAuthority());
            int code = connection.getResponseCode();
            if (code != 200) {
                if (code == 404) throw new TicketError("No watch voice setup endpoint was found. Check the card and TAKS version.");
                if (code == 401 || code == 403 || code == 410) throw new TicketError("This card cannot issue a voice setup code.");
                if (code == 429) throw new TicketError("Too many codes requested. Wait one minute and try again.");
                throw new TicketError("TAKS could not issue a setup code (HTTP " + code + ").");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (body.size() + count > MAX_RESPONSE) throw new TicketError("TAKS returned an oversized setup response.");
                    body.write(buffer, 0, count);
                }
                return validateSetupLink(endpoint, new JSONObject(body.toString("UTF-8")).getString("url"));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static final class TicketError extends IOException {
        TicketError(String message) { super(message); }
    }

    private void stopAudio() {
        AudioTrack audio = track;
        track = null;
        if (audio != null) {
            try { audio.stop(); } catch (IllegalStateException ignored) { }
            audio.release();
        }
    }

    private void stopPlayback(AlertDialog player) {
        if (dialog != player) return;
        generation++;
        if (focusListener != null && player.getWindow() != null) {
            player.getWindow().getDecorView().getViewTreeObserver().removeOnWindowFocusChangeListener(focusListener);
        }
        focusListener = null;
        stopAudio();
        dialog = null;
    }

    void close() {
        if (dialog != null) {
            AlertDialog open = dialog;
            stopPlayback(open);
            open.dismiss();
        }
    }
}
