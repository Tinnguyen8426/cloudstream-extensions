package androidx.media3.extractor.text;

import androidx.media3.common.text.Cue;
import com.google.common.collect.ImmutableList;
import java.util.List;

public final class CuesWithTiming {
    public final ImmutableList<Cue> cues;
    public final long startTimeUs;
    public final long durationUs;

    public CuesWithTiming(List<Cue> cues, long startTimeUs, long durationUs) {
        this.cues = ImmutableList.copyOf(cues);
        this.startTimeUs = startTimeUs;
        this.durationUs = durationUs;
    }
}
