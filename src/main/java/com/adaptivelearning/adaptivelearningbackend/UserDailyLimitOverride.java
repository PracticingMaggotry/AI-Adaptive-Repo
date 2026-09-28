package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;

/**
 * Optional per-student override of a daily action limit. When present for a
 * (studentId, actionType) pair it replaces the global ConfigurationService
 * default for that student only — e.g. bumping a power user's quiz-submit
 * cap without changing it for everyone else.
 */
@Entity
@Table(
        name = "user_daily_limit_overrides",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_daily_limit_override",
                columnNames = {"action_type", "student_id"}
        )
)
public class UserDailyLimitOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_type", nullable = false, length = 60)
    private String actionType;

    @Column(name = "student_id", nullable = false, length = 255)
    private String studentId;

    @Column(name = "custom_limit", nullable = false)
    private int customLimit;

    public UserDailyLimitOverride() {}

    public UserDailyLimitOverride(String actionType, String studentId, int customLimit) {
        this.actionType  = actionType;
        this.studentId   = studentId;
        this.customLimit = customLimit;
    }

    public Long   getId()          { return id; }
    public String getActionType()  { return actionType; }
    public String getStudentId()   { return studentId; }
    public int    getCustomLimit() { return customLimit; }

    public void setId(Long id)                 { this.id = id; }
    public void setActionType(String v)        { this.actionType = v; }
    public void setStudentId(String v)         { this.studentId = v; }
    public void setCustomLimit(int customLimit) { this.customLimit = customLimit; }
}