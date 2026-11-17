package com.superskidder.kylinops.backend.security;

import com.superskidder.kylinops.backend.auth.AuthenticatedUser;
import com.superskidder.kylinops.backend.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AccessControlService {

    public AuthenticatedUser currentUser() {
        return RequestContextHolder.required().user();
    }

    public String currentToken() {
        return RequestContextHolder.required().token();
    }

    public String currentOrganizationId() {
        return currentUser().currentOrganizationId();
    }

    public void requirePermission(String permission) {
        if (!currentUser().permissions().contains(permission)) {
            throw new AppException(HttpStatus.FORBIDDEN, "当前用户缺少权限: " + permission);
        }
    }
}
