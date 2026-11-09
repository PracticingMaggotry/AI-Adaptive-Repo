/*
 * Concept progress — student pages only (dashboard, quiz hub, quiz result, learning hub).
 *
 * One module renders every concept-progress surface so the same four statuses always look and read the same:
 *   Needs practice / Almost there / Got it / Not tried yet   (+ "Fixed" and "Review soon" tags)
 * The server (ConceptInsights) has already turned the learner model into these statuses and plain-language
 * evidence sentences; nothing here sees probabilities or the internal weak/slip/shaky states.
 *
 * Everything is built with DOM calls and textContent, never innerHTML: concept names, definitions and answers come
 * from uploaded handouts and must not be interpreted as markup.
 *
 * Depends on api.js (apiFetch) when present; falls back to fetch otherwise. If a request fails the component hides
 * itself: concept progress is an extra, and a broken extra must never break the page around it.
 */
(function () {
    "use strict";

    var S = {
        needs_practice: { key: "needsPractice", cls: "needs",  icon: "!",      label: "Needs practice" },
        almost_there:   { key: "almostThere",   cls: "almost", icon: "\u2197", label: "Almost there" },
        got_it:         { key: "gotIt",         cls: "got",    icon: "\u2713", label: "Got it" },
        not_tried:      { key: "notTried",      cls: "none",   icon: "\u25CB", label: "Not tried yet" }
    };
    /** Bar and legend: progress reads left to right. */
    var BAR_ORDER = ["got_it", "almost_there", "needs_practice", "not_tried"];
    /** Lists: what to do first goes first. */
    var LIST_ORDER = ["needs_practice", "almost_there", "got_it", "not_tried"];
    var PREVIEW_ROWS = 5;
    var uid = 0;

    function h(tag, props, kids) {
        var el = document.createElement(tag);
        if (props) Object.keys(props).forEach(function (k) {
            var v = props[k];
            if (v === null || v === undefined || v === false) return;
            if (k === "class") el.className = v;
            else if (k === "text") el.textContent = v;
            else if (k === "hidden") el.hidden = true;
            else el.setAttribute(k, v === true ? "" : v);
        });
        (kids || []).forEach(function (c) {
            if (c) el.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
        });
        return el;
    }

    function add(parent, child) { if (child) parent.appendChild(child); return parent; }

    function plural(n, one, many) { return n + " " + (n === 1 ? one : many); }

    function fetchJson(path) {
        if (typeof apiFetch === "function") return apiFetch(path);
        return fetch(path, { credentials: "include" }).then(function (r) {
            if (!r.ok) throw new Error("HTTP " + r.status);
            return r.json();
        });
    }

    function practiceHref(topic) { return "quizhub.html?focus=" + encodeURIComponent(topic); }
    function conceptsHref(topic) { return "learninghub.html?concepts=" + encodeURIComponent(topic); }

    /** The one-line takeaway above the bar. Leads with progress, then the single thing that needs attention. */
    function headline(c) {
        if (!c || !c.total) return "";
        if (c.gotIt === c.total) return "You\u2019ve got all " + c.total + " concepts.";
        var needs = c.needsPractice > 0
            ? c.needsPractice + (c.needsPractice === 1 ? " needs" : " need") + " practice" : "";
        if (c.gotIt > 0) {
            return "You\u2019ve got " + c.gotIt + " of " + c.total + " concepts" + (needs ? " \u00b7 " + needs : "");
        }
        if (needs) return c.needsPractice + " of " + c.total + " concepts " + (c.needsPractice === 1 ? "needs" : "need") + " practice";
        return "Just getting started \u2014 " + (c.total - c.notTried) + " of " + c.total + " concepts tried";
    }

    function countText(c, status) {
        var n = c[S[status].key];
        if (status === "needs_practice") return n + (n === 1 ? " needs practice" : " need practice");
        if (status === "almost_there") return n + " almost there";
        if (status === "got_it") return n + " got it";
        return n + " not tried yet";
    }

    function barLabel(c) {
        return BAR_ORDER.filter(function (s) { return c[S[s].key] > 0; })
            .map(function (s) { return countText(c, s); }).join(", ");
    }

    function barEl(c, mini) {
        if (!c || !c.total) return null;
        var bar = h("div", { class: "cp-bar" + (mini ? " cp-bar--mini" : ""), role: "img", "aria-label": barLabel(c) });
        BAR_ORDER.forEach(function (s) {
            var n = c[S[s].key];
            if (n <= 0) return;
            var seg = h("span", { class: "cp-seg cp-seg--" + S[s].cls });
            seg.style.flex = n + " 1 0";
            bar.appendChild(seg);
        });
        return bar;
    }

    function legendEl(c) {
        var ul = h("ul", { class: "cp-legend" });
        BAR_ORDER.forEach(function (s) {
            var n = c[S[s].key];
            if (n <= 0) return;
            ul.appendChild(h("li", null, [
                h("span", { class: "cp-dot cp-dot--" + S[s].cls, "aria-hidden": "true" }),
                S[s].label + " ",
                h("b", { text: String(n) })
            ]));
        });
        return ul;
    }

    function chipEl(status) {
        var s = S[status];
        return h("span", { class: "cp-chip cp-chip--" + s.cls }, [
            h("span", { class: "cp-chip-ico", "aria-hidden": "true", text: s.icon }),
            s.label
        ]);
    }

    function hasDetail(item) { return !!(item.definition || item.lastMistake); }

    function detailEl(item, id) {
        var box = h("div", { class: "cp-detail", id: id, hidden: true });
        if (item.definition) {
            box.appendChild(h("p", null, [h("b", { text: "In your handout: " }), item.definition]));
        }
        if (item.lastMistake) {
            var m = item.lastMistake;
            if (m.question) box.appendChild(h("p", null, [h("b", { text: "Last question you missed: " }), m.question]));
            if (m.yourAnswer) box.appendChild(h("p", null, [h("b", { text: "You answered: " }), m.yourAnswer]));
            if (m.correctAnswer) box.appendChild(h("p", null, [h("b", { text: "Correct answer: " }), m.correctAnswer]));
        }
        return box;
    }

    /**
     * One concept. The status is carried by the group or list it sits in (and by its heading text), so a row only
     * adds what is specific to it: the term, one plain sentence of evidence, and optional tags and details.
     */
    function rowEl(item, opts) {
        opts = opts || {};
        var li = h("li", { class: "cp-row" });
        var main = h("div", { class: "cp-row-main" }, [h("span", { class: "cp-term", text: item.term })]);
        if (item.fixed) main.appendChild(h("span", { class: "cp-tag cp-tag--fixed", text: "Fixed" }));
        if (item.reviewSoon) main.appendChild(h("span", { class: "cp-tag cp-tag--review", text: "Review soon" }));
        if (opts.topic) main.appendChild(h("span", { class: "cp-topic", text: "in " + opts.topic }));
        li.appendChild(main);
        if (item.evidence && !opts.hideEvidence) li.appendChild(h("p", { class: "cp-evidence", text: item.evidence }));
        if (opts.practiceTopic) {
            li.appendChild(h("a", {
                class: "cp-link", href: practiceHref(opts.practiceTopic),
                "aria-label": "Practice " + item.term + " in " + opts.practiceTopic, text: "Practice \u2192"
            }));
        }
        if (opts.details !== false && hasDetail(item)) {
            var id = "cp-d" + (++uid);
            var btn = h("button", {
                type: "button", class: "cp-more", "aria-expanded": "false", "aria-controls": id,
                "aria-label": "Show details for " + item.term, text: "Show details"
            });
            var box = detailEl(item, id);
            btn.addEventListener("click", function () {
                var open = box.hidden;
                box.hidden = !open;
                btn.setAttribute("aria-expanded", open ? "true" : "false");
                btn.setAttribute("aria-label", (open ? "Hide" : "Show") + " details for " + item.term);
                btn.textContent = open ? "Hide details" : "Show details";
            });
            li.appendChild(btn);
            li.appendChild(box);
        }
        return li;
    }

    /** A collapsible group of concepts that share a status. Only the first few rows show until asked. */
    function groupEl(status, items, expanded) {
        var s = S[status];
        var id = "cp-g" + (++uid);
        var sec = h("section", { class: "cp-group cp-group--" + s.cls });
        var btn = h("button", {
            type: "button", class: "cp-group-btn", "aria-expanded": expanded ? "true" : "false", "aria-controls": id
        }, [
            chipEl(status),
            h("span", { class: "cp-group-title" }),
            h("span", { class: "cp-count", text: plural(items.length, "concept", "concepts") }),
            h("span", { class: "cp-chev", "aria-hidden": "true", text: "\u25BE" })
        ]);
        sec.appendChild(h("h4", { class: "cp-group-h" }, [btn]));

        var body = h("div", { id: id, hidden: !expanded });
        var ul = h("ul", { class: "cp-list" });
        items.forEach(function (it, i) {
            var row = rowEl(it, { hideEvidence: status === "not_tried" });
            if (i >= PREVIEW_ROWS) row.hidden = true;
            ul.appendChild(row);
        });
        body.appendChild(ul);
        if (items.length > PREVIEW_ROWS) {
            var more = h("button", {
                type: "button", class: "cp-showmore",
                text: "Show " + (items.length - PREVIEW_ROWS) + " more"
            });
            more.addEventListener("click", function () {
                ul.querySelectorAll("li[hidden]").forEach(function (li) { li.hidden = false; });
                more.hidden = true;
            });
            body.appendChild(more);
        }
        sec.appendChild(body);

        btn.addEventListener("click", function () {
            var open = body.hidden;
            body.hidden = !open;
            btn.setAttribute("aria-expanded", open ? "true" : "false");
        });
        return sec;
    }

    function byStatus(items) {
        var g = { needs_practice: [], almost_there: [], got_it: [], not_tried: [] };
        (items || []).forEach(function (it) { if (g[it.status]) g[it.status].push(it); });
        return g;
    }

    function emptyEl(text, cta) {
        var box = h("div", { class: "cp-empty" }, [h("p", { text: text })]);
        if (cta) box.appendChild(h("p", null, [h("a", { class: "cp-btn cp-btn--primary", href: cta.href, text: cta.text })]));
        return box;
    }

    /** Summary, one clear next action, then every concept grouped by status with the urgent group open. */
    function renderTopicPanel(host, data) {
        host.textContent = "";
        host.classList.add("cp");
        if (!data || !data.hasConcepts || !data.counts || !data.counts.total) { host.hidden = true; return; }
        host.hidden = false;
        var c = data.counts;

        if (!data.hasActivity) {
            host.appendChild(emptyEl(
                "No quiz taken on this handout yet. After your first quiz, each of its " + c.total +
                " concepts is sorted here by how it went.",
                { href: "quizhub.html", text: "Go to Quiz Hub" }));
            return;
        }

        host.appendChild(h("p", { class: "cp-headline", text: headline(c) }));
        add(host, barEl(c));
        host.appendChild(legendEl(c));

        if (c.needsPractice > 0) {
            host.appendChild(h("div", { class: "cp-actions" }, [
                h("a", { class: "cp-btn cp-btn--primary", href: practiceHref(data.topic), text: "Practice these concepts" }),
                h("span", { class: "cp-note", text: "Builds questions aimed at what you missed." })
            ]));
        }

        var g = byStatus(data.items);
        var open = g.needs_practice.length ? "needs_practice" : (g.almost_there.length ? "almost_there" : null);
        var groups = h("div", { class: "cp-groups" });
        LIST_ORDER.forEach(function (st) {
            if (g[st].length) groups.appendChild(groupEl(st, g[st], st === open));
        });
        host.appendChild(groups);
    }

    /**
     * Good news first, then at most three concepts to practise, then one action. On wide screens the summary sits
     * beside the actions so the card stays short; on narrow screens the two stack in the same order.
     */
    function renderResultCard(cardEl, bodyEl, data) {
        bodyEl.textContent = "";
        bodyEl.classList.add("cp");
        if (!data || !data.hasConcepts || !data.hasActivity || !data.counts || !data.counts.total) { cardEl.hidden = true; return; }
        cardEl.hidden = false;
        var c = data.counts;
        var items = data.items || [];
        var main = h("div", { class: "cp-split-main" });
        var side = h("div", { class: "cp-split-side" });

        var fixed = items.filter(function (i) { return i.fixed; });
        if (fixed.length) {
            var names = fixed.slice(0, 3).map(function (i) { return i.term; }).join(", ");
            var extra = fixed.length > 3 ? " and " + (fixed.length - 3) + " more" : "";
            main.appendChild(h("div", { class: "cp-win", role: "status" }, [
                h("span", { "aria-hidden": "true", text: "\u2713" }),
                h("span", { text: (fixed.length === 1 ? "You fixed a concept you missed before: " : "You fixed " + fixed.length + " concepts you missed before: ") + names + extra + "." })
            ]));
        }
        main.appendChild(h("p", { class: "cp-headline", text: headline(c) }));
        add(main, barEl(c));
        main.appendChild(legendEl(c));

        if (data.focus && data.focus.length) {
            side.appendChild(h("h3", { class: "cp-focus-h", text: "Practice next" }));
            var ul = h("ul", { class: "cp-focus" });
            data.focus.forEach(function (it) { ul.appendChild(rowEl(it, { details: false })); });
            side.appendChild(ul);
            var more = c.needsPractice - data.focus.length;
            if (more > 0) side.appendChild(h("p", { class: "cp-note", text: "+ " + more + " more on the full list." }));
            side.appendChild(h("div", { class: "cp-actions" }, [
                h("a", { class: "cp-btn cp-btn--primary", href: practiceHref(data.topic), text: "Practice these concepts" }),
                h("a", { class: "cp-btn cp-btn--ghost", href: conceptsHref(data.topic), text: "See all concepts" })
            ]));
        } else {
            var msg = c.almostThere > 0
                ? "Nothing needs practice right now. " + plural(c.almostThere, "concept is", "concepts are") + " almost there \u2014 another quiz should lock them in."
                : "Nothing needs practice right now. Nice work.";
            side.appendChild(h("p", { class: "cp-sub", text: msg }));
            side.appendChild(h("div", { class: "cp-actions" }, [
                h("a", { class: "cp-btn cp-btn--ghost", href: conceptsHref(data.topic), text: "See all concepts" })
            ]));
        }
        bodyEl.appendChild(h("div", { class: "cp-split" }, [main, side]));
    }

    function renderFocusCard(bodyEl, sum) {
        bodyEl.textContent = "";
        bodyEl.classList.add("cp");
        if (!sum || !sum.totals || !sum.totals.total) {
            bodyEl.appendChild(emptyEl("Concepts from your handouts will show up here once you have a handout and a quiz.", { href: "quizhub.html", text: "Go to Quiz Hub" }));
            return;
        }
        if (!sum.hasActivity) {
            bodyEl.appendChild(emptyEl("Take your first quiz and your concepts will be sorted here by how it went.", { href: "quizhub.html", text: "Go to Quiz Hub" }));
            return;
        }
        var active = (sum.topics || []).filter(function (x) { return x.hasActivity; });
        var view = h("div");

        function draw(topicName) {
            view.textContent = "";
            var t = sum.totals, focus = sum.focus || [], handouts = active.length, href = "learninghub.html";
            var topic = topicName ? findTopic(sum, topicName) : null;
            if (topic) {
                t = topic.counts; focus = topic.focus || []; handouts = 1; href = conceptsHref(topic.topic);
            }
            view.appendChild(h("p", { class: "cp-headline", text: headline(t) }));
            if (handouts > 1) view.appendChild(h("p", { class: "cp-sub", text: "Across " + handouts + " handouts." }));
            add(view, barEl(t));
            view.appendChild(legendEl(t));

            if (focus.length) {
                view.appendChild(h("h3", { class: "cp-focus-h", text: "Practice next" }));
                var ul = h("ul", { class: "cp-focus" });
                focus.forEach(function (f) {
                    ul.appendChild(rowEl({ term: f.term, evidence: f.evidence }, {
                        topic: handouts > 1 ? f.topic : null, details: false, practiceTopic: f.topic
                    }));
                });
                view.appendChild(ul);
                if (t.needsPractice > focus.length) {
                    view.appendChild(h("p", { class: "cp-note", text: "+ " + (t.needsPractice - focus.length) + " more in the Learning Hub." }));
                }
            } else {
                view.appendChild(h("p", { class: "cp-sub", text: "Nothing needs practice right now. Nice work." }));
            }
            view.appendChild(h("div", { class: "cp-actions" }, [
                h("a", { class: "cp-btn cp-btn--ghost", href: href, text: "See all concepts" })
            ]));
        }

        if (active.length > 1) {
            var id = "cp-f" + (++uid);
            var sel = h("select", { class: "cp-filter", id: id, "aria-label": "Filter concepts by topic" });
            sel.appendChild(h("option", { value: "", text: "All topics" }));
            active.forEach(function (x) { sel.appendChild(h("option", { value: x.topic, text: x.topic })); });
            sel.addEventListener("change", function () { draw(sel.value); });
            bodyEl.appendChild(h("div", { class: "cp-filter-row" }, [sel]));
        }
        bodyEl.appendChild(view);
        draw("");
    }

    var summaryPromise = null;
    function loadSummary() {
        if (!summaryPromise) {
            summaryPromise = fetchJson("/api/concepts/summary").catch(function (e) {
                console.warn("Concept summary unavailable:", e);
                return null;
            });
        }
        return summaryPromise;
    }

    function findTopic(sum, name) {
        if (!sum || !sum.topics || !name) return null;
        var key = String(name).toLowerCase();
        for (var i = 0; i < sum.topics.length; i++) {
            if (String(sum.topics[i].topic).toLowerCase() === key) return sum.topics[i];
        }
        return null;
    }

    function inlineText(c) {
        return LIST_ORDER.filter(function (s) { return c[S[s].key] > 0; })
            .map(function (s) { return countText(c, s); }).join(" \u00b7 ");
    }

    function fillInline(el, t) {
        el.classList.add("cp", "cp-inline");
        if (!t.hasActivity) {
            el.appendChild(h("span", { class: "cp-inline-text", text: "Concepts: take a quiz to see which ones you\u2019ve got." }));
            return;
        }
        el.appendChild(h("strong", { text: "Concepts" }));
        add(el, barEl(t.counts, true));
        el.appendChild(h("span", { class: "cp-inline-text", text: inlineText(t.counts) }));
    }

    function fillHub(el, t, topicName) {
        el.classList.add("cp");
        // The compact line is the summary while closed; once the panel is open it carries its own, so this one hides
        // rather than showing the same numbers twice.
        var summary = h("div", { class: "cp-hub-summary" });
        fillInline(summary, t);
        el.appendChild(summary);
        if (!t.hasActivity) return;
        var id = "cp-p" + (++uid);
        var wrap = h("div", { id: id, class: "cp-panel-wrap", hidden: true });
        var panel = h("div", { class: "cp" });
        wrap.appendChild(panel);
        var btn = h("button", { type: "button", class: "cp-toggle", "aria-expanded": "false", "aria-controls": id, text: "View concepts" });
        var loaded = false;

        function load() {
            panel.textContent = "";
            panel.appendChild(h("p", { class: "cp-note", role: "status", text: "Loading concepts\u2026" }));
            fetchJson("/api/concepts?topic=" + encodeURIComponent(topicName)).then(function (data) {
                loaded = true;
                renderTopicPanel(panel, data);
            }).catch(function (e) {
                console.warn("Concept detail unavailable:", e);
                panel.textContent = "";
                var retry = h("button", { type: "button", class: "cp-toggle", text: "Couldn\u2019t load concepts. Try again" });
                retry.addEventListener("click", load);
                panel.appendChild(retry);
            });
        }
        function setOpen(open) {
            wrap.hidden = !open;
            summary.hidden = open;
            btn.setAttribute("aria-expanded", open ? "true" : "false");
            btn.textContent = open ? "Hide concepts" : "View concepts";
            if (open && !loaded) load();
        }
        btn.addEventListener("click", function () { setOpen(wrap.hidden); });
        el.appendChild(h("div", null, [btn]));
        el.appendChild(wrap);

        var want = new URLSearchParams(window.location.search).get("concepts");
        if (want && want.toLowerCase() === String(topicName).toLowerCase()) {
            setOpen(true);
            var card = el.closest(".topic-card") || el;
            if (card.scrollIntoView) card.scrollIntoView({ block: "center" });
        }
    }

    function fillSlot(el) {
        var isHub = el.hasAttribute("data-cp-hub");
        var name = el.getAttribute(isHub ? "data-cp-hub" : "data-cp-inline");
        loadSummary().then(function (sum) {
            var t = findTopic(sum, name);
            if (!t || !t.counts || !t.counts.total) { el.hidden = true; return; }
            el.textContent = "";
            if (isHub) fillHub(el, t, t.topic); else fillInline(el, t);
        });
    }

    var scanQueued = false;
    function scan() {
        scanQueued = false;
        document.querySelectorAll("[data-cp-inline]:not([data-cp-done]), [data-cp-hub]:not([data-cp-done])").forEach(function (el) {
            el.setAttribute("data-cp-done", "1");
            fillSlot(el);
        });
    }
    function queueScan() {
        if (scanQueued) return;
        scanQueued = true;
        (window.requestAnimationFrame || window.setTimeout)(scan, 0);
    }

    /** Pages create their cards asynchronously; filling slots as they appear keeps the page code to a single line. */
    function watchSlots() {
        if (typeof MutationObserver === "undefined") { scan(); return; }
        new MutationObserver(queueScan).observe(document.body, { childList: true, subtree: true });
        scan();
    }

    /** Quiz Hub: arriving from "Practice these concepts" scrolls to that handout and lands focus on Target Problems. */
    var highlighted = false;
    function highlightFromUrl() {
        var want = new URLSearchParams(window.location.search).get("focus");
        if (!want) return;
        function tryNow() {
            if (highlighted) return;
            var cards = document.querySelectorAll("[data-topic-name]");
            for (var i = 0; i < cards.length; i++) {
                if (String(cards[i].dataset.topicName).toLowerCase() !== want.toLowerCase()) continue;
                highlighted = true;
                var still = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
                cards[i].scrollIntoView({ block: "center", behavior: still ? "auto" : "smooth" });
                cards[i].classList.add("cp-pulse");
                var target = cards[i].querySelector(".targeted-quiz-link");
                if (target) target.focus({ preventScroll: true });
                return;
            }
        }
        if (typeof MutationObserver === "undefined") { tryNow(); return; }
        var obs = new MutationObserver(function () { tryNow(); if (highlighted) obs.disconnect(); });
        obs.observe(document.body, { childList: true, subtree: true });
        tryNow();
    }

    function mountResultCard(cardEl, bodyEl, topic) {
        if (!cardEl || !bodyEl || !topic) return;
        fetchJson("/api/concepts?topic=" + encodeURIComponent(topic))
            .then(function (data) { renderResultCard(cardEl, bodyEl, data); })
            .catch(function (e) { console.warn("Concept result card unavailable:", e); cardEl.hidden = true; });
    }

    function mountFocusCard(cardEl, bodyEl) {
        if (!cardEl || !bodyEl) return;
        loadSummary().then(function (sum) {
            if (!sum) { cardEl.hidden = true; return; }
            cardEl.hidden = false;
            renderFocusCard(bodyEl, sum);
        });
    }

    window.ConceptProgress = {
        watchSlots: watchSlots,
        highlightFromUrl: highlightFromUrl,
        mountResultCard: mountResultCard,
        mountFocusCard: mountFocusCard,
        renderTopicPanel: renderTopicPanel,
        renderResultCard: renderResultCard,
        renderFocusCard: renderFocusCard,
        headline: headline,
        barLabel: barLabel
    };
})();
