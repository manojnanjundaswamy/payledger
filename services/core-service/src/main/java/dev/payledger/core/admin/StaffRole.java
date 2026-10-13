package dev.payledger.core.admin;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a PayLedger staff member may do in the admin console (docs/adr/0008-admin-console.md).
 * Least privilege: each role gets only what its job needs, and no role can see a customer's money
 * (amounts, descriptions, notes); the console shows people, plans and counts.
 */
public enum StaffRole {

    /** Runs the business: everything, including who else is staff. */
    OWNER(EnumSet.allOf(Permission.class)),
    /** Runs the product day to day: users, plans and settings, but not staff. */
    ADMIN(EnumSet.complementOf(EnumSet.of(Permission.STAFF_MANAGE))),
    /** Helps customers: finds them, resends emails, unblocks sign-in. No plan or settings changes. */
    SUPPORT(EnumSet.of(Permission.ANALYTICS_VIEW, Permission.USERS_VIEW, Permission.USERS_SUPPORT)),
    /** Reads the numbers: aggregates only, never a person's details. */
    ANALYST(EnumSet.of(Permission.ANALYTICS_VIEW));

    public enum Permission {
        /** The dashboard: counts, active users, plans, retention. Aggregates only. */
        ANALYTICS_VIEW,
        /** Find users and see their profile, plan and usage counts. */
        USERS_VIEW,
        /** Resend the verification or password email, sign a user out everywhere. */
        USERS_SUPPORT,
        /** Create users, disable and enable them. */
        USERS_MANAGE,
        /** Give, extend or end Premium. */
        PLANS_MANAGE,
        /** Free's limits, the trial length, the announcement banner. */
        SETTINGS_MANAGE,
        /** Read the log of every admin action. */
        AUDIT_VIEW,
        /** Make someone staff, change or remove their role. */
        STAFF_MANAGE
    }

    private final Set<Permission> permissions;

    StaffRole(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
