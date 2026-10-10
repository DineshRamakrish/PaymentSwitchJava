import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executors;

public final class Main {
    private final Object lock = new Object();
    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final List<BlockingQueue<Payment>> listeners = new ArrayList<>();

    private Main() {
        balances.put("alice", 100_000L);
        balances.put("bob", 50_000L);
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8081"));
        Main service = new Main();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", service::handle);
        server.start();
        System.out.printf("Sandbox payment switch listening on http://localhost:%d%n", port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if (path.equals("/health") && method.equals("GET")) {
                send(exchange, 200, "{\"status\":\"ok\",\"mode\":\"simulation\"}");
            } else if (path.equals("/accounts") && method.equals("GET")) {
                send(exchange, 200, accountsJson());
            } else if (path.equals("/payments") && method.equals("GET")) {
                send(exchange, 200, paymentsJson());
            } else if (path.equals("/payments") && method.equals("POST")) {
                createPayment(exchange);
            } else if (path.equals("/events") && method.equals("GET")) {
                streamEvents(exchange);
            } else {
                send(exchange, 404, "{\"error\":\"Route not found\"}");
            }
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "{\"error\":\"" + escape(error.getMessage()) + "\"}");
        } catch (Exception error) {
            send(exchange, 500, "{\"error\":\"Payment service error\"}");
        }
    }

    private void createPayment(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String from = required(form, "from");
        String to = required(form, "to");
        String key = required(form, "key");
        long cents;
        try {
            java.math.BigDecimal amount = new java.math.BigDecimal(required(form, "amount"));
            cents = amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Amount must be positive with at most two decimals.");
        }
        if (cents <= 0 || from.equals(to)) {
            throw new IllegalArgumentException("Amount must be positive and accounts must differ.");
        }

        Payment payment;
        synchronized (lock) {
            Payment previous = payments.get(key);
            if (previous != null) {
                send(exchange, 200, paymentJson(previous));
                return;
            }
            if (!balances.containsKey(from) || !balances.containsKey(to)) {
                send(exchange, 404, "{\"error\":\"Unknown demo account; try alice or bob.\"}");
                return;
            }
            if (balances.get(from) < cents) {
                send(exchange, 422, "{\"error\":\"Insufficient simulated funds.\"}");
                return;
            }
            balances.put(from, balances.get(from) - cents);
            balances.put(to, balances.get(to) + cents);
            payment = new Payment(
                java.util.UUID.randomUUID().toString(), key, from, to, cents, "settled",
                java.time.Instant.now().toString());
            payments.put(key, payment);
        }
        synchronized (listeners) {
            for (BlockingQueue<Payment> listener : listeners) {
                listener.offer(payment);
            }
        }
        send(exchange, 201, paymentJson(payment));
    }

    private void streamEvents(HttpExchange exchange) throws IOException, InterruptedException {
        BlockingQueue<Payment> queue = new LinkedBlockingQueue<>(100);
        synchronized (listeners) {
            listeners.add(queue);
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
            output.write("retry: 3000\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            while (!Thread.currentThread().isInterrupted()) {
                Payment payment = queue.take();
                output.write(("event: payment\ndata: " + paymentJson(payment) + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } finally {
            synchronized (listeners) {
                listeners.remove(queue);
            }
            exchange.close();
        }
    }

    private String accountsJson() {
        synchronized (lock) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (var entry : balances.entrySet()) {
                if (!first) json.append(',');
                first = false;
                json.append("{\"id\":\"").append(escape(entry.getKey()))
                    .append("\",\"currency\":\"USD\",\"balance\":")
                    .append(entry.getValue() / 100.0).append('}');
            }
            return json.append(']').toString();
        }
    }

    private String paymentsJson() {
        synchronized (lock) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Payment payment : payments.values()) {
                if (!first) json.append(',');
                first = false;
                json.append(paymentJson(payment));
            }
            return json.append(']').toString();
        }
    }

    private static String paymentJson(Payment payment) {
        return "{\"id\":\"" + escape(payment.id()) + "\",\"idempotencyKey\":\""
            + escape(payment.key()) + "\",\"from\":\"" + escape(payment.from())
            + "\",\"to\":\"" + escape(payment.to()) + "\",\"amount\":"
            + String.format(java.util.Locale.ROOT, "%.2f", payment.cents() / 100.0)
            + ",\"status\":\"" + payment.status() + "\",\"createdAt\":\""
            + escape(payment.createdAt()) + "\"}";
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> values = new java.util.HashMap<>();
        if (!body.isBlank()) {
            for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
        }
        return values;
    }

    private static String required(Map<String, String> form, String name) {
        String value = form.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing form field: " + name);
        return value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record Payment(String id, String key, String from, String to, long cents, String status, String createdAt) {}
}
