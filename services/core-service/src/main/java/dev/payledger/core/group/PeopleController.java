package dev.payledger.core.group;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Everyone you share money with, and money lent or borrowed one to one. */
@RestController
@RequestMapping("/api/core/people")
class PeopleController {

    private final PeopleService people;

    PeopleController(PeopleService people) {
        this.people = people;
    }

    @GetMapping
    @Operation(summary = "People you share groups with: what you and each owe, across groups, and loan due dates")
    PeopleService.People list(CurrentUser user) {
        return people.list(user);
    }

    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record money lent to or borrowed from someone, in your one-to-one ledger with them")
    GroupExpenseService.Expense lend(CurrentUser user, @RequestBody PeopleService.Loan loan) {
        return people.lend(user, loan);
    }
}
