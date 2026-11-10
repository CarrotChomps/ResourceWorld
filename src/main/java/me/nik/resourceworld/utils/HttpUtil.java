package me.nik.resourceworld.utils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.nik.resourceworld.files.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Helper class for assisting in http connection and requests
 */
public class HttpUtil {

    public static final int BUFFER_SIZE = 8192;

    // HTTP request properties https://www.baeldung.com/java-http-request
    public static final String HTTP_METHOD = "POST";
    public static final String HTTP_CONTENT_TYPE = "application/gzip"; // tells what type of file we are sending; google "MIME"
    // HTTP request headers https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_ARCHIVE_NAME = "X-Archive-Name";
    public static final String HEADER_ARCHIVE_CHECKSUM = "X-Archive-SHA256";
    // conventional auth scheme https://swagger.io/docs/specification/v3_0/authentication/bearer-authentication/
    public static final String AUTH_SCHEME = "Bearer ";

    // Archive properties
    public static final String ARCHIVE_EXTENSION = ".tar.gz";
    public static final String TEMP_PREFIX = "ResourceWorldDelivery-";
    public static final String TEMP_EXTENSION = ".upload";
    public static final String CHECKSUM_ALGORITHM = "SHA-256";
    public static final String CHECKSUM_PATTERN = "[a-fA-F0-9]{64}";
    public static final String FILENAME_PATTERN = "[a-zA-Z0-9._-]+\\.tar\\.gz";

    // status codes https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status
    public static final int OK = 200;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int NOT_FOUND = 404;
    public static final int CONFLICT = 409;
    public static final int INTERNAL_ERROR = 500;

    private static final String LOG_PREFIX = "[HttpUtil] ";

    public static void respond(final HttpExchange exchange, final int status, final String message) throws IOException {

        byte[] body = message.getBytes(StandardCharsets.UTF_8);

        // set the content type to plain text because its not the end of the world if someone reads the world info lol
        exchange.getResponseHeaders().set(HEADER_CONTENT_TYPE, "text/plain; charset=utf-8");

        exchange.sendResponseHeaders(status, body.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    public static void configureConnection(final HttpURLConnection connection, final Path source, final String token, final int timeout, final String checksum) throws IOException {
        // no redirection thank you
        connection.setInstanceFollowRedirects(false);

        connection.setRequestMethod(HTTP_METHOD);

        // Authentication and archive metadata sent to the receiver.
        connection.setRequestProperty(HEADER_AUTHORIZATION, AUTH_SCHEME + token);

        connection.setRequestProperty(HEADER_CONTENT_TYPE, HTTP_CONTENT_TYPE);

        connection.setRequestProperty(HEADER_ARCHIVE_NAME, source.getFileName().toString());

        connection.setRequestProperty(HEADER_ARCHIVE_CHECKSUM, checksum);

        connection.setConnectTimeout(timeout * 1000);

        connection.setReadTimeout(timeout * 1000);

        connection.setDoOutput(true);

        connection.setChunkedStreamingMode(HttpUtil.BUFFER_SIZE);
    }

    // dont ddos me challenge go
    public static long getMaxBytes(HttpServer server, int port, String token) throws IOException {

        int maxMb = Config.Setting.WORLD_DELIVERY_HTTP_MAX_SIZE_MB.getInt();

        if (server != null) throw new IOException(LOG_PREFIX + "HTTP receiver is already running.");

        if (port < 1 || port > 65535) throw new IOException(LOG_PREFIX + "Invalid HTTP receiver port.");

        if (token == null || token.length() < 32) throw new IOException(LOG_PREFIX + "HTTP receiver token must contain at least 32 characters.");

        if (maxMb <= 0) throw new IOException(LOG_PREFIX + "HTTP maximum archive size must be positive.");

        return maxMb * 1024L * 1024L;
    }

    public static String toHex(final byte[] bytes) {

        StringBuilder result = new StringBuilder(bytes.length * 2);

        for (byte value : bytes) {

            result.append(Character.forDigit((value >> 4) & 0xF, 16));

            result.append(Character.forDigit(value & 0xF, 16));
        }

        return result.toString();
    }

    // creates a sha hash to ensure the file we sent is the file we received.
    public static String sha256(final Path source) throws IOException {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream input = Files.newInputStream(source)) {

                byte[] buffer = new byte[BUFFER_SIZE];
                int count;

                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }

            StringBuilder result = new StringBuilder(64);

            for (byte value : digest.digest()) {

                result.append(Character.forDigit((value >> 4) & 0xF, 16));

                result.append(Character.forDigit(value & 0xF, 16));
            }

            return result.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IOException(LOG_PREFIX + "SHA-256 unavailable.", e);
        }
    }

    // check if it matches our auth scheme (security)
    public static boolean isAuthorized(final HttpExchange exchange, final String token) {

        String authorization = exchange.getRequestHeaders().getFirst(HEADER_AUTHORIZATION);

        byte[] expected = (AUTH_SCHEME + token).getBytes(StandardCharsets.UTF_8);

        byte[] provided = (authorization == null ? "" : authorization).getBytes(StandardCharsets.UTF_8);

        return MessageDigest.isEqual(expected, provided);
    }

    // check if the exchange we got is the one we want (security)
    public static boolean isValidRequest(final HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst(HEADER_CONTENT_TYPE);

        String checksum = exchange.getRequestHeaders().getFirst(HEADER_ARCHIVE_CHECKSUM);

        String filename = exchange.getRequestHeaders().getFirst(HEADER_ARCHIVE_NAME);

        if (!HTTP_METHOD.equals(exchange.getRequestMethod())) return false;

        if (!HTTP_CONTENT_TYPE.equalsIgnoreCase(contentType)) return false;

        if (checksum == null || !checksum.matches(CHECKSUM_PATTERN)) return false;

        if (filename == null || !filename.matches(FILENAME_PATTERN) || filename.startsWith(".")) return false;

        return true;
    }

}
