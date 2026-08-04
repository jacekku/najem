rootProject.name = "najem"
include(
    "contracts",
    "platform:eventstore",
    "platform:mt940",
    "modules:propertymanagement",
    "modules:accounting",
    "modules:contacts",
    "modules:usermanagement",
    "modules:reporting",
    "apps:najem-app",
    "apps:fakebank",
    "e2e",
)
