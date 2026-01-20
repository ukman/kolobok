# Kolobok 0.2.0 Release Notes

## Highlights
- Optional params processing now uses bytecode transformation instead of javac internals.
- New Maven and Gradle plugins run the transformer after compilation (no `--add-exports`).
- Sample Spring Data JPA app with REST API, Swagger UI, and seed data.

## Compatibility
- Target runtime: Java 21 for the transformer.
- `@FindWithOptionalParams` is the only supported annotation in 0.2.0.

## How to Use
- Maven: add `com.github.ukman:kolobok` with scope `provided` and apply `kolobok-maven-plugin` goal `transform`.
- Gradle: add `com.github.ukman:kolobok` as `compileOnly` and apply `kolobok-gradle-plugin`.

## Samples
- `samples/spring-data-jpa-maven`: REST API `/persons`, search endpoint `/persons/search`, Swagger UI at `/swagger-ui.html`.
- `samples/spring-data-jpa-gradle`: Gradle wiring example.

## Known Limitations
- Only `Iterable`, `List`, and `Page` return types are supported for `@FindWithOptionalParams`.
