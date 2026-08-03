rootProject.name = "najem"
include(
    "contracts",
    "platform:eventstore",
    "modules:propertymanagement",
    "modules:accounting",
    "apps:najem-app",
    "apps:fakebank",
    "e2e",
)
