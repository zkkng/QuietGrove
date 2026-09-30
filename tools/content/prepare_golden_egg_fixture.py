#!/usr/bin/env python3
"""Prepare a guarded SQL precondition for CONTENT-01's fifth-care-day test.

Reads an offline, disposable character's captured HEX(character_content.state).
It never connects to the database or changes the source capture.
"""

import argparse
from datetime import datetime, timezone
from pathlib import Path


def decode(raw: str) -> dict[str, int]:
    if not raw.startswith("v1\n"):
        raise ValueError("Expected ContentState v1")
    values = {}
    for line in raw[3:].splitlines():
        if not line:
            continue
        key, sep, value = line.partition("=")
        if not sep or not key or key in values or not key.replace("_", "").replace(".", "").replace("-", "").isalnum():
            raise ValueError("Invalid or duplicate ContentState key")
        values[key] = int(value)
    return values


def encode(values: dict[str, int]) -> str:
    return "v1\n" + "".join(f"{key}={value}\n" for key, value in sorted(values.items()))


def prepare(raw: str, utc_day: int) -> str:
    state = decode(raw)
    if state.get("egg.active") != 1:
        raise ValueError("Character must first take the egg normally")
    if state.get("egg.feeds", 0) != 0 or state.get("egg.growth", 0) != 0:
        raise ValueError("Use a fresh active egg for the labeled artificial precondition")
    if state.get("egg.claimed") == utc_day:
        raise ValueError("Character already returned an egg today")
    state.update({"egg.feeds": 4, "egg.fed": utc_day - 1,
                  "egg.powderday": utc_day - 1, "egg.growth": 2400})
    return encode(state)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--observed-state-hex", type=Path, required=True,
                        help="File containing only SELECT HEX(state), no header")
    parser.add_argument("--character-id", type=int, required=True)
    parser.add_argument("--account-id", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--disposable-label", required=True,
                        help="Must be exactly 'CONTENT-01 disposable egg'")
    args = parser.parse_args()
    if args.disposable_label != "CONTENT-01 disposable egg":
        parser.error("Explicit disposable fixture label required")
    if args.character_id <= 0 or args.account_id <= 0:
        parser.error("Positive character/account IDs required")
    raw = bytes.fromhex(args.observed_state_hex.read_text(encoding="ascii").strip()).decode("utf-8")
    now = datetime.now(timezone.utc)
    utc_day = (now.date() - datetime(1970, 1, 1, tzinfo=timezone.utc).date()).days
    updated = prepare(raw, utc_day)
    old_hex = raw.encode("utf-8").hex().upper()
    new_hex = updated.encode("utf-8").hex().upper()
    sql = f"""-- CONTENT-01 disposable fifth-care-day precondition, generated {now.isoformat()}.
-- Artificial four-prior-day care state; not a claim that those client feeds occurred.
-- Capture this character before applying. Apply only while its account is logged out.
-- A changed account, egg inventory, or exact state yields changed_rows=0; stop and investigate.
START TRANSACTION;
UPDATE character_content AS cc
JOIN characters AS c ON c.id = cc.characterid
JOIN accounts AS a ON a.id = c.accountid
SET cc.state = CONVERT(UNHEX('{new_hex}') USING utf8mb4)
WHERE cc.characterid = {args.character_id}
  AND c.accountid = {args.account_id}
  AND a.loggedin = 0
  AND TO_DAYS(UTC_DATE()) - TO_DAYS('1970-01-01') = {utc_day}
  AND BINARY cc.state = BINARY CONVERT(UNHEX('{old_hex}') USING utf8mb4)
  AND (SELECT COUNT(*) FROM inventoryitems AS i
       WHERE i.characterid = cc.characterid AND i.inventorytype = 4
         AND i.itemid = 4220089 AND i.quantity = 1) = 1;
SELECT ROW_COUNT() AS changed_rows; -- must equal 1
COMMIT;
SELECT state FROM character_content WHERE characterid = {args.character_id};
"""
    if args.output.exists():
        raise FileExistsError(args.output)
    args.output.write_text(sql, encoding="utf-8")
    print(f"Generated {args.output}; character {args.character_id}; UTC day {utc_day}; verify changed_rows=1.")


if __name__ == "__main__":
    main()
