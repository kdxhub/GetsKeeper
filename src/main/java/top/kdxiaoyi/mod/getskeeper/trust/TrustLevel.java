package top.kdxiaoyi.mod.getskeeper.trust;

/**
 * How much GetsKeeper trusts a namespace or an item id.
 *
 * <p>Anything that is on none of the three configured lists is {@link #UNTRUSTED}.
 */
public enum TrustLevel {
    /** Verifies silently: the pass runs with no dialog to answer, and the operation goes through. */
    FULLY_TRUSTED,
    /** Verifies, then asks the player to confirm. */
    TRUSTED,
    /** Verifies, then warns that the item could not be checked. */
    UNTRUSTED,
    /** Verifies, then states the item is broken and offers to void it. */
    BLOCKED;

    /** {@return {@code true} when the operation may proceed without asking the player} */
    public boolean passesSilently() {
        return this == FULLY_TRUSTED;
    }
}
