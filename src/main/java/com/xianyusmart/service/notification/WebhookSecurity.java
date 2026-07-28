package com.xianyusmart.service.notification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Webhook 地址与签名规则
 */
public final class WebhookSecurity {

    private WebhookSecurity() {
    }

    public static String requireSafeUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("Webhook 地址必须使用完整的 HTTPS 地址");
            }
            if (uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Webhook 地址不能包含用户信息或片段");
            }
            resolveSafeTarget(uri);
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Webhook 地址无法解析");
        }
    }

    public static void validateResolvedTarget(String url) {
        resolveSafeTarget(URI.create(url));
    }

    public static SafeTarget resolveSafeTarget(URI uri) {
        try {
            String host = uri.getHost();
            if (host == null || "localhost".equalsIgnoreCase(host)) {
                throw new IllegalArgumentException("Webhook 地址不能指向本机或内网");
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isPrivateAddress(address)) {
                    throw new IllegalArgumentException("Webhook 地址不能解析到本机或内网");
                }
            }
            if (addresses.length == 0) {
                throw new IllegalArgumentException("Webhook 地址解析失败");
            }
            return new SafeTarget(uri, addresses[0]);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Webhook 地址解析失败");
        }
    }

    public static String sign(String secret, String body) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Webhook 签名生成失败", e);
        }
    }

    static boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return (first & 0xfe) == 0xfc
                    || (first == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8);
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        return first == 0
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 192 && second == 0 && (third == 0 || third == 2))
                || (first == 198 && (second == 18 || second == 19 || second == 51))
                || (first == 203 && second == 0 && third == 113)
                || first >= 240;
    }

    public record SafeTarget(URI uri, InetAddress address) {
    }
}
