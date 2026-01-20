package com.example.kolobok;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PersonRepositoryIT {

    @Autowired
    private PersonRepository personRepository;

    @Test
    void findByFirstNameAndLastNameSupportsNulls() {
        seedPeople();

        assertThat(personRepository.findByFirstNameAndLastName(null, null)).hasSize(10);
        assertThat(personRepository.findByFirstNameAndLastName("John", null)).hasSize(3);
        assertThat(personRepository.findByFirstNameAndLastName(null, "Smith")).hasSize(4);
        assertThat(personRepository.findByFirstNameAndLastName("John", "Smith")).hasSize(1);
        assertThat(personRepository.findByFirstNameAndLastName("Missing", "Smith")).isEmpty();
    }

    @Test
    void findByFirstNameAndLastNameAndTitleSupportsNullTitle() {
        seedPeople();

        assertThat(personRepository.findByFirstNameAndLastNameAndTitle("John", "Smith", "Mr")).hasSize(1);
        assertThat(personRepository.findByFirstNameAndLastNameAndTitle("John", "Smith", null)).hasSize(1);
    }

    private void seedPeople() {
        List<Person> people = List.of(
                new Person(null, "John", "Smith", "Mr"),
                new Person(null, "Jane", "Smith", "Ms"),
                new Person(null, "John", "Doe", "Mr"),
                new Person(null, "Alice", "Doe", "Ms"),
                new Person(null, "Bob", "Stone", "Mr"),
                new Person(null, "John", "Stone", "Mr"),
                new Person(null, "Alice", "Smith", "Ms"),
                new Person(null, "Bob", "Doe", "Mr"),
                new Person(null, "Carol", "Smith", "Ms"),
                new Person(null, "Carol", "Stone", "Ms")
        );
        personRepository.saveAll(people);
    }

}
