package comparison;

public final class Smoke {
    public static void main(String[] args) throws Exception {
        for (String engine : new String[]{"kokodb", "h2"}) {
            for (int rows : new int[]{1, 100, 1000}) {
                try (var fixture = new ModelFixture(engine, rows)) {
                    fixture.verify();
                    fixture.verify();
                }
                System.out.println("Verified " + engine + " with " + rows + " Kotlin models");
            }
        }
    }
}
