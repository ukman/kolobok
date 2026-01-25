package com.example.kolobok;

import lombok.extern.slf4j.Slf4j;
import org.kolobok.annotation.DebugLog;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Slf4j
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
        return repository.save(existing);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Person not found");
        }
        repository.deleteById(id);
    }

    @DebugLog(lineHeatMap = true, subHeatMap=true, logDuration = true, logLocalsOnException = true, logHttpRequest = true, tag = "persons")
    public List<Person> search(String firstName, String lastName, String title) {
        log.info("Searching for persons with firstName: {}, lastName: {}, title: {}", firstName, lastName, title);
        List<Person> res;
        int a = firstName == null ? 0 : firstName.length();
        int b = lastName == null ? 0 : lastName.length();
        if (a + b == 5) {
            throw new IllegalArgumentException("Wrong params");
        }
        if (title == null) {
            res = repository.findByFirstNameAndLastName(firstName, lastName);
        } else {
            res = repository.findByFirstNameAndLastNameAndTitle(firstName, lastName, title);
        }
        res = res.stream().map(p -> findById(p.getId())).collect(Collectors.toList());
        return res;
    }

    @DebugLog(lineHeatMap = true, subHeatMap=true, logDuration = true, logHttpRequest = true)
    public Person findById(Long id) {
        Person res = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Person not found"));
        return res;
    }

    private List<Person> toList(Iterable<Person> iterable) {
        List<Person> results = new ArrayList<>();
        StreamSupport.stream(iterable.spliterator(), false).forEach(results::add);
        return results;
    }
}
