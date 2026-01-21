# Changelog

All notable changes to this project will be documented in this file.

## 0.2.3
- Verified support for Java 11+ (tested on 11, 17, 21, 25) and updated build targets accordingly.
- Added `@LogContext` annotation with bytecode instrumentation for method entry/exit/exception logging using existing SLF4J logger fields.
- Updated sample and example dependencies to 0.2.3.

## 0.2.0
- Switched `@FindWithOptionalParams` to bytecode transformation (no javac internals).
- Added Maven and Gradle plugins to run the transformer after compilation.
- Multi-module structure (`kolobok`, `kolobok-transformer`, `kolobok-maven-plugin`, `kolobok-gradle-plugin`).
- Added integration test for optional params transformer.
- Added Spring Data JPA samples (Maven/Gradle) with REST API and Swagger UI.
- Added seed data and application configuration for the Maven sample.
- Removed legacy `@CompileTime` and `@BuildNumber` annotations.
