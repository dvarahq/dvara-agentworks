package com.dvarahq.agentworks.gateway.openaicompat;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Fails once more than {@code limit} bytes have been read, so one response cannot exhaust memory. */
final class BoundedInputStream extends FilterInputStream {

    static final class LimitExceeded extends IOException {
        LimitExceeded() {
            super("response byte limit exceeded");
        }
    }

    private final long limit;
    private long read;

    BoundedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count > 0) {
            count(count);
        }
        return count;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        count(skipped);
        return skipped;
    }

    private void count(long bytes) throws LimitExceeded {
        read += bytes;
        if (read > limit) {
            throw new LimitExceeded();
        }
    }
}
