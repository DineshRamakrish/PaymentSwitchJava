# Real-Time Payment Switch (Simulation)

A dependency-free Java 21 HTTP service demonstrating payment routing concepts:
an idempotent payment endpoint, fictional account balances, and a server-sent
event stream for payment status updates.

## Run

```text
javac --add-modules jdk.httpserver -d out Main.java
java --add-modules jdk.httpserver -cp out Main
```

The service listens on port `8081` by default. Set `PORT` to choose another.

## Try it

```text
curl http://localhost:8081/health
curl http://localhost:8081/accounts
curl -X POST http://localhost:8081/payments -H "Content-Type: application/x-www-form-urlencoded" -d "from=alice&to=bob&amount=12.50&key=payment-001"
curl http://localhost:8081/payments
curl -N http://localhost:8081/events
```

Repeated `key` values return the original payment rather than charging twice.
The in-memory demo starts with fictional balances and settles simulated
payments only. It does not connect to a bank, card network, payment provider,
or live money. No payment credentials are needed.
