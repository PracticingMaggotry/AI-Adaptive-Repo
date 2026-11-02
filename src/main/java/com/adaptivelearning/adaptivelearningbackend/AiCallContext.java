package com.adaptivelearning.adaptivelearningbackend;

/**
 * Tells the AI gateway who a call is made for, without threading a student id through every ClaudeService method.
 * Controllers open it around the work: {@code try (var ctx = AiCallContext.open(studentId)) { ... }}.
 * Used for per-student fairness in the scheduler and for per-student cost accounting.
 */
public final class AiCallContext {
    private AiCallContext() {}

    private static final ThreadLocal<String> STUDENT = new ThreadLocal<>();

    public static Handle open(String studentId) {
        String previous = STUDENT.get();
        STUDENT.set(studentId);
        return new Handle(previous);
    }

    /** Student the current thread is working for, or "_system". */
    public static String student() {
        String s = STUDENT.get();
        return s == null || s.isBlank() ? "_system" : s;
    }

    public static final class Handle implements AutoCloseable {
        private final String previous;
        private Handle(String previous) { this.previous = previous; }
        @Override public void close() {
            if (previous == null) STUDENT.remove(); else STUDENT.set(previous);
        }
    }
}
