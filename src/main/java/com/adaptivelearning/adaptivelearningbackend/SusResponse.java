package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One System Usability Scale submission per participant. Keyed by code, not email. */
@Entity
@Table(name = "sus_responses",
        uniqueConstraints = @UniqueConstraint(name = "uq_sus_code", columnNames = "participant_code"))
public class SusResponse {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_code", nullable = false, length = 20)
    private String participantCode;

    private int q1, q2, q3, q4, q5, q6, q7, q8, q9, q10;

    @Column(name = "sus_score", nullable = false)
    private double susScore;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    public SusResponse() {}

    public SusResponse(String code, int[] a) {
        this.participantCode = code;
        this.q1 = a[0]; this.q2 = a[1]; this.q3 = a[2]; this.q4 = a[3]; this.q5 = a[4];
        this.q6 = a[5]; this.q7 = a[6]; this.q8 = a[7]; this.q9 = a[8]; this.q10 = a[9];
        int sum = 0;
        for (int i = 0; i < 10; i++) sum += (i % 2 == 0) ? a[i] - 1 : 5 - a[i]; // odd items: x-1, even items: 5-x
        this.susScore = sum * 2.5;
    }

    public String getParticipantCode() { return participantCode; }
    public int[] answers() { return new int[]{q1, q2, q3, q4, q5, q6, q7, q8, q9, q10}; }
    public double getSusScore() { return susScore; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
}
