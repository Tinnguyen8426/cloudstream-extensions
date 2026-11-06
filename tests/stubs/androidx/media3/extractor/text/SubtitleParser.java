package androidx.media3.extractor.text;

import androidx.media3.common.util.Consumer;

public interface SubtitleParser {
    int getCueReplacementBehavior();

    void parse(byte[] data, int offset, int length, OutputOptions outputOptions, Consumer<CuesWithTiming> output);

    default void reset() {}

    public static class OutputOptions {
        public final long startTimeUs;
        public final boolean outputAllCues;

        public OutputOptions(long startTimeUs, boolean outputAllCues) {
            this.startTimeUs = startTimeUs;
            this.outputAllCues = outputAllCues;
        }

        public static OutputOptions allCues() {
            return new OutputOptions(0L, true);
        }
    }
}
