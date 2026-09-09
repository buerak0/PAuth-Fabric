package ru.zaralx.pauth.mixin;

import io.netty.handler.codec.EncoderException;
import net.minecraft.network.PacketEncoder;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.zaralx.pauth.Config;
import ru.zaralx.pauth.Pauth;

/**
 * Drops one specific piece of vanilla log noise: a full stack trace, several times an hour,
 * for a connection that was already dead.
 *
 * <p>When a connection errors, {@code Connection#exceptionCaught} tries to tell the client why
 * by sending a disconnect packet. It picks between the login and the common disconnect packet
 * from a single boolean, and neither exists in the status or handshake protocol - so for a
 * server-list ping that drops mid-handshake the encoder throws "Sending unknown packet" and
 * vanilla logs it at ERROR. Nothing is wrong: the channel is being torn down either way, and
 * there is no client left to inform.
 *
 * <p>Only that exact case is silenced, and only while {@code quietUnknownPacketErrors} is on.
 * Every other encoder failure is logged exactly as before. Nothing about the connection itself
 * is changed - suppressing the send instead would risk skipping vanilla's own cleanup.
 */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {

    @Redirect(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            // Cosmetic only: if this ever fails to bind, the log noise comes back rather than
            // the server refusing to start.
            require = 0,
            at = @At(value = "INVOKE",
                    target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void pauth$quietUnknownPacket(Logger logger, String message, Object packetType, Object thrown) {
        if (Config.QUIET_UNKNOWN_PACKET_ERRORS.get()
                && thrown instanceof EncoderException failure
                && failure.getMessage() != null
                && failure.getMessage().startsWith("Sending unknown packet")) {
            Pauth.LOGGER.debug("PAuth: quietened '{}' for {} ({})", message, packetType, failure.getMessage());
            return;
        }
        logger.error(message, packetType, thrown);
    }
}
