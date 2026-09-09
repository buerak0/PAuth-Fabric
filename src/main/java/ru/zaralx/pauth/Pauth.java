package ru.zaralx.pauth;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.zaralx.pauth.data.PlayerDatabase;
import ru.zaralx.pauth.event.AuthEvents;

public class Pauth implements DedicatedServerModInitializer {
    public static final String MODID = "pauth";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    @Override
    public void onInitializeServer() {
        Config.load();
        PlayerDatabase.load();
        AuthEvents.init();

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PlayerDatabase.save();
        });

        LOGGER.info("PAuth initialized for Fabric 26.2");
    }
}
