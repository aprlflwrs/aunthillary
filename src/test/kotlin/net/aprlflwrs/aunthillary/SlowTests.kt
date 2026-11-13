package net.aprlflwrs.aunthillary

/**
 * JUnit category for tests that take more than about ten seconds (long simulations). Mark a
 * test class with `@Category(SlowTests::class)` and it runs in `./gradlew slowTest` instead of
 * `./gradlew test` and `check`, which stay under about a minute.
 */
interface SlowTests
