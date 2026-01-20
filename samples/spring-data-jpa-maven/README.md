# Kolobok Spring Data JPA (Maven)

This sample shows how to wire `@FindWithOptionalParams` with the Maven plugin.

Steps:
1) Build and install Kolobok locally from the repo root:
   `mvn -DskipTests install`
2) From this directory run:
   `mvn test` or `mvn spring-boot:run`

The plugin runs after compilation and injects default methods into the repository interface.

Swagger UI is available at `http://localhost:8080/swagger-ui.html` when the app is running.
