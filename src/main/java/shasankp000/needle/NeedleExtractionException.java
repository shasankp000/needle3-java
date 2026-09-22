package shasankp000.needle;

/**
 * Thrown by {@link Extraction#extract} in strict mode when the engine's structured output is not grounded in
 * the input text: a temporal (year) mismatch, an engine-flagged value that isn't confirmed by a literal
 * number in the text, or a detected negation. Port of the Python package's {@code ExtractionValidationError}.
 */
public final class NeedleExtractionException extends NeedleException {
    public NeedleExtractionException(String message) {
        super(message);
    }
}
