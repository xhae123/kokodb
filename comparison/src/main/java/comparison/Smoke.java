package comparison;

public final class Smoke {
    public static void main(String[] args) throws Exception {
        for (String engine : new String[]{"kokodb", "h2"}) {
            for (int rows : new int[]{1, 100, 1000}) {
                try (var fixture = new ModelFixture(engine, rows)) {
                    fixture.verify();
                    fixture.verify();
                }
                try (var fixture = new PointFixture(engine, rows)) {
                    fixture.verify();
                    fixture.update(rows / 2, "updated");
                    fixture.verify();
                }
                try (var session = StartupProbe.open(engine)) {
                    session.insert(rows);
                    StartupProbe.verify(session.query(), rows);
                }
                System.out.println("Verified " + engine + " with " + rows + " Kotlin models and point queries");
            }
        }
    }
}
