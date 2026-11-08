/**
 * replace-warning.js
 *
 * One shared "this will replace your current question set" warning, used by every STUDENT-side action that
 * replaces a question set: Adapted Quiz, Target Problems (Quiz Hub and the quiz-complete screen), uploading a new
 * handout under an existing topic, and sending an assigned handout.
 *
 *   ReplaceWarning.confirm("adapted", topic)            -> Promise<boolean>  (always warns, unless switched off)
 *   ReplaceWarning.confirmIfSetExists("upload", topic)  -> Promise<boolean>  (warns only if a set really exists)
 *
 * Resolves true to go ahead, false if the student cancelled.
 *
 * The warning is ON by default. The student can tick "Don't show this warning again" and it stays off (saved on
 * their account, so it follows them across devices) until they switch it back on under Profile.
 * Admin sessions never see it (admins aren't students; the admin AI-testing sandbox embeds quizpage.html).
 *
 * Layout: a centred dialog on desktop, a bottom sheet on phones (<= 768px, same breakpoint as the rest of the app).
 * Requires api.js (apiFetch). Safe to include more than once.
 */
(function () {
    "use strict";
    if (window.ReplaceWarning) return;

    var STYLE_ID = "rw-style";
    var CSS = [
        ".rw-overlay{position:fixed;inset:0;z-index:10000;background:rgba(15,23,42,.55);display:flex;align-items:center;justify-content:center;padding:20px;}",
        ".rw-dialog{box-sizing:border-box;background:#fff;color:#111827;border-radius:20px;padding:28px 28px 24px;width:100%;max-width:420px;max-height:calc(100vh - 40px);max-height:calc(100dvh - 40px);overflow-y:auto;box-shadow:0 24px 60px rgba(0,0,0,.2);font-family:inherit;}",
        ".rw-icon{font-size:1.5rem;margin-bottom:10px;}",
        ".rw-title{font-size:1rem;font-weight:800;color:#111827;margin-bottom:8px;}",
        ".rw-intro{font-size:.85rem;color:#6b7280;line-height:1.6;margin-bottom:8px;overflow-wrap:anywhere;}",
        ".rw-warning{font-size:.82rem;color:#92400e;background:#fef3c7;border:1px solid #fcd34d;border-radius:10px;padding:10px 12px;margin-bottom:14px;line-height:1.55;}",
        ".rw-remember{display:flex;align-items:flex-start;gap:10px;font-size:.82rem;color:#374151;line-height:1.45;cursor:pointer;margin-bottom:4px;min-height:24px;}",
        ".rw-remember input{width:18px;height:18px;margin:1px 0 0;flex:0 0 auto;accent-color:#0284c7;cursor:pointer;}",
        ".rw-hint{font-size:.74rem;color:#9ca3af;line-height:1.4;margin:0 0 18px 28px;}",
        ".rw-actions{display:flex;gap:10px;}",
        ".rw-btn{flex:1;min-height:44px;padding:11px;border-radius:12px;font-weight:800;cursor:pointer;font-size:.88rem;font-family:inherit;}",
        ".rw-btn[disabled]{opacity:.6;cursor:default;}",
        ".rw-cancel{border:1px solid #e2e8f0;background:#f8fafc;color:#374151;font-weight:700;}",
        ".rw-confirm{border:none;background:linear-gradient(135deg,#0284c7,#7c3aed);color:#fff;box-shadow:0 4px 12px rgba(2,132,199,.3);}",
        ".rw-btn:focus-visible,.rw-remember input:focus-visible{outline:3px solid #93c5fd;outline-offset:2px;}",
        ".rw-status{display:none;margin-top:12px;font-size:.8rem;padding:8px 12px;border-radius:8px;background:#fef2f2;color:#991b1b;border:1px solid #fecaca;}",
        "@keyframes rw-sheet-in{from{transform:translateY(24px);opacity:0}to{transform:none;opacity:1}}",
        "@media (max-width:768px){",
        "  .rw-overlay{align-items:flex-end;padding:0;}",
        "  .rw-dialog{max-width:none;border-radius:20px 20px 0 0;padding:22px 20px calc(18px + env(safe-area-inset-bottom,0px));max-height:90vh;max-height:90dvh;animation:rw-sheet-in .22s ease-out;}",
        "}",
        "@media (max-width:380px){ .rw-actions{flex-direction:column-reverse;} }",
        "@media (prefers-reduced-motion:reduce){ .rw-dialog{animation:none;} }"
    ].join("\n");

    function injectStyle() {
        if (document.getElementById(STYLE_ID)) return;
        var st = document.createElement("style");
        st.id = STYLE_ID;
        st.textContent = CSS;
        document.head.appendChild(st);
    }

    // Segments are plain strings or {b: "bold text"}; built with text nodes so a topic name can never inject markup.
    function fill(node, segments) {
        (segments || []).forEach(function (seg) {
            if (typeof seg === "string") node.appendChild(document.createTextNode(seg));
            else if (seg && seg.b != null) {
                var strong = document.createElement("strong");
                strong.textContent = String(seg.b);
                node.appendChild(strong);
            }
        });
        return node;
    }

    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    }

    var REPLACE_LINE = ["⚠️ This will ", { b: "replace the current question set" }, " for this topic."];

    var PRESETS = {
        adapted: function (topic) {
            return {
                icon: "🧠", title: "Start Adapted Quiz?", confirmLabel: "Yes, Generate It",
                intro: ["Claude will generate a ", { b: "new set of questions" }, " tailored to your most recent score on ", { b: topic }, "."],
                warning: REPLACE_LINE.concat([" The difficulty will be locked to your adapted performance level until you take the next adapted quiz."])
            };
        },
        targeted: function (topic) {
            return {
                icon: "🎯", title: "Start Target Problems?", confirmLabel: "Yes, Generate It",
                intro: ["Claude will generate ", { b: "new questions" }, " aimed at your weak spots in ", { b: topic }, "."],
                warning: REPLACE_LINE
            };
        },
        upload: function (topic) {
            return {
                icon: "📄", title: "Upload to an existing topic?", confirmLabel: "Yes, Upload It",
                intro: ["You already have a topic called ", { b: topic }, ". The new file will replace its current handout."],
                warning: ["⚠️ If it's a different handout, this will ", { b: "replace the current question set" }, " for this topic with new questions. Uploading the same handout again keeps your current questions."]
            };
        },
        assigned: function (topic) {
            return {
                icon: "📄", title: "Build your quiz from this handout?", confirmLabel: "Yes, Send It",
                intro: ["Claude will generate questions from the handout assigned for ", { b: topic }, "."],
                warning: REPLACE_LINE
            };
        }
    };

    var prefsPromise = null;
    function loadPrefs() {
        if (!prefsPromise) {
            prefsPromise = apiFetch("/api/me").then(function (me) {
                return { skip: !!(me && (me.isAdmin || me.warnBeforeReplace === false)) };
            }).catch(function () {
                prefsPromise = null;          // couldn't read it: warn this time (the safe default) and try again next time
                return { skip: false };
            });
        }
        return prefsPromise;
    }

    function saveDontAskAgain() {
        return apiFetch("/api/account/preferences", {
            method: "POST",
            body: JSON.stringify({ warnBeforeReplace: false })
        }).then(function () {
            prefsPromise = Promise.resolve({ skip: true });
            return true;
        });
    }

    function showDialog(content) {
        injectStyle();
        return new Promise(function (resolve) {
            var opener = document.activeElement;
            var overlay = el("div", "rw-overlay");
            var dlg = el("div", "rw-dialog");
            dlg.setAttribute("role", "dialog");
            dlg.setAttribute("aria-modal", "true");
            var uid = "rw" + Date.now();
            dlg.setAttribute("aria-labelledby", uid + "t");
            dlg.setAttribute("aria-describedby", uid + "d");

            var title = el("div", "rw-title", content.title);
            title.id = uid + "t";
            var body = el("div", null);
            body.id = uid + "d";
            body.appendChild(fill(el("div", "rw-intro"), content.intro));
            body.appendChild(fill(el("div", "rw-warning"), content.warning));

            var remember = el("label", "rw-remember");
            var box = document.createElement("input");
            box.type = "checkbox";
            remember.appendChild(box);
            remember.appendChild(el("span", null, "Don't show this warning again"));
            var hint = el("div", "rw-hint", "You can turn it back on any time in Profile.");

            var cancel = el("button", "rw-btn rw-cancel", "Cancel");
            cancel.type = "button";
            var confirmBtn = el("button", "rw-btn rw-confirm", content.confirmLabel || "Continue");
            confirmBtn.type = "button";
            var actions = el("div", "rw-actions");
            actions.appendChild(cancel);
            actions.appendChild(confirmBtn);
            var status = el("div", "rw-status");
            status.setAttribute("role", "status");

            dlg.appendChild(el("div", "rw-icon", content.icon || "⚠️"));
            dlg.appendChild(title);
            dlg.appendChild(body);
            dlg.appendChild(remember);
            dlg.appendChild(hint);
            dlg.appendChild(actions);
            dlg.appendChild(status);
            overlay.appendChild(dlg);

            var prevOverflow = document.body.style.overflow;
            document.body.style.overflow = "hidden";
            document.body.appendChild(overlay);
            cancel.focus();                  // the non-destructive choice gets focus first

            var finished = false;
            function close(result) {
                if (finished) return;
                finished = true;
                document.removeEventListener("keydown", onKey, true);
                overlay.remove();
                document.body.style.overflow = prevOverflow;
                if (opener && typeof opener.focus === "function") { try { opener.focus(); } catch (e) {} }
                resolve(result);
            }

            function onKey(e) {
                if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); close(false); return; }
                if (e.key !== "Tab") return;
                var f = [cancel, box, confirmBtn].filter(function (x) { return !x.disabled; });
                if (!f.length) return;
                var i = f.indexOf(document.activeElement);
                if (e.shiftKey && (i <= 0)) { e.preventDefault(); f[f.length - 1].focus(); }
                else if (!e.shiftKey && (i === -1 || i === f.length - 1)) { e.preventDefault(); f[0].focus(); }
            }
            document.addEventListener("keydown", onKey, true);

            overlay.addEventListener("click", function (e) { if (e.target === overlay) close(false); });
            cancel.addEventListener("click", function () { close(false); });

            confirmBtn.addEventListener("click", function () {
                if (!box.checked) { close(true); return; }
                // Only remembered when they actually go ahead. A failed save never blocks the action they asked for.
                confirmBtn.disabled = true; cancel.disabled = true; box.disabled = true;
                saveDontAskAgain().catch(function () { /* they will simply be asked again next time */ })
                    .then(function () { close(true); });
            });
        });
    }

    function contentFor(kind, topic) {
        var make = PRESETS[kind];
        if (!make) throw new Error("ReplaceWarning: unknown kind \"" + kind + "\"");
        return make(String(topic == null ? "" : topic));
    }

    window.ReplaceWarning = {
        /** Always warns (unless switched off / admin). Use where a question set certainly exists. */
        confirm: function (kind, topic) {
            var content = contentFor(kind, topic);
            return loadPrefs().then(function (p) { return p.skip ? true : showDialog(content); });
        },
        /** Warns only if the student really has a question set for this topic; a first-time topic replaces nothing. */
        confirmIfSetExists: function (kind, topic) {
            var content = contentFor(kind, topic);
            return loadPrefs().then(function (p) {
                if (p.skip) return true;
                return apiFetch("/api/quiz/set-status?topic=" + encodeURIComponent(topic))
                    .then(function (r) { return !(r && r.exists === false); })
                    .catch(function () { return true; })           // can't tell: warn rather than risk a silent replace
                    .then(function (needsWarning) { return needsWarning ? showDialog(content) : true; });
            });
        }
    };
})();
