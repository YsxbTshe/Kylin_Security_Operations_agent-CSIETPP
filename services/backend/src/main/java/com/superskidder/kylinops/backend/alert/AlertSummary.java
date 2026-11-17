package com.superskidder.kylinops.backend.alert;

public record AlertSummary(String id, String level, String type, String host, String status, String suggestion) {
}
