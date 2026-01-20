package com.example.kolobok;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class PersonService {
    private final PersonRepository repository;

    public PersonService(PersonRepository repository) {
        this.repository = repository;
    }

    public Person create(Person person) {
        person.setId(null);
        return repository.save(person);
    }

    public Person get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Person not found"));
    }

    public List<Person> list() {
        return toList(repository.findAll());
    }

    public Person update(Long id, Person update) {
        Person existing = get(id);
        existing.setFirstName(update.getFirstName());
        existing.setLastName(update.getLastName());
        existing.setTitle(update.getTitle());
        return repository.save(existing);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Person not found");
        }
        repository.deleteById(id);
    }

    public List<Person> search(String firstName, String lastName, String title) {
        if (title == null) {
            return repository.findByFirstNameAndLastName(firstName, lastName);
        }
        return repository.findByFirstNameAndLastNameAndTitle(firstName, lastName, title);
    }

    private List<Person> toList(Iterable<Person> iterable) {
        List<Person> results = new ArrayList<>();
        StreamSupport.stream(iterable.spliterator(), false).forEach(results::add);
        return results;
    }
}
