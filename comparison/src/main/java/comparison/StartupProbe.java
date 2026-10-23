package comparison;

import kokodb.Database;
import kotlin.Unit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A fresh-process workload; each engine runs with only its own runtime dependencies. */
public final class StartupProbe {
    interface Session extends AutoCloseable {
        void insert(int rows) throws Exception;
        List<BenchmarkUser> query() throws Exception;
    }

    static Session open(String engine) throws Exception {
        return switch (engine) {
            case "kokodb" -> new KokoSession();
            case "h2" -> new H2Session();
            default -> throw new IllegalArgumentException(engine);
        };
    }

    static void verify(List<BenchmarkUser> users, int count) {
        if (users.size() != count) throw new AssertionError("Model count");
        boolean[] seen = new boolean[count];
        for (var user : users) {
            int id = user.getId();
            if (id < 0 || id >= count || seen[id] || !user.getName().equals("user" + id)) {
                throw new AssertionError("Model content");
            }
            seen[id] = true;
        }
    }

    public static void main(String[] args) throws Exception {
        String engine = args[0], mode = args[1];
        if (!mode.equals("empty")) {
            try (var session = open(engine)) {
                switch (mode) {
                    case "ready" -> { }
                    case "first", "load1000" -> {
                        int rows = mode.equals("first") ? 1 : 1000;
                        session.insert(rows);
                        verify(session.query(), rows);
                    }
                    default -> throw new IllegalArgumentException(mode);
                }
            }
        }
        System.out.println("VERIFIED " + engine + " " + mode);
    }

    private static final class KokoSession implements Session {
        private final Database db = Database.Companion.inMemory(StartupProbe.class.getClassLoader());
        public void insert(int rows) {
            db.transaction(tx -> {
                for (int i = 0; i < rows; i++) {
                    int count = tx.invoke("INSERT INTO benchmark_users VALUES (:id, :name)", Map.of("id", i, "name", "user" + i));
                    if (count != 1) throw new AssertionError("Insert count");
                }
                return Unit.INSTANCE;
            });
        }
        public List<BenchmarkUser> query() {
            return db.queryModels(BenchmarkUser.class, "SELECT id, name FROM benchmark_users", Map.of());
        }
        public void close() { db.close(); }
    }

    private static final class H2Session implements Session {
        private final Connection connection;
        private final PreparedStatement insert;
        private final PreparedStatement select;
        private int rowCount;
        H2Session() throws Exception {
            connection = DriverManager.getConnection("jdbc:h2:mem:startup", "sa", "");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE benchmark_users (id INT PRIMARY KEY, name VARCHAR NOT NULL)");
            }
            connection.setAutoCommit(false);
            insert = connection.prepareStatement("INSERT INTO benchmark_users VALUES (?, ?)");
            select = connection.prepareStatement("SELECT id, name FROM benchmark_users");
        }
        public void insert(int rows) throws Exception {
            for (int i = 0; i < rows; i++) {
                insert.setInt(1, i);
                insert.setString(2, "user" + i);
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            if (counts.length != rows) throw new AssertionError("Batch size");
            for (int count : counts) if (count != 1 && count != Statement.SUCCESS_NO_INFO) throw new AssertionError("Insert count");
            connection.commit();
            rowCount = rows;
        }
        public List<BenchmarkUser> query() throws Exception {
            var result = new ArrayList<BenchmarkUser>(rowCount);
            try (var cursor = select.executeQuery()) {
                while (cursor.next()) result.add(new BenchmarkUser(cursor.getInt(1), cursor.getString(2)));
            }
            return result;
        }
        public void close() throws Exception { connection.close(); }
    }
}
