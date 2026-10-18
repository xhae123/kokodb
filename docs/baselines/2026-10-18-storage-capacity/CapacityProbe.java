import kokodb.Database;
import java.nio.file.*;
import java.util.*;
public class CapacityProbe {
  public static void main(String[] args) throws Exception {
    Path path = Path.of(args[0]);
    String text = "x".repeat(4 * 1024 * 1024);
    int accepted = 0;
    long start = System.nanoTime();
    try (Database db = Database.Companion.open(path, CapacityProbe.class.getClassLoader())) {
      db.invoke("CREATE TABLE payloads (id INT, text TEXT)", Map.of());
      for (int id = 0; id < 65; id++) {
        try { db.invoke("INSERT INTO payloads VALUES (:id, :text)", Map.of("id", id, "text", text)); accepted++; }
        catch (RuntimeException error) { System.out.println("insert_rejected=" + id + ":" + error.getMessage()); break; }
      }
      System.out.println("accepted=" + accepted);
      System.out.println("visible=" + db.query("SELECT id FROM payloads", Map.of()).size());
      System.out.println("wal_bytes=" + Files.size(path.resolveSibling(path.getFileName() + ".wal")));
      try { db.checkpoint(); System.out.println("checkpoint=success"); }
      catch (RuntimeException error) { System.out.println("checkpoint=" + error.getClass().getSimpleName() + ":" + error.getMessage()); }
      System.out.println("snapshot_bytes=" + Files.size(path));
    }
    System.out.println("elapsed_ms=" + (System.nanoTime() - start) / 1_000_000);
  }
}
