package com.example.wagroupsexporter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Opens a WhatsApp end-to-end encrypted backup (msgstore.db.crypt15) with the user's 64-digit key and writes the
 * SQLite database it contains to a file. Works as a stream, so large backups do not need to fit in memory.
 *
 * The layout is detected by trial (several possible header layouts, IV positions and key derivations are tried and
 * checked against the first decrypted bytes), so small differences between WhatsApp versions are tolerated.
 */
public final class Crypt15 {
    private Crypt15() { }

    public interface Source { InputStream open() throws IOException; }

    public interface Progress { void update(long bytesProcessed); }

    /** "1234 abcd ..." (64 hex digits, spaces allowed) -> 32 bytes. */
    public static byte[] parseKey(String text) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (Character.digit(ch, 16) >= 0) sb.append(ch);
        }
        if (sb.length() != 64) {
            throw new IllegalArgumentException("The key must have exactly 64 digits (0-9 and a-f). You typed " + sb.length() + ".");
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) out[i] = (byte) Integer.parseInt(sb.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    // ------------------------------------------------------------------ keys
    private static byte[] hmac(byte[] key, byte[] msg) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(msg);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** The most likely AES key first (HKDF-SHA256 of the root key with the label "backup encryption"). */
    static List<byte[]> keyVariants(byte[] root) throws GeneralSecurityException {
        List<byte[]> out = new ArrayList<>();
        byte[] info = concat("backup encryption".getBytes(StandardCharsets.US_ASCII), new byte[] {1});
        byte[] prk = hmac(new byte[32], root);
        out.add(hmac(prk, info));
        out.add(root.clone());
        out.add(hmac(root, info));
        return out;
    }

    // ------------------------------------------------------------------ header
    static final class Candidate {
        final int start;
        final byte[] iv;

        Candidate(int start, byte[] iv) {
            this.start = start;
            this.iv = iv;
        }
    }

    private static long[] varint(byte[] b, int p, int end) {
        long v = 0;
        int shift = 0;
        while (p < end && shift < 64) {
            int x = b[p++] & 0xFF;
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return new long[] {v, p};
            shift += 7;
        }
        return null;
    }

    private static void collect(byte[] b, int off, int end, int depth, List<byte[]> out) {
        int p = off;
        while (p < end) {
            long[] tag = varint(b, p, end);
            if (tag == null) return;
            p = (int) tag[1];
            int wire = (int) (tag[0] & 7);
            if (wire == 0) {
                long[] v = varint(b, p, end);
                if (v == null) return;
                p = (int) v[1];
            } else if (wire == 1) {
                p += 8;
            } else if (wire == 5) {
                p += 4;
            } else if (wire == 2) {
                long[] len = varint(b, p, end);
                if (len == null) return;
                p = (int) len[1];
                int l = (int) len[0];
                if (l < 0 || p + l > end) return;
                if (l == 16 || l == 12) out.add(Arrays.copyOfRange(b, p, p + l));
                if (depth < 3 && l > 2) collect(b, p, p + l, depth + 1, out);
                p += l;
            } else {
                return;
            }
        }
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) sb.append(String.format("%02x", b[i] & 0xFF));
        return sb.toString();
    }

    static List<Candidate> candidates(byte[] head, int headLen) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long[] sz = varint(head, 0, headLen);
        if (sz == null) return out;
        int size = (int) sz[0];
        int p0 = (int) sz[1];
        if (size <= 0) return out;
        int[] protoStarts = {p0 + 1, p0};            // with / without the extra flag byte after the length
        for (int ps : protoStarts) {
            int pe = ps + size;
            if (pe > headLen) continue;
            List<byte[]> strings = new ArrayList<>();
            collect(head, ps, pe, 0, strings);
            strings.sort((a, b) -> Integer.compare(b.length, a.length));   // 16-byte IVs first
            for (byte[] iv : strings) {
                if (seen.add(pe + ":" + hex(iv, 16))) out.add(new Candidate(pe, iv));
            }
            if (pe + 16 <= headLen) {                // IV stored right in front of the encrypted data
                byte[] iv = Arrays.copyOfRange(head, pe, pe + 16);
                if (seen.add((pe + 16) + ":" + hex(iv, 16))) out.add(new Candidate(pe + 16, iv));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ AES-GCM keystream as plain CTR
    private static byte[] gmul(byte[] x, byte[] y) {
        long zh = 0, zl = 0;
        long vh = toLong(y, 0), vl = toLong(y, 8);
        for (int i = 0; i < 128; i++) {
            if (((x[i >> 3] >>> (7 - (i & 7))) & 1) != 0) {
                zh ^= vh;
                zl ^= vl;
            }
            boolean lsb = (vl & 1L) != 0;
            vl = (vl >>> 1) | (vh << 63);
            vh = vh >>> 1;
            if (lsb) vh ^= 0xE100000000000000L;
        }
        byte[] r = new byte[16];
        fromLong(zh, r, 0);
        fromLong(zl, r, 8);
        return r;
    }

    private static long toLong(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xFFL);
        return v;
    }

    private static void fromLong(long v, byte[] b, int o) {
        for (int i = 7; i >= 0; i--) {
            b[o + i] = (byte) v;
            v >>>= 8;
        }
    }

    private static byte[] ghash(byte[] h, byte[] data) {
        byte[] y = new byte[16];
        for (int off = 0; off < data.length; off += 16) {
            for (int i = 0; i < 16; i++) y[i] ^= data[off + i];
            y = gmul(y, h);
        }
        return y;
    }

    /** Counter block for the first data block of AES-GCM with this IV (J0 + 1). */
    static byte[] initialCounter(byte[] aesKey, byte[] iv) throws GeneralSecurityException {
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
        byte[] h = ecb.doFinal(new byte[16]);
        byte[] j0;
        if (iv.length == 12) {
            j0 = new byte[16];
            System.arraycopy(iv, 0, j0, 0, 12);
            j0[15] = 1;
        } else {
            int pad = (16 - iv.length % 16) % 16;
            byte[] data = new byte[iv.length + pad + 16];
            System.arraycopy(iv, 0, data, 0, iv.length);
            long bits = (long) iv.length * 8;
            for (int i = 0; i < 8; i++) data[data.length - 1 - i] = (byte) (bits >>> (8 * i));
            j0 = ghash(h, data);
        }
        byte[] ctr = j0.clone();
        for (int i = 15; i >= 12; i--) {
            ctr[i]++;
            if (ctr[i] != 0) break;
        }
        return ctr;
    }

    private static InputStream ctrStream(InputStream raw, byte[] aesKey, byte[] counter) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(counter));
        return new CipherInputStream(raw, c);
    }

    // ------------------------------------------------------------------ helpers
    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("File is too short");
                s = 1;
            }
            left -= s;
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) break;
            got += r;
        }
        return got == n ? b : Arrays.copyOf(b, got);
    }

    private static boolean isZlib(byte[] h) {
        if (h.length < 2) return false;
        int b0 = h[0] & 0xFF, b1 = h[1] & 0xFF;
        return b0 == 0x78 && ((b0 << 8) | b1) % 31 == 0;
    }

    private static boolean isSqlite(byte[] h) {
        byte[] m = "SQLite format 3".getBytes(StandardCharsets.US_ASCII);
        if (h.length < m.length) return false;
        for (int i = 0; i < m.length; i++) if (h[i] != m[i]) return false;
        return true;
    }

    private static boolean isZip(byte[] h) {
        return h.length >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
    }

    private static boolean plausible(Source src, byte[] aesKey, Candidate c) {
        try (InputStream raw = src.open()) {
            skipFully(raw, c.start);
            InputStream dec = ctrStream(raw, aesKey, initialCounter(aesKey, c.iv));
            byte[] h = readN(dec, 16);
            return h.length == 16 && (isZlib(h) || isSqlite(h) || isZip(h));
        } catch (Exception e) {
            return false;
        }
    }

    private static final class Counting extends FilterInputStream {
        private final Progress cb;
        private long count, last;

        Counting(InputStream in, Progress cb) {
            super(in);
            this.cb = cb;
        }

        private void add(long n) {
            if (n > 0) {
                count += n;
                if (cb != null && count - last > (4 << 20)) {
                    last = count;
                    cb.update(count);
                }
            }
        }

        @Override public int read() throws IOException {
            int r = super.read();
            if (r >= 0) add(1);
            return r;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            add(r);
            return r;
        }
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) >= 0) os.write(buf, 0, r);
        }
    }

    private static PushbackInputStream peek(InputStream in, byte[][] headOut) throws IOException {
        PushbackInputStream p = new PushbackInputStream(in, 32);
        byte[] h = readN(p, 16);
        p.unread(h, 0, h.length);
        headOut[0] = h;
        return p;
    }

    private static void writeDatabase(InputStream dec, File out) throws IOException {
        byte[][] h = new byte[1][];
        PushbackInputStream a = peek(dec, h);
        InputStream content = a;
        if (isZlib(h[0])) content = new InflaterInputStream(a, new Inflater(), 1 << 16);
        PushbackInputStream b = peek(content, h);
        if (isSqlite(h[0])) {
            copy(b, out);
        } else if (isZip(h[0])) {
            ZipInputStream z = new ZipInputStream(b);
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().endsWith("msgstore.db")) {
                    copy(z, out);
                    return;
                }
            }
            throw new IOException("The backup does not contain msgstore.db");
        } else {
            throw new IOException("format: unexpected content " + hex(h[0], 16));
        }
    }

    /**
     * Decrypts the backup into a SQLite file. Throws an IOException with a readable message when the key or the
     * file does not fit.
     */
    public static void decryptToFile(Source src, byte[] rootKey, File out, Progress progress) throws Exception {
        byte[] head;
        try (InputStream in = src.open()) {
            head = readN(in, 2048);
        }
        if (head.length < 64) throw new IOException("The chosen file is too small to be a WhatsApp backup.");
        List<byte[]> keys = keyVariants(rootKey);
        List<Candidate> cands = candidates(head, head.length);
        for (Candidate c : cands) {
            for (byte[] k : keys) {
                if (!plausible(src, k, c)) continue;
                try (InputStream raw0 = src.open()) {
                    skipFully(raw0, c.start);
                    InputStream raw = new Counting(raw0, progress);
                    InputStream dec = ctrStream(raw, k, initialCounter(k, c.iv));
                    writeDatabase(dec, out);
                    return;
                } catch (IOException e) {
                    if (e.getMessage() == null || !e.getMessage().startsWith("format:")) throw e;
                }
            }
        }
        throw new IOException("Could not decrypt this file. Check that (1) the 64-digit key is the one shown when you turned on "
                + "end-to-end encrypted backup, (2) the file is msgstore.db.crypt15 (not crypt14 or crypt12), and (3) the backup was "
                + "made after you turned the encryption on. File starts with: " + hex(head, 12));
    }
}
