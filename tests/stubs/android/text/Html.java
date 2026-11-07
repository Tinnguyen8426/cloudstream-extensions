package android.text;

public class Html {
    public static Spanned fromHtml(String source) {
        return new SpannableString(source);
    }
}
