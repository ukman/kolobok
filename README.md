# kolobok
Java Annotation Processor for Spring. It contains a set of annotations that makes working with Spring easier. It is inspired by [Lombok](https://projectlombok.org/).

## @FindWithOptionalParams
Usually Spring Data Repository interface includes find methods. Something like 
```java
@Repository
public interface PersonRepo extends PagingAndSortingRepository<Person, Long> {
    Iterable<Person> findByFirstNameAndLastNameAndCityId(String firstName, String lastName, Long cityId);
}
```
But if you want to find only by `cityId` and `lastName` (without specifying `firstName`) you need to add new method
```java
    Iterable<Person> findByLastNameAndCityId(String lastName, Long cityId);
```
`@FindWithOptionalParams` annotation allows you to use original `findByFirstNameAndLastNameAndCityId` method with `null` values for params which should not be used in search criteria.
```java
@Repository
public interface PersonRepo extends PagingAndSortingRepository<Person, Long> {
    @FindWithOptionalParams
    Iterable<Person> findByFirstNameAndLastNameAndCityId(String firstName, String lastName, Long cityId);
}
```
So now you can call 
```java
    Iterable<Person> persons = personRepo.findByFirstNameAndLastNameAndCityId(null, "Smith", 1L);
```
### How it works?
It generates all possible find methods for search params.
```java
  Iterable<Person> cityId(Long cityId);
  Iterable<Person> lastName(String lastName);
  Iterable<Person> lastNameAndCityId(String lastName, Long cityId);
  Iterable<Person> firstName(String firstName);
  Iterable<Person> firstNameAndCityId(String firstName, Long cityId);
  Iterable<Person> firstNameAndLastName(String firstName, String lastName);
  Iterable<Person> firstNameAndLastNameAndCityId(String firstName, String lastName, Long cityId);
```
Also it generates default implementation of `findByFirstNameAndLastNameAndCityId` method that checks which params are null and calls corresponded method. 
```java
    default Iterable<Person> findByFirstNameAndLastNameAndCityId(String firstName, String lastName, Long cityId) {
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

## @CompileTime

If you need to 
know when a class has been compiled, you can use 
this annotation and mark `long`/`java.lang.Long` fields with this annotation.
Variable is going to be initialized with value from `System.currentTimeMillis()`.

```java
import org.kolobok.annotation.CompileTime;

public class Main {
    @CompileTime
    static long ct;
    
    public static void main(String args[]) {
        System.out.println("Class has been compiled at " + new Date(ct));
    } 
}
```
Output is
```
Class has been compiled at Sun Jan 26 12:31:04 MSK 2020
```

## @BuildNumber
You can increment a field in a class every time when somebody compiles
your application. You need a HTTP service that returns incrementing
build number by every time when it's being called. You can use 
[Backendless](https://backendless.com/docs/rest/ut_increment_by_1__return_current.html)
for this.

```java
@import org.kolobok.annotation.BuildNumber;

public class Main {
    @BuildNumber(url = "https://api.backendless.com/${APP_ID}/${API_KEY}/counters/build/increment/get", method = "PUT")
    static long bn;
    
    public static void main(String args[]) {
        System.out.println("Build number = " + bn);
    } 
}
```
Output is
```
Build number = 1
```
After recompilation
```
Build number = 2
```

## How to use Kolobok?
Just include kolobok library in your project.
Maven:
```xml
  <dependency>
    <groupId>com.github.ukman</groupId>
    <artifactId>kolobok</artifactId>
    <version>0.1.5</version>
    <scope>compile</scope>  
  </dependency>
```
Gradle:
```gradle
compileOnly 'com.github.ukman:kolobok:0.1.5'
```
And mark find methods with new annotations.
