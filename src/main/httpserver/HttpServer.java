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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
    private ConcurrentHashMap<SocketChannel, Long> clients = new ConcurrentHashMap<SocketChannel, Long>();

    public HttpServer(final String host, final int port, final HttpHandler handler) {
        this.host = host;
        this.port = port;
        this.handler = Objects.requireNonNull(handler);
        this.acceptWorker = new Worker(Selector::open);
        final int size = Runtime.getRuntime().availableProcessors() - 1;
        this.ioWorkers = IntStream.range(0, size)
                .mapToObj(i -> new Worker(Selector::open)).collect(Collectors.toList());
       // IdleSocketChecker isc = new IdleSocketChecker();
       // new Thread(isc).start();
    }

    public void start() throws IOException {
        logger.info(() -> "start");
        acceptWorker.start();
        ioWorkers.forEach(Thread::start);
        final ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);

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

            sc.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            sc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            sc.configureBlocking(false);
            clients.put(sc, System.currentTimeMillis());

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
            ServerSocketChannel ssc = ServerSocketChannel.open();
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

                    //TODO: header에 있는 Content-Length 가져오기
                    // connection.add(request.headers.getOrDefault("Content-Length", Collections.emptyList());
                    if (sc != null) {
                        try {
                            Thread.sleep(5);
                          //  sc.setOption(StandardSocketOptions.SO_LINGER, 0);
                        } catch (InterruptedException e) {
                        } finally {
                            //TODO: 1.responseEntity가 남아 있으면 sleep을 작게 걸어본다
                            //TODO: 2.RST면 SERVER에 있는 데이터를 여러개로 쪼개서 수신할 수 있는지, Chunked\
                            key.cancel();
                            ssc.close();
                            sc.close();
                        }
                    }
//                    if (connection.contains("keep-alive") == false) {
//                        key.cancel();
//                        sc.close();
//                    }
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

    private void checkIdleSockets() {
        Iterator<Map.Entry<SocketChannel, Long>> iter = clients.entrySet().iterator();
        //TODO: timeout이 걸리면 iter에서 제거를 해줘야하는데


        while (iter.hasNext()) {
            try {
                Map.Entry<SocketChannel, Long> entry = iter.next();
                SocketChannel client = entry.getKey();

                long mills = entry.getValue();
                double minutes = (System.currentTimeMillis() - mills) / (double) (60*1000);
                //TODO: client의 관심사가 아무것도 없으면 제거해도록 조건 추가
                if (minutes > 1) {
                    /* key is idle for */
                    //logger.info("[IdleSocketChecker] Socket is idle for " + Math.round(minutes) + ", closing......");
                    try {
                        client.close();
                    } catch (IOException e) {
                    } finally {
                        iter.remove();
                    }
                }
            } catch (Exception e) {

            }
        }
        logger.info("[checkIdleSockets] Total connected : " + clients.size());
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

    private class IdleSocketChecker implements Runnable{
        private boolean RUN = true;

        @Override
        public void run() {
            try{
                while(RUN){
                    Thread.sleep(60*1000);
                    checkIdleSockets();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}