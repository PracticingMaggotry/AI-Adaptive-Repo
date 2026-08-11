package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * One row per (actionType, studentId, date) counting AI-backed-action usage
 * for that day. The unique constraint lets the repository upsert atomically
 * via a single UPDATE ... WHERE count < limit, avoiding a SELECT-then-UPDATE
 * race across instances.
 *
 * Old rows aren't auto-pruned but are tiny; DBA can run:
 *   DELETE FROM daily_action_counts WHERE action_date < CURDATE() - INTERVAL 30 DAY;
 */
@Entity
@Table(
        name = "daily_action_counts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_daily_action",
                columnNames = {"action_type", "student_id", "action_date"}
        )
)
public class DailyActionCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_type", nullable = false, length = 60)
    private String actionType;

    @Column(name = "student_id", nullable = false, length = 255)
    private String studentId;

    @Column(name = "action_date", nullable = false)
    private LocalDate actionDate;

    @Column(name = "count", nullable = false)
    private int count = 0;

    public DailyActionCount() {}

    public DailyActionCount(String actionType, String studentId, LocalDate actionDate) {
        this.actionType  = actionType;
        this.studentId   = studentId;
        this.actionDate  = actionDate;
        this.count       = 0;
    }

    public Long      getId()         { return id; }
    public String    getActionType() { return actionType; }
    public String    getStudentId()  { return studentId; }
    public LocalDate getActionDate() { return actionDate; }
    public int       getCount()      { return count; }

    public void setId(Long id)                   { this.id = id; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public void setStudentId(String studentId)   { this.studentId = studentId; }
    public void setActionDate(LocalDate d)       { this.actionDate = d; }
    public void setCount(int count)              { this.count = count; }
}