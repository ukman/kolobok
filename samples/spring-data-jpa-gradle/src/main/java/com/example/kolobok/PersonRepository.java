package com.example.kolobok;

import org.kolobok.annotation.FindWithOptionalParams;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PersonRepository extends JpaRepository<Person, Long> {
    @FindWithOptionalParams
    List<Person> findByFirstNameAndLastName(String firstName, String lastName);

    @FindWithOptionalParams
    List<Person> findByFirstNameAndLastNameAndTitle(String firstName, String lastName, String title);
}
