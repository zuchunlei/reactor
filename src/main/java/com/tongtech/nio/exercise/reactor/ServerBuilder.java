package com.tongtech.nio.exercise.reactor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * nio reactor
 */
public class ServerBuilder {

    public static Server build(IoHandler handler) {
        return new Server(handler);
    }

    static class Server {
        private static final String DEFAULT_HOST = "localhost";
        private static final int DEFAULT_PORT = 6666;
        private volatile boolean running = false;
        private boolean init = false;
        private IoHandler handler;
        private IoConfig config;
        private Acceptor acceptor;
        private Poller[] pollers;
        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;

        public Server(IoHandler handler) {
            this.handler = handler;
        }

        public Server host(String host) {
            this.host = host;
            return this;
        }

        public Server port(int port) {
            this.port = port;
            return this;
        }

        public Server config(IoConfig config) {
            this.config = config;
            return this;
        }

        public Server init() {
            if (!init) {
                this.acceptor = new Acceptor();
                this.pollers = new Poller[Runtime.getRuntime().availableProcessors() + 1];
                for (int i = 0; i < pollers.length; i++) {
                    pollers[i] = new Poller();
                }
                init = true;
            }
            return this;
        }

        public Server start() {
            if (!init) {
                init();
            }
            this.running = true;
            for (int i = 0; i < pollers.length; i++) {
                new Thread(pollers[i], "Poller Thread " + i).start();
            }
            new Thread(acceptor, "Acceptor").start();
            return this;
        }

        public Server shutdown() {
            this.running = false;
            return this;
        }

        class Acceptor implements Runnable {
            private AtomicInteger incr = new AtomicInteger();
            private Selector selector;

            public void wakeup() {
                if (selector != null) {
                    this.selector.wakeup();
                }
            }

            public void run() {
                try {
                    selector = Selector.open();
                    ServerSocketChannel servSockChannel = ServerSocketChannel.open();
                    servSockChannel.configureBlocking(false);
                    ServerSocket serverSock = servSockChannel.socket();
                    if (config != null) {
                        config.configServerSocket(serverSock);
                    }
                    servSockChannel.socket().bind(new InetSocketAddress(host, port), 200);
                    servSockChannel.register(selector, SelectionKey.OP_ACCEPT);
                    while (running) {
                        int selected = selector.select();
                        if (selected > 0) {
                            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                            while (it.hasNext()) {
                                SelectionKey key = it.next();
                                it.remove();
                                if (!key.isValid()) {
                                    key.cancel();
                                    key.channel().close();
                                    break;
                                }

                                SocketChannel channel = accept(key);
                                pollers[Math.abs(incr.decrementAndGet() % pollers.length)].registerChannel(channel);
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            private SocketChannel accept(SelectionKey key) throws IOException {
                SocketChannel channel = null;
                if (key.isAcceptable()) {
                    ServerSocketChannel servSockChannel = (ServerSocketChannel) key.channel();
                    channel = servSockChannel.accept();
                    if (config != null) {
                        config.configSocket(channel.socket());
                    }
                }
                return channel;
            }
        }

        class Poller implements Runnable {
            private Queue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
            private Selector selector;

            public void run() {
                try {
                    this.selector = Selector.open();
                    while (running) {
                        int selected = selector.select(1000);
                        if (selected > 0) {
                            processEvents(selector.selectedKeys());
                        }
                        queueEvents();
                    }
                } catch (IOException e) {
                    // ignore
                }

            }

            public void processEvents(final Collection<SelectionKey> selectedKeys) {
                for (SelectionKey key : selectedKeys) {
                    processEvent(key);
                }
                selectedKeys.clear();
            }

            private void processEvent(final SelectionKey key) {
                IoSession session = (IoSession) key.attachment();
                try {
                    if (session.isReadable()) {
                        handler.read(session);
                    } else if (session.isWritable()) {
                        handler.write(session);
                    }
                } catch (Exception e) {
                    // log exception msg
                    handler.detach(session);
                    session.close();
                }
            }

            public void registerChannel(SocketChannel channel) {
                final SocketChannel sc = channel;
                final Poller poller = this;
                queue.add(new Runnable() {
                    public void run() {
                        try {
                            sc.configureBlocking(false);
                            sc.register(selector, 0);
                            IoSession session = new SessionContext(sc, poller);
                            handler.attach(session);
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                });
            }

            public void queueEvents() {
                for (Iterator<Runnable> it = queue.iterator(); it.hasNext();) {
                    Runnable task = it.next();
                    it.remove();
                    task.run();
                }
            }
        }

        static class SessionContext implements IoSession {
            private SocketChannel channel;
            private SelectionKey key;
            private Map<String, Object> attributes;

            public SessionContext(SocketChannel channel, Poller poller) {
                this.attributes = new ConcurrentHashMap<String, Object>();
                this.channel = channel;
                this.key = channel.keyFor(poller.selector);
                key.attach(this);
            }

            public SocketChannel channel() {
                return this.channel;
            }

            public boolean isReadable() {
                return key.isReadable();
            }

            public boolean isWritable() {
                return key.isWritable();
            }

            public IoSession interestWrite() {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                return this;
            }

            public IoSession interestRead() {
                key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                return this;
            }

            public IoSession uninterestWrite() {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                return this;
            }

            public IoSession uninterestRead() {
                key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                return this;
            }

            public void close() {
                if (key.isValid()) {
                    key.cancel();
                }
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                    }
                }
            }

            public Object putAttribute(String key, Object value) {
                return this.attributes.put(key, value);
            }

            public Object getAttribute(String key) {
                return this.attributes.get(key);
            }
        }

    }

}
