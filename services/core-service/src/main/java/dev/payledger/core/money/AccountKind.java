package dev.payledger.core.money;

/**
 * Where money lives. A CARD is a liability: its balance is what you owe, so in the ledger it is
 * negative when you owe money (amounts are signed, positive = debit).
 */
public enum AccountKind {
    BANK, CASH, CARD, WALLET;

    public String ledgerType() {
        return this == CARD ? "LIABILITY" : "ASSET";
    }

    /** Converts an amount in the user's terms (money in it, or owed on a card) to a ledger amount. */
    public long toLedger(long userAmount) {
        return this == CARD ? Math.negateExact(userAmount) : userAmount;
    }
}
