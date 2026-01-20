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
`@FindWithOptionalParams` annotation allows you to use original `findByFirstNameAndLastNameAndCityId` method with `null` values for params which should not be used in search criteria.
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

## How to use Kolobok?
Kolobok uses a bytecode transformer after compilation, so no special compiler flags are needed. Java 21 is the target runtime for the transformer.

Maven:
```xml
  <dependency>
    <groupId>com.github.ukman</groupId>
    <artifactId>kolobok</artifactId>
    <version>0.2.0</version>
    <scope>provided</scope>
  </dependency>

  <build>
    <plugins>
      <plugin>
        <groupId>com.github.ukman</groupId>
        <artifactId>kolobok-maven-plugin</artifactId>
        <version>0.2.0</version>
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
    compileOnly 'com.github.ukman:kolobok:0.2.0'
}

buildscript {
    dependencies {
        classpath 'com.github.ukman:kolobok-gradle-plugin:0.2.0'
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
