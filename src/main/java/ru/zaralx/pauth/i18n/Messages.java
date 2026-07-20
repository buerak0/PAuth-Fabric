package ru.zaralx.pauth.i18n;

import ru.zaralx.pauth.Config;

import java.util.EnumMap;
import java.util.Map;

/**
 * Server-side localization. Messages are resolved on the server (clients may be vanilla
 * and would not have our translation keys), and the language is chosen by the
 * `language` config option ("ru" or "en").
 */
public final class Messages {
    public enum Lang { RU, EN }

    public enum Key {
        PROMPT_LOGIN, PROMPT_REGISTER, LOGIN_TIMEOUT, PREMIUM_OK, SESSION_RESTORED,
        MUST_LOGIN_FIRST, ONLY_AUTH_COMMANDS, ALREADY_LOGGED_IN, ALREADY_REGISTERED,
        PASSWORDS_MISMATCH, PASSWORD_TOO_SHORT, REGISTER_OK, NOT_REGISTERED, LOGIN_OK,
        WRONG_PASSWORD, TOO_MANY_ATTEMPTS, NO_PASSWORD_PREMIUM, NEW_PASSWORD_TOO_SHORT,
        PASSWORD_CHANGED, WRONG_OLD_PASSWORD, ACCOUNT_RESET_BY_ADMIN, PLAYER_NOT_FOUND,
        ACCOUNT_DELETED, LICENSE_CHECK_FAILED, INTERNAL_LOGIN_ERROR
    }

    private static final Map<Key, String> RU = new EnumMap<>(Key.class);
    private static final Map<Key, String> EN = new EnumMap<>(Key.class);

    private static void put(Key key, String ru, String en) {
        RU.put(key, ru);
        EN.put(key, en);
    }

    static {
        put(Key.PROMPT_LOGIN, "§eВойдите в аккаунт: §6/login <пароль>", "§eLog in: §6/login <password>");
        put(Key.PROMPT_REGISTER, "§eЗарегистрируйтесь: §6/register <пароль> <пароль>", "§eRegister: §6/register <password> <password>");
        put(Key.LOGIN_TIMEOUT, "Время на вход истекло", "Login time expired");
        put(Key.PREMIUM_OK, "§aЛицензия подтверждена, приятной игры!", "§aLicense verified, enjoy the game!");
        put(Key.SESSION_RESTORED, "§aВход выполнен автоматически (сессия восстановлена).", "§aLogged in automatically (session restored).");
        put(Key.MUST_LOGIN_FIRST, "§cСначала войдите в аккаунт.", "§cLog in first.");
        put(Key.ONLY_AUTH_COMMANDS, "§cДоступны только /login и /register.", "§cOnly /login and /register are available.");
        put(Key.ALREADY_LOGGED_IN, "§aВы уже вошли в аккаунт.", "§aYou are already logged in.");
        put(Key.ALREADY_REGISTERED, "§cВы уже зарегистрированы, используйте /login <пароль>.", "§cYou are already registered, use /login <password>.");
        put(Key.PASSWORDS_MISMATCH, "§cПароли не совпадают.", "§cPasswords do not match.");
        put(Key.PASSWORD_TOO_SHORT, "§cПароль слишком короткий (минимум %d символа).", "§cPassword too short (minimum %d characters).");
        put(Key.REGISTER_OK, "§aРегистрация успешна, приятной игры!", "§aRegistration successful, enjoy the game!");
        put(Key.NOT_REGISTERED, "§cВы не зарегистрированы: /register <пароль> <пароль>.", "§cYou are not registered: /register <password> <password>.");
        put(Key.LOGIN_OK, "§aВход выполнен, приятной игры!", "§aLogged in, enjoy the game!");
        put(Key.WRONG_PASSWORD, "§cНеверный пароль! Осталось попыток: %d", "§cWrong password! Attempts left: %d");
        put(Key.TOO_MANY_ATTEMPTS, "Слишком много неверных попыток входа", "Too many failed login attempts");
        put(Key.NO_PASSWORD_PREMIUM, "§cУ вас нет пароля (premium-аккаунт).", "§cYou have no password (premium account).");
        put(Key.NEW_PASSWORD_TOO_SHORT, "§cНовый пароль слишком короткий.", "§cNew password is too short.");
        put(Key.PASSWORD_CHANGED, "§aПароль изменён.", "§aPassword changed.");
        put(Key.WRONG_OLD_PASSWORD, "§cНеверный старый пароль.", "§cWrong current password.");
        put(Key.ACCOUNT_RESET_BY_ADMIN, "Ваш аккаунт был сброшен администратором", "Your account was reset by an administrator");
        put(Key.PLAYER_NOT_FOUND, "Игрок %s не найден в базе.", "Player %s not found in the database.");
        put(Key.ACCOUNT_DELETED, "Аккаунт %s удалён из базы.", "Account %s removed from the database.");
        put(Key.LICENSE_CHECK_FAILED, "Не удалось проверить лицензию (сервис Mojang недоступен). Попробуйте зайти чуть позже.", "Could not verify your license (Mojang service unavailable). Please try again shortly.");
        put(Key.INTERNAL_LOGIN_ERROR, "PAuth: внутренняя ошибка входа", "PAuth: internal login error");
    }

    private Messages() {}

    public static Lang lang() {
        try {
            return "en".equalsIgnoreCase(Config.LANGUAGE.get()) ? Lang.EN : Lang.RU;
        } catch (Exception e) {
            return Lang.RU;
        }
    }

    public static String t(Key key) {
        return (lang() == Lang.EN ? EN : RU).getOrDefault(key, key.name());
    }

    public static String t(Key key, Object... args) {
        return String.format(t(key), args);
    }
}
