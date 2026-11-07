package androidx.media3.common.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ParsableByteArray {
    private byte[] data = new byte[0];
    private int position = 0;
    private int limit = 0;

    public ParsableByteArray() {}

    public void reset(byte[] data, int limit) {
        this.data = data;
        this.limit = limit;
        this.position = 0;
    }

    public void setPosition(int position) {
        this.position = Math.min(position, limit);
    }

    public int getPosition() {
        return position;
    }

    public int limit() {
        return limit;
    }

    public Charset readUtfCharsetFromBom() {
        if (limit - position >= 3 &&
            (data[position] & 0xFF) == 0xEF &&
            (data[position + 1] & 0xFF) == 0xBB &&
            (data[position + 2] & 0xFF) == 0xBF) {
            position += 3;
            return StandardCharsets.UTF_8;
        }
        return null;
    }

    public String readLine(Charset charset) {
        if (position >= limit) {
            return null;
        }
        int lineEnd = position;
        while (lineEnd < limit && data[lineEnd] != '\n' && data[lineEnd] != '\r') {
            lineEnd++;
        }
        String line = new String(data, position, lineEnd - position, charset != null ? charset : StandardCharsets.UTF_8);
        position = lineEnd;
        if (position < limit && data[position] == '\r') {
            position++;
        }
        if (position < limit && data[position] == '\n') {
            position++;
        }
        return line;
    }
}
