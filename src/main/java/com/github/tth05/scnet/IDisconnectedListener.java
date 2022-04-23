package com.github.tth05.scnet;

public interface IDisconnectedListener extends IConnectionListener {

    @Override
    default void onConnected() {}
}
