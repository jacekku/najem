rootProject.name = "najem"
include(
    "contracts",
    "platform:eventstore",
    "modules:propertymanagement",
    "modules:accounting",
    "modules:contacts",
    "modules:usermanagement",
    "modules:reporting",
    "apps:najem-app",
    "apps:fakebank",
    "e2e",
)
