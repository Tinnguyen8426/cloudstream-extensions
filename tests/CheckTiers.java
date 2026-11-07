
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CheckTiers {
    public static void main(String[] args) throws Exception {
        File dir = new File("check_tiers_dir");
        dir.mkdirs();
        File destFile = new File(dir, "dest.srt");
        Files.writeString(destFile.toPath(), "Initial Content");

        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(12);

        AtomicInteger tier1Success = new AtomicInteger(0);
        AtomicInteger tier2Success = new AtomicInteger(0);
        AtomicInteger tier3Success = new AtomicInteger(0);
        AtomicInteger allFailed = new AtomicInteger(0);
        AtomicInteger nullReadCount = new AtomicInteger(0);

        // 10 concurrent readers continuously reading destFile
        for (int i = 0; i < 10; i++) {
            pool.submit(() -> {
                while (running.get()) {
                    if (!destFile.exists()) {
                        nullReadCount.incrementAndGet();
                    } else {
                        try {
                            String s = Files.readString(destFile.toPath());
                        } catch (Exception e) {
                            nullReadCount.incrementAndGet();
                        }
                    }
                }
            });
        }

        // Writer does 40 saves simulating saveFullFile
        for (int i = 0; i < 40; i++) {
            File tempFile = new File(dir, "tmp_" + System.currentTimeMillis() + ".srt");
            Files.writeString(tempFile.toPath(), "Content " + i);

            boolean replaced = false;
            for (int attempt = 0; attempt <= 5; attempt++) {
                try {
                    Files.move(tempFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    replaced = true;
                    tier1Success.incrementAndGet();
                    break;
                } catch (Throwable t1) {
                    try {
                        Files.move(tempFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        replaced = true;
                        tier2Success.incrementAndGet();
                        break;
                    } catch (Throwable t2) {
                        if (attempt < 5) {
                            try { Thread.sleep(10); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (!replaced) {
                // Tier 3
                boolean tier3Ok = false;
                try {
                    if (destFile.exists()) destFile.delete();
                    if (!destFile.exists() && tempFile.renameTo(destFile)) {
                        replaced = true;
                        tier3Ok = true;
                    }
                } catch (Throwable t3) {}
                if (tier3Ok) {
                    tier3Success.incrementAndGet();
                } else {
                    allFailed.incrementAndGet();
                }
            }
            tempFile.delete();
            Thread.sleep(5);
        }

        running.set(false);
        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.SECONDS);

        System.out.println("Tier 1 (ATOMIC_MOVE) success: " + tier1Success.get());
        System.out.println("Tier 2 (REPLACE_EXISTING) success: " + tier2Success.get());
        System.out.println("Tier 3 (delete + rename) success: " + tier3Success.get());
        System.out.println("All tiers failed: " + allFailed.get());
        System.out.println("Null read count: " + nullReadCount.get());

        destFile.delete();
        dir.delete();
    }
}
