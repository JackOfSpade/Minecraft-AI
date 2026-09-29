package io.github.zoyluo.minecraftai.network;

import net.minecraft.network.ClientConnection;

/** Read access to the protected {@code connection} of a player's network handler. */
public interface ServerConnectionAccessor {
    ClientConnection minecraftai$getConnection();
}
