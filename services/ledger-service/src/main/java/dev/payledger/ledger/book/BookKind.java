package dev.payledger.ledger.book;

public enum BookKind {
    /** One person's own money. */
    PERSONAL,
    /** Money a household manages together. */
    HOUSEHOLD,
    /** A splitting group: tracks what members owe each other. */
    GROUP,
    /** Pre-pivot wallet data. Read-only; nothing posts to it. */
    LEGACY
}
