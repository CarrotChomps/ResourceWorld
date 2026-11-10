
package me.nik.resourceworld.utils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.nik.resourceworld.ResourceWorld;
import me.nik.resourceworld.files.Config;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static me.nik.resourceworld.utils.HttpUtil.*;

/**
 * A service in charge of coordinating the sending or receive of packaged resource worlds.
 * <p>
 * <a href="https://teachyourselfcoding.com/java-http-client-server/">Guide on HTTP in Java</a>
 * </p>
 */
public final class WorldDeliveryService {

    private static final int THREAD_COUNT = 2;
    private static final int CONNECTION_QUEUE_SIZE = 8;

    private static final String BIND_ADDRESS = "0.0.0.0";
    private static final String ENDPOINT = "/api/world-delivery";

    // Atomic boolean to ensure only one world is being received at a time.
    private final AtomicBoolean receiving = new AtomicBoolean(false);

    private static final String LOG_PREFIX = "[WorldDeliveryService] ";

    private HttpServer server;
    private ExecutorService executor;

    public WorldDeliveryService() {
    }

    public void initialize() throws IOException {

        // Only a receiver plugin needs to have a server started.
        if (!Config.Setting.WORLD_DELIVERY_HTTP_RECEIVER_ENABLED.getBoolean()) return;

        int port = Config.Setting.WORLD_DELIVERY_HTTP_PORT.getInt();
        String token = Config.Setting.WORLD_DELIVERY_HTTP_TOKEN.getString();

        // Define the max download size; security and performance reasons.
        long maxBytes = HttpUtil.getMaxBytes(server, port, token);

        Path directory = ResourceWorld.getInstance().getDataFolder().toPath().resolve("packaged").toAbsolutePath().normalize();

        Files.createDirectories(directory);

        // Multithreading to prevent server lag when receiving.
        executor = Executors.newFixedThreadPool(THREAD_COUNT);

        // Create server listening for connections on the configured port.
        server = HttpServer.create(new InetSocketAddress(BIND_ADDRESS, port), CONNECTION_QUEUE_SIZE);
        server.createContext(ENDPOINT, exchange -> receive(exchange, directory, token, maxBytes));
        server.setExecutor(executor);
        server.start();

        ChatUtils.log(LOG_PREFIX + "HTTP receiver listening on port " + port);
    }

    public void stop() {

        if (server != null) {
            server.stop(2);
            server = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        ChatUtils.log(LOG_PREFIX + "HTTP receiver stopped.");
    }

    private void receive(final HttpExchange exchange, final Path directory, final String token, final long maxBytes) {

        Path temporary = null;
        boolean locked = false;

        try {
            // Authenticate the sender before processing the request.
            if (!HttpUtil.isAuthorized(exchange, token)) {
                HttpUtil.respond(exchange, HttpUtil.UNAUTHORIZED, "Unauthorized");
                return;
            }

            // Check if the request matches the content
            if (!HttpUtil.isValidRequest(exchange)) {
                HttpUtil.respond(exchange, HttpUtil.BAD_REQUEST, "Invalid delivery request");
                return;
            }

            // Only allow one world upload at a time.
            if (!receiving.compareAndSet(false, true)) {
                HttpUtil.respond(exchange, HttpUtil.CONFLICT, "Delivery already in progress");
                return;
            }

            locked = true;

            String filename = exchange.getRequestHeaders().getFirst(HttpUtil.HEADER_ARCHIVE_NAME);
            String checksum = exchange.getRequestHeaders().getFirst(HttpUtil.HEADER_ARCHIVE_CHECKSUM);

            temporary = Files.createTempFile(directory, HttpUtil.TEMP_PREFIX, HttpUtil.TEMP_EXTENSION);

            // Download and calculate the archive checksum.
            MessageDigest digest = MessageDigest.getInstance(HttpUtil.CHECKSUM_ALGORITHM);
            long received = receiveArchive(exchange, temporary, digest, maxBytes);

            if (received <= 0 || !HttpUtil.toHex(digest.digest()).equalsIgnoreCase(checksum)) {
                HttpUtil.respond(exchange, HttpUtil.BAD_REQUEST, "Invalid archive");
                return;
            }

            // Replace the existing archive only after successful verification.
            Path destination = directory.resolve(filename);

            saveArchive(temporary, destination);

            temporary = null;

            ChatUtils.log(LOG_PREFIX + "Received archive: " + destination + " (" + received + " bytes)");
            HttpUtil.respond(exchange, HttpUtil.OK, "World archive received successfully");

        } catch (Exception e) {
            ChatUtils.log(LOG_PREFIX + "Delivery failed: " + e.getMessage());

            try {
                HttpUtil.respond(exchange, HttpUtil.INTERNAL_ERROR, "World delivery failed");
            } catch (IOException ignored) {
            }

        } finally {

            // Clean up failed or interrupted uploads.
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    ChatUtils.log(LOG_PREFIX + "Failed to clean up upload: " + e.getMessage());
                }
            }

            if (locked) receiving.set(false);

            exchange.close();
        }
    }

    private long receiveArchive(final HttpExchange exchange, final Path temporary, final MessageDigest digest, final long maxBytes) throws IOException {

        long received = 0;

        try (InputStream input = exchange.getRequestBody();
             OutputStream output = Files.newOutputStream(temporary)) {

            byte[] buffer = new byte[HttpUtil.BUFFER_SIZE];
            int count;

            while ((count = input.read(buffer)) != -1) {

                // Reject uploads exceeding the configured file size limit.
                if (count > maxBytes - received) return -1;

                received += count;
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }

        return received;
    }

    private void saveArchive(final Path temporary, final Path destination) throws IOException {

        // Atomic replacement prevents exposing a partially delivered archive.
        Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    public static CompletableFuture<Void> deliverPackagedWorld(final Path source) {

        if (!Config.Setting.WORLD_DELIVERY_ENABLED.getBoolean()) return CompletableFuture.completedFuture(null);

        String method = Config.Setting.WORLD_DELIVERY_METHOD.getString().trim().toUpperCase(Locale.ROOT);

        List<String> destinations = new ArrayList<>(
                method.equals("HTTP")
                        ? Config.Setting.WORLD_DELIVERY_HTTP_DESTINATIONS.getStringList()
                        : Config.Setting.WORLD_DELIVERY_LOCAL_PATHS.getStringList()
        );

        String token = Config.Setting.WORLD_DELIVERY_HTTP_TOKEN.getString();
        int timeout = Config.Setting.WORLD_DELIVERY_HTTP_TIMEOUT.getInt();

        return CompletableFuture.runAsync(() -> {

            try {
                deliver(source, method, destinations, token, timeout);
            } catch (Exception e) {
                ChatUtils.log(LOG_PREFIX + "Delivery failed: " + e.getMessage());
                throw new CompletionException(e);
            }

        }, task -> Bukkit.getScheduler().runTaskAsynchronously(ResourceWorld.getInstance(), task));
    }

    private static void deliver(final Path source, final String method, final List<String> destinations, final String token, final int timeout) throws IOException {

        if (source == null || !Files.isRegularFile(source)) throw new IOException("Invalid source archive: " + source);
        if (!method.equals("LOCAL") && !method.equals("HTTP")) throw new IOException("Invalid delivery method: " + method);

        if (destinations.isEmpty()) {
            ChatUtils.log(LOG_PREFIX + "No destinations configured.");
            return;
        }

        if (method.equals("HTTP") && (token == null || token.isEmpty())) throw new IOException("HTTP authentication token is missing.");

        if (method.equals("HTTP") && (timeout <= 0 || timeout > Integer.MAX_VALUE / 1000)) {
            throw new IOException("Invalid HTTP timeout: " + timeout);
        }

        String checksum = method.equals("HTTP") ? HttpUtil.sha256(source) : null;

        int successes = 0;

        ChatUtils.log(LOG_PREFIX + "Delivering " + source + " using " + method + " to " + destinations.size() + " destination(s).");

        for (String destination : destinations) {

            try {
                if (destination == null || destination.trim().isEmpty()) throw new IOException("Destination is empty.");

                if (method.equals("HTTP")) {
                    deliverHttp(source, URI.create(destination), token, timeout, checksum);
                } else {
                    deliverLocal(source, destination);
                }

                successes++;
                ChatUtils.log(LOG_PREFIX + "Delivered to: " + destination);

            } catch (Exception e) {
                ChatUtils.log(LOG_PREFIX + "Failed to deliver to " + destination + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        ChatUtils.log(LOG_PREFIX + "Delivery complete. Successful=" + successes + ", Failed=" + (destinations.size() - successes));

        if (successes != destinations.size()) throw new IOException("One or more world deliveries failed.");
    }

    /** Delivery Methods **/

    private static void deliverLocal(final Path source, final String address) throws IOException {

        Path target = Paths.get(address).toAbsolutePath().normalize();

        if (source.equals(target) || (Files.exists(target) && Files.isSameFile(source, target))) {
            throw new IOException("Source and destination are the same file.");
        }

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deliverHttp(final Path source, final URI destination, final String token, final int timeout, final String checksum) throws IOException {

        String scheme = destination.getScheme();

        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Invalid HTTP destination: " + destination);
        }

        HttpURLConnection connection = (HttpURLConnection) destination.toURL().openConnection();

        try {
            HttpUtil.configureConnection(connection, source, token, timeout, checksum);

            // Stream the packaged world directly to the destination server.
            try (OutputStream output = connection.getOutputStream()) {
                Files.copy(source, output);
            }

            int status = connection.getResponseCode();

            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP delivery failed: " + status + " from " + destination);
            }

        } finally {
            connection.disconnect();
        }
    }
}
