package com.superskidder.kylinops.backend.audit;

public record AuditRecord(String id, String operator, String action, String target, String time) {
}
