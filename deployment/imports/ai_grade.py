import os
#!/usr/bin/env python3
"""Send the 50 AI ground-truth questions to /v1/ai/chat and grade responses
against expected values. Stop early if 3 consecutive identical responses
(stop-on-repeat per feedback_ai_test_stop_on_repeat memory).
"""
import json
import urllib.request
import urllib.error
import re
import time

BASE = os.environ.get("BIPROS_API_BASE", "http://localhost:8080")
TOKEN = open(os.environ.get("BIPROS_TOKEN_FILE", os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/admin-token.txt")).read().strip()
PROJECT_ID = open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/project-id.txt").read().strip()
GROUND_TRUTH = json.load(open("/tmp/ai-ground-truth.json"))


def http(method, path, body=None, timeout=120):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Content-Type", "application/json")
    data = json.dumps(body).encode() if body else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            err = json.loads(e.read())
        except Exception:
            err = {"error": str(e)}
        return e.code, err
    except Exception as e:
        return 0, {"error": str(e)}


def normalize_value(v: str) -> str:
    """Normalize for matching: strip whitespace, lowercase, remove punctuation."""
    if v is None:
        return ""
    return re.sub(r"[,\s]+", "", str(v).lower())


def grade(expected: str, actual_text: str) -> str:
    """PASS / PARTIAL / FAIL based on whether expected value appears in actual response."""
    if not actual_text:
        return "FAIL"
    exp_norm = normalize_value(expected)
    actual_norm = normalize_value(actual_text)
    # Direct substring match
    if exp_norm and exp_norm in actual_norm:
        return "PASS"
    # Numeric tolerance (within 1% if expected is numeric)
    try:
        exp_num = float(expected)
        # Find any number in the response
        nums = re.findall(r"-?\d+\.?\d*", actual_text)
        for n in nums:
            try:
                if abs(float(n) - exp_num) / max(abs(exp_num), 1) < 0.01:
                    return "PASS"
            except ValueError:
                pass
    except (ValueError, TypeError):
        pass
    # Partial: expected substring at all?
    if expected and str(expected).lower() in actual_text.lower():
        return "PARTIAL"
    return "FAIL"


def main():
    results = []
    last_responses = []
    aborted = False
    pass_count = partial_count = fail_count = 0

    for q in GROUND_TRUTH:
        if aborted:
            break
        body = {"message": q["question"], "projectId": PROJECT_ID}
        t0 = time.time()
        code, resp = http("POST", "/v1/ai/chat", body, timeout=180)
        elapsed = time.time() - t0

        if code != 200:
            err_msg = resp.get("error", {}).get("message", str(resp.get("error")))
            grade_val = "ERROR"
            actual = f"HTTP {code}: {err_msg[:200]}"
            tool_trace = []
        else:
            data = resp.get("data", {})
            actual = data.get("text") or data.get("responseText") or data.get("message") or ""
            tool_trace = data.get("toolCalls", []) or data.get("tools", []) or []
            grade_val = grade(q["expected_value"], actual)

        if grade_val == "PASS":
            pass_count += 1
        elif grade_val == "PARTIAL":
            partial_count += 1
        else:
            fail_count += 1

        # Stop on repeat
        last_responses.append(actual[:80] if actual else "")
        if len(last_responses) > 3:
            last_responses.pop(0)
        if len(last_responses) == 3 and len(set(last_responses)) == 1 and last_responses[0]:
            aborted = True

        results.append({
            "id": q["id"],
            "family": q["family"],
            "question": q["question"],
            "expected": q["expected_value"],
            "actual": actual[:500] if actual else "",
            "grade": grade_val,
            "elapsed_sec": round(elapsed, 1),
            "tool_count": len(tool_trace),
            "tool_names": [t.get("name") or t.get("toolName", "?") for t in tool_trace[:3]],
        })
        print(f"  {q['id']} [{grade_val:7}] {q['question'][:50]}... → exp={q['expected_value'][:30]}, actual={actual[:50]}", flush=True)

    summary = {
        "total": len(results),
        "pass": pass_count,
        "partial": partial_count,
        "fail": fail_count,
        "aborted_early": aborted,
        "results": results,
    }
    with open("/tmp/ai-results.json", "w") as f:
        json.dump(summary, f, indent=2)

    print(f"\n=== SUMMARY ===")
    print(f"  PASS:    {pass_count}/{len(results)}")
    print(f"  PARTIAL: {partial_count}/{len(results)}")
    print(f"  FAIL:    {fail_count}/{len(results)}")
    if aborted:
        print(f"  ABORTED EARLY (3 identical responses)")
    print(f"  Saved to /tmp/ai-results.json")


if __name__ == "__main__":
    main()
