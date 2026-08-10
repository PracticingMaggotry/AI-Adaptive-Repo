document.addEventListener("DOMContentLoaded", () => {
    const passwordInput = document.getElementById("password");
    const meterFill = document.getElementById("pwMeterFill");
    const meterLabel = document.getElementById("pwMeterLabel");
    const requirementItems = document.querySelectorAll("#pwRequirements li[data-rule]");
    const form = document.querySelector("form");
    const submitBtn = form ? form.querySelector("button[type=submit]") : null;

    if (!passwordInput || !meterFill || !meterLabel || !form) return;

    const MIN_LENGTH = 8;

    const SEQUENCE_RUN_LENGTH = 4;

    const KEYBOARD_ROWS = [
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
        "1234567890"
    ];

    const COMMON_PASSWORDS = new Set([
        "password", "password1", "password123", "12345678", "123456789",
        "qwerty123", "letmein123", "admin1234", "welcome123", "iloveyou1",
        "passw0rd", "abc123456", "1234567890", "changeme1"
    ]);

    function isAllSameCharacter(value) {
        if (!value) return false;
        return [...value].every(ch => ch === value[0]);
    }

    function countCharacterClasses(value) {
        let hasUpper = false, hasLower = false, hasDigit = false, hasSymbol = false;
        for (const ch of value) {
            if (/[A-Z]/.test(ch)) hasUpper = true;
            else if (/[a-z]/.test(ch)) hasLower = true;
            else if (/[0-9]/.test(ch)) hasDigit = true;
            else if (!/\s/.test(ch)) hasSymbol = true;
        }
        return (hasUpper ? 1 : 0) + (hasLower ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSymbol ? 1 : 0);
    }


    function containsSequentialRun(value) {
        const lower = value.toLowerCase();
        let ascRun = 1;
        let descRun = 1;
        for (let i = 1; i < lower.length; i++) {
            const prev = lower.charCodeAt(i - 1);
            const curr = lower.charCodeAt(i);

            ascRun = (curr - prev === 1) ? ascRun + 1 : 1;
            descRun = (prev - curr === 1) ? descRun + 1 : 1;

            if (ascRun >= SEQUENCE_RUN_LENGTH || descRun >= SEQUENCE_RUN_LENGTH) {
                return true;
            }
        }
        return false;
    }


    function containsKeyboardRun(value) {
        const lower = value.toLowerCase();
        for (const row of KEYBOARD_ROWS) {
            for (let i = 0; i + SEQUENCE_RUN_LENGTH <= row.length; i++) {
                const fragment = row.substring(i, i + SEQUENCE_RUN_LENGTH);
                const reversed = fragment.split("").reverse().join("");
                if (lower.includes(fragment) || lower.includes(reversed)) {
                    return true;
                }
            }
        }
        return false;
    }

    function hasSequenceOrKeyboardRun(value) {
        return containsSequentialRun(value) || containsKeyboardRun(value);
    }


    function evaluate(value) {
        const length = value.length >= MIN_LENGTH;
        const classes = countCharacterClasses(value) >= 3;
        const common = !COMMON_PASSWORDS.has(value.toLowerCase());
        const repeated = !isAllSameCharacter(value);
        const noRun = !hasSequenceOrKeyboardRun(value);
        const ok = value.length > 0 && length && classes && common && repeated && noRun;
        return { ok, length, classes, common, repeated, noRun };
    }


    function scoreStrength(value, result) {
        if (!value) return 0;
        let score = 0;
        if (result.length) score++;
        if (result.classes) score++;
        if (value.length >= 12) score++;
        if (result.common && result.repeated && result.noRun) score++;
        return score;
    }

    const STRENGTH_LEVELS = [
        { width: "0%",   color: "#ef4444", label: "" },
        { width: "25%",  color: "#ef4444", label: "Too weak" },
        { width: "50%",  color: "#f59e0b", label: "Weak" },
        { width: "75%",  color: "#eab308", label: "Okay" },
        { width: "100%", color: "#22c55e", label: "Strong" }
    ];

    function render() {
        const value = passwordInput.value;
        const result = evaluate(value);
        const score = scoreStrength(value, result);
        const level = STRENGTH_LEVELS[score];

        meterFill.style.width = level.width;
        meterFill.style.background = level.color;
        meterLabel.textContent = value ? level.label : "\u00A0";
        meterLabel.style.color = value ? level.color : "#9ca3af";

        requirementItems.forEach(li => {
            const rule = li.dataset.rule;
            let met;
            if (rule === "length") met = result.length;
            else if (rule === "classes") met = result.classes;
            else if (rule === "common") met = result.common && result.repeated && result.noRun;
            else met = false;

            li.classList.toggle("met", met);
            li.classList.toggle("unmet-touched", value.length > 0 && !met);
        });

        return result.ok;
    }

    passwordInput.addEventListener("input", render);
    render();

    form.addEventListener("submit", (event) => {
        const ok = render();
        if (!ok) {
            event.preventDefault();
            event.stopImmediatePropagation();
            meterLabel.textContent = "Please meet all password requirements before continuing.";
            meterLabel.style.color = "#ef4444";
            passwordInput.focus();
        }
    }, { capture: true });
});