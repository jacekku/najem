package pl.najem.fakebank;

public class UnknownScenarioException extends RuntimeException {

    private final String name;

    public UnknownScenarioException(String name) {
        super("Unknown scenario: " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
