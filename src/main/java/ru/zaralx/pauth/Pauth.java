package ru.zaralx.pauth;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;
import ru.zaralx.pauth.data.PlayerDatabase;

@Mod(Pauth.MODID)
public class Pauth {
    public static final String MODID = "pauth";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Pauth() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Server-side only: tell clients they don't need this mod to connect
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));

        MinecraftForge.EVENT_BUS.addListener(this::onServerAboutToStart);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        PlayerDatabase.load();
    }
}
