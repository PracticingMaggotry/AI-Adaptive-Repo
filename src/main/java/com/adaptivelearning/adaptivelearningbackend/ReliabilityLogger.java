package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Records reliability events. Never throws: logging must not break the request. */
@Component
public class ReliabilityLogger {

    @Autowired private ReliabilityEventRepository repo;

    public void apiCall(String task, String model, long latencyMs, boolean success,
                        int httpStatus, String detail, int inTok, int outTok) {
        save(new ReliabilityEvent("API_CALL", task).model(model).latencyMs(latencyMs)
                .success(success).httpStatus(httpStatus).tokens(inTok, outTok).detail(detail));
    }

    public void limitBlock(String actionType, int limit) {
        save(new ReliabilityEvent("LIMIT_BLOCK", actionType).success(false).detail("limit=" + limit));
    }

    /** How many generated questions passed validation vs were dropped. (Named questionYield: "yield" is a restricted identifier in modern Java.) */
    public void questionYield(String task, int kept, int dropped) {
        save(new ReliabilityEvent("QUESTION_YIELD", task).items(kept, dropped).success(kept > 0));
    }

    public void pipelineFailure(String task, String detail) {
        save(new ReliabilityEvent("PIPELINE_FAILURE", task).success(false).detail(detail));
    }

    private void save(ReliabilityEvent e) {
        try { repo.save(e); }
        catch (Exception ex) { System.err.println("Reliability logging failed (non-fatal): " + ex.getMessage()); }
    }
}
