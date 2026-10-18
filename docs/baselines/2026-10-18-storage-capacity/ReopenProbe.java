import kokodb.Database;
import java.nio.file.*;
import java.util.*;
public class ReopenProbe {
  public static void main(String[] args) throws Exception {
    try (Database db = Database.Companion.open(Path.of(args[0]), ReopenProbe.class.getClassLoader())) {
      System.out.println("recovered_rows=" + db.query("SELECT id FROM payloads", Map.of()).size());
      System.out.println("first_text_bytes=" + db.query("SELECT text FROM payloads WHERE id = 0", Map.of()).get(0).getString("text").length());
      System.out.println("last_id=" + db.query("SELECT id FROM payloads WHERE id = 62", Map.of()).get(0).getInt("id"));
    }
  }
}
