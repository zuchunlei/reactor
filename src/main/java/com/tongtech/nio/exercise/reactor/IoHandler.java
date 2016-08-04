package com.tongtech.nio.exercise.reactor;

public interface IoHandler {

    public void attach(IoSession session);

    public void read(IoSession session);

    public void write(IoSession session);

    public void detach(IoSession session);

}
