package androidx.media3.common;

import java.util.List;

public class Format {
    public final String sampleMimeType;
    public final List<byte[]> initializationData;

    public Format(String sampleMimeType) {
        this.sampleMimeType = sampleMimeType;
        this.initializationData = null;
    }

    public Format(String sampleMimeType, List<byte[]> initializationData) {
        this.sampleMimeType = sampleMimeType;
        this.initializationData = initializationData;
    }
}
