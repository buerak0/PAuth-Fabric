package ru.zaralx.pauth;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    public enum PremiumMode {
        /** Names owned by Mojang accounts can only be used by their licensed owners. */
        STRICT,
        /** Unknown names never trigger a Mojang lookup; anyone may register any free name. */
        LENIENT
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.EnumValue<PremiumMode> PREMIUM_MODE = BUILDER
            .comment("STRICT: if a username belongs to a Mojang account, only the licensed owner can join with it (others are kicked).",
                    "LENIENT: unknown names are never checked against Mojang; first join can register with any free name.")
            .defineEnum("premiumMode", PremiumMode.STRICT);

    public static final ForgeConfigSpec.BooleanValue OFFLINE_UUID_FOR_PREMIUM = BUILDER
            .comment("If true, premium players get the same offline-style UUID as cracked players (stable player data,",
                    "but breaks skins). If false, premium players keep their real Mojang UUID.")
            .define("offlineUuidForPremium", false);

    public static final ForgeConfigSpec.IntValue LOGIN_TIMEOUT_SECONDS = BUILDER
            .comment("Seconds an unauthenticated player may stay on the server before being kicked.")
            .defineInRange("loginTimeoutSeconds", 60, 10, 600);

    public static final ForgeConfigSpec.IntValue SESSION_MINUTES = BUILDER
            .comment("If a registered player rejoins from the same IP within this many minutes, /login is skipped. 0 disables sessions.")
            .defineInRange("sessionMinutes", 30, 0, 10080);

    public static final ForgeConfigSpec.IntValue MAX_LOGIN_ATTEMPTS = BUILDER
            .comment("Wrong password attempts before the player is kicked.")
            .defineInRange("maxLoginAttempts", 3, 1, 10);

    public static final ForgeConfigSpec.IntValue MOJANG_API_TIMEOUT_MS = BUILDER
            .comment("Timeout for Mojang API requests. On timeout/error the player is treated as cracked (falls back to password auth).")
            .defineInRange("mojangApiTimeoutMs", 5000, 500, 30000);

    public static final ForgeConfigSpec.IntValue MIN_PASSWORD_LENGTH = BUILDER
            .defineInRange("minPasswordLength", 4, 1, 32);

    public static final ForgeConfigSpec.BooleanValue APPLY_BLINDNESS = BUILDER
            .comment("Apply blindness to players while they are not logged in.")
            .define("applyBlindness", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();
}
