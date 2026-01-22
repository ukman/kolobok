# Kolobok 0.2.4 Release Notes

## Highlights
- Global DebugLog defaults via plugin config and ENV/JVM properties.
- New unit tests for local-variable logging (JSON and HUMAN formats).
- Log prefix switched to `[KLB]` for easier filtering.
- Maven and Gradle samples synchronized.

## Changes
- DebugLog defaults precedence: annotation > plugin config > env/system > built-in defaults.
- Gradle plugin adds `kolobok { debugLogDefaults { ... } }` configuration.
- Maven plugin adds `<debugLogDefaults>` configuration block.
- Transformer now depends on annotations module at compile time.
- Added locals logging integration tests.
- Updated sample apps to use DebugLog consistently, Lombok in Gradle sample, and matching logging config.

## Migration Notes
- `@LogContext` was removed in favor of `@DebugLog` (breaking change if still referenced).
- Log prefix changed from `[LC]` to `[KLB]`.

## Compatibility
- Java 11+ (verified 11/17/21/25).
- Spring Boot 2.x compatible; Spring 3 target remains supported.
