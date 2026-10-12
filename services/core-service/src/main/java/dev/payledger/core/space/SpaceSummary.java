package dev.payledger.core.space;

import java.util.UUID;

public record SpaceSummary(UUID id, String kind, String name, String currency, String role) {
}
