package io.github.zoyluo.minecraftai.brain;

public class LlmApiException extends Exception {
    public LlmApiException(String message) {
        super(message);
    }

    public LlmApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
