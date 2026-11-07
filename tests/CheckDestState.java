
import java.io.*;
import java.nio.file.*;

public class CheckDestState {
    public static void main(String[] args) throws Exception {
        File dir = new File("check_dest_state");
        dir.mkdirs();
        File destFile = new File(dir, "dest.srt");
        Files.writeString(destFile.toPath(), "Initial Content");

        System.out.println("Initially exists: " + destFile.exists());

        File tmp = new File(dir, "tmp.srt");
        Files.writeString(tmp.toPath(), "Tmp Content");

        try {
            Files.move(tmp.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("After move exists: " + destFile.exists() + ", length=" + destFile.length());
        } catch (Exception e) {
            System.out.println("Move failed: " + e);
        }

        destFile.delete();
        dir.delete();
    }
}
