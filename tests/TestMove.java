
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;

public class TestMove {
    public static void main(String[] args) throws Exception {
        File f1 = new File("test1.txt");
        File f2 = new File("test2.txt");
        f1.createNewFile();
        f2.createNewFile();
        try {
            Files.move(f1.toPath(), f2.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("ATOMIC_MOVE + REPLACE_EXISTING succeeded!");
        } catch (Exception e) {
            System.out.println("Failed ATOMIC_MOVE + REPLACE_EXISTING: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
        
        try {
            f1.createNewFile();
            Files.move(f1.toPath(), f2.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("REPLACE_EXISTING succeeded!");
        } catch (Exception e) {
            System.out.println("Failed REPLACE_EXISTING: " + e.getClass().getName() + ": " + e.getMessage());
        }
        f1.delete();
        f2.delete();
    }
}
