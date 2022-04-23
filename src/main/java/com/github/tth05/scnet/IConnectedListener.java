package com.github.tth05.scnet;

public interface IConnectedListener extends IConnectionListener{

    @Override
    default void onDisconnected() {}
}
