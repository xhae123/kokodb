import kokodb.Database;
import kokodb.storage.*;
import java.nio.file.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
public class RecoveryProbe {
  static void fixture(Path path, int rows) throws Exception {
    var codec = StorageCodec.INSTANCE;
    var identity = new UUID(38, rows);
    var columns = List.of(new Column("id", DataType.INT, true), new Column("name", DataType.TEXT, false));
    Files.write(path, codec.encodeSnapshot(new Snapshot(identity, 0, new Catalog())));
    try (var output = new BufferedOutputStream(Files.newOutputStream(path.resolveSibling(path.getFileName()+".wal")))) {
      output.write(codec.encodeWalHeader(new WalHeader(identity, 0, 2)));
      output.write(codec.encodeSpliceFrame(1, Map.of("items", new TableSplice(columns, -1, 0, 0, List.of()))));
      for (int id=0; id<rows; id++) output.write(codec.encodeSpliceFrame(id+2L, Map.of("items", new TableSplice(columns, id, id, 0,
          List.of(List.of(new Value.IntValue(id), new Value.TextValue("value-"+id)))))));
    }
  }
  static void read(Path path, int rows, int round) throws Exception {
    var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    long start=System.nanoTime();
    try (var db=Database.Companion.open(path, RecoveryProbe.class.getClassLoader())) {
      long elapsed=System.nanoTime()-start;
      allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
      var result=db.query("SELECT * FROM items", Map.of());
      if (result.size()!=rows) throw new AssertionError("row count");
      boolean[] seen = new boolean[rows];
      for (var row : result) { int id=row.getInt("id"); if(id<0 || id>=rows || seen[id] || !row.getString("name").equals("value-"+id)) throw new AssertionError("row "+id); seen[id]=true; }
      if(round>=0) System.out.printf(Locale.ROOT,"%d,%d,%d,%.3f,%d%n", rows,round,Files.size(path.resolveSibling(path.getFileName()+".wal")),elapsed/1e6,allocated);
    }
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);Files.createDirectories(root);
    Path warm=root.resolve("warm.koko");fixture(warm,1000);for(int i=0;i<3;i++)read(warm,1000,-1);
    System.out.println("rows,round,wal_bytes,open_ms,allocated_bytes");
    for(int rows:new int[]{4000,16000}) {
      Path path=root.resolve(rows+".koko");fixture(path,rows);
      for(int round=0;round<3;round++)read(path,rows,round);
    }
  }
}
