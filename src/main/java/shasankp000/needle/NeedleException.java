package shasankp000.needle;

/** Thrown when the native engine reports a failure or the SDK cannot locate/load it. */
public class NeedleException extends RuntimeException {
    private final int code;

    public NeedleException(String message) {
        this(message, 0, null);
    }

    public NeedleException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public NeedleException(String message, int code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Negative return code from the native call, or 0 if not applicable. */
    public int code() {
        return code;
    }
}
