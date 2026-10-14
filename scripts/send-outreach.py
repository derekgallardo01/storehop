#!/usr/bin/env python3
"""
StoreHop Outreach Email Sender CLI (ForwardEmail.net SMTP)
==========================================================

Zero-dependency Python 3 CLI tool that parses ready-to-send outreach emails from:
  - docs/outreach-targets-batch-1.md   (Emails #1 – #24)
  - docs/ahrefs-link-intersect-report.md (Emails A – Z)

Features:
  - Separates direct email targets from web contact form targets (--forms).
  - Verifies zero unreplaced [bracketed] placeholders exist in any subject or body.
  - Sends via smtp.forwardemail.net:465 (Implicit SSL/TLS) as:
      Derek <info@storehop.app>
  - Tracks every sent email in scripts/outreach-sent-log.json so the same ID or
    recipient email address is NEVER emailed twice.
  - Defaults to safe --dry-run mode unless --send or --test is explicitly passed.

Usage Examples:
  python scripts/send-outreach.py --list
  python scripts/send-outreach.py --dry-run --id 1
  python scripts/send-outreach.py --dry-run --limit 5
  python scripts/send-outreach.py --forms
  python scripts/send-outreach.py --test info@storehop.app
  python scripts/send-outreach.py --send --id 1
  python scripts/send-outreach.py --send --limit 5 --delay 20-45
"""

from __future__ import annotations

import argparse
import datetime as dt
import email.utils
import json
import os
import random
import re
import smtplib
import ssl
import sys
import time
import webbrowser
from dataclasses import dataclass
from email.message import EmailMessage
from pathlib import Path
from typing import Dict, List, Optional, Tuple


# Ensure UTF-8 output on Windows consoles
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


REPO_ROOT = Path(__file__).resolve().parent.parent
BATCH1_MD = REPO_ROOT / "docs" / "outreach-targets-batch-1.md"
AHREFS_MD = REPO_ROOT / "docs" / "ahrefs-link-intersect-report.md"
SENT_LOG_PATH = REPO_ROOT / "scripts" / "outreach-sent-log.json"
ENV_PATH = REPO_ROOT / ".env"

DEFAULT_SMTP_HOST = "smtp.forwardemail.net"
DEFAULT_SMTP_PORT = 465
DEFAULT_FROM_NAME = "Derek"
DEFAULT_FROM_EMAIL = "info@storehop.app"


@dataclass
class OutreachEmail:
    id: str                  # e.g. "1".."24" or "A".."Z"
    display_id: str          # e.g. "#1" or "A"
    source_file: str         # relative path to markdown source
    publication: str         # e.g. "Don't Waste the Crumbs"
    contact: str             # email address or https:// contact URL
    is_direct_email: bool    # True if contact is an email address
    target_urls: List[str]   # list of target article URLs
    subject: str             # email subject line
    body: str                # plain-text email body


EMAIL_REGEX = re.compile(r"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$")
PLACEHOLDER_REGEX = re.compile(r"\[[^\]\n]+\]")


def load_dotenv(env_path: Path) -> Dict[str, str]:
    """Minimal zero-dependency .env parser."""
    values: Dict[str, str] = {}
    if not env_path.exists():
        return values
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, val = line.split("=", 1)
        key = key.strip()
        val = val.strip()
        if len(val) >= 2 and ((val[0] == val[-1] == '"') or (val[0] == val[-1] == "'")):
            val = val[1:-1]
        values[key] = val
    return values


def get_config_value(key: str, dotenv_vars: Dict[str, str], default: str = "") -> str:
    return os.environ.get(key) or dotenv_vars.get(key) or default


def normalize_id(raw_id: str) -> str:
    """Normalizes user-supplied IDs like '#1', 'Email #1', 'email a', '1' -> '1' or 'A'."""
    cleaned = raw_id.strip()
    cleaned = re.sub(r"^email\s*", "", cleaned, flags=re.IGNORECASE).strip()
    cleaned = cleaned.lstrip("#").strip()
    return cleaned.upper()


def extract_contact_from_heading_or_block(heading_rest: str, block_text: str) -> Tuple[str, str]:
    """
    Extracts (publication_name, contact_channel) from an email section.
    Checks explicit **Contact**: `...` first, then backticked email/URL in heading.
    """
    # Clean publication name from heading_rest (e.g. "Don't Waste the Crumbs (`tiffany@...`) — *Note*")
    pub_name = heading_rest.split("(")[0].split("—")[0].strip()

    # 1. Explicit **Contact**: `...` line inside the block
    contact_line_match = re.search(
        r"^\*\*Contact\*\*:\s*`([^`]+)`", block_text, flags=re.MULTILINE
    )
    if contact_line_match:
        return pub_name, contact_line_match.group(1).strip()

    # 2. Backticked tokens inside the heading line
    backtick_tokens = re.findall(r"`([^`]+)`", heading_rest)
    for token in backtick_tokens:
        token = token.strip()
        if EMAIL_REGEX.match(token) or token.startswith("http://") or token.startswith("https://"):
            return pub_name, token

    raise ValueError(f"Could not extract contact email or URL for section: {heading_rest}")


def parse_markdown_emails(md_path: Path) -> List[OutreachEmail]:
    """
    Parses all `### Email #N — ...` or `### Email X — ...` sections from a markdown file.
    """
    if not md_path.exists():
        raise FileNotFoundError(f"Outreach markdown file not found: {md_path}")

    content = md_path.read_text(encoding="utf-8")
    rel_source = str(md_path.relative_to(REPO_ROOT)).replace("\\", "/")

    # Match headings like:
    # ### Email #1 — Don't Waste the Crumbs (`tiffany@dontwastethecrumbs.com`)
    # ### Email A — Chowhound (`Marc Schenker` / Editorial)
    heading_pattern = re.compile(
        r"^### Email\s+(#[0-9]+|[A-Z]{1,2})\s+—\s+(.+)$", flags=re.MULTILINE
    )
    matches = list(heading_pattern.finditer(content))
    parsed: List[OutreachEmail] = []

    for idx, match in enumerate(matches):
        raw_id = match.group(1).strip()
        heading_rest = match.group(2).strip()
        norm_id = raw_id.lstrip("#").upper()
        display_id = f"#{norm_id}" if norm_id.isdigit() else norm_id

        start_pos = match.end()
        end_pos = matches[idx + 1].start() if idx + 1 < len(matches) else len(content)
        section_text = content[start_pos:end_pos]

        publication, contact = extract_contact_from_heading_or_block(heading_rest, section_text)
        is_direct_email = bool(EMAIL_REGEX.match(contact))

        # Extract Target URL(s)
        target_urls: List[str] = []
        single_url_match = re.search(
            r"^\*\*Target URL\*\*:\s*`([^`]+)`", section_text, flags=re.MULTILINE
        )
        if single_url_match:
            target_urls.append(single_url_match.group(1).strip())
        else:
            multi_url_block = re.search(
                r"^\*\*Target URLs\*\*:\s*\n((?:-\s*`[^`]+`\s*\n?)+)",
                section_text,
                flags=re.MULTILINE,
            )
            if multi_url_block:
                target_urls.extend(re.findall(r"`([^`]+)`", multi_url_block.group(1)))

        if not target_urls:
            raise ValueError(f"Missing **Target URL** in Email {display_id} ({rel_source})")

        # Extract Subject
        subject_match = re.search(
            r"^\*\*Subject\*\*:\s*(.+)$", section_text, flags=re.MULTILINE
        )
        if not subject_match:
            raise ValueError(f"Missing **Subject** in Email {display_id} ({rel_source})")
        subject = subject_match.group(1).strip()

        # Extract ```text ... ``` code block
        body_match = re.search(
            r"```text\r?\n(.*?)\r?\n```", section_text, flags=re.DOTALL
        )
        if not body_match:
            raise ValueError(f"Missing ```text code block in Email {display_id} ({rel_source})")
        body = body_match.group(1).strip() + "\n"

        parsed.append(
            OutreachEmail(
                id=norm_id,
                display_id=display_id,
                source_file=rel_source,
                publication=publication,
                contact=contact,
                is_direct_email=is_direct_email,
                target_urls=target_urls,
                subject=subject,
                body=body,
            )
        )

    return parsed


def load_all_outreach_emails() -> List[OutreachEmail]:
    emails = parse_markdown_emails(BATCH1_MD) + parse_markdown_emails(AHREFS_MD)
    validate_no_placeholders(emails)
    return emails


def validate_no_placeholders(emails: List[OutreachEmail]) -> None:
    """Ensures zero unreplaced [bracketed] template placeholders exist in any subject or body."""
    problems: List[str] = []
    for item in emails:
        subj_hits = PLACEHOLDER_REGEX.findall(item.subject)
        body_hits = PLACEHOLDER_REGEX.findall(item.body)
        if subj_hits or body_hits:
            problems.append(
                f"Email {item.display_id} ({item.publication}): found placeholders {subj_hits + body_hits}"
            )
    if problems:
        raise ValueError("Placeholder validation failed:\n  " + "\n  ".join(problems))


def load_sent_log(log_path: Path) -> Dict[str, dict]:
    """
    Loads scripts/outreach-sent-log.json.
    Structure:
      {
        "sent_by_id": { "1": { ... }, ... },
        "sent_by_recipient": { "tiffany@dontwastethecrumbs.com": "1", ... },
        "history": [ ... ]
      }
    """
    if not log_path.exists():
        return {"sent_by_id": {}, "sent_by_recipient": {}, "history": []}
    try:
        data = json.loads(log_path.read_text(encoding="utf-8"))
        data.setdefault("sent_by_id", {})
        data.setdefault("sent_by_recipient", {})
        data.setdefault("history", [])
        return data
    except Exception as exc:
        raise RuntimeError(f"Failed to parse sent log at {log_path}: {exc}") from exc


def record_sent_email(
    log_path: Path,
    item: OutreachEmail,
    message_id: str,
    from_header: str,
) -> None:
    log_data = load_sent_log(log_path)
    now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
    entry = {
        "id": item.id,
        "display_id": item.display_id,
        "publication": item.publication,
        "to": item.contact,
        "from": from_header,
        "subject": item.subject,
        "target_urls": item.target_urls,
        "source_file": item.source_file,
        "message_id": message_id,
        "sent_at": now_iso,
    }
    log_data["sent_by_id"][item.id] = entry
    log_data["sent_by_recipient"][item.contact.lower()] = item.id
    log_data["history"].append(entry)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(json.dumps(log_data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def get_item_status(item: OutreachEmail, sent_log: Dict[str, dict], seen_recipients: Optional[set] = None) -> str:
    if not item.is_direct_email:
        return "WEB_FORM"
    if item.id in sent_log["sent_by_id"]:
        return "SENT"
    rcpt_lower = item.contact.lower()
    if rcpt_lower in sent_log["sent_by_recipient"]:
        first_id = sent_log["sent_by_recipient"][rcpt_lower]
        ref = f"#{first_id}" if first_id.isdigit() else first_id
        return f"DUP_SENT ({ref})"
    if seen_recipients is not None and rcpt_lower in seen_recipients:
        return "DUP_RECIPIENT"
    return "PENDING"


def build_email_message(
    to_email: str,
    subject: str,
    body: str,
    from_name: str = DEFAULT_FROM_NAME,
    from_email: str = DEFAULT_FROM_EMAIL,
) -> EmailMessage:
    msg = EmailMessage()
    msg["From"] = email.utils.formataddr((from_name, from_email))
    msg["To"] = to_email
    msg["Reply-To"] = from_email
    msg["Subject"] = subject
    msg["Date"] = email.utils.formatdate(localtime=True)
    domain = from_email.split("@")[-1] if "@" in from_email else "storehop.app"
    msg["Message-ID"] = email.utils.make_msgid(domain=domain)
    msg.set_content(body, subtype="plain", charset="utf-8")
    return msg


def parse_delay_range(delay_str: str) -> Tuple[float, float]:
    """Parses '20-45' or '30' into (min_sec, max_sec)."""
    cleaned = delay_str.strip()
    if "-" in cleaned:
        parts = cleaned.split("-", 1)
        low = float(parts[0].strip())
        high = float(parts[1].strip())
        if low < 0 or high < low:
            raise ValueError(f"Invalid delay range: {delay_str}")
        return low, high
    val = float(cleaned)
    if val < 0:
        raise ValueError(f"Invalid delay value: {delay_str}")
    return val, val


def smtp_send_message(
    msg: EmailMessage,
    smtp_host: str,
    smtp_port: int,
    smtp_user: str,
    smtp_password: str,
) -> None:
    context = ssl.create_default_context()
    if smtp_port == 465:
        with smtplib.SMTP_SSL(smtp_host, smtp_port, context=context, timeout=30) as server:
            server.login(smtp_user, smtp_password)
            server.send_message(msg)
    else:
        with smtplib.SMTP(smtp_host, smtp_port, timeout=30) as server:
            server.ehlo()
            server.starttls(context=context)
            server.ehlo()
            server.login(smtp_user, smtp_password)
            server.send_message(msg)


def truncate(text: str, width: int) -> str:
    if len(text) <= width:
        return text
    return text[: max(0, width - 1)] + "…"


def compute_list_counts(emails: List[OutreachEmail], sent_log: Dict[str, dict]) -> Tuple[List[Tuple[OutreachEmail, str]], Dict[str, int]]:
    seen_rcpts: set = set()
    counts = {"total": len(emails), "direct": 0, "forms": 0, "pending": 0, "sent": 0, "dup": 0}
    rows: List[Tuple[OutreachEmail, str]] = []

    for item in emails:
        status = get_item_status(item, sent_log, seen_rcpts)
        if item.is_direct_email:
            counts["direct"] += 1
            seen_rcpts.add(item.contact.lower())
            if status == "PENDING":
                counts["pending"] += 1
            elif status == "SENT":
                counts["sent"] += 1
            else:
                counts["dup"] += 1
        else:
            counts["forms"] += 1
        rows.append((item, status))

    return rows, counts


def cmd_list(emails: List[OutreachEmail], sent_log: Dict[str, dict]) -> None:
    print("=" * 125)
    print(
        f"{'ID':<4} | {'STATUS':<15} | {'PUBLICATION':<24} | {'CONTACT (EMAIL / WEB FORM)':<34} | {'SUBJECT'}"
    )
    print("-" * 125)

    rows, counts = compute_list_counts(emails, sent_log)

    for item, status in rows:
        print(
            f"{item.display_id:<4} | {status:<15} | {truncate(item.publication, 24):<24} | "
            f"{truncate(item.contact, 34):<34} | {truncate(item.subject, 40)}"
        )
        for url in item.target_urls:
            print(f"     └─ Target URL: {url}")

    print("=" * 125)
    print(
        f"Summary: {counts['total']} total pitches parsed "
        f"({counts['direct']} direct email pitches [{counts['pending']} unique pending, "
        f"{counts['sent']} sent, {counts['dup']} secondary pitch to same address], "
        f"{counts['forms']} web contact forms)."
    )
    print("Zero unreplaced [bracketed] placeholders verified across all subjects and bodies.")


def cmd_forms(
    emails: List[OutreachEmail],
    target_id: Optional[str] = None,
    open_browser: bool = False,
) -> None:
    forms = [e for e in emails if not e.is_direct_email]
    if target_id:
        norm = normalize_id(target_id)
        forms = [e for e in forms if e.id == norm]
        if not forms:
            print(f"No web-form target found matching ID '{target_id}'.", file=sys.stderr)
            sys.exit(1)

    print(f"Found {len(forms)} web contact form outreach target(s):\n")
    for item in forms:
        print("=" * 90)
        print(f"ID          : {item.display_id} ({item.publication})")
        print(f"Source      : {item.source_file}")
        print(f"Form URL    : {item.contact}")
        for url in item.target_urls:
            print(f"Target URL  : {url}")
        print(f"Subject     : {item.subject}")
        print("-" * 90)
        print(item.body.rstrip())
        print("=" * 90 + "\n")
        if open_browser:
            print(f"Opening {item.contact} in default browser...")
            webbrowser.open(item.contact)


def print_message_preview(item: OutreachEmail, msg: EmailMessage, mode_label: str) -> None:
    print("=" * 90)
    print(f"[{mode_label}] Email {item.display_id} — {item.publication} ({item.source_file})")
    for url in item.target_urls:
        print(f"Target Article : {url}")
    print("-" * 90)
    print(f"From           : {msg['From']}")
    print(f"To             : {msg['To']}")
    print(f"Reply-To       : {msg['Reply-To']}")
    print(f"Subject        : {msg['Subject']}")
    print(f"Date           : {msg['Date']}")
    print(f"Message-ID     : {msg['Message-ID']}")
    print("-" * 90)
    print(msg.get_content().rstrip())
    print("=" * 90 + "\n")


def select_candidates(
    emails: List[OutreachEmail],
    sent_log: Dict[str, dict],
    target_id: Optional[str],
    limit: int,
    allow_same_recipient: bool = False,
) -> List[OutreachEmail]:
    if target_id:
        norm = normalize_id(target_id)
        matches = [e for e in emails if e.id == norm]
        if not matches:
            print(f"Error: No outreach email found with ID '{target_id}'.", file=sys.stderr)
            sys.exit(1)
        item = matches[0]
        if not item.is_direct_email:
            print(
                f"Error: Email {item.display_id} ({item.publication}) uses a web contact form: {item.contact}\n"
                f"Run `python scripts/send-outreach.py --forms --id {item.id}` to view copy-paste text.",
                file=sys.stderr,
            )
            sys.exit(1)
        if item.id in sent_log["sent_by_id"]:
            sent_entry = sent_log["sent_by_id"][item.id]
            print(
                f"Skipping Email {item.display_id}: already sent to {item.contact} at {sent_entry.get('sent_at')}.",
                file=sys.stderr,
            )
            return []
        rcpt_lower = item.contact.lower()
        if not allow_same_recipient and rcpt_lower in sent_log["sent_by_recipient"]:
            prev_id = sent_log["sent_by_recipient"][rcpt_lower]
            print(
                f"Skipping Email {item.display_id}: recipient {item.contact} was already emailed in pitch {prev_id}.\n"
                f"(Pass --allow-same-recipient if you intentionally want to send a follow-up angle to {item.contact}.)",
                file=sys.stderr,
            )
            return []
        return [item]

    if limit <= 0:
        return []

    selected: List[OutreachEmail] = []
    seen_rcpts_in_batch: set = set()
    for item in emails:
        if not item.is_direct_email:
            continue
        if item.id in sent_log["sent_by_id"]:
            continue
        rcpt_lower = item.contact.lower()
        if not allow_same_recipient:
            if rcpt_lower in sent_log["sent_by_recipient"] or rcpt_lower in seen_rcpts_in_batch:
                continue
        seen_rcpts_in_batch.add(rcpt_lower)
        selected.append(item)
        if len(selected) >= limit:
            break
    return selected


def run_self_tests() -> None:
    """Runs automated self-tests verifying parsing, placeholder checks, headers, and deduplication."""
    import tempfile

    emails = load_all_outreach_emails()
    assert len(emails) == 50, f"Expected 50 total emails (24 Batch 1 + 26 Ahrefs), got {len(emails)}"

    batch1_ids = [e.id for e in emails if e.source_file.endswith("outreach-targets-batch-1.md")]
    ahrefs_ids = [e.id for e in emails if e.source_file.endswith("ahrefs-link-intersect-report.md")]
    assert batch1_ids == [str(i) for i in range(1, 25)], f"Unexpected Batch 1 IDs: {batch1_ids}"
    assert ahrefs_ids == [chr(ord("A") + i) for i in range(26)], f"Unexpected Ahrefs IDs: {ahrefs_ids}"

    direct_emails = [e for e in emails if e.is_direct_email]
    web_forms = [e for e in emails if not e.is_direct_email]
    assert len(direct_emails) == 38, f"Expected 38 direct email pitches, got {len(direct_emails)}"
    assert len(web_forms) == 12, f"Expected 12 web form pitches, got {len(web_forms)}"

    # Verify Email #9 has both Target URLs parsed
    email_9 = [e for e in emails if e.id == "9"][0]
    assert len(email_9.target_urls) == 2, f"Expected 2 target URLs for #9, got {email_9.target_urls}"

    for item in emails:
        assert item.publication, f"Empty publication for {item.display_id}"
        assert item.subject, f"Empty subject for {item.display_id}"
        assert "https://www.storehop.app/" in item.body, f"Missing storehop.app URL in {item.display_id}"
        assert "Derek Gallardo" not in item.body, f"Forbidden full-name signature in {item.display_id}"
        assert item.target_urls and all(u.startswith("http") for u in item.target_urls), (
            f"Invalid target_urls for {item.display_id}"
        )

    # Verify placeholder detector catches both short [First Name] and >60-char placeholders
    for placeholder_text in [
        "[Editor Name]",
        "[Insert a customized sentence here referencing their latest grocery comparison article]",
    ]:
        fake_item = OutreachEmail(
            id="999",
            display_id="#999",
            source_file="test.md",
            publication="Test Pub",
            contact="test@example.com",
            is_direct_email=True,
            target_urls=["https://example.com"],
            subject="Hello there",
            body=f"Hi {placeholder_text}, check out https://www.storehop.app/",
        )
        try:
            validate_no_placeholders([fake_item])
            raise AssertionError(f"validate_no_placeholders should have raised ValueError on {placeholder_text}")
        except ValueError:
            pass

    # Verify sent-log deduplication (both by ID and by duplicate recipient email address)
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_log = Path(tmpdir) / "outreach-sent-log.json"
        empty_log = load_sent_log(tmp_log)

        # Verify limit <= 0 returns empty list
        assert select_candidates(emails, empty_log, target_id=None, limit=0) == []
        assert select_candidates(emails, empty_log, target_id=None, limit=-5) == []

        # All 36 unique direct recipients should be selectable when limit=50
        all_unique = select_candidates(emails, empty_log, target_id=None, limit=50)
        assert len(all_unique) == 36, f"Expected 36 unique recipients, got {len(all_unique)}"

        # Record Email #9 (tips@thekitchn.com) as sent
        msg_9 = build_email_message(email_9.contact, email_9.subject, email_9.body)
        assert msg_9["From"] == "Derek <info@storehop.app>"
        assert msg_9["Reply-To"] == "info@storehop.app"
        assert "@storehop.app>" in msg_9["Message-ID"]

        record_sent_email(tmp_log, email_9, str(msg_9["Message-ID"]), str(msg_9["From"]))
        updated_log = load_sent_log(tmp_log)

        # Verify compute_list_counts accurately reports 1 sent, 35 pending, 2 dup when #9 is sent
        rows, counts = compute_list_counts(emails, updated_log)
        assert counts["sent"] == 1, f"Expected 1 sent in list summary after sending #9, got {counts['sent']}"
        assert counts["pending"] == 35, f"Expected 35 pending after sending #9, got {counts['pending']}"
        assert counts["dup"] == 2, f"Expected 2 dup after sending #9, got {counts['dup']}"
        status_by_id = {item.id: st for item, st in rows}
        assert status_by_id["9"] == "SENT"
        assert status_by_id["10"] == "DUP_SENT (#9)"

        # Now #9 is skipped by ID, and #10 (also tips@thekitchn.com) is skipped by recipient deduplication
        assert select_candidates(emails, updated_log, target_id="9", limit=1) == []
        assert select_candidates(emails, updated_log, target_id="10", limit=1) == []
        # Unless --allow-same-recipient is explicitly True
        assert len(select_candidates(emails, updated_log, target_id="10", limit=1, allow_same_recipient=True)) == 1

    print("ALL SELF-TESTS PASSED: 50 emails parsed (38 direct, 12 web forms), 0 placeholders, headers & dedup verified.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="StoreHop Outreach CLI Sender via ForwardEmail.net SMTP (smtp.forwardemail.net:465)"
    )
    mode_group = parser.add_mutually_exclusive_group()
    mode_group.add_argument(
        "--list",
        action="store_true",
        help="List all parsed outreach emails (Batch 1 #1-#24 + Ahrefs A-Z) and their status.",
    )
    mode_group.add_argument(
        "--forms",
        action="store_true",
        help="List all web contact form targets with copy-ready Subject and Body.",
    )
    mode_group.add_argument(
        "--dry-run",
        action="store_true",
        help="Print exact email headers and body without connecting to SMTP (default mode).",
    )
    mode_group.add_argument(
        "--test",
        metavar="TO_EMAIL",
        help="Send a single test email (e.g. to info@storehop.app) to verify SMTP auth & headers.",
    )
    mode_group.add_argument(
        "--send",
        action="store_true",
        help="Send pending direct-email outreach pitches via smtp.forwardemail.net:465.",
    )
    mode_group.add_argument(
        "--self-test",
        action="store_true",
        help="Run automated verification tests on markdown parsing, placeholders, headers, and deduplication.",
    )

    parser.add_argument(
        "--id",
        metavar="ID",
        help="Target a specific email ID (e.g. '1', '#1', 'A', 'Z').",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=5,
        help="Maximum number of pending emails to process when --id is not set (default: 5).",
    )
    parser.add_argument(
        "--delay",
        default="20-45",
        help="Delay in seconds between live emails, e.g. '20-45' or '30' (default: '20-45').",
    )
    parser.add_argument(
        "--open-forms",
        action="store_true",
        help="When used with --forms, open each contact form URL in your default web browser.",
    )
    parser.add_argument(
        "--allow-same-recipient",
        action="store_true",
        help="Allow sending a second distinct pitch to a general tips@ inbox that was already emailed.",
    )
    parser.add_argument(
        "--smtp-host",
        default=None,
        help=f"SMTP server hostname (default: {DEFAULT_SMTP_HOST}).",
    )
    parser.add_argument(
        "--smtp-port",
        type=int,
        default=None,
        help=f"SMTP server port (default: {DEFAULT_SMTP_PORT} SSL/TLS).",
    )
    parser.add_argument(
        "--from-email",
        default=None,
        help=f"Sender email address (default: {DEFAULT_FROM_EMAIL}).",
    )
    parser.add_argument(
        "--from-name",
        default=None,
        help=f"Sender display name (default: {DEFAULT_FROM_NAME}).",
    )

    args = parser.parse_args()
    if args.self_test:
        run_self_tests()
        return

    dotenv_vars = load_dotenv(ENV_PATH)

    smtp_host = args.smtp_host or get_config_value("FORWARDEMAIL_SMTP_HOST", dotenv_vars, DEFAULT_SMTP_HOST)
    smtp_port = args.smtp_port or int(get_config_value("FORWARDEMAIL_SMTP_PORT", dotenv_vars, str(DEFAULT_SMTP_PORT)))
    from_email = args.from_email or get_config_value("FORWARDEMAIL_USER", dotenv_vars, DEFAULT_FROM_EMAIL)
    from_name = args.from_name or get_config_value("FORWARDEMAIL_FROM_NAME", dotenv_vars, DEFAULT_FROM_NAME)
    smtp_password = get_config_value("FORWARDEMAIL_PASSWORD", dotenv_vars, "")

    emails = load_all_outreach_emails()
    sent_log = load_sent_log(SENT_LOG_PATH)

    if args.list:
        cmd_list(emails, sent_log)
        return

    if args.forms:
        cmd_forms(emails, target_id=args.id, open_browser=args.open_forms)
        return

    if args.test:
        test_to = args.test.strip()
        if not EMAIL_REGEX.match(test_to):
            print(f"Error: Invalid test recipient email address '{test_to}'.", file=sys.stderr)
            sys.exit(1)
        sample = emails[0]
        test_subject = f"[SMTP TEST] {sample.subject}"
        test_body = (
            "--- StoreHop ForwardEmail.net SMTP Verification Test ---\n"
            f"SMTP Host : {smtp_host}:{smtp_port} (SSL/TLS)\n"
            f"Sample ID : {sample.display_id} ({sample.publication})\n"
            "--------------------------------------------------------\n\n"
            + sample.body
        )
        msg = build_email_message(
            to_email=test_to,
            subject=test_subject,
            body=test_body,
            from_name=from_name,
            from_email=from_email,
        )
        print_message_preview(sample, msg, mode_label="SMTP TEST")
        if not smtp_password:
            print(
                "Error: FORWARDEMAIL_PASSWORD is not set in environment or .env file.\n"
                "Set FORWARDEMAIL_PASSWORD in your environment or .env to send live test emails.",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"Connecting to {smtp_host}:{smtp_port} as {from_email}...")
        smtp_send_message(msg, smtp_host, smtp_port, from_email, smtp_password)
        print(f"Success! Test email sent to {test_to} (Message-ID: {msg['Message-ID']}).")
        return

    # Either --send or default --dry-run
    is_live_send = bool(args.send)
    if not args.id and args.limit < 1:
        print(f"Error: --limit must be at least 1 (got {args.limit}).", file=sys.stderr)
        sys.exit(1)
    try:
        delay_min, delay_max = parse_delay_range(args.delay)
    except ValueError as exc:
        print(f"Error: {exc} (expected format like '20-45' or '30').", file=sys.stderr)
        sys.exit(1)

    candidates = select_candidates(
        emails=emails,
        sent_log=sent_log,
        target_id=args.id,
        limit=args.limit,
        allow_same_recipient=args.allow_same_recipient,
    )

    if not candidates:
        print("No eligible pending direct-email pitches matched your selection.")
        return

    if not is_live_send:
        for item in candidates:
            msg = build_email_message(
                to_email=item.contact,
                subject=item.subject,
                body=item.body,
                from_name=from_name,
                from_email=from_email,
            )
            print_message_preview(item, msg, mode_label="DRY-RUN")
        print(
            f"Dry run complete: previewed {len(candidates)} email(s). "
            f"No SMTP connection was made. Pass --send to send live via {smtp_host}:{smtp_port}."
        )
        return

    # Live --send mode
    if not smtp_password:
        print(
            "Error: FORWARDEMAIL_PASSWORD is not set in environment or .env file.\n"
            "Create a .env file (or export FORWARDEMAIL_PASSWORD) before running with --send.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(
        f"Preparing to send {len(candidates)} outreach email(s) via {smtp_host}:{smtp_port} "
        f"as {from_name} <{from_email}>..."
    )

    for idx, item in enumerate(candidates):
        msg = build_email_message(
            to_email=item.contact,
            subject=item.subject,
            body=item.body,
            from_name=from_name,
            from_email=from_email,
        )
        print_message_preview(item, msg, mode_label=f"SENDING {idx + 1}/{len(candidates)}")
        smtp_send_message(msg, smtp_host, smtp_port, from_email, smtp_password)
        record_sent_email(
            log_path=SENT_LOG_PATH,
            item=item,
            message_id=str(msg["Message-ID"]),
            from_header=str(msg["From"]),
        )
        print(
            f"[SENT] Email {item.display_id} -> {item.contact} "
            f"(logged to {SENT_LOG_PATH.relative_to(REPO_ROOT)})"
        )

        if idx + 1 < len(candidates):
            wait_sec = random.uniform(delay_min, delay_max)
            print(f"Waiting {wait_sec:.1f}s before next email...")
            time.sleep(wait_sec)

    print(f"\nCompleted sending {len(candidates)} outreach email(s).")


if __name__ == "__main__":
    main()

