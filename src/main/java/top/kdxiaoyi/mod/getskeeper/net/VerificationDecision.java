package top.kdxiaoyi.mod.getskeeper.net;

/** What the player chose on a verification dialog. */
public enum VerificationDecision {
    /** Dismiss without doing anything. The intercepted operation is not retried. */
    CANCEL,
    /** Proceed with the operation that was intercepted. */
    CONTINUE,
    /** Destroy the entire stack that was being verified. */
    VOID;

    public static VerificationDecision byOrdinal(int ordinal) {
        VerificationDecision[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : CANCEL;
    }
}
