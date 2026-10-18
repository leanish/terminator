# Terminator

Terminator is a small Java library that coordinates the orderly shutdown of heterogeneous services. It understands two kinds of services:

* **Blocking services** (`BlockingTerminable`) that finish all work before returning from `terminate()`.
* **Non-blocking services** (`NonBlockingTerminable`) that initiate shutdown in the background and expose `awaitTermination(Duration)` to finish up.

The coordinator makes sure non-blocking services are signalled first and waited on, while blocking services are stopped afterwards in reverse registration order. It also keeps you honest about interrupts and timeout handling so shutdown code stays responsive.

## Key features

- Register blocking, non-blocking, or `AutoCloseable` services manually or through annotations.
- Initiate shutdown once with `Terminator#terminate()`, or call it repeatedly without side effects.
- Wait for the async tail of shutdown to finish with `awaitTermination(Duration)`; negative durations are treated as zero, and a zero wait is still passed through to the services.
- Aggregates failures thrown during termination, keeping the first as the cause and suppressing the rest.
- Propagates interrupts consistently during both `terminate()` and `awaitTermination()`.

## Quick start

Add the dependency to your Gradle build:

```groovy
dependencies {
    implementation 'io.github.leanish:terminator:0.1.0'
}
```

### Implement services

```java
class HttpServer implements BlockingTerminable {
    private final Server server;

    ...
    
    @Override
    public void terminate()
            throws InterruptedException {
        server.stop(); // blocks until fully stopped
    }
}

class QueueProcessor implements NonBlockingTerminable {
    private final ExecutorService executor;

    ...

    @Override
    public void terminate() {
        executor.shutdown(); // starts shutdown but returns immediately
    }

    @Override
    public boolean awaitTermination(Duration timeout)
            throws InterruptedException {
        return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
```

### Coordinate shutdown

```java
Terminator terminator = new Terminator();
terminator.register(httpServer);
terminator.register(queueProcessor);

...

terminator.terminate();
boolean finished = terminator.awaitTermination(Duration.ofSeconds(5));
if (!finished) {
    // log or trigger fallback action
}
```

### Use annotations and auto-registration

Instead of implementing the marker interfaces directly, mark your shutdown methods and let the registrar inspect them:

```java
class AsyncWorkerPool {
    private final ExecutorService executor;

    @Terminates
    void shutdown() {
        executor.shutdown();
    }

    @AwaitsTermination
    boolean awaitTermination(Duration timeout) throws InterruptedException {
        return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}

Terminator terminator = new Terminator();
TerminatorAutoRegistration registration = new TerminatorAutoRegistration(terminator);

registration.registerAll(List.of(
        httpServer,         // implements BlockingTerminable
        queueProcessor,     // implements NonBlockingTerminable
        new AsyncWorkerPool() // annotated service
));
```

The registrar recognises:

- `BlockingTerminable` and `NonBlockingTerminable` implementations.
- Methods annotated with `@Terminates` and `@AwaitsTermination`.
- Any `AutoCloseable`, where `close()` is treated as a blocking shutdown.

This makes it easy to plug into any dependency injection container: resolve the managed beans and pass them to `registerAll`.

## Building & testing

This project uses Gradle 8 and Java 21:

```bash
./gradlew build   # compile, test, package
./gradlew test    # run the test suite
```

To regenerate the API documentation:

```bash
./gradlew javadoc
```

## Change log

See [CHANGELOG.md](CHANGELOG.md) for notable updates.

## Contributing

Issues and pull requests are welcome. Please run `./gradlew test` locally before submitting changes.

## License

This project is distributed under the terms of the [MIT License](LICENSE).
