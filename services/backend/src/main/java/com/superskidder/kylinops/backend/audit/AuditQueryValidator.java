package com.superskidder.kylinops.backend.audit;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AuditQueryValidator {

    private AuditQueryValidator() {
    }

    public static void validate(
            String operator,
            String action,
            String target,
            String fromTime,
            String toTime,
            int limit
    ) {
        validateLength("operator", operator, 128);
        validateLength("action", action, 128);
        validateLength("target", target, 128);
        validateLength("fromTime", fromTime, 32);
        validateLength("toTime", toTime, 32);
        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须在1到500之间");
        }
    }

    private static void validateLength(String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 长度不能超过" + maxLength);
        }
    }
}
