package androidx.media3.common.text;

public class Cue {
    public final CharSequence text;

    public Cue(CharSequence text) {
        this.text = text != null ? text : "";
    }

    public CharSequence getText() {
        return text;
    }

    public static class Builder {
        private CharSequence text;
        private int positionAnchor;
        private int lineAnchor;
        private float position;
        private float line;
        private int lineType;
        private Object alignment;

        public Builder() {}

        public Builder setText(CharSequence text) {
            this.text = text;
            return this;
        }

        public Builder setPositionAnchor(int positionAnchor) {
            this.positionAnchor = positionAnchor;
            return this;
        }

        public int getPositionAnchor() {
            return positionAnchor;
        }

        public Builder setLineAnchor(int lineAnchor) {
            this.lineAnchor = lineAnchor;
            return this;
        }

        public int getLineAnchor() {
            return lineAnchor;
        }

        public Builder setPosition(float position) {
            this.position = position;
            return this;
        }

        public Builder setLine(float line, int lineType) {
            this.line = line;
            this.lineType = lineType;
            return this;
        }

        public Builder setTextAlignment(Object alignment) {
            this.alignment = alignment;
            return this;
        }

        public Cue build() {
            return new Cue(text);
        }
    }
}
