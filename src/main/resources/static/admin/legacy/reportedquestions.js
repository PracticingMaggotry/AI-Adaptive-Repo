/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    async function refreshReportedBadge() {
        try {
            const resp = await apiFetch("/api/admin/reports/pending-count");
            const badge = document.getElementById("reportedBadge");
            if (!badge) return;
            if (resp.count > 0) {
                badge.textContent = resp.count;
                badge.style.display = "inline-block";
            } else {
                badge.style.display = "none";
            }
        } catch (err) {
        }
    }

    async function renderReportedQuestions() {
        document.getElementById("adminContent").innerHTML = `
    <div class="admin-card">
      <div class="report-header">
        <div>
          <div class="admin-card-title">🚩 Reported Questions</div>
          <div class="admin-card-hint">Student-flagged AI-generated questions — QuestionValidator only checks structure, so this is the human feedback loop that catches wrong answers, confusing wording, or out-of-scope content.</div>
        </div>
        <div class="range-filter" id="reportedStatusFilter"></div>
      </div>
      <div id="reportedList"><div class="empty-state">Loading reports...</div></div>
    </div>`;

        renderReportedStatusFilter();
        await loadReportedQuestions();
    }

    function renderReportedStatusFilter() {
        const el = document.getElementById("reportedStatusFilter");
        if (!el) return;
        const options = [
            { key: "PENDING", label: "Pending" },
            { key: "FIXED", label: "Fixed" },
            { key: "DISMISSED", label: "Dismissed" },
            { key: "DELETED", label: "Deleted" },
            { key: "ALL", label: "All" }
        ];
        el.innerHTML = options.map(o => `
            <button class="range-filter-btn ${reportedStatusFilter === o.key ? 'active' : ''}"
                    onclick="setReportedStatusFilter('${o.key}')">${o.label}</button>`).join("");
    }

    async function setReportedStatusFilter(status) {
        reportedStatusFilter = status;
        renderReportedStatusFilter();
        await loadReportedQuestions();
    }

    async function loadReportedQuestions() {
        const listEl = document.getElementById("reportedList");
        if (!listEl) return;
        listEl.innerHTML = `<div class="empty-state">Loading reports...</div>`;
        try {
            const resp = await apiFetch(`/api/admin/reports?status=${reportedStatusFilter}`);
            reportedQuestionsData = resp.reports || [];
            renderReportedList();
        } catch (err) {
            listEl.innerHTML = `<div class="empty-state">Could not load reports: ${err.message || "Unknown error"}</div>`;
        }
    }

    function renderReportedList() {
        const listEl = document.getElementById("reportedList");
        if (!listEl) return;

        if (reportedQuestionsData.length === 0) {
            listEl.innerHTML = `<div class="empty-state">No ${reportedStatusFilter === 'ALL' ? '' : reportedStatusFilter.toLowerCase() + ' '}reports found.</div>`;
            return;
        }

        listEl.innerHTML = reportedQuestionsData.map(r => {
            const reasonLabel = REASON_LABELS[r.reason] || r.reason;
            const statusBadge = STATUS_BADGE[r.status] || `<span class="role-badge role-student">${escHtmlGlobal(r.status)}</span>`;
            const reportedDate = r.reportedAt ? new Date(r.reportedAt).toLocaleString() : "—";
            const questionText = r.questionStillExists
                ? (r.currentQuestionText || r.questionText || "(no text)")
                : (r.questionText || "(question was already deleted)");
            const goneNotice = !r.questionStillExists
                ? `<div style="font-size:.72rem;color:#9ca3af;margin-top:4px;font-style:italic;">This question no longer exists in the database.</div>` : "";
            const multiReportNote = r.reportCount > 1
                ? `<span class="badge-flagged" style="margin-left:6px;">${r.reportCount}× reported</span>` : "";

            const answerBlock = r.questionStillExists ? buildReportedQuestionAnswerBlock(r) : "";
            const canAct = r.status === "PENDING";
            const questionGone = !r.questionStillExists;

            return `
            <div class="admin-card" style="margin-bottom:12px;border:1px solid ${r.status === 'PENDING' ? '#fcd34d' : '#e2e8f0'};">
                <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:12px;flex-wrap:wrap;">
                    <div style="flex:1;min-width:240px;">
                        <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:6px;">
                            ${statusBadge}
                            <span class="role-badge role-student">${escHtmlGlobal(r.topic || "Unknown topic")}</span>
                            ${r.type ? `<span class="role-badge" style="background:#eef2ff;color:#3730a3;">${escHtmlGlobal(formatReportedType(r.type))}</span>` : ""}
                            <span style="font-size:.72rem;color:#6b7280;">${reasonLabel}</span>
                            ${multiReportNote}
                        </div>
                        <div style="font-size:.88rem;font-weight:600;color:#111827;line-height:1.5;">${escHtmlGlobal(questionText)}</div>
                        ${goneNotice}
                        ${answerBlock}
                        ${r.notes ? `<div style="font-size:.78rem;color:#374151;margin-top:8px;padding:8px 10px;background:#f8fafc;border-radius:8px;"><strong>Student note:</strong> ${escHtmlGlobal(r.notes)}</div>` : ""}
                        <div style="font-size:.7rem;color:#9ca3af;margin-top:8px;">
                            Reported by ${escHtmlGlobal(r.reporterEmail)} · ${reportedDate}
                            ${r.reviewedBy ? ` · Reviewed by ${escHtmlGlobal(r.reviewedBy)}` : ""}
                        </div>
                        ${r.adminNote ? `<div style="font-size:.75rem;color:#5b21b6;margin-top:6px;"><strong>Admin note:</strong> ${escHtmlGlobal(r.adminNote)}</div>` : ""}
                    </div>
                    ${canAct ? `
                    <div style="display:flex;flex-direction:column;gap:6px;flex-shrink:0;" data-report-actions data-report-id="${r.id}" data-topic="${escHtmlGlobal(r.topic||'')}" data-question-gone="${questionGone}">
                        ${questionGone ? `
                        <button class="action-btn btn-block" data-review-action="DISMISSED">🧹 Clean Up</button>
                        ` : `
                        <button class="action-btn btn-unblock" data-review-action="FIXED">✓ Mark Fixed</button>
                        <button class="action-btn btn-block" data-review-action="DISMISSED">✕ Dismiss</button>
                        <button class="action-btn btn-delete" data-review-action="DELETED">🗑 Delete Question</button>
                        `}
                    </div>` : ""}
                </div>
            </div>`;
        }).join("");

        listEl.querySelectorAll("[data-report-actions]").forEach(wrap => {
            const reportId = Number(wrap.dataset.reportId);
            const topic = wrap.dataset.topic || "";
            const questionGone = wrap.dataset.questionGone === "true";
            wrap.querySelectorAll("button[data-review-action]").forEach(btn => {
                btn.addEventListener("click", () => reviewReportPrompt(reportId, btn.dataset.reviewAction, topic, questionGone));
            });
        });
    }

    function formatReportedType(type) {
        const labels = {
            MCQ: "Multiple Choice",
            TRUEFALSE: "True / False",
            MATCHING: "Matching",
            FILLBLANK: "Fill in the Blank",
            ESSAY: "Essay",
            SORTING: "Sorting",
            CONCEPTID: "Four Ideas – One Name",
            PRACTICAL: "Practical / Applied"
        };
        return labels[type] || type || "Question";
    }

    function parseReportedPayload(r) {
        if (!r || !r.payload) return {};
        if (typeof r.payload === "object") return r.payload;
        try {
            const parsed = JSON.parse(r.payload);
            return parsed && typeof parsed === "object" ? parsed : {};
        } catch (e) {
            return {};
        }
    }

    function reportedAnswerRow(label, value, correct = false) {
        if (value === null || value === undefined || String(value).trim() === "") return "";
        return `<div style="display:flex;gap:8px;align-items:flex-start;padding:5px 0;${correct ? 'color:#047857;font-weight:700;' : 'color:#374151;'}">
            <span style="width:20px;flex:0 0 20px;">${correct ? '✓' : '·'}</span>
            <span style="font-weight:600;min-width:70px;">${escHtmlGlobal(label)}</span>
            <span style="flex:1;white-space:pre-wrap;">${escHtmlGlobal(String(value))}</span>
        </div>`;
    }

    function buildReportedQuestionAnswerBlock(r) {
        const type = String(r.type || "").toUpperCase();
        const payload = parseReportedPayload(r);
        const boxStyle = "margin-top:10px;padding:10px 12px;background:#f8fafc;border:1px solid #e5e7eb;border-radius:9px;font-size:.78rem;line-height:1.45;";
        const sectionTitle = (title, color = "#1f2937") => `<div style="font-weight:800;color:${color};margin-bottom:6px;">${title}</div>`;
        let body = "";

        if (type === "MCQ" || type === "PRACTICAL") {
            const options = [
                { label: "A", value: r.optionA },
                { label: "B", value: r.optionB },
                { label: "C", value: r.optionC },
                { label: "D", value: r.optionD }
            ].filter(o => o.value !== null && o.value !== undefined && String(o.value).trim() !== "");
            // MCQ/PRACTICAL have a normalized text answer in Question.correctAnswer.
            // Fall back to payload.correctAnswer for legacy/incomplete rows.
            const correct = r.correctAnswer || payload.correctAnswer || "";
            body = sectionTitle("✓ Correct answer", "#047857") +
                options.map(o => reportedAnswerRow(o.label, o.value, String(o.value).trim().toLowerCase() === String(correct).trim().toLowerCase())).join("") +
                (!options.length ? reportedAnswerRow("Answer", correct, true) : "");

            if (type === "PRACTICAL" && payload.kind) {
                body += `<div style="margin-top:6px;color:#6b7280;"><strong>Question kind:</strong> ${escHtmlGlobal(payload.kind)}</div>`;
            }
        } else if (type === "TRUEFALSE") {
            const correct = payload.correctAnswer || r.correctAnswer || "";
            body = sectionTitle("✓ Correct answer", "#047857") +
                reportedAnswerRow("True", "True", String(correct).trim().toLowerCase() === "true") +
                reportedAnswerRow("False", "False", String(correct).trim().toLowerCase() === "false");
        } else if (type === "MATCHING") {
            const left = Array.isArray(payload.leftItems) ? payload.leftItems : [];
            const right = Array.isArray(payload.rightItems) ? payload.rightItems : [];
            const pairs = Array.isArray(payload.correctPairs) ? payload.correctPairs : [];
            body = sectionTitle("✓ Correct matches", "#047857");
            if (pairs.length) {
                body += pairs.map(pair => {
                    const li = Number(pair?.[0]);
                    const ri = Number(pair?.[1]);
                    const l = Number.isInteger(li) && left[li] !== undefined ? left[li] : `Left item ${li + 1}`;
                    const rr = Number.isInteger(ri) && right[ri] !== undefined ? right[ri] : `Right item ${ri + 1}`;
                    return `<div style="display:flex;gap:8px;align-items:flex-start;padding:5px 0;">
                        <span style="color:#047857;font-weight:800;">✓</span>
                        <span style="flex:1;white-space:pre-wrap;"><strong>${escHtmlGlobal(String(l))}</strong> <span style="color:#9ca3af;">↔</span> ${escHtmlGlobal(String(rr))}</span>
                    </div>`;
                }).join("");
            } else {
                body += `<div style="color:#b45309;">No correctPairs were stored for this question.</div>`;
            }
            if (left.length || right.length) {
                body += `<details style="margin-top:7px;"><summary style="cursor:pointer;color:#64748b;">Show raw matching choices</summary>
                    <div style="margin-top:7px;"><strong>Left:</strong> ${left.map((x,i)=>`${i+1}. ${escHtmlGlobal(String(x))}`).join(" · ") || "—"}</div>
                    <div style="margin-top:5px;"><strong>Right:</strong> ${right.map((x,i)=>`${i+1}. ${escHtmlGlobal(String(x))}`).join(" · ") || "—"}</div>
                </details>`;
            }
        } else if (type === "FILLBLANK") {
            const excerpt = payload.excerpt || "";
            const blanks = Array.isArray(payload.blanks) ? payload.blanks : [];
            body = sectionTitle("✓ Correct answers", "#047857");
            if (excerpt) {
                body += `<div style="padding:8px 10px;background:#fff;border:1px solid #e5e7eb;border-radius:7px;white-space:pre-wrap;line-height:1.55;"><strong>Excerpt:</strong> ${escHtmlGlobal(String(excerpt))}</div>`;
            }
            body += blanks.length
                ? blanks.map(b => reportedAnswerRow(`Blank ${b?.id ?? ""}`, b?.answer, true)).join("")
                : `<div style="color:#b45309;margin-top:6px;">No blank answer key was stored.</div>`;
            if (payload.mode) body += `<div style="margin-top:6px;color:#6b7280;"><strong>Mode:</strong> ${escHtmlGlobal(String(payload.mode))}</div>`;
        } else if (type === "ESSAY") {
            const rubric = Array.isArray(payload.rubric) ? payload.rubric : [];
            body = sectionTitle("✓ Expected answer / grading rubric", "#047857");
            body += rubric.length
                ? `<ol style="margin:0;padding-left:22px;">${rubric.map(x => `<li style="padding:3px 0;white-space:pre-wrap;">${escHtmlGlobal(String(x))}</li>`).join("")}</ol>`
                : `<div style="color:#b45309;">No essay rubric was stored.</div>`;
            body += `<div style="margin-top:8px;color:#6b7280;font-style:italic;">Essay questions do not have one exact text answer; the rubric above is the answer key.</div>`;
        } else if (type === "SORTING") {
            const items = Array.isArray(payload.items) ? payload.items : [];
            body = sectionTitle("✓ Correct classification", "#047857");
            if (payload.categoryA || payload.categoryB) {
                body += `<div style="margin-bottom:7px;"><strong>Categories:</strong> ${escHtmlGlobal(String(payload.categoryA || "A"))} / ${escHtmlGlobal(String(payload.categoryB || "B"))}</div>`;
            }
            body += items.length
                ? items.map(item => {
                    const cat = String(item?.correctCategory || "").toUpperCase();
                    const label = cat === "A" ? payload.categoryA : cat === "B" ? payload.categoryB : item?.correctCategory;
                    return reportedAnswerRow(String(item?.text ?? "Item"), label || "—", true);
                }).join("")
                : `<div style="color:#b45309;">No sorting answer key was stored.</div>`;
        } else if (type === "CONCEPTID") {
            const clues = Array.isArray(payload.clues) ? payload.clues : [];
            const correct = payload.correctAnswer || r.correctAnswer || "";
            const accepted = Array.isArray(payload.acceptedAnswers) ? payload.acceptedAnswers : [];
            body = sectionTitle("✓ Correct answer", "#047857") + reportedAnswerRow("Canonical", correct, true);
            if (accepted.length) {
                body += `<div style="margin-top:7px;font-weight:700;color:#374151;">Accepted answers:</div>` +
                    accepted.map((x, i) => `<div style="padding:3px 0 3px 28px;">• ${escHtmlGlobal(String(x))}</div>`).join("");
            }
            body += `<div style="margin-top:10px;">${sectionTitle("Clues", "#374151")}` +
                (clues.length
                    ? `<ol style="margin:0;padding-left:22px;">${clues.map(x => `<li style="padding:3px 0;white-space:pre-wrap;">${escHtmlGlobal(String(x))}</li>`).join("")}</ol>`
                    : `<div style="color:#b45309;">No clues were stored.</div>`) + `</div>`;
        } else {
            // Future-proof fallback: show any answer key in the payload instead of silently
            // hiding it when a new question type is added.
            const answerKeys = ["correctAnswer", "acceptedAnswers", "correctPairs", "rubric", "blanks", "items", "leftItems", "rightItems", "clues"]
                .filter(key => payload[key] !== undefined && payload[key] !== null);
            body = sectionTitle("✓ Stored answer key", "#047857");
            body += answerKeys.length
                ? answerKeys.map(key => `<div style="padding:4px 0;"><strong>${escHtmlGlobal(key)}:</strong> <span style="white-space:pre-wrap;">${escHtmlGlobal(typeof payload[key] === "string" ? payload[key] : JSON.stringify(payload[key], null, 2))}</span></div>`).join("")
                : `<div style="color:#b45309;">No structured answer key was stored for this question type.</div>`;
        }

        // Common metadata is useful for every question type and gives the admin the
        // same context the student sees when deciding whether a report is valid.
        if (r.hint) body += `<div style="margin-top:10px;padding-top:8px;border-top:1px solid #e5e7eb;color:#6b7280;"><strong>Hint:</strong> ${escHtmlGlobal(r.hint)}</div>`;
        if (r.explanation) body += `<div style="margin-top:6px;color:#374151;"><strong>Explanation:</strong> ${escHtmlGlobal(r.explanation)}</div>`;

        return `<div style="${boxStyle}">${body}</div>`;
    }

    function reviewReportPrompt(reportId, action, topic, questionGone) {
        const actionLabel = action === "DISMISSED" && questionGone
            ? "Clean up this report"
            : { FIXED: "Mark as Fixed", DISMISSED: "Dismiss this report", DELETED: "Delete the question" }[action];
        const warnBlock = action === "DELETED"
            ? `<div style="font-size:.82rem;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:12px 14px;margin-bottom:14px;color:#7f1d1d;line-height:1.6;">
                 ⚠️ This permanently deletes the question from <strong>${escHtmlGlobal(topic)}</strong> for every student. Any other pending reports on the same question will be auto-resolved too. This cannot be undone.
               </div>`
            : "";
        showModal(`
    <div class="modal-title">${actionLabel}</div>
    ${warnBlock}
    <div class="ip-form-field" style="width:100%;margin-bottom:14px;">
      <label>Admin note (optional)</label>
      <textarea id="reviewAdminNote" rows="2" style="width:100%;padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;resize:none;box-sizing:border-box;" placeholder="e.g. Corrected the answer to option C..."></textarea>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="${action === 'DELETED' ? 'background:#ef4444;border-color:#ef4444;' : ''}" id="confirmReviewBtn">${actionLabel}</button>
    </div>
    <div id="reviewModalStatus" style="display:none; margin-top:12px; font-size:0.8rem; padding:8px 12px; border-radius:8px;"></div>`);
        document.getElementById("confirmReviewBtn").onclick = () => confirmReviewReport(reportId, action);
    }

    async function confirmReviewReport(reportId, action) {
        const adminNote = document.getElementById("reviewAdminNote")?.value.trim() || "";
        const btn = document.getElementById("confirmReviewBtn");
        btn.disabled = true;
        btn.textContent = "Working...";
        try {
            await apiFetch(`/api/admin/reports/${reportId}/review`, {
                method: "POST",
                body: JSON.stringify({ action, adminNote })
            });
            closeModal();
            await loadReportedQuestions();
            await refreshReportedBadge();
        } catch (err) {
            btn.disabled = false;
            btn.textContent = "Retry";
            setModalInlineStatus("reviewModalStatus", "Failed: " + (err.message || "Unknown error"), "error");
        }
    }
