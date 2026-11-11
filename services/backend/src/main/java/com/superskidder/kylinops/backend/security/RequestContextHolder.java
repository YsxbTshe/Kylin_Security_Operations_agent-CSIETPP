package com.superskidder.kylinops.backend.security;

import com.superskidder.kylinops.backend.common.AppException;
import java.util.Optional;
import org.springframework.http.HttpStatus;

public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    public static void set(RequestContext context) {
        HOLDER.set(context);
    }

    public static RequestContext get() {
        return HOLDER.get();
    }

    public static RequestContext required() {
        RequestContext context = HOLDER.get();
        if (context == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "未认证或会话已失效");
        }
        return context;
    }

    public static Optional<RequestContext> optional() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static void clear() {
        HOLDER.remove();
    }
}
