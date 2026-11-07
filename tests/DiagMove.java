
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class DiagMove {
    public static void main(String[] args) throws Exception {
        File dir = new File("test_diag_dir");
        dir.mkdirs();
        File dest = new File(dir, "target.srt");
        Files.writeString(dest.toPath(), "Initial Content");

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger nullCount = new AtomicInteger(0);
        AtomicInteger notExistsCount = new AtomicInteger(0);
        AtomicInteger zeroLengthCount = new AtomicInteger(0);
        AtomicInteger readCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(8);

        // Readers
        for (int i = 0; i < 6; i++) {
            pool.submit(() -> {
                while (running.get()) {
                    readCount.incrementAndGet();
                    if (!dest.exists()) {
                        notExistsCount.incrementAndGet();
                    } else if (dest.length() == 0) {
                        zeroLengthCount.incrementAndGet();
                    } else {
                        try {
                            String s = Files.readString(dest.toPath());
                        } catch (Exception e) {
                            // read error
                        }
                    }
                    try { Thread.sleep(1); } catch (Exception e) {}
                }
            });
        }

        // Writer
        for (int i = 0; i < 20; i++) {
            File tmp = new File(dir, "tmp_" + System.currentTimeMillis() + ".srt");
            Files.writeString(tmp.toPath(), "Content " + i);
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Write " + i + ": ATOMIC_MOVE OK");
            } catch (Exception e1) {
                System.out.println("Write " + i + ": ATOMIC_MOVE FAILED (" + e1.getClass().getSimpleName() + ": " + e1.getMessage() + ")");
                try {
                    Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Write " + i + ": REPLACE_EXISTING OK");
                } catch (Exception e2) {
                    System.out.println("Write " + i + ": REPLACE_EXISTING FAILED (" + e2.getClass().getSimpleName() + ": " + e2.getMessage() + ")");
                }
            }
            tmp.delete();
            Thread.sleep(5);
        }

        running.set(false);
        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.SECONDS);

        System.out.println("Reads: " + readCount.get());
        System.out.println("notExists: " + notExistsCount.get());
        System.out.println("zeroLength: " + zeroLengthCount.get());

        dest.delete();
        dir.delete();
    }
}
