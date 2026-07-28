package com.xianyusmart.service.notification;

import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 固定 DNS 解析结果的 HTTPS 客户端
 */
@Component
public class PinnedHttpsClient {

    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final ScheduledThreadPoolExecutor TIMEOUT_GUARD = createTimeoutGuard();

    public Response post(String url, Map<String, String> headers, String body, Duration timeout) {
        URI uri = URI.create(url);
        WebhookSecurity.SafeTarget target = WebhookSecurity.resolveSafeTarget(uri);
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        long requestedTimeoutMillis = timeout.toMillis();
        if (requestedTimeoutMillis <= 0) {
            throw new IllegalArgumentException("HTTPS 请求超时时间必须大于 0");
        }
        int timeoutMillis = Math.toIntExact(Math.min(Integer.MAX_VALUE, requestedTimeoutMillis));
        try (Socket tcpSocket = new Socket()) {
            // 绝对截止时间覆盖连接、TLS 握手和读取全过程，避免慢速响应长期占用业务线程。
            ScheduledFuture<?> timeoutTask = TIMEOUT_GUARD.schedule(
                    () -> closeQuietly(tcpSocket), timeoutMillis, TimeUnit.MILLISECONDS);
            try {
                // 连接已校验的固定 IP，TLS 仍使用原始域名完成 SNI 与证书校验。
                tcpSocket.connect(new InetSocketAddress(target.address(), port), timeoutMillis);
                tcpSocket.setSoTimeout(timeoutMillis);
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket socket = (SSLSocket) factory.createSocket(tcpSocket, uri.getHost(), port, true)) {
                    SSLParameters sslParameters = socket.getSSLParameters();
                    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!isIpLiteral(uri.getHost())) {
                        sslParameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
                    }
                    socket.setSSLParameters(sslParameters);
                    socket.startHandshake();
                    writeRequest(socket.getOutputStream(), uri, port, headers, body);
                    return readResponse(new BufferedInputStream(socket.getInputStream()));
                }
            } finally {
                timeoutTask.cancel(false);
            }
        } catch (Exception e) {
            throw new IllegalStateException("HTTPS 请求失败: " + e.getMessage(), e);
        }
    }

    private static ScheduledThreadPoolExecutor createTimeoutGuard() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "pinned-https-timeout");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 超时关闭失败时由原请求继续抛出网络异常。
        }
    }

    private void writeRequest(OutputStream output, URI uri, int port,
                              Map<String, String> headers, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        String host = uri.getHost() + (port == 443 ? "" : ":" + port);
        StringBuilder request = new StringBuilder()
                .append("POST ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Connection: close\r\n")
                .append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            validateHeader(header.getKey(), header.getValue());
            String lowerName = header.getKey().toLowerCase(Locale.ROOT);
            if (!List.of("host", "connection", "content-length").contains(lowerName)) {
                request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(bodyBytes);
        output.flush();
    }

    private Response readResponse(BufferedInputStream input) throws IOException {
        for (int interimCount = 0; interimCount < 5; interimCount++) {
            String statusLine = readLine(input);
            String[] statusParts = statusLine.split(" ", 3);
            if (statusParts.length < 2) {
                throw new IOException("HTTPS 响应状态行无效");
            }
            int statusCode = Integer.parseInt(statusParts[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            int headerBytes = statusLine.length();
            while (true) {
                String line = readLine(input);
                headerBytes += line.length();
                if (headerBytes > MAX_HEADER_BYTES) {
                    throw new IOException("HTTPS 响应头过大");
                }
                if (line.isEmpty()) {
                    break;
                }
                int separator = line.indexOf(':');
                if (separator > 0) {
                    headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                            line.substring(separator + 1).trim());
                }
            }
            if (statusCode >= 100 && statusCode < 200) {
                continue;
            }
            // 204 和 304 按协议不包含响应体，直接返回可兼容保持连接的 Webhook 服务。
            byte[] body = statusCode == 204 || statusCode == 304
                    ? new byte[0]
                    : "chunked".equalsIgnoreCase(headers.get("transfer-encoding"))
                    ? readChunkedBody(input)
                    : readBody(input, headers.get("content-length"));
            return new Response(statusCode, new String(body, StandardCharsets.UTF_8));
        }
        throw new IOException("HTTPS 临时响应次数过多");
    }

    private byte[] readBody(BufferedInputStream input, String contentLength) throws IOException {
        int expected = contentLength == null ? -1 : Integer.parseInt(contentLength);
        if (expected > MAX_BODY_BYTES) {
            throw new IOException("HTTPS 响应体过大");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(expected > 0 ? expected : 1024);
        byte[] buffer = new byte[8192];
        int remaining = expected;
        while (remaining != 0) {
            int read = input.read(buffer, 0, remaining > 0 ? Math.min(buffer.length, remaining) : buffer.length);
            if (read < 0) {
                break;
            }
            if (output.size() + read > MAX_BODY_BYTES) {
                throw new IOException("HTTPS 响应体过大");
            }
            output.write(buffer, 0, read);
            if (remaining > 0) {
                remaining -= read;
            }
        }
        return output.toByteArray();
    }

    private byte[] readChunkedBody(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(input);
            int separator = sizeLine.indexOf(';');
            int size = Integer.parseInt((separator >= 0 ? sizeLine.substring(0, separator) : sizeLine).trim(), 16);
            if (size == 0) {
                while (!readLine(input).isEmpty()) {
                    // 跳过 trailer 头。
                }
                return output.toByteArray();
            }
            if (output.size() + size > MAX_BODY_BYTES) {
                throw new IOException("HTTPS 响应体过大");
            }
            byte[] chunk = input.readNBytes(size);
            if (chunk.length != size) {
                throw new EOFException("HTTPS 分块响应提前结束");
            }
            output.write(chunk);
            if (!readLine(input).isEmpty()) {
                throw new IOException("HTTPS 分块响应格式无效");
            }
        }
    }

    private String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() <= MAX_HEADER_BYTES) {
            int current = input.read();
            if (current < 0) {
                throw new EOFException("HTTPS 响应提前结束");
            }
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            previous = current;
        }
        throw new IOException("HTTPS 响应行过长");
    }

    private void validateHeader(String name, String value) {
        if (name == null || name.isBlank() || value == null
                || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("HTTPS 请求头无效");
        }
    }

    private boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    public record Response(int statusCode, String body) {
    }
}
