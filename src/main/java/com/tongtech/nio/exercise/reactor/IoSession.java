package com.tongtech.nio.exercise.reactor;

import java.nio.channels.SocketChannel;

public interface IoSession {
    public SocketChannel channel();

    public boolean isReadable();

    public boolean isWritable();

    public IoSession interestWrite();

    public IoSession interestRead();

    public IoSession uninterestWrite();

    public IoSession uninterestRead();

    public void close();

    public Object putAttribute(String key, Object value);

    public Object getAttribute(String key);
}
