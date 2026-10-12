package dev.payledger.ledger.account;

/**
 * Classic double-entry account types. Amounts are signed: positive = debit, negative = credit,
 * so assets and expenses normally have positive balances, the others negative ones.
 */
public enum AccountType {
    /** What you have: bank, cash, money others owe you. */
    ASSET,
    /** What you owe: credit card, loans, money you owe others. */
    LIABILITY,
    /** Opening balances and other adjustments. */
    EQUITY,
    /** Salary, interest, refunds. */
    INCOME,
    /** Spending, one account per category. */
    EXPENSE,
    /** @deprecated pre-pivot wallet account, only in the LEGACY book */
    @Deprecated USER,
    /** @deprecated pre-pivot funding account, only in the LEGACY book */
    @Deprecated SYSTEM
}
