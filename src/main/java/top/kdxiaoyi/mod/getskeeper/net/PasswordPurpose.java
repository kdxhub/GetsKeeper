package top.kdxiaoyi.mod.getskeeper.net;

/**
 * Why the client is being asked for a password.
 *
 * <p>The number travels over the wire, so the order of these constants is part of the protocol.
 */
public enum PasswordPurpose {
    /** First-time setup: an operator with no password yet cannot play until one is stored. */
    SET_PASSWORD,
    /** {@code /mattr} asked for a password before authorizing an item. */
    AUTHORIZE_ITEM,
    /** A switch on the privacy screen is being flipped. */
    TOGGLE_AUTHORIZATION,
    /** "Use anyway" was pressed on an item that was refused this session. */
    GRANT_BLOCKED_USE;

    public static PasswordPurpose byOrdinal(int ordinal) {
        PasswordPurpose[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : SET_PASSWORD;
    }
}
