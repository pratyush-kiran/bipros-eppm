package com.bipros.hds.infrastructure.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Tee-style InputStream that updates a SHA-256 digest as bytes are read.
 * Use {@link #hexSha256()} after the stream is fully consumed to retrieve the digest.
 */
public class ShaInputStream extends FilterInputStream {
    private final MessageDigest md;

    public ShaInputStream(InputStream in) {
        super(in);
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) md.update((byte) b);
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int r = super.read(b, off, len);
        if (r > 0) md.update(b, off, r);
        return r;
    }

    public String hexSha256() {
        return HexFormat.of().formatHex(md.digest());
    }
}
