package pl.najem.fakebank;

import java.util.List;

/** What a seed call produced, so a test can name a specific line afterwards. */
public record ScenarioResult(int seeded, List<String> externalIds) {
}
