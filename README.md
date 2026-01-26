# kolobok
Java tooling for Spring repositories. It contains a set of annotations that makes working with Spring easier. It is inspired by [Lombok](https://projectlombok.org/).

## How to use Kolobok?
Kolobok uses a bytecode transformer after compilation, so no special compiler flags are needed. It works on Java 11 and newer (verified on 11, 17, 21, 25). If you enable `@DebugLog` (especially heat maps), ensure the `kolobok` jar is available at runtime (do not use `provided`/`compileOnly`). If you only use `@FindWithOptionalParams`/`@SafeCall`, you may keep the dependency as `compileOnly`/`provided`.

Maven:
```xml
  <dependency>
    <groupId>com.github.ukman</groupId>
    <artifactId>kolobok</artifactId>
    <version>0.2.5</version>
  </dependency>

  <build>
    <plugins>
      <plugin>
        <groupId>com.github.ukman</groupId>
        <artifactId>kolobok-maven-plugin</artifactId>
        <version>0.2.5</version>
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
    implementation 'com.github.ukman:kolobok:0.2.5'
}

buildscript {
    dependencies {
        classpath 'com.github.ukman:kolobok-gradle-plugin:0.2.5'
    }
}

apply plugin: 'org.kolobok'
```

If you want to disable transformation for production builds, see [Disable Transformer For Production Builds](#disable-transformer-for-production-builds).

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

## @SafeCall
`@SafeCall` makes chained calls null-safe without repeating side-effecting operations. The transformer rewrites the chain to store intermediate results in synthetic locals and returns a default value if any link is null.

Supported contexts:
- Local variable initialization.
- Method parameters (all chains rooted at the parameter become null-safe).
- Field initialization at declaration (only the initializer is transformed; usages inside methods are not yet transformed).

Defaults:
- Reference types → `null`.
- `Optional` → `Optional.empty()`.
- Primitives → `0`/`false`.

Examples:

```java
import org.kolobok.annotation.SafeCall;

@SafeCall
String zipCode = person.getAddress().getZipCode();
```

transforms to:

```java
Address _tmp1 = (person == null) ? null : person.getAddress();
String zipCode = (_tmp1 == null) ? null : _tmp1.getZipCode();
```

Method parameter example:

```java
public void processOrder(@SafeCall Order order) {
    String sku = order.getItem().getDetails().getSku();
    System.out.println(sku);
}
```

Field initializer example:

```java
public class Service {
    @SafeCall
    private Config config = provider.getGlobalConfig();
}
```

## @DebugLog
`@DebugLog` logs method entry (arguments), return values, exceptions, and execution time using an existing logger field on the class. The transformer looks for a static logger field named `log`, `logger`, `LOG`, or `LOGGER` with type `org.slf4j.Logger`. Entry/exit/heat map logs are emitted at `logLevel` (default: `DEBUG`), exceptions at `error`.

Optional flags:
- `lineHeatMap` collects per-line hit counts and logs a compressed JSON heat map after method exit.
- `lineHeatMapOnException` logs the heat map only when the method throws.
- `subHeatMap` suppresses top-level output when there is no parent heat map.
- `logDuration` adds `durationNs` to the heat map JSON.
- `aggregateChildren` collapses repeated child methods into one node (default: true).
- `logArgs` toggles argument logging (default: true).
- `mask` hides selected arguments by index (e.g. `"0,2-3"` or `"*"`).
- `maxArgLength` caps stringified arguments (default: 200).
- `resultMask` masks the return value using `first,last` format (e.g. `"2,4"`).
- `maxResultLength` caps stringified return values (default: same as `maxArgLength`).
- `logLevel` controls log level for entry/exit/heat map (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`).
- `logFormat` controls log format for entry/exit/heat map (`HUMAN` or `JSON`, default: `HUMAN`).
- `logThreadId` adds `threadId` (default: false).
- `logThreadName` adds `threadName` (default: false).
- `logHttpRequest` adds HTTP request info if available (best-effort, default: false).
  Uses Spring `RequestContextHolder` when present, otherwise falls back to MDC keys `http.method`, `http.path`, `http.query` or `http.url`.
- `tag` adds a static tag to logs (useful for filtering).
- `slowThresholdMs` logs only slow executions (entry logs are suppressed; exit/heatmap logs emitted when duration >= threshold; errors always log).

Example for Dropwizard/Jersey (JAX-RS) using MDC (framework-specific):

```java
import org.slf4j.MDC;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class MdcRequestFilter implements ContainerRequestFilter {
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String method = requestContext.getMethod();
        String path = requestContext.getUriInfo().getPath();
        String query = requestContext.getUriInfo().getRequestUri().getQuery();

        if (method != null) MDC.put("http.method", method);
        if (path != null) MDC.put("http.path", path);
        if (query != null) MDC.put("http.query", query);
    }
}
```

```java
import org.kolobok.annotation.DebugLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SampleService {
    private static final Logger log = LoggerFactory.getLogger(SampleService.class);

    @DebugLog(lineHeatMap = true, logDuration = true, logHttpRequest = true)
    public String work(String name, int count) {
        return name + count;
    }
}
```

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
[KLB] ENTER com.example.Foo#bar(String, int):String trace=... t=32 tn=http-nio-8080-exec-1 args=["val1", 2]
[KLB] EXIT com.example.Foo#bar(String, int):String trace=... t=32 dur=123456ns result=ok
[KLB] ERROR com.example.Foo#bar(String, int):String trace=... t=32 dur=123456ns err=IllegalStateException:boom
[KLB] HEATMAP com.example.Foo#bar(String, int):String trace=... t=32 dur=123456ns args=["val1", 2] heatmap={100-103:1,104:10}
  - com.example.Foo#child(String):void count=2 dur=4000ns args=["x"] heatmap={120:2}
```

## Global DebugLog Defaults
You can override defaults without touching source code. Precedence:
1) annotation values
2) Maven/Gradle plugin configuration
3) system properties / environment variables
4) built-in defaults

Note: if an annotation value equals its built-in default, it is treated as "unset" and can be overridden.

System properties (examples):
```
-Dkolobok.debuglog.logLocals=true
-Dkolobok.debuglog.logFormat=JSON
-Dkolobok.debuglog.maxArgLength=500
```

Environment variables (examples):
```
KLB_DEBUGLOG_LOG_LOCALS=true
KLB_DEBUGLOG_LOG_FORMAT=JSON
KLB_DEBUGLOG_MAX_ARG_LENGTH=500
```

Supported keys (system/env):
`lineHeatMap`, `lineHeatMapOnException`, `subHeatMap`, `logDuration`, `aggregateChildren`, `logArgs`,
`mask`, `maxArgLength`, `resultMask`, `maxResultLength`, `logLevel`, `logFormat`, `logThreadId`,
`logThreadName`, `logHttpRequest`, `tag`, `slowThresholdMs`, `logLocals`, `logLocalsOnException`.

Maven:
```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.github.ukman</groupId>
      <artifactId>kolobok-maven-plugin</artifactId>
      <version>0.2.5</version>
      <configuration>
        <debugLogDefaults>
          <tag>billing</tag>
          <slowThresholdMs>50</slowThresholdMs>
          <logLocals>true</logLocals>
          <logFormat>JSON</logFormat>
          <logHttpRequest>true</logHttpRequest>
          <maxArgLength>500</maxArgLength>
          <maxResultLength>500</maxResultLength>
          <resultMask>2,4</resultMask>
        </debugLogDefaults>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Gradle:
```gradle
kolobok {
    debugLogDefaults {
        tag = "billing"
        slowThresholdMs = 50
        logLocals = true
        logFormat = "JSON"
        logHttpRequest = true
        maxArgLength = 500
        maxResultLength = 500
        resultMask = "2,4"
    }
}
```

## Performance Overhead
`@DebugLog` is designed for debugging, not for always-on production use. Overhead depends on the depth of instrumentation, argument sizes, logging configuration, and whether heat maps are enabled.

Max impact (worst cases):
- `lineHeatMap=true` on methods with tight loops or large code blocks (adds per-line counters).
- Deep call trees with many annotated methods.
- Large or expensive-to-stringify arguments or return values.
- Synchronous log appenders under load.

Low impact scenarios:
- `lineHeatMap=false` and `logArgs=false` (minimal overhead).
- Only top-level endpoints are annotated.
- Logging level disabled by configuration (e.g., DEBUG off).

Parameter impact:
- `lineHeatMap`: highest overhead; adds per-line increments.
- `lineHeatMapOnException`: same overhead as `lineHeatMap`, but logs only on errors.
- `subHeatMap`: reduces top-level log volume; no significant runtime savings.
- `logDuration`: minimal overhead (nanoTime).
- `aggregateChildren`: reduces log size; little runtime cost.
- `logArgs`: can be expensive if arguments are large or have heavy `toString`.
- `mask`: small overhead; applied during argument formatting.
- `maxArgLength`: reduces string size and memory usage; slight processing cost.
- `resultMask`: masks return values; same cost profile as `mask`.
- `maxResultLength`: reduces return value size; same cost profile as `maxArgLength`.
- `logFormat`: JSON is usually heavier than HUMAN (escaping/formatting).
- `logThreadId`/`logThreadName`: minimal overhead.
- `logLevel`: if logging level is disabled, most work is skipped early.
- `logLocals`: logs all local variables (best-effort, for `int` and reference types), except those marked with `@DebugLogIgnore` or `@DebugLogMask`.
- `logLocalsOnException`: logs locals only on exceptions (same rules as `logLocals`).

## Parameter And Local Masking
You can control per-parameter and local-variable logging with annotations:

```java
import org.kolobok.annotation.DebugLog;
import org.kolobok.annotation.DebugLogIgnore;
import org.kolobok.annotation.DebugLogMask;

@DebugLog
void doPost(@DebugLogIgnore String cardNumber,
            @DebugLogMask(mask = "2,4") String passport) {
    @DebugLogIgnore(mode = DebugLogIgnore.Mode.SUCCESS)
    String tmp = loadTemp();
    @DebugLogMask(mask = "2,4")
    String secret = readSecret();
    // ...
}
```

Notes:
- `@DebugLogIgnore` hides the value completely.
- `@DebugLogIgnore(mode = SUCCESS)` hides the value on success, but shows it on exceptions.
- `@DebugLogMask(mask = "first,last")` prints only the first/last characters, masking the middle.
- Local-variable annotations require debug symbols (`-g`) and are best-effort (based on local variable tables).
- Local-variable capture currently tracks `int` and reference types; other primitives are ignored.

## Disable Transformer For Production Builds
You can disable bytecode transformation without changing source code.

Maven:
```
mvn -Dkolobok.skip=true package
```
Or in `pom.xml`:
```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.github.ukman</groupId>
      <artifactId>kolobok-maven-plugin</artifactId>
      <version>0.2.5</version>
      <configuration>
        <skip>true</skip>
      </configuration>
    </plugin>
  </plugins>
</build>
```
Or using a `prod` profile:
```xml
<profiles>
  <profile>
    <id>prod</id>
    <build>
      <plugins>
        <plugin>
          <groupId>com.github.ukman</groupId>
          <artifactId>kolobok-maven-plugin</artifactId>
          <version>0.2.5</version>
          <configuration>
            <skip>true</skip>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

Gradle:
```
./gradlew build -Pkolobok.skip=true
```
Or in `build.gradle`:
```groovy
tasks.named("kolobokTransform").configure {
    enabled = false
}
```
Or with a `prod` property:
```groovy
if (project.hasProperty("prod")) {
    tasks.named("kolobokTransform").configure {
        enabled = false
    }
}
```

## Samples
- Maven sample: `samples/spring-data-jpa-maven`
- Gradle sample: `samples/spring-data-jpa-gradle`

The Maven sample includes a REST API (`/persons`) with a `/persons/search` endpoint and Swagger UI at `http://localhost:8080/swagger-ui.html`. It also ships with `data.sql` seed data.
The Gradle sample mirrors the Maven sample and works with the Gradle plugin as well.
