package comparison;

import kokodb.Database;
import kotlin.Unit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PointFixture implements AutoCloseable {
    private final String[] names;
    private Database database;
    private Connection connection;
    private PreparedStatement select;
    private PreparedStatement update;

    PointFixture(String engine, int rows) throws Exception {
        names = new String[rows];
        for (int i = 0; i < rows; i++) names[i] = "user" + i;
        if (engine.equals("kokodb")) {
            database = Database.Companion.inMemory(PointFixture.class.getClassLoader());
            database.transaction(tx -> {
                for (int i = 0; i < rows; i++) tx.invoke("INSERT INTO benchmark_users VALUES (:id, :name)", Map.of("id", i, "name", names[i]));
                return Unit.INSTANCE;
            });
        } else if (engine.equals("h2")) {
            connection = DriverManager.getConnection("jdbc:h2:mem:point_" + UUID.randomUUID(), "sa", "");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE benchmark_users (id INT PRIMARY KEY, name VARCHAR NOT NULL)");
            }
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO benchmark_users VALUES (?, ?)")) {
                for (int i = 0; i < rows; i++) {
                    insert.setInt(1, i);
                    insert.setString(2, names[i]);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(true);
            select = connection.prepareStatement("SELECT id, name FROM benchmark_users WHERE id = ?");
            update = connection.prepareStatement("UPDATE benchmark_users SET name = ? WHERE id = ?");
        } else throw new IllegalArgumentException("Unknown engine " + engine);
        verify();
    }

    List<BenchmarkUser> query(int id) throws Exception {
        if (database != null) return database.queryModels(BenchmarkUser.class,
            "SELECT id, name FROM benchmark_users WHERE id = :id", Map.of("id", id));
        select.setInt(1, id);
        var result = new ArrayList<BenchmarkUser>(1);
        try (var cursor = select.executeQuery()) {
            while (cursor.next()) result.add(new BenchmarkUser(cursor.getInt(1), cursor.getString(2)));
        }
        return result;
    }

    void update(int id, String name) throws Exception {
        int affected;
        if (database != null) affected = database.invoke("UPDATE benchmark_users SET name = :name WHERE id = :id", Map.of("id", id, "name", name));
        else {
            update.setString(1, name);
            update.setInt(2, id);
            affected = update.executeUpdate();
        }
        if (affected != 1) throw new AssertionError("Update count");
        names[id] = name;
    }

    void verify() throws Exception {
        for (int id = 0; id < names.length; id++) {
            var rows = query(id);
            if (rows.size() != 1 || rows.get(0).getId() != id || !rows.get(0).getName().equals(names[id])) {
                throw new AssertionError("Point query content at " + id);
            }
        }
        if (!query(-1).isEmpty() || !query(names.length).isEmpty()) throw new AssertionError("Point query miss");
    }

    @Override
    public void close() throws Exception {
        try {
            if (select != null) select.close();
            if (update != null) update.close();
        } finally {
            if (connection != null) connection.close();
            if (database != null) database.close();
        }
    }
}
