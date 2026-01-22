# kolobok
Java tooling for Spring repositories. It contains a set of annotations that makes working with Spring easier. It is inspired by [Lombok](https://projectlombok.org/).

## @FindWithOptionalParams
Usually Spring Data Repository interface includes find methods. Something like 
```java
@Repository
public interface PersonRepo extends PagingAndSortingRepository<Person, Long> {
    List<Person> findByFirstNameAndLastNameAndCityId(String firstName, String lastName, Long cityId);
}
```
But if you want to find only by `cityId` and `lastName` (without specifying `firstName`) you need to add new method
```java
    List<Person> findByLastNameAndCityId(String lastName, Long cityId);
```
`@FindWithOptionalParams` annotation allows you to use original `findByFirstNameAndLastNameAndCityId` method with `null` values for params which should not be used in search criteria. If the return type is `List`, ensure your repository exposes a `findAll()` that returns `List` (for example by extending `JpaRepository`).
```java
@Repository
public interface PersonRepo extends PagingAndSortingRepository<Person, Long> {
    @FindWithOptionalParams
    List<Person> findByFirstNameAndLastNameAndCityId(String firstName, String lastName, Long cityId);
}
```
So now you can call 
```java
    List<Person> persons = personRepo.findByFirstNameAndLastNameAndCityId(null, "Smith", 1L);
```
### How it works?
It generates all possible find methods for search params. Supported return types: `java.lang.Iterable`, `java.util.List`, and `org.springframework.data.domain.Page`.
```java
  List<Person> cityId(Long cityId);
  List<Person> lastName(String lastName);
  List<Person> lastNameAndCityId(String lastName, Long cityId);
  List<Person> firstName(String firstName);
  List<Person> firstNameAndCityId(String firstName, Long cityId);
  List<Person> firstNameAndLastName(String firstName, String lastName);
  List<Person> firstNameAndLastNameAndCityId(String firstName, String lastName, Long cityId);
```
Also it generates default implementation of `findByFirstNameAndLastNameAndCityId` method that checks which params are null and calls corresponded method. This is done by a bytecode transformer after compilation.
```java
    default List<Person> findByFirstNameAndLastNameAndCityId(String firstName, String lastName, Long cityId) {
      if(firstName == null) {
        if(lastName == null) {
          if(cityId == null) {
            return findAll();
          } else {
            return cityId(cityId);
          }
        } else {
          if(cityId == null) {
            return lastName(lastName);
          } else {
            return lastNameAndCityId(lastName, cityId);
          }
        }
      } else {
        if(lastName == null) {
          if(cityId == null) {
            return firstName(firstName);
          } else {
            return firstNameAndCityId(firstName, cityId);
          }
        } else {
          if(cityId == null) {
            return firstNameAndLastName(firstName, lastName);
          } else {
            return firstNameAndLastNameAndCityId(firstName, lastName, cityId);
          }
        }
      }
    }
```

## @LogContext
`@LogContext` logs method entry (arguments), return values, exceptions, and execution time using an existing logger field on the class. The transformer looks for a static logger field named `log`, `logger`, `LOG`, or `LOGGER` with type `org.slf4j.Logger`. Entry/exit/heat map logs are emitted at `logLevel` (default: `DEBUG`), exceptions at `error`.

Optional flags:
- `lineHeatMap` collects per-line hit counts and logs a compressed JSON heat map after method exit.
- `lineHeatMapOnException` logs the heat map only when the method throws.
- `subHeatMap` suppresses top-level output when there is no parent heat map.
- `logDuration` adds `durationNs` to the heat map JSON.
- `aggregateChildren` collapses repeated child methods into one node (default: true).
- `logArgs` toggles argument logging (default: true).
- `mask` hides selected arguments by index (e.g. `"0,2-3"` or `"*"`).
- `maxArgLength` caps stringified arguments (default: 200).
- `logLevel` controls log level for entry/exit/heat map (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`).
- `logFormat` controls log format for entry/exit/heat map (`HUMAN` or `JSON`, default: `HUMAN`).
- `logThreadId` adds `threadId` (default: false).
- `logThreadName` adds `threadName` (default: false).

```java
import org.kolobok.annotation.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SampleService {
    private static final Logger log = LoggerFactory.getLogger(SampleService.class);

    @LogContext(lineHeatMap = true, logDuration = true)
    public String work(String name, int count) {
        return name + count;
    }
}

Heat map output format:
```json
{
  "traceId": "b9ce1b1e-23a3-4a4f-9f0b-78e8c78d82aa",
  "method": "com.example.Foo#bar(Ljava/lang/String;I)Ljava/lang/String;",
  "count": 1,
  "arguments": ["val1", 2],
  "lineHeatMap": {
    "100-103": 1,
    "104": 10
  },
  "durationNs": 123456,
  "children": []
}
```

Human log examples:
```
[LC] ENTER com.example.Foo#bar(String, int):String trace=... t=32 tn=http-nio-8080-exec-1 args=["val1", 2]
[LC] EXIT com.example.Foo#bar(String, int):String trace=... t=32 dur=123456ns result=ok
[LC] ERROR com.example.Foo#bar(String, int):String trace=... t=32 dur=123456ns err=IllegalStateException:boom
[LC] HEATMAP com.example.Foo#bar(String, int):String trace=... t=32 dur=123456ns args=["val1", 2] heatmap={100-103:1,104:10}
  - com.example.Foo#child(String):void count=2 dur=4000ns args=["x"] heatmap={120:2}
```
```

## How to use Kolobok?
Kolobok uses a bytecode transformer after compilation, so no special compiler flags are needed. It works on Java 11 and newer (verified on 11, 17, 21, 25). If you enable `@LogContext` heat maps, ensure the `kolobok` jar is available at runtime (do not use `provided`/`compileOnly`).

Maven:
```xml
  <dependency>
    <groupId>com.github.ukman</groupId>
    <artifactId>kolobok</artifactId>
    <version>0.2.3</version>
    <scope>provided</scope>
  </dependency>

  <build>
    <plugins>
      <plugin>
        <groupId>com.github.ukman</groupId>
        <artifactId>kolobok-maven-plugin</artifactId>
        <version>0.2.3</version>
        <executions>
          <execution>
            <goals>
              <goal>transform</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```

Gradle:
```gradle
dependencies {
    compileOnly 'com.github.ukman:kolobok:0.2.3'
}

buildscript {
    dependencies {
        classpath 'com.github.ukman:kolobok-gradle-plugin:0.2.3'
    }
}

apply plugin: 'org.kolobok'
```

And mark find methods with new annotations.

## Samples
- Maven sample: `samples/spring-data-jpa-maven`
- Gradle sample: `samples/spring-data-jpa-gradle`

The Maven sample includes a REST API (`/persons`) with a `/persons/search` endpoint and Swagger UI at `http://localhost:8080/swagger-ui.html`. It also ships with `data.sql` seed data.
The Gradle sample mirrors the Maven sample and works with the Gradle plugin as well.
Email me : sergey.grigorchuk@gmail.com
