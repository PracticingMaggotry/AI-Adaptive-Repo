package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Records which questions were served to whom. Never throws: logging must not break quiz serving. */
@Component
public class QuestionExposureLogger {

    @Autowired private QuestionExposureLogRepository repo;

    public void logServed(String studentId, String topic, String requestedDifficulty, List<Question> served) {
        if (studentId == null || served == null || served.isEmpty()) return;
        try {
            String serveId = UUID.randomUUID().toString();
            List<QuestionExposureLog> rows = new ArrayList<>(served.size());
            for (int i = 0; i < served.size(); i++) {
                rows.add(new QuestionExposureLog(serveId, studentId, topic, requestedDifficulty, served.get(i), i));
            }
            repo.saveAll(rows);
        } catch (Exception e) {
            System.err.println("Question exposure logging failed (non-fatal): " + e.getMessage());
        }
    }
}