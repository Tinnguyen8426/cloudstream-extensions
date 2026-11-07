
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class PinpointNull {
    public static void main(String[] args) throws Exception {
        File dir = new File("pinpoint_null_dir");
        dir.mkdirs();
        File destFile = new File(dir, "dest.srt");
        Files.writeString(destFile.toPath(), "Initial Content");

        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(12);

        AtomicInteger existsFailed = new AtomicInteger(0);
        AtomicInteger isFileFailed = new AtomicInteger(0);
        AtomicInteger lengthZeroFailed = new AtomicInteger(0);
        AtomicInteger totalReads = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            pool.submit(() -> {
                while (running.get()) {
                    totalReads.incrementAndGet();
                    boolean ex = destFile.exists();
                    if (!ex) {
                        existsFailed.incrementAndGet();
                        continue;
                    }
                    boolean isF = destFile.isFile();
                    if (!isF) {
                        isFileFailed.incrementAndGet();
                        continue;
                    }
                    long len = destFile.length();
                    if (len <= 0) {
                        lengthZeroFailed.incrementAndGet();
                        continue;
                    }
                    try {
                        String s = Files.readString(destFile.toPath());
                    } catch (Exception e) {}
                }
            });
        }

        // Writer replaces file 50 times
        for (int i = 0; i < 50; i++) {
            File tmp = new File(dir, "tmp_" + System.currentTimeMillis() + ".srt");
            Files.writeString(tmp.toPath(), "Content " + i);
            try {
                Files.move(tmp.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                try {
                    Files.move(tmp.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {}
            }
            tmp.delete();
            Thread.sleep(5);
        }

        running.set(false);
        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.SECONDS);

        System.out.println("Total reads: " + totalReads.get());
        System.out.println("existsFailed: " + existsFailed.get());
        System.out.println("isFileFailed: " + isFileFailed.get());
        System.out.println("lengthZeroFailed: " + lengthZeroFailed.get());

        destFile.delete();
        dir.delete();
    }
}
