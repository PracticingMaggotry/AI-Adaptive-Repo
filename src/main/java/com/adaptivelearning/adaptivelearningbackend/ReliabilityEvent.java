package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Append-only system reliability/performance record. Contains no student identifiers. */
@Entity
@Table(name = "reliability_log",
        indexes = @Index(name = "idx_rel_kind_time", columnList = "kind, occurred_at"))
public class ReliabilityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    /** API_CALL | LIMIT_BLOCK | QUESTION_YIELD | PIPELINE_FAILURE */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(length = 60)
    private String task;

    @Column(length = 60)
    private String model;

    @Column(name = "latency_ms")
    private Long latencyMs;

    private Boolean success;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "items_kept")
    private Integer itemsKept;

    @Column(name = "items_dropped")
    private Integer itemsDropped;

    @Column(length = 400)
    private String detail;

    public ReliabilityEvent() {}
    public ReliabilityEvent(String kind, String task) { this.kind = kind; this.task = task; }

    public Long getId() { return id; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public String getKind() { return kind; }
    public String getTask() { return task; }
    public String getModel() { return model; }
    public Long getLatencyMs() { return latencyMs; }
    public Boolean getSuccess() { return success; }
    public Integer getHttpStatus() { return httpStatus; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Integer getItemsKept() { return itemsKept; }
    public Integer getItemsDropped() { return itemsDropped; }
    public String getDetail() { return detail; }

    public ReliabilityEvent model(String v) { this.model = v; return this; }
    public ReliabilityEvent latencyMs(Long v) { this.latencyMs = v; return this; }
    public ReliabilityEvent success(Boolean v) { this.success = v; return this; }
    public ReliabilityEvent httpStatus(Integer v) { this.httpStatus = v; return this; }
    public ReliabilityEvent tokens(Integer in, Integer out) { this.inputTokens = in; this.outputTokens = out; return this; }
    public ReliabilityEvent items(Integer kept, Integer dropped) { this.itemsKept = kept; this.itemsDropped = dropped; return this; }
    public ReliabilityEvent detail(String v) {
        this.detail = (v == null) ? null : (v.length() > 400 ? v.substring(0, 400) : v);
        return this;
    }
}
