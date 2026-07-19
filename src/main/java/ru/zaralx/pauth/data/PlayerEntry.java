package ru.zaralx.pauth.data;

public class PlayerEntry {
    public String name;
    /** PBKDF2 hash in "pbkdf2:iterations:base64salt:base64hash" form; null for premium-only entries. */
    public String passwordHash;
    /** True once the Mojang session server confirmed ownership of this name. */
    public boolean premium;
    public String mojangUuid;
    public String lastIp;
    public long lastLoginMs;
    public long registeredAtMs;
}
