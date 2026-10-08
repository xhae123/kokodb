package comparison;

import kokodb.Database;
import kotlin.Unit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ModelFixture implements AutoCloseable {
    private final int rows;
    private Database database;
    private Connection connection;
    private PreparedStatement select;

    ModelFixture(String engine, int rows) throws Exception {
        this.rows = rows;
        if (engine.equals("kokodb")) {
            database = Database.Companion.inMemory(ModelFixture.class.getClassLoader());
            database.transaction(tx -> {
                for (int i = 0; i < rows; i++) {
                    tx.invoke("INSERT INTO benchmark_users VALUES (:id, :name)", Map.of("id", i, "name", name(i)));
                }
                return Unit.INSTANCE;
            });
        } else if (engine.equals("h2")) {
            connection = DriverManager.getConnection("jdbc:h2:mem:bench_" + UUID.randomUUID(), "sa", "");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE benchmark_users (id INT PRIMARY KEY, name VARCHAR NOT NULL)");
            }
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO benchmark_users VALUES (?, ?)")) {
                for (int i = 0; i < rows; i++) {
                    insert.setInt(1, i);
                    insert.setString(2, name(i));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(true);
            select = connection.prepareStatement("SELECT id, name FROM benchmark_users");
        } else throw new IllegalArgumentException("Unknown engine " + engine);
        verify();
    }

    List<BenchmarkUser> query() throws Exception {
        if (database != null) {
            return database.queryModels(BenchmarkUser.class, "SELECT id, name FROM benchmark_users", Map.of());
        }
        var result = new ArrayList<BenchmarkUser>(rows);
        try (var cursor = select.executeQuery()) {
            while (cursor.next()) result.add(new BenchmarkUser(cursor.getInt(1), cursor.getString(2)));
        }
        return result;
    }

    void verify() throws Exception {
        var result = query();
        if (result.size() != rows) throw new AssertionError("Model count");
        var seen = new HashSet<Integer>();
        for (var user : result) {
            int id = user.getId();
            if (id < 0 || id >= rows || !seen.add(id) || !user.getName().equals(name(id))) {
                throw new AssertionError("Model content");
            }
        }
    }

    private static String name(int id) { return "user" + id; }

    @Override
    public void close() throws Exception {
        try {
            if (select != null) select.close();
        } finally {
            if (connection != null) connection.close();
            if (database != null) database.close();
        }
    }
}
