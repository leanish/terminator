# Changelog

## Unreleased
- Upgraded `io.github.leanish.java-conventions` from `0.5.5` to `0.6.1`, keeping its PIT mutation testing switched off
  (`leanish.conventions.pitest.enabled=false`).
- Upgraded the Gradle wrapper from `9.5.1` to `9.8.0`.
- Upgraded GitHub Actions to `actions/checkout@v7` and `actions/setup-java@v6`.

### Security
- Floor Guava at `33.7.2-jre` in Error Prone and Checkstyle configurations to address
  [GHSA-xxph-c9ww-hj94](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94), preserving the configured Checkstyle tool version.

## 0.1.0 - 2025-09-30
- Introduce the `Terminator` coordinator with blocking and non-blocking service support plus interrupt-aware shutdown orchestration.
- Provide `BlockingTerminable`, `NonBlockingTerminable` and `TerminationException` APIs.
- Add JUnit 5 + AssertJ regression tests covering termination ordering, timeouts and failure aggregation.
- Configure Gradle publishing for Maven Local and GitHub Packages alongside a GitHub Actions build on Temurin JDK 21.
