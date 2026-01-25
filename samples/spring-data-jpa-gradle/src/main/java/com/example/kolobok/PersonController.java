package com.example.kolobok;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.kolobok.annotation.DebugLog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/persons")
@Tag(name = "Persons", description = "CRUD operations for persons")
@Slf4j
public class PersonController {
    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @Operation(summary = "Create a person")
    @PostMapping
    public ResponseEntity<Person> create(@RequestBody Person person) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(person));
    }

    @Operation(summary = "Get a person by id")
    @GetMapping("/{id}")
    public Person get(@Parameter(description = "Person id") @PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "List all persons")
    @GetMapping
    public List<Person> list() {
        return service.list();
    }

    @Operation(summary = "Update a person")
    @PutMapping("/{id}")
    public Person update(
            @Parameter(description = "Person id") @PathVariable Long id,
            @RequestBody Person person
    ) {
        return service.update(id, person);
    }

    @Operation(summary = "Delete a person")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "Person id") @PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Search persons by optional firstName/lastName/title")
    @GetMapping("/search")
    @DebugLog(lineHeatMap = true, logHttpRequest = true, tag = "persons")
    public List<Person> search(
            @Parameter(description = "First name") @RequestParam(required = false) String firstName,
            @Parameter(description = "Last name") @RequestParam(required = false) String lastName,
            @Parameter(description = "Title") @RequestParam(required = false) String title
    ) {
        if ("error".equals(firstName)) {
            throw new RuntimeException("Simulated error");
        }
        return service.search(firstName, lastName, title);
    }
}
