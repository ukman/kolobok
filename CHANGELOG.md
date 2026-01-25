# Changelog

All notable changes to this project will be documented in this file.

## 0.2.5
- Global DebugLog defaults via plugin config and ENV/JVM properties (annotation > plugin > env/sys > built-in).
- Added Gradle `kolobok { debugLogDefaults { ... } }` configuration.
- Added Maven `<debugLogDefaults>` configuration.
- New unit tests for locals logging (JSON/HUMAN).
- Switched log prefix to `[KLB]`.
- Added `resultMask`/`maxResultLength` for return-value masking/truncation and unified `@DebugLogMask(mask="2,4")` style.
- Synced Maven/Gradle samples (DebugLog usage, Lombok, logging config).
- Transformer now compiles against annotations module (compile-scope dependency).

## 0.2.3
- Verified support for Java 11+ (tested on 11, 17, 21, 25) and updated build targets accordingly.
- Added `@DebugLog` annotation with bytecode instrumentation for method entry/exit/exception logging using existing SLF4J logger fields.
- Added line heat map aggregation for `@DebugLog` with nested output, traceId, and optional duration logging.
- Added `logArgs`, `mask`, `maxArgLength`, `logLevel`, `logFormat`, `logThreadId`, and `logThreadName` options for `@DebugLog`.
- Expanded HUMAN heat map logs to include full nested details with indentation.
- Added Gradle support for `-Pkolobok.skip=true` to disable transformer.
- Added `@DebugLogIgnore` and `@DebugLogMask` for per-parameter and local-variable masking in error logs.
- Local-variable capture currently supports `int` and reference types.
- Added `logLocals` and `logLocalsOnException` options for `@DebugLog`.
- `logLocals`/`logLocalsOnException` now log all locals by default, unless ignored/masked.
- Updated sample and example dependencies to 0.2.3.

## 0.2.0
- Switched `@FindWithOptionalParams` to bytecode transformation (no javac internals).
- Added Maven and Gradle plugins to run the transformer after compilation.
- Multi-module structure (`kolobok`, `kolobok-transformer`, `kolobok-maven-plugin`, `kolobok-gradle-plugin`).
- Added integration test for optional params transformer.
- Added Spring Data JPA samples (Maven/Gradle) with REST API and Swagger UI.
- Added seed data and application configuration for the Maven sample.
- Removed legacy `@CompileTime` and `@BuildNumber` annotations.
