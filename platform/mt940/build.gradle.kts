plugins { `java-library` }

// Deliberately dependency-free: this library parses text into records and knows nothing else.
// Test dependencies (JUnit, AssertJ) come from the root build's subprojects block.
