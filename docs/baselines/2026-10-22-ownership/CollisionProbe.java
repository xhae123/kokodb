import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import kokodb.Database;

public class CollisionProbe {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        ClassLoader loader = CollisionProbe.class.getClassLoader();
        Path lockPath = dir.resolve("app.koko.lock");
        try (Database snapshotOwner = Database.Companion.open(lockPath, loader);
             Database databaseOwner = Database.Companion.open(dir.resolve("app.koko"), loader)) {
            snapshotOwner.checkpoint();
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                 FileLock extraLock = channel.tryLock()) {
                if (extraLock == null) throw new AssertionError("Expected the pre-fix inode collision");
                System.out.println("REPRODUCED: acquired a second lock while the database owner is still open");
            }
        }
    }
}
