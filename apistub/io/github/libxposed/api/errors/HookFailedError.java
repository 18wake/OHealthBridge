package io.github.libxposed.api.errors;

/** 编译桩。 */
public class HookFailedError extends RuntimeException {
    public HookFailedError(String message) {
        super(message);
    }

    public HookFailedError(String message, Throwable cause) {
        super(message, cause);
    }
}
