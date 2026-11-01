package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDate;

/** Daily token and cost totals per student, task and model. Feeds the per-student budget and the admin cost view. */
@Entity
@Table(name = "ai_usage",
        uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "usage_day", "task", "model"}))
public class AiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "_system" for calls not made on behalf of a student (e.g. admin tools). */
    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "usage_day", nullable = false)
    private LocalDate day;

    @Column(nullable = false, length = 60)
    private String task;

    @Column(nullable = false, length = 60)
    private String model;

    private long calls;
    @Column(name = "input_tokens") private long inputTokens;
    @Column(name = "output_tokens") private long outputTokens;
    @Column(name = "cache_read_tokens") private long cacheReadTokens;
    @Column(name = "cache_write_tokens") private long cacheWriteTokens;
    @Column(name = "cost_micros") private long costMicros;

    public AiUsage() {}

    public AiUsage(String studentId, LocalDate day, String task, String model) {
        this.studentId = studentId; this.day = day; this.task = task; this.model = model;
    }

    public void add(long in, long out, long cacheRead, long cacheWrite, long micros) {
        calls++; inputTokens += in; outputTokens += out; cacheReadTokens += cacheRead; cacheWriteTokens += cacheWrite;
        costMicros += micros;
    }

    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public LocalDate getDay() { return day; }
    public String getTask() { return task; }
    public String getModel() { return model; }
    public long getCalls() { return calls; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public long getCacheWriteTokens() { return cacheWriteTokens; }
    public long getCostMicros() { return costMicros; }
}
