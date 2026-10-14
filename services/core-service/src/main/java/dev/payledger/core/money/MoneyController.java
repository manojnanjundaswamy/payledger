package dev.payledger.core.money;

import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Money accounts, categories and transactions of one space ({@code /api/core/me} lists your spaces).
 * Constraints on parameters are checked by Spring MVC's built-in method validation (400 problem responses);
 * no {@code @Validated} here, which would validate through a proxy and turn bad input into a 500.
 */
@RestController
@RequestMapping("/api/core/spaces/{spaceId}")
class MoneyController {

    private final AccountService accounts;
    private final CategoryService categories;
    private final TransactionService transactions;
    private final BalanceService balances;

    MoneyController(AccountService accounts, CategoryService categories, TransactionService transactions,
                    BalanceService balances) {
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.balances = balances;
    }

    // ---------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    @Operation(summary = "Bank, cash, card and wallet accounts with balances from the ledger (null if it is unreachable)")
    List<AccountView> accounts(CurrentUser user, @PathVariable UUID spaceId,
                               @RequestParam(defaultValue = "false") boolean includeArchived) {
        return balances.accounts(user, spaceId, includeArchived);
    }

    @GetMapping("/accounts/{accountId}")
    AccountView account(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID accountId) {
        return balances.account(user, spaceId, accountId);
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an account; a non-zero opening balance is posted to the ledger")
    MoneyAccount createAccount(CurrentUser user, @PathVariable UUID spaceId, @Valid @RequestBody AccountRequests.Create request) {
        return accounts.create(user, spaceId, request);
    }

    @PatchMapping("/accounts/{accountId}")
    @Operation(summary = "Rename, change the opening balance, or archive / unarchive")
    MoneyAccount updateAccount(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID accountId,
                               @Valid @RequestBody AccountRequests.Update request) {
        return accounts.update(user, spaceId, accountId, request);
    }

    @GetMapping("/net-worth")
    @Operation(summary = "Net worth at the end of each of the last months (this month: today), in the space's currency; 503 if the ledger is unreachable")
    List<BalanceService.NetWorthPoint> netWorth(CurrentUser user, @PathVariable UUID spaceId,
                                                @RequestParam(defaultValue = "12") @Min(2) @Max(60) int months) {
        return balances.netWorth(user, spaceId, months, accounts.today(user));
    }

    // ---------------------------------------------------------------- categories

    @GetMapping("/categories")
    @Operation(summary = "Expense and income categories, hidden ones included (flagged)")
    List<Category> categories(CurrentUser user, @PathVariable UUID spaceId) {
        return categories.list(user, spaceId);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a category or subcategory; 409 category-name-in-use if the name is taken (ignoring case)")
    Category createCategory(CurrentUser user, @PathVariable UUID spaceId, @Valid @RequestBody CategoryRequests.Create request) {
        return categories.create(user, spaceId, request);
    }

    @PutMapping("/categories/{categoryId}")
    @Operation(summary = "Rename, change icon or colour, move under a parent, hide or show (subcategories follow)")
    Category replaceCategory(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID categoryId,
                             @Valid @RequestBody CategoryRequests.Replace request) {
        return categories.replace(user, spaceId, categoryId, request);
    }

    @DeleteMapping("/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a category nothing ever used; 409 category-in-use (hide it instead) or category-has-subcategories")
    void deleteCategory(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID categoryId) {
        categories.delete(user, spaceId, categoryId);
    }

    @GetMapping("/category-totals")
    @Operation(summary = "Spending (EXPENSE) or income (INCOME) per category between two dates, optionally per month; 503 if the ledger is unreachable")
    List<BalanceService.CategoryTotal> categoryTotals(CurrentUser user, @PathVariable UUID spaceId,
                                                      @RequestParam(defaultValue = "EXPENSE") @Pattern(regexp = "EXPENSE|INCOME") String kind,
                                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @RequestParam(defaultValue = "false") boolean byMonth) {
        return balances.categoryTotals(user, spaceId, kind, from, to, byMonth);
    }

    // ---------------------------------------------------------------- transactions

    @GetMapping("/transactions")
    @Operation(summary = "Newest first, filtered by dates, account, category, kind, amount, tag or words (q); pass nextCursor to get more")
    TransactionService.Page transactions(CurrentUser user, @PathVariable UUID spaceId, TransactionFilter filter,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(defaultValue = "50") @Min(1) @Max(TransactionService.MAX_PAGE) int limit) {
        return transactions.list(user, spaceId, filter, cursor, limit);
    }

    @GetMapping("/transactions/summary")
    @Operation(summary = "How many transactions a filter matches, and their money out, in and moved, per currency")
    TransactionService.Summary transactionSummary(CurrentUser user, @PathVariable UUID spaceId, TransactionFilter filter) {
        return transactions.summary(user, spaceId, filter);
    }

    @GetMapping("/transactions/{id}")
    Transaction transaction(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id) {
        return transactions.get(user, spaceId, id);
    }

    @PostMapping("/transactions")
    @Operation(summary = "Add an expense, income or transfer. Retrying with the same client id returns the first (200)")
    ResponseEntity<Transaction> createTransaction(CurrentUser user, @PathVariable UUID spaceId,
                                                  @Valid @RequestBody TransactionRequests.Create request) {
        TransactionService.Outcome outcome = transactions.create(user, spaceId, request);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.transaction());
    }

    @PutMapping("/transactions/{id}")
    @Operation(summary = "Replace a transaction (send the version you edited; 409 if it changed meanwhile)")
    Transaction replaceTransaction(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id,
                                   @Valid @RequestBody TransactionRequests.Replace request) {
        return transactions.replace(user, spaceId, id, request);
    }

    @DeleteMapping("/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a transaction (its ledger entry is reversed, not erased)")
    void deleteTransaction(CurrentUser user, @PathVariable UUID spaceId, @PathVariable UUID id, @RequestParam int version) {
        transactions.delete(user, spaceId, id, version);
    }
}
