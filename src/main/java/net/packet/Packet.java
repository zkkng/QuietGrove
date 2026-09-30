package net.packet;

public interface Packet {
    byte[] getBytes();
    default int size() {return getBytes().length;}
}
