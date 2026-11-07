import subprocess

diag_code = """
import java.io.*;
import java.nio.file.*;

public class WhyAtomicFails {
    public static void main(String[] args) throws Exception {
        File f1 = new File("src.txt");
        File f2 = new File("dst.txt");
        Files.writeString(f1.toPath(), "source");
        Files.writeString(f2.toPath(), "dest");

        // Open reader handle with standard FileInputStream
        FileInputStream in = new FileInputStream(f2);

        try {
            Files.move(f1.toPath(), f2.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("ATOMIC_MOVE succeeded with open reader handle!");
        } catch (Throwable t) {
            System.out.println("ATOMIC_MOVE failed: " + t.getClass().getName() + ": " + t.getMessage());
        }

        try {
            Files.move(f1.toPath(), f2.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("REPLACE_EXISTING succeeded with open reader handle!");
        } catch (Throwable t) {
            System.out.println("REPLACE_EXISTING failed: " + t.getClass().getName() + ": " + t.getMessage());
        }

        in.close();
        f1.delete();
        f2.delete();
    }
}
"""

with open("tests/WhyAtomicFails.java", "w") as f:
    f.write(diag_code)

subprocess.run(["javac", "tests/WhyAtomicFails.java"], check=True)
subprocess.run(["java", "-cp", "tests", "WhyAtomicFails"], check=True)
