package com.superskidder.kylinops.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 登录失败锁与审计用的客户端 IP。默认只用 TCP 对端地址；
 * 仅当对端落在受信代理网段时才采信 {@code X-Forwarded-For} 最左一项，避免伪造头绕过爆破锁。
 */
@Component
public class ClientIpResolver {

    private final List<String> trustedProxyCidrs;

    public ClientIpResolver(
            @Value("${app.security.trusted-proxy-cidrs:}") String trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null || trustedProxyCidrs.isBlank()
                ? List.of()
                : List.of(trustedProxyCidrs.split("\\s*,\\s*")).stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remote = sanitize(request.getRemoteAddr());
        if (!trustedProxyCidrs.isEmpty() && isTrustedProxy(remote)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return sanitize(comma > 0 ? forwarded.substring(0, comma) : forwarded);
            }
        }
        return remote;
    }

    private boolean isTrustedProxy(String ip) {
        for (String cidr : trustedProxyCidrs) {
            if (matchesCidr(ip, cidr)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesCidr(String ip, String cidr) {
        try {
            if (!cidr.contains("/")) {
                return ip.equals(cidr);
            }
            String[] parts = cidr.split("/", 2);
            InetAddress cidrAddr = InetAddress.getByName(parts[0]);
            InetAddress ipAddr = InetAddress.getByName(ip);
            byte[] cidrBytes = cidrAddr.getAddress();
            byte[] ipBytes = ipAddr.getAddress();
            if (cidrBytes.length != ipBytes.length) {
                return false;
            }
            int prefix = Integer.parseInt(parts[1]);
            int fullBytes = prefix / 8;
            int remain = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (cidrBytes[i] != ipBytes[i]) {
                    return false;
                }
            }
            if (remain == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remain)) & 0xFF;
            return (cidrBytes[fullBytes] & mask) == (ipBytes[fullBytes] & mask);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String normalized = value.trim().replaceAll("[\r\n\t]", " ");
        return normalized.length() > 256 ? normalized.substring(0, 256) : normalized;
    }
}
