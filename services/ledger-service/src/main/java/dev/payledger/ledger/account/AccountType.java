package dev.payledger.ledger.account;

public enum AccountType {
    /** A customer wallet. Can never go negative. */
    USER,
    /** Internal platform account, e.g. external funding. May go negative. */
    SYSTEM
}
