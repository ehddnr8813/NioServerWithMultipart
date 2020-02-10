package main.httpserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class HttpServer {

    private static final Logger logger = Logger.getLogger(HttpServer.class.getName());
    private final String host;
    private final int port;
    private final HttpHandler handler;
    private final Worker acceptWorker;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final List<Worker> ioWorkers;

    public HttpServer(final String host, final int port, final HttpHandler handler) {
        this.host = host;
        this.port = port;
        this.handler = Objects.requireNonNull(handler);
        this.acceptWorker = new Worker(Selector::open);
        final int size = Runtime.getRuntime().availableProcessors() - 1;
        this.ioWorkers = IntStream.range(0, size)
                .mapToObj(i -> new Worker(Selector::open)).collect(Collectors.toList());
    }

    public void start() throws IOException {
        logger.info(() -> "start");
        acceptWorker.start();
        ioWorkers.forEach(Thread::start);
        final ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ssc.setOption(StandardSocketOptions.SO_LINGER, 5000);
        //ssc.setOption(StandardSocketOptions.SO_LINGER, 0);
        ssc.bind(new InetSocketAddress(host, port));
        acceptWorker.register(ssc, SelectionKey.OP_ACCEPT, new AcceptHandler());
    }

    public void stop() {
        logger.info(() -> "stop");
        acceptWorker.shutdown();
        ioWorkers.forEach(Worker::shutdown);
    }

    private interface Handler {

        void handle(SelectionKey key) throws IOException;

        default void handleWithUncheckedIOException(final SelectionKey key) {
            try {
                handle(key);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private class AcceptHandler implements Handler {

        @Override
        public void handle(final SelectionKey key) throws IOException {
            final ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            final SocketChannel sc = ssc.accept();
            sc.setOption(StandardSocketOptions.SO_REUSEADDR, true);

            sc.configureBlocking(false);
            final int index = counter.getAndIncrement() % ioWorkers.size();
            ioWorkers.get(index).register(sc, SelectionKey.OP_READ, new IOHandler());
        }
    }

    private class IOHandler implements Handler {

        private HttpRequestParser parser = new HttpRequestParser();
        private final ByteBuffer buf = ByteBuffer.allocate(8192);
        private ByteBuffer responseEntity;
        private HttpRequest request;

        @Override
        public void handle(final SelectionKey key) throws IOException {
            final SocketChannel sc = (SocketChannel) key.channel();
            if (key.isReadable()) {
                int i;
                while ((i = sc.read(buf)) > 0) {
                    buf.flip();
                    final boolean parsed = parser.parse(buf);
                    if (parsed) {
                        request = parser.build();
                        parser = new HttpRequestParser();
                        final HttpResponse response = handle(request);
                        final HttpResponseFormatter formatter = new HttpResponseFormatter();
                        responseEntity = formatter.format(response);
                        if ((key.interestOps() & SelectionKey.OP_WRITE) != SelectionKey.OP_WRITE) {
                            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                        }
                    }
                    buf.clear();
                }
                if (i < 0) {
                    key.interestOps(key.interestOps() ^ SelectionKey.OP_READ);
                }
            }
            if (key.isWritable()) {
                sc.write(responseEntity);
                if (responseEntity.hasRemaining() == false) {
                    key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE);

                    final List<String> connection = request.headers.getOrDefault("Connection",
                            Collections.emptyList());
                    if (connection.contains("keep-alive") == false) {
                        key.cancel();
                        sc.close();
                    }
                }
            }
        }

        private HttpResponse handle(final HttpRequest request) {
            try {
                return handler.handle(request);
            } catch (final Exception e) {
                logger.log(Level.SEVERE, "exception in handle request", e);
                return createErrorResponse(e);
            }
        }

        private HttpResponse createErrorResponse(final Exception e) {
            final Map<String, List<String>> headers = new HashMap<>();
            headers.put("Content-Type", Arrays.asList("text/plain"));
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (PrintStream out = new PrintStream(baos)) {
                e.printStackTrace(out);
            }
            final ByteBuffer entity = ByteBuffer.wrap(baos.toByteArray());
            return new HttpResponse(500, "Internal Server Error", headers, entity);
        }
    }

    private static class Worker extends Thread {

        private final Selector selector;
        private final BlockingQueue<IOAction> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(true);

        public Worker(final IOSupplier<Selector> selector) {
            this.selector = selector.getWithUncheckedIOException();
        }

        @Override
        public void run() {
            logger.info(() -> getName() + " begin");
            try {
                while (running.get()) {
                    selector.select(key -> {
                        final var h = (Handler) key.attachment();
                        h.handleWithUncheckedIOException(key);
                    });
                    IOAction task;
                    while ((task = queue.poll()) != null) {
                        task.act();
                    }
                }
                logger.info(() -> getName() + " end");
            } catch (final Exception e) {
                logger.log(Level.SEVERE, "exception in run", e);
            } finally {
                try {
                    selector.close();
                } catch (final IOException e) {
                    logger.log(Level.SEVERE, "exception in close selector", e);
                }
            }
        }

        public void register(final AbstractSelectableChannel channel, final int op,
                             final Handler handler) {
            queue.add(() -> channel.register(selector, op, handler));
            selector.wakeup();
        }

        public void shutdown() {
            running.set(false);
            if (selector.isOpen()) {
                selector.wakeup();
            }
        }
    }

    @FunctionalInterface
    private interface IOAction {
        void act() throws IOException;
    }

    @FunctionalInterface
    private interface IOSupplier<T> {

        T get() throws IOException;

        default T getWithUncheckedIOException() {
            try {
                return get();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}