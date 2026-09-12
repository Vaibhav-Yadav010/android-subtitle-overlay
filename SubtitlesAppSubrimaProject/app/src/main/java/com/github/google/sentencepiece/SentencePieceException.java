package com.github.google.sentencepiece;

/**
 * Compatibility exception for the archive SentencePiece JNI bridge.
 * The native bridge throws this type for invalid piece IDs.
 */
public class SentencePieceException extends RuntimeException {
    public SentencePieceException() {
        super();
    }

    public SentencePieceException(String message) {
        super(message);
    }

    public SentencePieceException(String message, Throwable cause) {
        super(message, cause);
    }
}
