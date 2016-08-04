package com.tongtech.nio.exercise.reactor;

import java.nio.ByteBuffer;

import com.tongtech.nio.exercise.reactor.ServerBuilder.Server;

/**
 * echo server demo
 */
public class EchoServer {

    public static void main(String[] args) {
        EchoHandler handler = new EchoHandler();
        final Server server = ServerBuilder.build(handler).host("192.168.17.1").start();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                server.shutdown();
            }
        });
    }

    static class EchoHandler implements IoHandler {
        private static final int BUF_SIZE = 1024;
        private static final String BUF_KEY = "buf";

        public void attach(IoSession session) {
            ByteBuffer buffer = ByteBuffer.allocate(BUF_SIZE);
            session.putAttribute(BUF_KEY, buffer);
            session.interestRead();
        }

        public void read(IoSession session) {
            final ByteBuffer buffer = (ByteBuffer) session.getAttribute(BUF_KEY);
            try {
                final int count = session.channel().read(buffer);
                if (count < 0) {// channel read fin flag
                    session.close();
                } else {
                    if (buffer.position() > 0) {
                        if (buffer.hasRemaining()) {
                            session.interestRead().interestWrite();
                        } else {
                            // no space to read, set to only write mode
                            session.uninterestRead().interestWrite();
                        }
                    }
                }
            } catch (final Exception e) {
                session.close();
            }
        }

        public void write(IoSession session) {
            final ByteBuffer buffer = (ByteBuffer) session.getAttribute(BUF_KEY);
            try {
                buffer.flip();
                int count = session.channel().write(buffer);
                if (count > 0) {// channel written data from buffer to network
                    if (buffer.hasRemaining()) {
                        session.interestRead().interestWrite();
                    } else {// nothing to write, set to read mode
                        session.uninterestWrite().interestRead();
                    }
                }
                buffer.compact();
            } catch (final Exception ex) {
                session.close();
            }
        }

        public void detach(IoSession session) {
            // let it go.
        }
    }
}
