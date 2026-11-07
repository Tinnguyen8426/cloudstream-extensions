import subprocess

diag_code = """
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class PinpointNull2 {
    public static void main(String[] args) throws Exception {
        File dir = new File("pinpoint_null_dir2");
        dir.mkdirs();
        File destFile = new File(dir, "dest.srt");
        Files.writeString(destFile.toPath(), "Initial Content");

        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(12);

        AtomicInteger move1Count = new AtomicInteger(0);
        AtomicInteger move2Count = new AtomicInteger(0);
        AtomicInteger moveFailCount = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            pool.submit(() -> {
                while (running.get()) {
                    try {
                        String s = Files.readString(destFile.toPath());
                    } catch (Exception e) {
                        // System.out.println("Read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            });
        }

        // Writer replaces file 50 times
        for (int i = 0; i < 50; i++) {
            File tmp = new File(dir, "tmp_" + System.currentTimeMillis() + ".srt");
            Files.writeString(tmp.toPath(), "Content " + i);
            try {
                Files.move(tmp.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                move1Count.incrementAndGet();
            } catch (Exception e1) {
                try {
                    Files.move(tmp.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    move2Count.incrementAndGet();
                } catch (Exception e2) {
                    moveFailCount.incrementAndGet();
                }
            }
            tmp.delete();
            Thread.sleep(5);
        }

        running.set(false);
        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.SECONDS);

        System.out.println("Move 1 ok: " + move1Count.get());
        System.out.println("Move 2 ok: " + move2Count.get());
        System.out.println("Move failed: " + moveFailCount.get());
        System.out.println("destFile exists at end: " + destFile.exists());

        destFile.delete();
        dir.delete();
    }
}
"""

with open("tests/PinpointNull2.java", "w") as f:
    f.write(diag_code)

subprocess.run(["javac", "tests/PinpointNull2.java"], check=True)
subprocess.run(["java", "-cp", "tests", "PinpointNull2"], check=True)
