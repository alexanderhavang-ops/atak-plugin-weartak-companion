package se.takcampus.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** WTK1 acoustic transport: short HTTPS link only, not credentials. See docs/AUDIO_ONBOARDING.md. */
public final class AudioLinkEncoder {
    public static final int RATE = 48000, SLOT = 144, MAX_LINK = 384, CHUNK = 32, FRAME = 44;
    private static final int SYNC = 0xD391C5A7;
    private AudioLinkEncoder() {}
    private static long crc(byte[] bytes, int length) { CRC32 crc = new CRC32(); crc.update(bytes, 0, length); return crc.getValue(); }
    public static byte[][] frames(String link) {
        byte[] bytes = link.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_LINK) throw new IllegalArgumentException("Link too long");
        int count = (bytes.length + CHUNK - 1) / CHUNK;
        byte[][] frames = new byte[count][];
        int transfer = (int) crc(bytes, bytes.length);
        for (int index = 0; index < count; index++) {
            byte[] frame = new byte[FRAME];
            ByteBuffer.wrap(frame).putInt(transfer).putShort((short) bytes.length).put((byte) index).put((byte) count);
            System.arraycopy(bytes, index * CHUNK, frame, 8, Math.min(CHUNK, bytes.length - index * CHUNK));
            ByteBuffer.wrap(frame, FRAME - 4, 4).putInt((int) crc(frame, FRAME - 4)); frames[index] = frame;
        }
        return frames;
    }
    /** Encode the same WTK1 setup sound as the TAKS onboarding card. */
    public static short[] encode(String link) {
        ByteArrayOutputStream slots = new ByteArrayOutputStream();
        for (byte[] frame : frames(link)) {
            for (int i = 0; i < 16; i++) slots.write(i & 1);
            for (int i = 31; i >= 0; i--) bit(slots, (SYNC >>> i) & 1);
            for (byte b : frame) for (int i = 7; i >= 0; i--) bit(slots, (b >>> i) & 1);
            // Quiet gap lets every decoder abandon a damaged frame before the next sync.
            for (int i = 0; i < 24; i++) slots.write(2);
        }
        byte[] tones = slots.toByteArray(); short[] pcm = new short[tones.length * SLOT];
        for (int slot = 0; slot < tones.length; slot++) {
            if (tones[slot] == 2) continue;
            int frequency = tones[slot] == 0 ? 2000 : 4000;
            for (int n = 0; n < SLOT; n++) pcm[slot * SLOT + n] = (short) (15000 * Math.sin(2 * Math.PI * frequency * n / RATE));
        }
        return pcm;
    }
    private static void bit(ByteArrayOutputStream slots, int bit) { slots.write(bit); slots.write(1 - bit); }
}
