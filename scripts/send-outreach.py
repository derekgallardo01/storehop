#!/usr/bin/env python3
"""
StoreHop Outreach Email Sender CLI (ForwardEmail.net SMTP)
==========================================================

Zero-dependency Python 3 CLI tool that parses and generates ready-to-send outreach emails from:
  - docs/outreach-targets-batch-1.md              (Curated Emails #1 – #24)
  - docs/ahrefs-link-intersect-report.md          (Curated Emails A – Z)
  - docs/ahrefs-competitor-intersect-tracker.csv  (Dynamic CSV Pitches R1 – R1081)

Features:
  - Enriches docs/ahrefs-competitor-intersect-tracker.csv with verified Contact_Email values (--enrich-csv).
  - Dynamically generates tailored, zero-placeholder outreach emails for every CSV row with a Contact_Email.
  - Deduplicates by referring domain and recipient email against both markdown pitches and outreach-sent-log.json.
  - Separates direct email targets from web contact form targets (--forms).
  - Verifies zero unreplaced [bracketed] placeholders exist in any subject or body.
  - Sends via smtp.forwardemail.net:465 (Implicit SSL/TLS) as:
      Derek <info@storehop.app>
  - Tracks every sent email in scripts/outreach-sent-log.json so the same ID, domain, or
    recipient email address is NEVER emailed twice.
  - Defaults to safe --dry-run mode unless --send or --test is explicitly passed.

Usage Examples:
  python scripts/send-outreach.py --enrich-csv
  python scripts/send-outreach.py --self-test
  python scripts/send-outreach.py --list
  python scripts/send-outreach.py --dry-run --id R3
  python scripts/send-outreach.py --dry-run --limit 5
  python scripts/send-outreach.py --forms
  python scripts/send-outreach.py --test info@storehop.app
  python scripts/send-outreach.py --send --id 2
  python scripts/send-outreach.py --send --limit 5 --delay 15-30
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import email.utils
import html
import json
import os
import random
import re
import smtplib
import ssl
import sys
import time
import urllib.parse
import urllib.request
import webbrowser
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from email.message import EmailMessage
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple


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
TRACKER_CSV = REPO_ROOT / "docs" / "ahrefs-competitor-intersect-tracker.csv"
SENT_LOG_PATH = REPO_ROOT / "scripts" / "outreach-sent-log.json"
ENV_PATH = REPO_ROOT / ".env"

DEFAULT_SMTP_HOST = "smtp.forwardemail.net"
DEFAULT_SMTP_PORT = 465
DEFAULT_FROM_NAME = "Derek"
DEFAULT_FROM_EMAIL = "info@storehop.app"


@dataclass
class OutreachEmail:
    id: str                  # e.g. "1".."24", "A".."Z", or "R1".."R1081"
    display_id: str          # e.g. "#1", "A", or "R3"
    source_file: str         # relative path to markdown or CSV source
    publication: str         # e.g. "Don't Waste the Crumbs"
    contact: str             # email address or https:// contact URL
    is_direct_email: bool    # True if contact is an email address
    target_urls: List[str]   # list of target article URLs
    subject: str             # email subject line
    body: str                # plain-text email body
    domain: str = ""         # normalized referring domain


EMAIL_REGEX = re.compile(r"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,15}$")
EMAIL_FIND_REGEX = re.compile(r"([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,15})")
MAILTO_REGEX = re.compile(r"mailto:([^\s\"'<>?&#]+)", re.IGNORECASE)
CFEMAIL_REGEX = re.compile(r"data-cfemail=[\"\']([0-9a-fA-F]+)[\"\']")
CONTACT_HREF_REGEX = re.compile(
    r"href=[\"\']([^\"\']*?(?:contact|about|impressum|contatti|contacto|contato|redazione|"
    r"redaccion|redacao|equipe|team|write-for-us|work-with-me|press|masthead)[^\"\']*?)[\"\']",
    re.IGNORECASE,
)
PLACEHOLDER_REGEX = re.compile(r"\[[^\]\n]+\]")

# Strictly forbidden junk/placeholder/system email domains and extensions
JUNK_EMAIL_DOMAINS: Set[str] = {
    "example.com", "example.org", "example.net", "domain.com", "yourdomain.com",
    "yoursite.com", "email.com", "mysite.com", "site.com", "test.com", "company.com",
    "website.com", "sentry.io", "wixpress.com", "wix.com", "wordpress.com",
    "wordpress.org", "wpengine.com", "squarespace.com", "shopify.com", "godaddy.com",
    "cloudflare.com", "googleusercontent.com", "googlesyndication.com", "doubleclick.net",
    "facebook.com", "twitter.com", "instagram.com", "pinterest.com", "youtube.com",
    "apple.com", "microsoft.com", "amazon.com", "schema.org", "w3.org", "storehop.app",
    "anylist.com", "getbring.com", "outofmilk.com", "ourgroceries.com", "groceryiq.com",
    "listonic.com", "ziplist.com", "flipp.com", "basket.com", "google.com", "gstatic.com",
    "gravatar.com", "typekit.net", "fontawesome.com", "jsdelivr.net", "unpkg.com",
    "change.org", "creativecommons.org", "mediavine.com", "adthrive.com", "raptive.com",
    "mastodon.social", "exquisite.social", "wallkit.com", "b2adops.com", "audienzz.ch",
    "wearemci.com", "nimia.com",
}

JUNK_EMAIL_EXTENSIONS: Tuple[str, ...] = (
    ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".css", ".js", ".woff", ".woff2",
    ".ttf", ".eot", ".ico", ".mp4", ".mp3", ".pdf", ".zip", ".html", ".htm", ".php",
    ".asp", ".aspx", ".json", ".xml", ".avif", ".bmp", ".tiff",
)

JUNK_LOCAL_SUBSTRINGS: Tuple[str, ...] = (
    "noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "do_not_reply",
    "privacy", "dataprotection", "datenschutz", "dpo", "gdpr", "ccpa", "dmca", "copyright",
    "takedown", "abuse", "security", "vulnerability", "phishing", "spam", "postmaster",
    "hostmaster", "webmaster", "mailer-daemon", "sysadmin", "legal", "compliance",
    "arbitration", "subpoena", "jobs", "careers", "carreiras", "recruiting", "recruitment",
    "talent", "hiring", "internships", "billing", "invoices", "payments", "accounting",
    "orders", "returns", "shipping", "unsubscribe", "bounces", "accessibility",
    "closedcaptioning", "sentry", "wixpress", "placeholder", "yourname", "firstname",
    "lastname", "username", "example", "johndoe", "janedoe", "name@", "user@", "adops",
    "salessupport", "alessupport", "abasales", "epapersupport", "mysubscription",
    "subscriptions", "customerservice", "custserv", "webcustomercare", "customercare",
    "memberservices", "membercare", "memberapp", "contactcentre", "contact.centre",
    "homecareintake", "advertising", "sponsorships", "reklama", "publicfile",
    "fehlerbericht", "impressum", "thankyou", "internetops",
)

PERSONAL_EMAIL_DOMAINS: Set[str] = {
    "gmail.com", "googlemail.com", "icloud.com", "me.com", "mac.com",
    "outlook.com", "hotmail.com", "live.com", "msn.com", "yahoo.com",
    "yahoo.co.uk", "yahoo.ca", "protonmail.com", "proton.me", "fastmail.com",
    "zoho.com", "aol.com", "gmx.de", "gmx.net", "web.de", "t-online.de",
    "sapo.pt", "libero.it", "virgilio.it", "orange.fr", "wanadoo.fr", "free.fr",
    "wp.pl", "onet.pl", "interia.pl", "o2.pl", "seznam.cz",
}

# Verified editorial/contact emails for domains in the tracker (including sites whose contact
# pages are behind JS/Cloudflare or use parent-publisher editorial inboxes).
KNOWN_DOMAIN_EMAILS: Dict[str, str] = {
    # Curated markdown targets
    "chowhound.com": "experts@static.com",
    "cupla.app": "hello@cupla.app",
    "lifehacker.com": "tips@lifehacker.com",
    "everblog.com": "emily@everblog.com",
    "togetherprice.com": "support@togetherprice.com",
    "cnet.com": "cnet-corrections@cnet.com",
    "rottenwifi.com": "contact@rottenwifi.com",
    "jackiesilvernutrition.com": "jackie@jackiesilvernutrition.com",
    "techpp.com": "tips@techpp.com",
    "roomiapp.com": "hello@roomiapp.com",
    "moneydoneright.com": "support@moneydoneright.com",
    "remessaonline.com.br": "imprensa@remessaonline.com.br",
    "100daysofrealfood.com": "lisa@100daysofrealfood.com",
    "androidpolice.com": "tips@androidpolice.com",
    "noozhawk.com": "news@noozhawk.com",
    "kidsactivitiesblog.com": "holly@kidsactivitiesblog.com",
    "howdoesshe.com": "help@howdoesshe.com",
    "onehundreddollarsamonth.com": "onehundreddollarsamonth@gmail.com",
    "apartmenttherapy.com": "editors@apartmenttherapy.com",
    "thekitchn.com": "tips@thekitchn.com",
    "orgjunkie.com": "organizingjunkie@gmail.com",
    "ourcal.com": "hi@ourcal.com",
    "garlicdelight.com": "anna@garlicdelight.com",
    "toolfinder.com": "tools@keepproductive.com",
    "canstarblue.com.au": "enquiries@canstarblue.com.au",
    "talkandroid.com": "tips@talkandroid.com",
    # Additional verified editorial/author contacts across Tier 1A, 1B, 2, 3, 4
    "amnhealthcare.com": "info@amnhealthcare.com",
    "concordia.ca": "media.relations@concordia.ca",
    "santander.pt": "salto@santander.pt",
    "bancobpm.it": "redazione@bancobpm.it",
    "scarymommy.com": "pitches@scarymommy.com",
    "grocerydive.com": "grocery.dive.editors@industrydive.com",
    "blog-united.com": "contact@blog-united.com",
    "hellobrigit.com": "info@hellobrigit.com",
    "womansday.com": "womansday@hearst.com",
    "moneycrashers.com": "info@moneycrashers.com",
    "latimes.com": "business@latimes.com",
    "huffingtonpost.co.uk": "ukblogteam@huffpost.com",
    "ing.it": "redazione.vocearancio@ing.com",
    "rehack.com": "info@rehack.com",
    "mynexttablet.com": "contact@mynexttablet.com",
    "elpais.com": "redaccion@elpais.es",
    "computerworld.com": "editors@computerworld.com",
    "sbs.com.au": "food@sbs.com.au",
    "womansworld.com": "DearWW@womansworldmag.com",
    "indailysa.com.au": "editorial@solsticemedia.com.au",
    "thurrott.com": "paul@thurrott.com",
    "firstforwomen.com": "ffw@firstforwomen.com",
    "liliputing.com": "brad@liliputing.com",
    "yellowhammernews.com": "tips@yellowhammernews.com",
    "elliott.org": "chris@elliott.org",
    "hardware.com.br": "contato@hardware.com.br",
    "carlahall.com": "info@carlahall.com",
    "nurturelife.com": "support@nurturelife.com",
    "melaniejadedesign.com": "melanie@melaniejadedesign.com",
    "monee-app.com": "support@monee-app.com",
    "vaia.eu": "info@vaia.eu",
    "adultdownsyndrome.org": "adsc@advocatehealth.com",
    "beforepay.com.au": "support@beforepay.com.au",
    "klaw.com": "news@townsquaremedia.com",
    "meetpenny.com": "tabitha@meetpenny.com",
    "ai-mealplan.com": "support@ai-mealplan.com",
    "idnes.cz": "redakce@idnes.cz",
    "connect-living.de": "redaktion@connect-living.de",
    "moneypantry.com": "contact@moneypantry.com",
    "today.com": "today@nbcuni.com",
    "huffpost.com": "scoop@huffpost.com",
    "buzzfeed.com": "tips@buzzfeed.com",
    "thestreet.com": "tips@thestreet.com",
    "wisebread.com": "editor@wisebread.com",
    "inkl.com": "support@inkl.com",
    "itechguides.com": "info@itechguides.com",
    "nbcnews.com": "tips@nbcuni.com",
    "betakit.com": "tips@betakit.com",
    "fastcompany.com": "edit@fastcompany.com",
    "engadget.com": "tips@engadget.com",
    "bustle.com": "pitches@bustle.com",
    "money.com": "letters@money.com",
    "nbcwashington.com": "newstips4@nbcuni.com",
    "reviewed.com": "editor@reviewed.com",
    "moneytalksnews.com": "editors@moneytalksnews.com",
    "phandroid.com": "tips@phandroid.com",
    "thefinancialdiet.com": "info@thefinancialdiet.com",
    "momtrends.com": "info@momtrends.com",
    "xataka.com": "editores@xataka.com",
    "consumer.es": "info@consumer.es",
    "ratehub.ca": "media@ratehub.ca",
    "lifehack.org": "press@lifehack.org",
    "telemundo.com": "noticias@telemundo.com",
    "bioguia.com": "hola@bioguia.com",
    "familyfocusblog.com": "familyfocusblog@gmail.com",
    "lowestrates.ca": "media@lowestrates.ca",
    "androidauthority.com": "tips@androidauthority.com",
    "uniplaces.com": "press@uniplaces.com",
    "forbes.com": "tips@forbes.com",
    "nytimes.com": "tips@nytimes.com",
    "apnews.com": "info@ap.org",
    "globalnews.ca": "tips@globalnews.ca",
    "makeuseof.com": "tips@makeuseof.com",
    "retaildive.com": "retail.dive.editors@industrydive.com",
    "parade.com": "editorial@parade.com",
    "bgr.com": "tips@bgr.com",
    "slashgear.com": "tips@slashgear.com",
    "tastingtable.com": "editorial@tastingtable.com",
    "abc15.com": "news@abc15.com",
    "fox26houston.com": "ktvfdesk@fox.com",
    "thedailymeal.com": "editorial@thedailymeal.com",
    "androidheadlines.com": "tips@androidheadlines.com",
    "gestion.pe": "redaccion@gestion.pe",
    "triplepundit.com": "editorial@triplepundit.com",
    "tidbits.com": "editors@tidbits.com",
    "cheapism.com": "editors@cheapism.com",
    "wwwhatsnew.com": "contacto@wwwhatsnew.com",
    "moneysavingmom.com": "crystal@moneysavingmom.com",
    "theworkathomewoman.com": "holly@theworkathomewoman.com",
    "iheartorganizing.com": "iheartorganizing@gmail.com",
    "momadvice.com": "amy@momadvice.com",
    "supermarketguru.com": "phil@supermarketguru.com",
    "kashkick.com": "support@kashkick.com",
    "livingonthecheap.com": "info@livingonthecheap.com",
    "lifeskillsadvocate.com": "info@lifeskillsadvocate.com",
    "nogettingoffthistrain.com": "jaime@nogettingoffthistrain.com",
    "fillyourplate.org": "info@azfb.org",
    "goodenessgracious.com": "tessa@goodenessgracious.com",
    "liveabout.com": "contact@liveabout.com",
    "adweek.com": "tips@adweek.com",
    "digitaltrends.com": "tips@digitaltrends.com",
    "techrepublic.com": "editorial@techrepublic.com",
    "baltimoresun.com": "newsroom@baltsun.com",
    "marketingdive.com": "marketing.dive.editors@industrydive.com",
    "softonic.com": "press@softonic.com",
    "pressherald.com": "news@pressherald.com",
    "komonews.com": "tips@komonews.com",
    "alternet.org": "info@alternet.org",
    "fatherly.com": "editors@fatherly.com",
    "chatelaine.com": "letters@chatelaine.com",
    "marketingdirecto.com": "redaccion@marketingdirecto.com",
    "nhregister.com": "letters@nhregister.com",
    "wthr.com": "newstips@wthr.com",
    "datamation.com": "editor@datamation.com",
    "foodprint.org": "info@foodprint.org",
    "foodtank.com": "danielle@foodtank.com",
    "redbookmag.com": "redbook@hearst.com",
    "whatsyourgrief.com": "whatsyourgrief@gmail.com",
    "theoaklandpress.com": "newsroom@oakpress.com",
    "moneycheck.com": "contact@moneycheck.com",
    "geekdad.com": "ken@geekdad.com",
    "spafinder.com": "editorial@spafinder.com",
    "skiptomylou.org": "cindy@skiptomylou.org",
    "cooksmarts.com": "hello@cooksmarts.com",
    "noobpreneur.com": "ivan@noobpreneur.com",
    "asweatlife.com": "jeana@asweatlife.com",
    "imom.com": "info@imom.com",
    "thebudgetnista.com": "info@thebudgetnista.com",
    "modernparentsmessykids.com": "stephanie@modernparentsmessykids.com",
    "wondermomwannabe.com": "corinne@wondermomwannabe.com",
    "familytoday.com": "editors@familytoday.com",
    "todaysmama.com": "info@todaysmama.com",
    "banktrack.com": "hola@banktrack.com",
    "askthemoneycoach.com": "info@askthemoneycoach.com",
    "eldiario.es": "pistas@eldiario.es",
    "casafacile.it": "casafacile@mondadori.it",
    "elle.com": "elle@hearst.es",
    "dallasnews.com": "aldia@dallasnews.com",
    "eluniversal.com.mx": "techbit@eluniversal.com.mx",
    "elcomercio.pe": "contactenos@comercio.com.pe",
    "observador.pt": "geral@observador.pt",
    "consumidormoderno.com.br": "redacao@consumidormoderno.com.br",
    "prensalibre.com": "redaccion@prensalibre.com.gt",
    "enter.co": "redaccion@enter.co",
    "xatakandroid.com": "editores@xatakandroid.com",
    "facilisimo.com": "redaccion@facilisimo.com",
    "panelinha.com.br": "contato@panelinha.com.br",
    "ideepercomputeredinternet.com": "info@ideepercomputeredinternet.com",
    "androidphoria.com": "contacto@androidphoria.com",
    "pcprofessionale.it": "redazione@pcprofessionale.it",
    "eloutput.com": "hola@eloutput.com",
    "donnaglamour.it": "redazione@donnaglamour.it",
    "larevista.cr": "info@larevista.cr",
    "lodehoy.com.mx": "contacto@lodehoy.com.mx",
    "tuexpertoapps.com": "contacto@tuexperto.com",
    "talkwithfrida.com": "hello@talkwithfrida.com",
    "nourish.com": "press@usenourish.com",
    "moneymentors.ca": "info@moneymentors.ca",
    "ramseysolutions.com": "help@ramseysolutions.com",
    "rent.com": "press@rent.com",
    "sproutsocial.com": "pr@sproutsocial.com",
    "feastandwest.com": "susannah@feastandwest.com",
    "androidguys.com": "scott@androidguys.com",
    "ahealthysliceoflife.com": "brittany@ahealthysliceoflife.com",
    "neathousesweethome.com": "flavia@neathousesweethome.com",
    "berxi.com": "support@berxi.com",
    "ctvnews.ca": "newsonline@ctv.ca",
    "moneygenius.ca": "info@moneygenius.ca",
    "nerdwallet.com": "press@nerdwallet.com",
    "fodmapeveryday.com": "info@fodmapeveryday.com",
    "thesavvysparrow.com": "amy@thesavvysparrow.com",
    "cbsnews.com": "tips@cbsnews.com",
    "adhdcentre.co.uk": "enquiries@adhdcentre.co.uk",
    "milesopedia.com": "info@milesopedia.com",
    "whistleout.com": "media@whistleout.com",
    "pcmag.com": "tips@pcmag.com",
    "foodandwine.com": "letters@foodandwine.com",
    "thewaystowealth.com": "rj@thewaystowealth.com",
    "milliondollarjourney.com": "frugaltrader@milliondollarjourney.com",
    "creditcanada.com": "info@creditcanada.com",
    "cbc.ca": "cbcnewstips@cbc.ca",
    "hibobbie.com": "press@hibobbie.com",
    "springfinancial.ca": "info@springfinancial.ca",
    "lifewire.com": "contact@lifewire.com",
    "narcity.com": "tips@narcity.com",
    "amusingfoodie.com": "jen@amusingfoodie.com",
    "gobankingrates.com": "editor@gobankingrates.com",
    "coolmompicks.com": "info@coolmompicks.com",
    "food52.com": "editors@food52.com",
    "muscleandfitness.com": "editorial@muscleandfitness.com",
    "canadianfoodfocus.org": "info@canadianfoodfocus.org",
    "npr.org": "lifekit@npr.org",
    "allrecipes.com": "editorial@allrecipes.com",
    "eatthis.com": "tips@eatthis.com",
    "hip2save.com": "collin@hip2save.com",
    "daringfireball.net": "gruber@daringfireball.net",
    "osnews.com": "thom@osnews.com",
}

NON_PERSON_AUTHOR_KEYWORDS: Set[str] = {
    "blog", "account", "business", "insider", "common", "sense", "media", "control",
    "diva", "cool", "english", "deutsche", "glasfaser", "electronic", "products",
    "elite", "sports", "clubs", "featured", "redaktion", "redacción", "redacao",
    "redazione", "federated", "insurance", "fill", "your", "plate", "food", "network",
    "kitchen", "anzeiger", "great", "day", "houston", "grupa", "wirtualna", "polska",
    "hasty", "reader", "impact", "health", "sharing", "intown", "suites", "revista",
    "life", "kit", "mac", "mortgage", "investors", "group", "oaken", "financial",
    "click", "root", "legal", "paleo", "leaper", "parse", "internet", "passiv", "logo",
    "raclette", "suisse", "retail", "signet", "spoon", "university", "sun", "journal",
    "thesis", "whisperer", "unsung", "weekly", "digest", "women", "money", "équipe",
    "equipe", "team", "editorial", "staff", "admin", "editor", "editors", "news",
    "press", "official", "inc", "llc", "ltd", "corp", "desk", "magazine", "channel",
    "salut", "bonjour", "techbit", "frugal",
}


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
    """
    Normalizes user-supplied IDs like '#1', 'Email #1', 'email a', 'R3', 'CSV-3' -> '1', 'A', or 'R3'.
    """
    cleaned = raw_id.strip()
    cleaned = re.sub(r"^email\s*", "", cleaned, flags=re.IGNORECASE).strip()
    cleaned = cleaned.lstrip("#").strip().upper()
    m_csv = re.match(r"^CSV[-_]?([0-9]+)$", cleaned)
    if m_csv:
        return f"R{m_csv.group(1)}"
    return cleaned


def extract_domain_from_url(url_or_domain: str) -> str:
    """Normalizes a URL or domain string to its bare referring domain (without www.)."""
    val = url_or_domain.strip().lower()
    if not val:
        return ""
    if "://" in val:
        parsed = urllib.parse.urlparse(val)
        host = parsed.netloc.lower()
    else:
        host = val.split("/")[0].lower()
    host = host.split(":")[0]
    if host.startswith("www."):
        host = host[4:]
    return host


def is_valid_outreach_email(raw_email: str) -> bool:
    """Returns True if and only if raw_email is a valid, non-junk, non-placeholder outreach email."""
    return bool(clean_candidate_email(raw_email))


# Valid TLDs longer than 4 chars; anything else appended after a 2-3 char ccTLD/gTLD is a concatenated text artifact
VALID_LONG_TLDS: Set[str] = {
    "agency", "blog", "cloud", "company", "digital", "email", "events", "family",
    "finance", "foundation", "global", "group", "guide", "health", "house", "market",
    "marketing", "media", "money", "network", "online", "press", "recipes", "report",
    "review", "reviews", "school", "services", "social", "solutions", "space", "store",
    "studio", "support", "Swiss", "swiss", "systems", "today", "tools", "travel",
    "video", "watch", "website", "world",
}


def clean_candidate_email(raw: str) -> str:
    """Cleans and validates a candidate email address, returning '' if junk or invalid."""
    if not raw:
        return ""
    e = urllib.parse.unquote(raw).strip().lower()
    e = re.sub(r"^(?:u003e|u003c|u0020|20)+", "", e)
    e = e.strip(". ,;:/!?)(\"'[]<>")
    m = EMAIL_FIND_REGEX.match(e)
    if not m:
        return ""
    e = m.group(1)
    if not EMAIL_REGEX.match(e) or "@" not in e:
        return ""
    local, dom = e.split("@", 1)
    if dom.startswith("www."):
        dom = dom[4:]
        e = f"{local}@{dom}"
    if not local or not dom or len(local) > 64 or len(dom) > 120:
        return ""
    dom_parts = dom.split(".")
    if len(dom_parts) < 2:
        return ""
    tld = dom_parts[-1]
    # Strip concatenated word after a 2-letter ccTLD like 'redakcia@standard.sk.touto' -> 'redakcia@standard.sk'
    if len(dom_parts) >= 3 and len(dom_parts[-2]) == 2 and len(tld) > 4 and tld not in VALID_LONG_TLDS:
        dom = ".".join(dom_parts[:-1])
        tld = dom_parts[-2]
        e = f"{local}@{dom}"
    if len(tld) > 4 and tld not in VALID_LONG_TLDS:
        return ""
    if any(e.endswith(ext) or dom.endswith(ext) for ext in JUNK_EMAIL_EXTENSIONS):
        return ""
    if dom in JUNK_EMAIL_DOMAINS or any(dom.endswith("." + bd) for bd in JUNK_EMAIL_DOMAINS):
        return ""
    if re.fullmatch(r"[0-9]+", local) or re.match(r"^u00[0-9a-f]{2}", local):
        return ""
    if len(local) >= 22 and re.fullmatch(r"[0-9a-f]+", local):
        return ""
    if local in (
        "sales", "ads", "ad", "adv", "abo", "newsletter", "webmaster", "postmaster",
        "hostmaster", "hr", "legal", "privacy", "dmca", "abuse", "jobs", "careers",
        "events", "alerts", "budgets", "exams", "awards", "overseas", "tokyo",
        "programming", "ops",
    ):
        return ""
    for bad_sub in JUNK_LOCAL_SUBSTRINGS:
        if bad_sub in local:
            return ""
    return e


def _decode_cfemail(hex_str: str) -> str:
    try:
        r = int(hex_str[:2], 16)
        return "".join(chr(int(hex_str[i : i + 2], 16) ^ r) for i in range(2, len(hex_str), 2))
    except Exception:
        return ""


def _root_domain_token(domain: str) -> str:
    d = extract_domain_from_url(domain)
    if not d:
        return ""
    parts = d.split(".")
    # Handle 2-part country-code SLDs like environment.sa.gov.au or alliance-scotland.org.uk
    if len(parts) >= 3 and parts[-2] in ("co", "com", "org", "net", "gov", "edu", "ac") and len(parts[-1]) == 2:
        if len(parts) >= 4 and len(parts[-3]) == 2:
            return parts[-4]
        return parts[-3]
    return parts[0]


def _score_discovered_email(
    email_addr: str,
    site_domain: str,
    author: str,
    from_mailto: bool = False,
    from_contact_page: bool = False,
) -> int:
    local, dom = email_addr.split("@", 1)
    s_dom = extract_domain_from_url(site_domain)
    s_root = _root_domain_token(s_dom)
    e_root = _root_domain_token(dom)

    same_domain = (
        dom == s_dom
        or dom.endswith("." + s_dom)
        or s_dom.endswith("." + dom)
        or (len(s_root) >= 4 and s_root == e_root)
    )
    is_personal = dom in PERSONAL_EMAIL_DOMAINS
    auth_parts = [p.lower() for p in re.findall(r"[A-Za-z]{3,}", author or "")]

    if not same_domain and not is_personal and not from_mailto:
        return -100
    if is_personal and not from_mailto:
        if (len(s_root) < 4 or s_root not in local) and not any(ap in local for ap in auth_parts):
            return -100
    # Prevent unrelated third-party mailto links embedded inside article bodies unless from contact page or editorial local
    if not same_domain and not is_personal and not from_contact_page:
        editorial_locals = (
            "tips", "editor", "editors", "editorial", "redazione", "redaccion",
            "redacao", "redakcja", "redaktion", "pitch", "pitches", "news",
            "newsroom", "press", "imprensa", "stampa", "prensa", "letters",
        )
        if not any(local == k or local.startswith(k + ".") or local.startswith(k + "-") for k in editorial_locals):
            if not any(ap in local for ap in auth_parts):
                return -100

    score = 0
    if same_domain:
        score += 50
    if from_mailto:
        score += 30
    if from_contact_page:
        score += 20

    if auth_parts and any(ap == local or local.startswith(ap) for ap in auth_parts):
        score += 35

    preferred_locals = {
        "tips": 34, "editor": 32, "editors": 32, "editorial": 32,
        "redazione": 30, "redaccion": 30, "redacao": 30, "redakcja": 30, "redaktion": 30,
        "pitch": 30, "pitches": 30, "news": 28, "newsroom": 28,
        "press": 25, "imprensa": 25, "stampa": 25, "prensa": 25,
        "hello": 26, "hi": 26, "hey": 24, "hola": 26, "ciao": 25, "ola": 25,
        "contact": 25, "contacto": 25, "contato": 25, "contatti": 25, "kontakt": 25,
        "info": 23, "team": 21, "blog": 22, "content": 21, "media": 19,
        "inquiries": 21, "enquiries": 21, "help": 16, "support": 15,
        "mail": 16, "office": 15, "geral": 18, "redakce": 25,
    }
    for prefix, pts in preferred_locals.items():
        if local == prefix or local.startswith(prefix + ".") or local.startswith(prefix + "-"):
            score += pts
            break

    if local in ("ads", "advertising", "adops", "sales", "sponsorship", "sponsorships", "custserv", "customerservice", "subscriptions"):
        score -= 20

    return score


def _fetch_html_polite(url: str, ssl_ctx: ssl.SSLContext, timeout: float = 3.5) -> str:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            ),
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout, context=ssl_ctx) as resp:
        ctype = resp.headers.get("Content-Type", "").lower()
        if ctype and "text/html" not in ctype and "application/xhtml" not in ctype:
            return ""
        raw = resp.read(220_000)
        return raw.decode("utf-8", errors="ignore")


def _extract_emails_from_html(
    page_html: str,
    site_domain: str,
    author: str,
    from_contact_page: bool = False,
) -> Dict[str, int]:
    candidates: Dict[str, int] = {}
    for m in MAILTO_REGEX.finditer(page_html):
        cleaned = clean_candidate_email(m.group(1))
        if cleaned:
            sc = _score_discovered_email(
                cleaned, site_domain, author, from_mailto=True, from_contact_page=from_contact_page
            )
            if sc > candidates.get(cleaned, -999):
                candidates[cleaned] = sc

    for m in CFEMAIL_REGEX.finditer(page_html):
        decoded = _decode_cfemail(m.group(1))
        cleaned = clean_candidate_email(decoded)
        if cleaned:
            sc = _score_discovered_email(
                cleaned, site_domain, author, from_mailto=True, from_contact_page=from_contact_page
            )
            if sc > candidates.get(cleaned, -999):
                candidates[cleaned] = sc

    unescaped = html.unescape(page_html)
    for m in EMAIL_FIND_REGEX.finditer(unescaped):
        cleaned = clean_candidate_email(m.group(1))
        if cleaned:
            sc = _score_discovered_email(
                cleaned, site_domain, author, from_mailto=False, from_contact_page=from_contact_page
            )
            if sc > candidates.get(cleaned, -999):
                candidates[cleaned] = sc

    return {e: sc for e, sc in candidates.items() if sc > 0}


def discover_contact_email_for_row(row: Dict[str, str], ssl_ctx: ssl.SSLContext) -> Tuple[str, str]:
    """
    Discovers the best verified, non-junk editorial/author/site contact email for a CSV row.
    Returns (domain, discovered_email).
    """
    dom = extract_domain_from_url(row.get("Referring_Domain", ""))
    if not dom:
        return "", ""

    existing = clean_candidate_email(row.get("Contact_Email", ""))
    if existing:
        return dom, existing

    if dom in KNOWN_DOMAIN_EMAILS:
        known = clean_candidate_email(KNOWN_DOMAIN_EMAILS[dom])
        if known:
            return dom, known

    author = row.get("Author", "")
    article_url = row.get("Best_Referring_Page_URL", "").strip()
    parsed = urllib.parse.urlparse(article_url) if article_url else None
    base_origin = (
        f"{parsed.scheme}://{parsed.netloc}"
        if (parsed and parsed.scheme and parsed.netloc)
        else f"https://{dom}"
    )

    all_candidates: Dict[str, int] = {}
    contact_links: List[str] = []

    for start_url in (base_origin + "/", article_url):
        if not start_url:
            continue
        try:
            page_html = _fetch_html_polite(start_url, ssl_ctx=ssl_ctx, timeout=3.2)
            if page_html:
                cands = _extract_emails_from_html(page_html, dom, author, from_contact_page=False)
                for e, sc in cands.items():
                    all_candidates[e] = max(all_candidates.get(e, -999), sc)
                if any(sc >= 75 for sc in all_candidates.values()):
                    break
                for hm in CONTACT_HREF_REGEX.finditer(page_html):
                    href = hm.group(1).strip()
                    full_u = urllib.parse.urljoin(start_url, href)
                    u_dom = extract_domain_from_url(full_u)
                    if (u_dom == dom or u_dom.endswith("." + dom)) and full_u not in contact_links:
                        contact_links.append(full_u)
        except Exception:
            pass

    if not any(sc >= 65 for sc in all_candidates.values()):
        probe_urls = list(contact_links[:2])
        for fallback_path in ("/contact", "/contact-us", "/about", "/impressum", "/contacto", "/contatti", "/contato"):
            candidate_url = base_origin + fallback_path
            if candidate_url not in probe_urls:
                probe_urls.append(candidate_url)
            if len(probe_urls) >= 3:
                break
        for u in probe_urls[:3]:
            try:
                c_html = _fetch_html_polite(u, ssl_ctx=ssl_ctx, timeout=3.0)
                if c_html:
                    cands = _extract_emails_from_html(c_html, dom, author, from_contact_page=True)
                    for e, sc in cands.items():
                        all_candidates[e] = max(all_candidates.get(e, -999), sc)
                    if any(sc >= 70 for sc in all_candidates.values()):
                        break
            except Exception:
                pass

    if not all_candidates:
        return dom, ""

    best_email = sorted(all_candidates.items(), key=lambda kv: (-kv[1], len(kv[0]), kv[0]))[0][0]
    return dom, best_email


def enrich_tracker_csv(csv_path: Path = TRACKER_CSV, max_workers: int = 64) -> int:
    """
    Enriches docs/ahrefs-competitor-intersect-tracker.csv in-place with:
      - Has_Dead_GroceryIQ_Link (Yes/No)
      - Has_Dead_ZipList_Link (Yes/No)
      - Competitors_Linked_Count (integer)
      - Contact_Email (verified, non-junk editorial/author/site contact email)
    Returns total number of rows with a valid Contact_Email.
    """
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV tracker not found: {csv_path}")

    with csv_path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        orig_fields = list(reader.fieldnames or [])
        rows = list(reader)

    # Ensure enriched columns exist in fieldnames in a clean order before Status
    extra_cols = [
        "Competitors_Linked_Count",
        "Has_Dead_GroceryIQ_Link",
        "Has_Dead_ZipList_Link",
        "Contact_Email",
    ]
    new_fields: List[str] = []
    for col in orig_fields:
        if col == "Status":
            for ec in extra_cols:
                if ec not in orig_fields and ec not in new_fields:
                    new_fields.append(ec)
        if col not in new_fields:
            new_fields.append(col)
    for ec in extra_cols:
        if ec not in new_fields:
            new_fields.append(ec)

    # Populate derived columns first
    for row in rows:
        comps_raw = row.get("Competitors_Linked", "")
        comp_items = [c.strip() for c in comps_raw.split(",") if c.strip()]
        comps_lower = comps_raw.lower()
        count_val = row.get("Competitor_Intersect_Count") or str(len(comp_items))
        row["Competitors_Linked_Count"] = str(count_val).strip()
        row["Has_Dead_GroceryIQ_Link"] = "Yes" if "groceryiq" in comps_lower else "No"
        row["Has_Dead_ZipList_Link"] = "Yes" if "ziplist" in comps_lower else "No"
        # Clean any existing Contact_Email or apply KNOWN_DOMAIN_EMAILS
        dom = extract_domain_from_url(row.get("Referring_Domain", ""))
        existing = clean_candidate_email(row.get("Contact_Email", ""))
        if not existing and dom in KNOWN_DOMAIN_EMAILS:
            existing = clean_candidate_email(KNOWN_DOMAIN_EMAILS[dom])
        row["Contact_Email"] = existing

    rows_to_probe = [r for r in rows if not r.get("Contact_Email")]
    print(
        f"Enriching {csv_path.relative_to(REPO_ROOT)}: {len(rows)} total rows "
        f"({len(rows) - len(rows_to_probe)} already mapped, probing {len(rows_to_probe)} live domains with {max_workers} workers)..."
    )

    ssl_ctx = ssl.create_default_context()
    ssl_ctx.check_hostname = False
    ssl_ctx.verify_mode = ssl.CERT_NONE

    t0 = time.time()
    discovered_by_domain: Dict[str, str] = {}
    if rows_to_probe:
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            fut_map = {executor.submit(discover_contact_email_for_row, r, ssl_ctx): r for r in rows_to_probe}
            completed = 0
            for fut in as_completed(fut_map):
                completed += 1
                try:
                    dom, email_addr = fut.result()
                    if dom and email_addr:
                        discovered_by_domain[dom] = email_addr
                except Exception:
                    pass
                if completed % 150 == 0 or completed == len(rows_to_probe):
                    print(
                        f"  Probed {completed}/{len(rows_to_probe)} domains "
                        f"(+{len(discovered_by_domain)} live emails discovered, {time.time() - t0:.1f}s elapsed)",
                        flush=True,
                    )

    # Apply discovered emails & update Status for already-sent domains
    sent_log = load_sent_log(SENT_LOG_PATH)
    sent_domains: Set[str] = set()
    sent_rcpts: Set[str] = set(k.lower() for k in sent_log.get("sent_by_recipient", {}).keys())
    for entry in sent_log.get("history", []):
        for u in entry.get("target_urls", []):
            d = extract_domain_from_url(u)
            if d:
                sent_domains.add(d)

    total_with_email = 0
    tier_counts: Dict[str, int] = {}
    for row in rows:
        dom = extract_domain_from_url(row.get("Referring_Domain", ""))
        if not row.get("Contact_Email") and dom in discovered_by_domain:
            row["Contact_Email"] = discovered_by_domain[dom]
        email_val = clean_candidate_email(row.get("Contact_Email", ""))
        row["Contact_Email"] = email_val
        if email_val:
            total_with_email += 1
            tier = row.get("Outreach_Tier", "Unknown")
            tier_counts[tier] = tier_counts.get(tier, 0) + 1
        if dom in sent_domains or (email_val and email_val.lower() in sent_rcpts):
            if row.get("Status", "").strip() in ("", "Not Contacted"):
                row["Status"] = "Contacted (2026-10-14)"

    with csv_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=new_fields)
        writer.writeheader()
        writer.writerows(rows)

    print(f"\nCSV Enrichment Complete: {total_with_email}/{len(rows)} rows now have a verified Contact_Email.")
    for tier_name, cnt in sorted(tier_counts.items()):
        print(f"  - {tier_name}: {cnt} verified emails")
    return total_with_email


def sanitize_no_brackets(text: str) -> str:
    """Replaces any square brackets [foo] in external article titles with (foo) to guarantee zero bracket placeholders."""
    return text.replace("[", "(").replace("]", ")")


def extract_author_first_name(author_raw: str, site_domain: str) -> Optional[str]:
    """
    Returns the author's first name if `Author` is a real person's name, or None if blank/editorial/brand.
    """
    if not author_raw:
        return None
    cleaned = author_raw.strip()
    if not cleaned:
        return None
    # Strip titles like Dr. or Dr
    cleaned = re.sub(r"^(?:Dr\.?|Prof\.?)\s+", "", cleaned, flags=re.IGNORECASE).strip()
    # Fix stuck month suffixes from scraper artifacts like 'Alyssa FurtadoSeptember'
    cleaned = re.sub(
        r"(January|February|March|April|May|June|July|August|September|October|November|December)$",
        "",
        cleaned,
    ).strip()

    tokens = [t.strip(".,;:/!?)(\"'[]<>") for t in cleaned.split() if t.strip(".,;:/!?)(\"'[]<>")]
    if not tokens or len(tokens) > 4:
        return None

    lower_tokens = [t.lower() for t in tokens]
    if any(t in NON_PERSON_AUTHOR_KEYWORDS for t in lower_tokens):
        return None

    # Allow 2-3 token personal names even if the author's name is also their eponymous blog domain
    # (e.g. 'Casey Liss' on caseyliss.com, 'Kalyn Brooke' on kalynbrooke.com), while still rejecting
    # single-token brand domains.
    dom_root = _root_domain_token(site_domain)
    if len(tokens) == 1 and dom_root and len(dom_root) >= 4 and lower_tokens[0] == dom_root:
        return None

    first = tokens[0]
    # Must be standard Latin/accented/extended-Latin letters (supporting hyphenated names like Mary-Ann or Maja-Lina)
    if not (2 <= len(first) <= 18):
        return None
    if not re.fullmatch(
        r"[A-ZÀ-ÖØ-ÞĀ-Ž][a-zà-öø-ÿā-ž']+(?:-[A-ZÀ-ÖØ-ÞĀ-Ža-zà-öø-ÿā-ž][a-zà-öø-ÿā-ž']+)*",
        first,
    ):
        return None
    return first


def derive_site_name(domain: str, page_title: str) -> str:
    """Derives a clean, human-readable publication name from the domain and page title."""
    dom = extract_domain_from_url(domain)
    dom_root = _root_domain_token(dom)

    # Known clean brand names (checked first so trailing page-title crumbs like 'News - Concordia University' don't override)
    brand_overrides = {
        "amnhealthcare.com": "AMN Healthcare",
        "concordia.ca": "Concordia University",
        "santander.pt": "Santander Salto",
        "bancobpm.it": "Banco BPM",
        "scarymommy.com": "Scary Mommy",
        "grocerydive.com": "Grocery Dive",
        "hellobrigit.com": "Brigit",
        "womansday.com": "Woman's Day",
        "moneycrashers.com": "Money Crashers",
        "latimes.com": "Los Angeles Times",
        "huffingtonpost.co.uk": "HuffPost UK",
        "ing.it": "ING Italia",
        "mynexttablet.com": "MyNextTablet",
        "elpais.com": "EL PAÍS",
        "computerworld.com": "Computerworld",
        "sbs.com.au": "SBS Food",
        "womansworld.com": "Woman's World",
        "indailysa.com.au": "InDaily",
        "firstforwomen.com": "First For Women",
        "yellowhammernews.com": "Yellowhammer News",
        "carlahall.com": "Carla Hall",
        "nurturelife.com": "Nurture Life",
        "melaniejadedesign.com": "Melanie Jade Design",
        "monee-app.com": "Monee",
        "adultdownsyndrome.org": "Adult Down Syndrome Center",
        "beforepay.com.au": "Beforepay",
        "meetpenny.com": "Meet Penny",
        "ai-mealplan.com": "AI MealPlan",
        "moneypantry.com": "MoneyPantry",
        "today.com": "TODAY",
        "huffpost.com": "HuffPost",
        "buzzfeed.com": "BuzzFeed",
        "thestreet.com": "TheStreet",
        "wisebread.com": "Wise Bread",
        "itechguides.com": "iTechGuides",
        "nbcnews.com": "NBC News",
        "betakit.com": "BetaKit",
        "fastcompany.com": "Fast Company",
        "engadget.com": "Engadget",
        "bustle.com": "Bustle",
        "money.com": "Money",
        "nbcwashington.com": "NBC4 Washington",
        "reviewed.com": "Reviewed",
        "moneytalksnews.com": "Money Talks News",
        "phandroid.com": "Phandroid",
        "thefinancialdiet.com": "The Financial Diet",
        "momtrends.com": "Mom Trends",
        "xataka.com": "Xataka",
        "consumer.es": "Eroski Consumer",
        "ratehub.ca": "Ratehub",
        "lifehack.org": "Lifehack",
        "telemundo.com": "Telemundo",
        "bioguia.com": "Bioguia",
        "familyfocusblog.com": "Family Focus Blog",
        "lowestrates.ca": "LowestRates.ca",
        "androidauthority.com": "Android Authority",
        "uniplaces.com": "Uniplaces",
        "forbes.com": "Forbes",
        "nytimes.com": "The New York Times",
        "apnews.com": "AP News",
        "globalnews.ca": "Global News",
        "makeuseof.com": "MakeUseOf",
        "retaildive.com": "Retail Dive",
        "parade.com": "Parade",
        "bgr.com": "BGR",
        "slashgear.com": "SlashGear",
        "tastingtable.com": "Tasting Table",
        "thedailymeal.com": "The Daily Meal",
        "androidheadlines.com": "Android Headlines",
        "triplepundit.com": "TriplePundit",
        "tidbits.com": "TidBITS",
        "cheapism.com": "Cheapism",
        "moneysavingmom.com": "Money Saving Mom",
        "theworkathomewoman.com": "The Work at Home Woman",
        "iheartorganizing.com": "I Heart Organizing",
        "momadvice.com": "Mom Advice",
        "supermarketguru.com": "SupermarketGuru",
        "livingonthecheap.com": "Living on the Cheap",
        "lifeskillsadvocate.com": "Life Skills Advocate",
        "nogettingoffthistrain.com": "No Getting Off This Train",
        "goodenessgracious.com": "Goodeness Gracious",
        "liveabout.com": "LiveAbout",
        "adweek.com": "Adweek",
        "digitaltrends.com": "Digital Trends",
        "techrepublic.com": "TechRepublic",
        "baltimoresun.com": "Baltimore Sun",
        "marketingdive.com": "Marketing Dive",
        "softonic.com": "Softonic",
        "pressherald.com": "Portland Press Herald",
        "komonews.com": "KOMO News",
        "alternet.org": "AlterNet",
        "fatherly.com": "Fatherly",
        "chatelaine.com": "Chatelaine",
        "foodprint.org": "FoodPrint",
        "foodtank.com": "Food Tank",
        "redbookmag.com": "Redbook",
        "whatsyourgrief.com": "What's Your Grief",
        "moneycheck.com": "MoneyCheck",
        "geekdad.com": "GeekDad",
        "spafinder.com": "Spafinder",
        "skiptomylou.org": "Skip To My Lou",
        "cooksmarts.com": "Cook Smarts",
        "noobpreneur.com": "Noobpreneur",
        "asweatlife.com": "aSweatLife",
        "imom.com": "iMOM",
        "thebudgetnista.com": "The Budgetnista",
        "modernparentsmessykids.com": "Modern Parents Messy Kids",
        "wondermomwannabe.com": "Wondermom Wannabe",
        "familytoday.com": "FamilyToday",
        "todaysmama.com": "Today's Mama",
        "banktrack.com": "Banktrack",
        "askthemoneycoach.com": "Ask The Money Coach",
        "talkwithfrida.com": "Frida",
        "nourish.com": "Nourish",
        "moneymentors.ca": "Money Mentors",
        "ramseysolutions.com": "Ramsey Solutions",
        "feastandwest.com": "Feast + West",
        "androidguys.com": "AndroidGuys",
        "ahealthysliceoflife.com": "A Healthy Slice of Life",
        "neathousesweethome.com": "Neat House. Sweet Home.",
        "ctvnews.ca": "CTV News",
        "moneygenius.ca": "moneyGenius",
        "nerdwallet.com": "NerdWallet",
        "fodmapeveryday.com": "FODMAP Everyday",
        "thesavvysparrow.com": "The Savvy Sparrow",
        "cbsnews.com": "CBS News",
        "adhdcentre.co.uk": "The ADHD Centre",
        "pcmag.com": "PCMag",
        "foodandwine.com": "Food & Wine",
        "thewaystowealth.com": "The Ways To Wealth",
        "milliondollarjourney.com": "Million Dollar Journey",
        "creditcanada.com": "Credit Canada",
        "lifewire.com": "Lifewire",
        "amusingfoodie.com": "Amusing Foodie",
        "gobankingrates.com": "GOBankingRates",
        "coolmompicks.com": "Cool Mom Picks",
        "food52.com": "Food52",
        "muscleandfitness.com": "Muscle & Fitness",
        "canadianfoodfocus.org": "Canadian Food Focus",
        "npr.org": "NPR Life Kit",
        "allrecipes.com": "Allrecipes",
        "eatthis.com": "Eat This, Not That!",
        "hip2save.com": "Hip2Save",
        "daringfireball.net": "Daring Fireball",
        "reasonstoskipthehousework.com": "Reasons To Skip The Housework",
        "archaeologypodcastnetwork.com": "Archaeology Podcast Network",
        "7hauben.com": "7Hauben",
        "derstandard.at": "DER STANDARD",
        "lovefoodhatewaste.co.nz": "Love Food Hate Waste",
        "collegefashion.net": "College Fashion",
        "bestversionmedia.com": "Best Version Media",
        "thesocietypages.org": "The Society Pages",
        "gluesticksgumdrops.com": "Gluesticks & Gumdrops",
        "truemoneysaver.com": "True Money Saver",
        "the1thing.com": "The ONE Thing",
        "sassymamasg.com": "Sassy Mama",
        "everydayhealth.com": "Everyday Health",
        "Consumidormoderno.com.br": "Consumidor Moderno",
        "consumidormoderno.com.br": "Consumidor Moderno",
        "ideepercomputeredinternet.com": "Idee per Computer ed Internet",
        "canaleenergia.com": "Canale Energia",
        "comiendoconmaria.com": "Comiendo con María",
        "eluniversal.com.mx": "El Universal",
        "abc15.com": "ABC15 Arizona",
        "gpb.org": "GPB",
        "kk.org": "Cool Tools",
        "cua.com": "CUA",
    }
    if dom in brand_overrides:
        return brand_overrides[dom]

    # Check if page_title has a clean trailing site name after ' | ', ' - ', ' – ', ' • '
    for sep in (" | ", " - ", " – ", " — ", " • "):
        if sep in page_title:
            candidate = sanitize_no_brackets(page_title.rsplit(sep, 1)[-1]).strip()
            cand_alnum = re.sub(r"[^a-z0-9]", "", candidate.lower())
            if 2 <= len(candidate) <= 28 and dom_root and (dom_root[:4] in cand_alnum or cand_alnum[:4] in dom_root):
                return candidate

    if not dom_root:
        return "Editorial"
    words = [w for w in re.split(r"[-_]+", dom_root) if w]
    return " ".join(w.capitalize() for w in words) if words else dom


def format_competitors_human(comps_raw: str, conjunction: str = "and") -> str:
    """Formats 'AnyList, OutOfMilk, GroceryIQ' -> 'AnyList, Out of Milk, and Grocery iQ'."""
    name_map = {
        "OutOfMilk": "Out of Milk",
        "GroceryIQ": "Grocery iQ",
        "AnyList": "AnyList",
        "OurGroceries": "OurGroceries",
        "Bring!": "Bring!",
        "Listonic": "Listonic",
        "ZipList": "ZipList",
        "Flipp": "Flipp",
        "Basket": "Basket",
    }
    items = [name_map.get(c.strip(), c.strip()) for c in comps_raw.split(",") if c.strip()]
    if not items:
        return "grocery list apps"
    if len(items) == 1:
        return items[0]
    if len(items) == 2:
        return f"{items[0]} {conjunction} {items[1]}"
    if conjunction == "and":
        return ", ".join(items[:-1]) + f", and {items[-1]}"
    return ", ".join(items[:-1]) + f" {conjunction} {items[-1]}"


def _resolve_pitch_language(row: Dict[str, str], domain: str, page_url: str, raw_title: str) -> str:
    """
    Determines the effective pitch language ('pt', 'es', 'it', or 'en') from the CSV Language column,
    domain/URL path, and article title so bilingual rows (e.g. English article on getunwired.com/es/...)
    are never pitched in the wrong language.
    """
    lang_raw = (row.get("Language") or "en").strip().lower()
    primary_lang = lang_raw.split(",")[0].strip() or "en"
    title_l = f" {raw_title.lower()} "

    # Detect clearly English titles when Language was mis-tagged 'es'/'pt'/'it' due to URL path
    en_markers = (" the ", " best ", " apps ", " how to ", " grocery ", " shopping ", " ways to ", " save money ")
    if primary_lang.startswith(("pt", "es", "it")) and sum(1 for m in en_markers if m in title_l) >= 2:
        return "en"

    if primary_lang.startswith(("pt", "es", "it")):
        return primary_lang[:2]

    # If Language was blank, infer from domain/URL/title
    if not row.get("Language", "").strip():
        if domain.endswith((".es", ".mx", ".pe", ".gt", ".cr", ".cat")) or "/es/" in page_url or "/ca/" in page_url:
            return "es"
        if domain.endswith((".pt", ".br")) or "/pt/" in page_url:
            return "pt"
        if domain.endswith(".it") or "/it/" in page_url:
            return "it"

    return primary_lang


def generate_csv_pitch(row: Dict[str, str]) -> Tuple[str, str, str]:
    """
    Dynamically generates (publication_name, subject, body) for a CSV row with zero placeholders.
    """
    domain = extract_domain_from_url(row.get("Referring_Domain", ""))
    raw_title = (row.get("Best_Referring_Page_Title") or "").strip()
    clean_title = sanitize_no_brackets(raw_title) if raw_title else f"your grocery guide on {domain}"
    # Strip trailing ' | Site Name' from the article title used in prose so it reads naturally
    short_title = clean_title
    for sep in (" | ", " - ", " – ", " — ", " • "):
        if sep in short_title and len(short_title.split(sep)[0].strip()) >= 14:
            short_title = short_title.split(sep)[0].strip()
            break

    page_url = (row.get("Best_Referring_Page_URL") or f"https://{domain}/").strip()
    site_name = sanitize_no_brackets(derive_site_name(domain, clean_title))
    first_name = extract_author_first_name(row.get("Author", ""), domain)

    comps_raw = row.get("Competitors_Linked", "")
    comps_lower = comps_raw.lower()
    comp_items = [c.strip() for c in comps_raw.split(",") if c.strip()]
    comp_count = int(
        row.get("Competitors_Linked_Count")
        or row.get("Competitor_Intersect_Count")
        or len(comp_items)
        or 1
    )
    comps_human = sanitize_no_brackets(format_competitors_human(comps_raw, conjunction="and"))

    has_dead_giq = (
        row.get("Has_Dead_GroceryIQ_Link", "").strip().lower() == "yes"
        or "groceryiq" in comps_lower
    )
    has_dead_zip = (
        row.get("Has_Dead_ZipList_Link", "").strip().lower() == "yes"
        or "ziplist" in comps_lower
    )

    primary_lang = _resolve_pitch_language(row, domain, page_url, raw_title)

    # 1. Native European Portuguese (pt-PT / pt)
    if primary_lang.startswith("pt"):
        comps_pt = sanitize_no_brackets(format_competitors_human(comps_raw, conjunction="e"))
        greeting = f"Olá {first_name}," if first_name else f"Olá equipa editorial da {site_name},"
        subject = f"Sugestão para o artigo \"{short_title}\" (App gratuita em Português)"
        dead_note = ""
        if has_dead_giq or has_dead_zip:
            dead_app = "Grocery iQ e ZipList" if (has_dead_giq and has_dead_zip) else ("Grocery iQ" if has_dead_giq else "ZipList")
            dead_note = f" Notei também que o artigo menciona a {dead_app}, que entretanto foi descontinuada."
        body = (
            f"{greeting}\n\n"
            f"Estive a ler o vosso excelente artigo \"{short_title}\" ({page_url}), onde abordam aplicações de compras como {comps_pt}.{dead_note}\n\n"
            f"Para quem divide as compras da casa entre vários supermercados (como Continente, Pingo Doce, Lidl, Aldi ou Mercadona), as aplicações tradicionais obrigam a manter listas separadas para cada loja.\n\n"
            f"Desenvolvi a StoreHop (https://www.storehop.app/ — gratuita para iOS e Android e totalmente em Português de Portugal) precisamente para quem faz compras em mais do que um supermercado:\n\n"
            f"- Lista Principal Única: adicione cada artigo uma única vez e associe os supermercados onde o costuma comprar (Continente, Pingo Doce, Lidl e Aldi já vêm pré-configurados).\n"
            f"- Baixa Automática Entre Lojas: quando compra e marca um produto no Lidl, ele desaparece automaticamente das listas do Continente e do Pingo Doce para evitar compras duplicadas.\n"
            f"- Ordem de Corredores por Loja: organize as categorias de acordo com o layout físico de cada supermercado.\n"
            f"- 100% Gratuita e Sem Anúncios: uso individual gratuito, sem anúncios, sem necessidade de registo e sem subscrições mensais.\n\n"
            f"Deixo os links oficiais caso considerem útil incluir a StoreHop numa futura atualização do artigo:\n"
            f"- Site oficial: https://www.storehop.app/\n"
            f"- App Store (iOS): https://apps.apple.com/us/app/storehop/id6771575122\n"
            f"- Google Play (Android): https://play.google.com/store/apps/details?id=com.storehop.app\n\n"
            f"Se quiserem testar a partilha familiar em tempo real, terei todo o gosto em ativar gratuitamente a versão StoreHop Premium vitalícia nas vossas contas!\n\n"
            f"Com os melhores cumprimentos,\n"
            f"Derek\n"
            f"Creator of StoreHop\n"
            f"https://www.storehop.app/\n"
        )
        return site_name, subject, body

    # 2. Native Spanish (es)
    if primary_lang.startswith("es"):
        comps_es = sanitize_no_brackets(format_competitors_human(comps_raw, conjunction="y"))
        greeting = f"Hola {first_name}," if first_name else f"Hola equipo editorial de {site_name},"
        subject = f"Sugerencia para el artículo \"{short_title}\" (App gratuita en español)"
        dead_note = ""
        if has_dead_giq or has_dead_zip:
            dead_app = "Grocery iQ y ZipList" if (has_dead_giq and has_dead_zip) else ("Grocery iQ" if has_dead_giq else "ZipList")
            dead_note = f" También noté que el artículo menciona {dead_app}, que ya fue descontinuada y retirada de las tiendas de aplicaciones."
        body = (
            f"{greeting}\n\n"
            f"Leí con mucho interés vuestro artículo \"{short_title}\" ({page_url}), donde mencionáis aplicaciones como {comps_es}.{dead_note}\n\n"
            f"Cuando una familia divide sus compras entre varios supermercados para ahorrar (por ejemplo, Lidl, Aldi, Costco, Walmart o supermercados locales), las apps tradicionales obligan a mantener listas separadas para cada tienda.\n\n"
            f"He creado StoreHop (https://www.storehop.app/ — disponible gratis para iOS y Android y totalmente traducida al español) específicamente para compras en múltiples supermercados:\n\n"
            f"- Una Sola Lista Principal: añade cada producto una vez y etiquétalo con los supermercados donde sueles comprarlo.\n"
            f"- Tachado Automático Entre Tiendas: al marcar la leche como comprada en la primera tienda, desaparece automáticamente de las listas de las demás tiendas para evitar compras duplicadas.\n"
            f"- Orden de Pasillos por Tienda: ordena las categorías de forma independiente según el recorrido de cada supermercado.\n"
            f"- Gratis, Sin Anuncios y Sin Suscripción: 100% libre de publicidad, sin cuenta obligatoria y sin pagos mensuales.\n\n"
            f"Os dejo los enlaces oficiales por si os resulta útil añadirla en una próxima actualización de vuestra guía:\n"
            f"- Sitio web: https://www.storehop.app/\n"
            f"- App Store (iOS): https://apps.apple.com/us/app/storehop/id6771575122\n"
            f"- Google Play (Android): https://play.google.com/store/apps/details?id=com.storehop.app\n\n"
            f"Si queréis probar la sincronización familiar en tiempo real, estaré encantado de activar gratis StoreHop Premium de por vida en vuestras cuentas.\n\n"
            f"Un cordial saludo,\n"
            f"Derek\n"
            f"Creator of StoreHop\n"
            f"https://www.storehop.app/\n"
        )
        return site_name, subject, body

    # 3. Native Italian (it)
    if primary_lang.startswith("it"):
        comps_it = sanitize_no_brackets(format_competitors_human(comps_raw, conjunction="e"))
        greeting = f"Ciao {first_name}," if first_name else f"Ciao redazione di {site_name},"
        subject = f"Suggerimento per l'articolo \"{short_title}\" (App gratuita in italiano)"
        dead_note = ""
        if has_dead_giq or has_dead_zip:
            dead_app = "Grocery iQ e ZipList" if (has_dead_giq and has_dead_zip) else ("Grocery iQ" if has_dead_giq else "ZipList")
            dead_note = f" Ho notato inoltre che l'articolo cita {dead_app}, che non è più attiva."
        body = (
            f"{greeting}\n\n"
            f"Ho letto con molto piacere il vostro articolo \"{short_title}\" ({page_url}), in cui citate {comps_it}.{dead_note}\n\n"
            f"Chi fa la spesa in più supermercati per risparmiare (ad esempio Lidl o Aldi per i prodotti base e il supermercato di fiducia per il resto) si scontra sempre con lo stesso problema nelle app tradizionali: dover gestire liste separate per ogni negozio.\n\n"
            f"Ho sviluppato StoreHop (https://www.storehop.app/ — disponibile gratuitamente per iOS e Android e completamente tradotta in italiano) proprio per risolvere questo problema:\n\n"
            f"- Una Sola Lista Principale: aggiungi un prodotto una sola volta e associ i supermercati in cui lo acquisti.\n"
            f"- Spunta Automatica tra Negozi: quando compri un articolo nel primo negozio e lo spunti, sparisce automaticamente anche dalle liste degli altri supermercati.\n"
            f"- Ordine delle Corsie Personalizzabile per ogni singolo supermercato.\n"
            f"- Gratis, Senza Pubblicità e Senza Abbonamento: nessun account obbligatorio e nessun costo mensile.\n\n"
            f"Ecco i link ufficiali se vi fa piacere aggiungerla alla vostra guida:\n"
            f"- Sito web: https://www.storehop.app/\n"
            f"- App Store (iOS): https://apps.apple.com/us/app/storehop/id6771575122\n"
            f"- Google Play (Android): https://play.google.com/store/apps/details?id=com.storehop.app\n\n"
            f"Se volete testare la sincronizzazione familiare, sarò felice di attivare gratuitamente StoreHop Premium sul vostro account!\n\n"
            f"Un caro saluto,\n"
            f"Derek\n"
            f"Creator of StoreHop\n"
            f"https://www.storehop.app/\n"
        )
        return site_name, subject, body

    # English greeting
    greeting = f"Hi {first_name}," if first_name else f"Hi {site_name} Team,"

    # 4. Double Dead App (both Grocery iQ and ZipList)
    if has_dead_giq and has_dead_zip:
        subject = f"Heads-up: both Grocery iQ and ZipList shut down (on \"{short_title}\")"
        body = (
            f"{greeting}\n\n"
            f"I was reading your article \"{short_title}\" ({page_url}) and noticed it still references both Grocery iQ and ZipList.\n\n"
            f"Just a friendly heads-up that Coupons.com shut down Grocery iQ and Condé Nast shut down ZipList, so both of those links now lead to dead ends.\n\n"
            f"If you're updating the piece with an active, modern replacement for shoppers who split trips across multiple stores (like Aldi, Costco, Trader Joe's, and Walmart), I built a free, 100% ad-free iOS & Android app called StoreHop (https://www.storehop.app/):\n\n"
            f"- One Master List: Tag each item to every store where you shop instead of maintaining separate lists per store.\n"
            f"- Automatic Cross-Store Check-Off: Check an item off at Aldi once, and StoreHop automatically clears it from your Costco and Walmart views so you never accidentally double-buy.\n"
            f"- Per-Store Custom Aisle Order: Sort categories independently to match each store's physical layout.\n"
            f"- 100% Free Core App & Zero Ads: No account required and no recurring monthly or annual subscription.\n\n"
            f"Here are the live links if you'd like to swap StoreHop in:\n"
            f"- Website: https://www.storehop.app/\n"
            f"- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122\n"
            f"- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app\n\n"
            f"Happy to permanently unlock StoreHop Premium on your account if you'd like to test the real-time household sync!\n\n"
            f"Best regards,\n"
            f"Derek\n"
            f"Creator of StoreHop\n"
            f"https://www.storehop.app/\n"
        )
        return site_name, subject, body

    # 5. Single Dead App Reclamation (Grocery iQ OR ZipList)
    if has_dead_giq or has_dead_zip:
        dead_name = "Grocery iQ" if has_dead_giq else "ZipList"
        owner_note = "Coupons.com permanently shut down Grocery iQ" if has_dead_giq else "ZipList was permanently shut down"
        other_comps = [c for c in comp_items if c.lower() not in ("groceryiq", "ziplist")]
        alongside_clause = f" alongside {format_competitors_human(', '.join(other_comps))}" if other_comps else ""
        subject = f"Quick heads-up: discontinued app ({dead_name}) in \"{short_title}\""
        body = (
            f"{greeting}\n\n"
            f"I was reading your guide \"{short_title}\" ({page_url}) and noticed it recommends {dead_name}{alongside_clause}. Just a quick heads-up that {owner_note} and pulled it from the app stores, so readers clicking that link hit a dead end.\n\n"
            f"If you're refreshing the article and want an active, free, ad-free replacement built for households that shop across multiple stores (like Aldi, Costco, Trader Joe's, and Walmart), I built StoreHop (https://www.storehop.app/) for iOS and Android:\n\n"
            f"- One Master List Across Stores: Add an item once and tag every store where you're willing to buy it.\n"
            f"- Automatic Cross-Store Check-Off: Check off milk at Aldi once, and StoreHop automatically removes it from your Costco and Walmart lists so nobody double-buys.\n"
            f"- Custom Per-Store Aisle Ordering: Arrange categories independently for each store's layout.\n"
            f"- Free, 100% Ad-Free & No Subscription: Works local-first with no account required and zero recurring fees.\n\n"
            f"Here are the direct links if you'd like to replace the broken {dead_name} recommendation:\n"
            f"- Website: https://www.storehop.app/\n"
            f"- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122\n"
            f"- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app\n\n"
            f"I'd be happy to unlock permanent StoreHop Premium on your account for free if you'd like to test the shared household feature!\n\n"
            f"Best regards,\n"
            f"Derek\n"
            f"Creator of StoreHop\n"
            f"https://www.storehop.app/\n"
        )
        return site_name, subject, body

    # 6. Multi-Competitor Roundup (Competitors_Linked_Count >= 2)
    if comp_count >= 2:
        subject = f"Multi-store addition for \"{short_title}\""
        body = (
            f"{greeting}\n\n"
            f"I really enjoyed reading \"{short_title}\" ({page_url}) where you covered {comps_human}.\n\n"
            f"One gap I kept running into with existing grocery apps is that they treat each store as a separate silo: if a household splits shopping runs across Aldi, Costco, Trader Joe's, and Walmart, you have to maintain separate lists for every store.\n\n"
            f"I'm an indie developer and built a free, 100% ad-free iOS & Android app called StoreHop (https://www.storehop.app/) specifically for multi-store shoppers:\n\n"
            f"- One Master List: Add an item once and tag every store where you're willing to buy it (e.g., Milk -> Aldi, Costco, Walmart).\n"
            f"- Cross-Store Check-Off Cascade: Walk into Aldi and see only your Aldi items in Aldi's exact aisle order. Check an item off at Aldi once, and StoreHop automatically clears it from your Costco and Walmart views so you never double-buy.\n"
            f"- $0 Core App / No Subscription Fatigue: Free and ad-free with no account required (optional multi-user household invite creation is a one-time $7.99 unlock for the inviter only; everyone they invite joins 100% free).\n\n"
            f"If you ever update \"{short_title}\", StoreHop would be a great fit for readers who shop more than one store:\n"
            f"- Website: https://www.storehop.app/\n"
            f"- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122\n"
            f"- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app\n\n"
            f"Happy to permanently whitelist your account for StoreHop Premium if you'd like to test the real-time household sync!\n\n"
            f"Best regards,\n"
            f"Derek\n"
            f"Creator of StoreHop\n"
            f"https://www.storehop.app/\n"
        )
        return site_name, subject, body

    # 7. Frugal / Flyer / Single-Competitor Topical (Flipp, Basket, AnyList, OurGroceries, Bring!, Listonic, OutOfMilk)
    is_flyer_or_price = ("flipp" in comps_lower) or ("basket" in comps_lower)
    if is_flyer_or_price:
        subject = f"Multi-store shopping list companion for \"{short_title}\""
        opening = (
            f"I really enjoyed your article \"{short_title}\" ({page_url}), especially the recommendation of {comps_human} for comparing grocery deals across stores.\n\n"
            f"Once shoppers spot the best weekly deals across Aldi, Costco, Trader Joe's, and Walmart, the hardest part is keeping track of what to buy where without juggling three or four separate shopping lists on their phone."
        )
    else:
        subject = f"Multi-store grocery app tip for \"{short_title}\""
        opening = (
            f"I really enjoyed reading \"{short_title}\" ({page_url}) and your mention of {comps_human}.\n\n"
            f"One challenge many shoppers run into with traditional list apps like {comps_human} is what happens when you split your routine across multiple stores (like Aldi for produce and dairy, Costco for bulk staples, and Trader Joe's or Walmart for everything else) — you end up maintaining separate, disconnected lists for each store."
        )

    body = (
        f"{greeting}\n\n"
        f"{opening}\n\n"
        f"I built a free, 100% ad-free iOS & Android app called StoreHop (https://www.storehop.app/) designed specifically for multi-store grocery runs:\n\n"
        f"- One Master List: Tag each item to every store where you're willing to buy it (Aldi, Costco, Trader Joe's, Walmart, etc.).\n"
        f"- Automatic Cross-Store Check-Off: Walk into Aldi and see only your Aldi items, sorted in your local Aldi's custom aisle order. Check an item off at Aldi once, and StoreHop automatically clears it from your Costco and Walmart lists so you never double-buy.\n"
        f"- Free, Ad-Free & No Subscription: Zero ads, no required account, and no recurring subscription.\n\n"
        f"If you ever refresh \"{short_title}\", here are the links if you'd like to check it out:\n"
        f"- Website: https://www.storehop.app/\n"
        f"- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122\n"
        f"- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app\n\n"
        f"Happy to unlock permanent StoreHop Premium on your account for free if you'd like to try the shared household sync!\n\n"
        f"Best regards,\n"
        f"Derek\n"
        f"Creator of StoreHop\n"
        f"https://www.storehop.app/\n"
    )
    return site_name, subject, body


def extract_contact_from_heading_or_block(heading_rest: str, block_text: str) -> Tuple[str, str]:
    """
    Extracts (publication_name, contact_channel) from a markdown email section.
    Checks explicit **Contact**: `...` first, then backticked email/URL in heading.
    """
    pub_name = heading_rest.split("(")[0].split("—")[0].strip()

    contact_line_match = re.search(
        r"^\*\*Contact\*\*:\s*`([^`]+)`", block_text, flags=re.MULTILINE
    )
    if contact_line_match:
        return pub_name, contact_line_match.group(1).strip()

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

        subject_match = re.search(
            r"^\*\*Subject\*\*:\s*(.+)$", section_text, flags=re.MULTILINE
        )
        if not subject_match:
            raise ValueError(f"Missing **Subject** in Email {display_id} ({rel_source})")
        subject = subject_match.group(1).strip()

        body_match = re.search(
            r"```text\r?\n(.*?)\r?\n```", section_text, flags=re.DOTALL
        )
        if not body_match:
            raise ValueError(f"Missing ```text code block in Email {display_id} ({rel_source})")
        body = body_match.group(1).strip() + "\n"

        dom = extract_domain_from_url(target_urls[0]) if target_urls else ""

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
                domain=dom,
            )
        )

    return parsed


def parse_csv_outreach_emails(
    csv_path: Path,
    markdown_emails: List[OutreachEmail],
) -> List[OutreachEmail]:
    """
    Loads all rows from docs/ahrefs-competitor-intersect-tracker.csv that have a valid Contact_Email,
    assigning stable IDs R<Priority_Rank> (e.g. R3, R5, ...) and deduplicating by referring domain
    and recipient email against direct-email markdown pitches and higher-ranked CSV rows.
    """
    if not csv_path.exists():
        return []

    rel_source = str(csv_path.relative_to(REPO_ROOT)).replace("\\", "/")

    # Collect domains and recipient emails already covered by direct-email markdown pitches
    md_direct_domains: Set[str] = set()
    md_direct_recipients: Set[str] = set()
    for item in markdown_emails:
        if item.is_direct_email:
            md_direct_recipients.add(item.contact.lower())
            for u in item.target_urls:
                d = extract_domain_from_url(u)
                if d:
                    md_direct_domains.add(d)

    seen_csv_domains: Set[str] = set()
    seen_csv_recipients: Set[str] = set()
    csv_emails: List[OutreachEmail] = []

    with csv_path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            raw_contact = (row.get("Contact_Email") or "").strip()
            contact = clean_candidate_email(raw_contact)
            if not contact:
                continue

            rank_str = (row.get("Priority_Rank") or "").strip()
            if not rank_str.isdigit():
                continue

            dom = extract_domain_from_url(row.get("Referring_Domain") or row.get("Best_Referring_Page_URL") or "")
            rcpt_lower = contact.lower()

            # Deduplicate against curated direct-email markdown pitches (#1-#24 and A-Z)
            if dom in md_direct_domains or rcpt_lower in md_direct_recipients:
                continue

            # Deduplicate across CSV rows by domain and recipient address
            if (dom and dom in seen_csv_domains) or rcpt_lower in seen_csv_recipients:
                continue

            if dom:
                seen_csv_domains.add(dom)
            seen_csv_recipients.add(rcpt_lower)

            item_id = f"R{rank_str}"
            pub_name, subject, body = generate_csv_pitch(row)
            page_url = (row.get("Best_Referring_Page_URL") or f"https://{dom}/").strip()

            csv_emails.append(
                OutreachEmail(
                    id=item_id,
                    display_id=item_id,
                    source_file=rel_source,
                    publication=pub_name,
                    contact=contact,
                    is_direct_email=True,
                    target_urls=[page_url],
                    subject=subject,
                    body=body,
                    domain=dom,
                )
            )

    return csv_emails


def load_all_outreach_emails(include_csv: bool = True) -> List[OutreachEmail]:
    md_emails = parse_markdown_emails(BATCH1_MD) + parse_markdown_emails(AHREFS_MD)
    if include_csv and TRACKER_CSV.exists():
        csv_emails = parse_csv_outreach_emails(TRACKER_CSV, md_emails)
        all_emails = md_emails + csv_emails
    else:
        all_emails = md_emails
    validate_no_placeholders(all_emails)
    return all_emails


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


def get_sent_domains_map(sent_log: Dict[str, dict]) -> Dict[str, str]:
    """Returns a mapping of normalized referring domain -> first sent email ID from sent_log."""
    domain_to_id: Dict[str, str] = {}
    for sent_id, entry in sent_log.get("sent_by_id", {}).items():
        for u in entry.get("target_urls", []):
            d = extract_domain_from_url(u)
            if d and d not in domain_to_id:
                domain_to_id[d] = str(sent_id)
    for entry in sent_log.get("history", []):
        sent_id = str(entry.get("id", ""))
        for u in entry.get("target_urls", []):
            d = extract_domain_from_url(u)
            if d and sent_id and d not in domain_to_id:
                domain_to_id[d] = sent_id
    return domain_to_id


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


def get_item_status(
    item: OutreachEmail,
    sent_log: Dict[str, dict],
    seen_recipients: Optional[set] = None,
    sent_domains: Optional[Dict[str, str]] = None,
) -> str:
    if not item.is_direct_email:
        return "WEB_FORM"
    if item.id in sent_log["sent_by_id"]:
        return "SENT"
    rcpt_lower = item.contact.lower()
    if rcpt_lower in sent_log["sent_by_recipient"]:
        first_id = sent_log["sent_by_recipient"][rcpt_lower]
        ref = f"#{first_id}" if first_id.isdigit() else first_id
        return f"DUP_SENT ({ref})"
    if sent_domains and item.domain and item.domain in sent_domains:
        first_id = sent_domains[item.domain]
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


def compute_list_counts(
    emails: List[OutreachEmail],
    sent_log: Dict[str, dict],
) -> Tuple[List[Tuple[OutreachEmail, str]], Dict[str, int]]:
    seen_rcpts: set = set()
    sent_domains = get_sent_domains_map(sent_log)
    counts = {
        "total": len(emails),
        "direct": 0,
        "forms": 0,
        "pending": 0,
        "sent": 0,
        "dup": 0,
        "csv_direct": 0,
        "md_direct": 0,
    }
    rows: List[Tuple[OutreachEmail, str]] = []

    for item in emails:
        status = get_item_status(item, sent_log, seen_rcpts, sent_domains)
        if item.is_direct_email:
            counts["direct"] += 1
            if item.source_file.endswith(".csv"):
                counts["csv_direct"] += 1
            else:
                counts["md_direct"] += 1
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
    print("=" * 128)
    print(
        f"{'ID':<6} | {'STATUS':<15} | {'PUBLICATION':<24} | {'CONTACT (EMAIL / WEB FORM)':<34} | {'SUBJECT'}"
    )
    print("-" * 128)

    rows, counts = compute_list_counts(emails, sent_log)

    for item, status in rows:
        print(
            f"{item.display_id:<6} | {status:<15} | {truncate(item.publication, 24):<24} | "
            f"{truncate(item.contact, 34):<34} | {truncate(item.subject, 40)}"
        )
        for url in item.target_urls:
            print(f"       └─ Target URL: {url}")

    print("=" * 128)
    print(
        f"Summary: {counts['total']} total pitches loaded "
        f"({counts['direct']} direct email pitches [{counts['md_direct']} markdown + {counts['csv_direct']} CSV; "
        f"{counts['pending']} unique pending, {counts['sent']} sent, {counts['dup']} secondary pitch to same address/domain], "
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
    sent_domains = get_sent_domains_map(sent_log)

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
        if not allow_same_recipient:
            if rcpt_lower in sent_log["sent_by_recipient"]:
                prev_id = sent_log["sent_by_recipient"][rcpt_lower]
                print(
                    f"Skipping Email {item.display_id}: recipient {item.contact} was already emailed in pitch {prev_id}.\n"
                    f"(Pass --allow-same-recipient if you intentionally want to send a follow-up angle to {item.contact}.)",
                    file=sys.stderr,
                )
                return []
            if item.domain and item.domain in sent_domains:
                prev_id = sent_domains[item.domain]
                print(
                    f"Skipping Email {item.display_id}: domain {item.domain} was already emailed in pitch {prev_id}.\n"
                    f"(Pass --allow-same-recipient if you intentionally want to send a follow-up angle to {item.domain}.)",
                    file=sys.stderr,
                )
                return []
        return [item]

    if limit <= 0:
        return []

    selected: List[OutreachEmail] = []
    seen_rcpts_in_batch: set = set()
    seen_domains_in_batch: set = set()
    for item in emails:
        if not item.is_direct_email:
            continue
        if item.id in sent_log["sent_by_id"]:
            continue
        rcpt_lower = item.contact.lower()
        if not allow_same_recipient:
            if rcpt_lower in sent_log["sent_by_recipient"] or rcpt_lower in seen_rcpts_in_batch:
                continue
            if item.domain and (item.domain in sent_domains or item.domain in seen_domains_in_batch):
                continue
        seen_rcpts_in_batch.add(rcpt_lower)
        if item.domain:
            seen_domains_in_batch.add(item.domain)
        selected.append(item)
        if len(selected) >= limit:
            break
    return selected


def run_self_tests() -> None:
    """Runs automated self-tests verifying markdown + CSV parsing, dynamic pitch angles, placeholders, headers, and deduplication."""
    import tempfile

    md_emails = load_all_outreach_emails(include_csv=False)
    assert len(md_emails) == 50, f"Expected 50 curated markdown emails (24 Batch 1 + 26 Ahrefs), got {len(md_emails)}"

    batch1_ids = [e.id for e in md_emails if e.source_file.endswith("outreach-targets-batch-1.md")]
    ahrefs_ids = [e.id for e in md_emails if e.source_file.endswith("ahrefs-link-intersect-report.md")]
    assert batch1_ids == [str(i) for i in range(1, 25)], f"Unexpected Batch 1 IDs: {batch1_ids}"
    assert ahrefs_ids == [chr(ord("A") + i) for i in range(26)], f"Unexpected Ahrefs IDs: {ahrefs_ids}"

    md_direct = [e for e in md_emails if e.is_direct_email]
    md_forms = [e for e in md_emails if not e.is_direct_email]
    assert len(md_direct) == 38, f"Expected 38 markdown direct email pitches, got {len(md_direct)}"
    assert len(md_forms) == 12, f"Expected 12 markdown web form pitches, got {len(md_forms)}"

    # Load full email pool including CSV rows
    all_emails = load_all_outreach_emails(include_csv=True)
    csv_emails = [e for e in all_emails if e.source_file.endswith("ahrefs-competitor-intersect-tracker.csv")]
    assert len(csv_emails) >= 150, (
        f"Expected at least 150 deduplicated CSV-backed outreach pitches, got {len(csv_emails)}"
    )

    # Verify Email #9 has both Target URLs parsed
    email_9 = [e for e in all_emails if e.id == "9"][0]
    assert len(email_9.target_urls) == 2, f"Expected 2 target URLs for #9, got {email_9.target_urls}"

    # Verify normalize_id supports #1, A, R3, CSV-3
    assert normalize_id("#1") == "1"
    assert normalize_id("Email A") == "A"
    assert normalize_id("R3") == "R3"
    assert normalize_id("CSV-3") == "R3"

    # Verify every email across markdown + CSV has valid contact, canonical URL, no Derek Gallardo, no junk email
    seen_csv_rcpts: Set[str] = set()
    seen_csv_domains: Set[str] = set()
    for item in all_emails:
        assert item.publication, f"Empty publication for {item.display_id}"
        assert item.subject, f"Empty subject for {item.display_id}"
        assert "https://www.storehop.app/" in item.body, f"Missing storehop.app URL in {item.display_id}"
        assert "Derek Gallardo" not in item.body and "Derek Gallardo" not in item.subject, (
            f"Forbidden full-name signature in {item.display_id}"
        )
        assert "\nDerek\n" in item.body, f"Missing 'Derek' sign-off in {item.display_id}"
        assert item.target_urls and all(u.startswith("http") for u in item.target_urls), (
            f"Invalid target_urls for {item.display_id}"
        )
        if item.is_direct_email:
            assert is_valid_outreach_email(item.contact), f"Invalid or junk email '{item.contact}' in {item.display_id}"
            assert "@www." not in item.contact, f"Unstripped @www. in email '{item.contact}' in {item.display_id}"
        if item.source_file.endswith(".csv"):
            rcpt_l = item.contact.lower()
            assert rcpt_l not in seen_csv_rcpts, f"Duplicate CSV recipient {rcpt_l} in {item.display_id}"
            seen_csv_rcpts.add(rcpt_l)
            if item.domain:
                assert item.domain not in seen_csv_domains, f"Duplicate CSV domain {item.domain} in {item.display_id}"
                seen_csv_domains.add(item.domain)

    # Verify junk filter rejects known bad addresses and cleans @www. / .touto artifacts
    assert clean_candidate_email("support@www.hastyreader.com") == "support@hastyreader.com"
    assert clean_candidate_email("redakcia@standard.sk.touto") == "redakcia@standard.sk"
    for junk in [
        "user@example.com",
        "err@sentry.io",
        "a@wixpress.com",
        "blog@wordpress.com",
        "icon@2x.png",
        "photo@site.jpg",
        "noreply@domain.org",
        "no-reply@domain.org",
        "privacy@domain.org",
        "datenschutz@unicum.com",
        "dmca@domain.org",
        "abuse@domain.org",
        "legal@domain.org",
        "jobs@domain.org",
        "careers@domain.org",
        "carreiras@eseg.edu.br",
        "reklama@interaktywnie.com",
        "adv@shuba.life",
        "ad@elephantjournal.com",
        "publicfile@kfvs12.com",
        "impressum@vodafone.com",
        "fehlerbericht@mobiflip.de",
    ]:
        assert not is_valid_outreach_email(junk), f"Junk email filter failed to reject: {junk}"

    # Verify author first-name extraction vs editorial/brand fallback
    assert extract_author_first_name("Megan Poinski", "grocerydive.com") == "Megan"
    assert extract_author_first_name("Dr. Rachel Paul", "drrachelpaul.com") == "Rachel"
    assert extract_author_first_name("Alyssa FurtadoSeptember", "ratehub.ca") == "Alyssa"
    assert extract_author_first_name("Mary-Ann Marriott", "wecanhelp.ca") == "Mary-Ann"
    assert extract_author_first_name("Maja-Lina Lauer", "imtest.de") == "Maja-Lina"
    assert extract_author_first_name("Fırat Demirel", "webrazzi.com") == "Fırat"
    assert extract_author_first_name("Casey Liss", "caseyliss.com") == "Casey"
    assert extract_author_first_name("Kalyn Brooke", "kalynbrooke.com") == "Kalyn"
    assert extract_author_first_name("Freddy Frugal", "pentictonnow.com") is None
    assert extract_author_first_name("Business Insider", "financialpost.com") is None
    assert extract_author_first_name("Fill Your Plate", "fillyourplate.org") is None
    assert extract_author_first_name("", "amnhealthcare.com") is None

    # Verify all 5 dynamic CSV pitch angles + bracket sanitization
    _, subj_dbl, body_dbl = generate_csv_pitch({
        "Referring_Domain": "thestreet.com",
        "Best_Referring_Page_Title": "My Frugal Thanksgiving [2026]",
        "Best_Referring_Page_URL": "https://www.thestreet.com/thanksgiving",
        "Author": "Kristin Colella",
        "Language": "en",
        "Competitors_Linked": "AnyList, Flipp, GroceryIQ, ZipList",
        "Competitors_Linked_Count": "4",
        "Has_Dead_GroceryIQ_Link": "Yes",
        "Has_Dead_ZipList_Link": "Yes",
    })
    assert "Hi Kristin," in body_dbl and "both Grocery iQ and ZipList" in body_dbl
    assert "[" not in subj_dbl and "]" not in subj_dbl and "[" not in body_dbl and "]" not in body_dbl

    _, subj_zip, body_zip = generate_csv_pitch({
        "Referring_Domain": "womansday.com",
        "Best_Referring_Page_Title": "Eating Healthy on a Budget",
        "Best_Referring_Page_URL": "https://www.womansday.com/budget",
        "Author": "",
        "Language": "en",
        "Competitors_Linked": "Bring!, OutOfMilk, ZipList",
        "Competitors_Linked_Count": "3",
        "Has_Dead_GroceryIQ_Link": "No",
        "Has_Dead_ZipList_Link": "Yes",
    })
    assert "Hi Woman's Day Team," in body_zip and "ZipList" in subj_zip

    for lang_code, expected_word in [("pt", "Olá Rute,"), ("es", "Hola Miguel,"), ("it", "Ciao redazione di Banco BPM,")]:
        _, s_l, b_l = generate_csv_pitch({
            "Referring_Domain": "santander.pt" if lang_code == "pt" else ("elpais.com" if lang_code == "es" else "bancobpm.it"),
            "Best_Referring_Page_Title": "Lista de compras",
            "Best_Referring_Page_URL": "https://example-news.org/lista",
            "Author": "Rute Ferreira" if lang_code == "pt" else ("Miguel Corcobado" if lang_code == "es" else ""),
            "Language": lang_code,
            "Competitors_Linked": "Bring!, Listonic",
            "Competitors_Linked_Count": "2",
            "Has_Dead_GroceryIQ_Link": "No",
            "Has_Dead_ZipList_Link": "No",
        })
        assert expected_word in b_l, f"Expected '{expected_word}' in {lang_code} pitch, got: {b_l[:80]}"
        assert "https://www.storehop.app/" in b_l

    _, subj_rnd, body_rnd = generate_csv_pitch({
        "Referring_Domain": "grocerydive.com",
        "Best_Referring_Page_Title": "Pantry Staples Growth",
        "Best_Referring_Page_URL": "https://www.grocerydive.com/pantry",
        "Author": "Megan Poinski",
        "Language": "en",
        "Competitors_Linked": "Listonic, OurGroceries, OutOfMilk",
        "Competitors_Linked_Count": "3",
        "Has_Dead_GroceryIQ_Link": "No",
        "Has_Dead_ZipList_Link": "No",
    })
    assert "Hi Megan," in body_rnd and "Cross-Store Check-Off Cascade" in body_rnd

    _, subj_flp, body_flp = generate_csv_pitch({
        "Referring_Domain": "lifewire.com",
        "Best_Referring_Page_Title": "The 8 Best Mobile Shopping Apps",
        "Best_Referring_Page_URL": "https://www.lifewire.com/best-shopping-apps",
        "Author": "",
        "Language": "en",
        "Competitors_Linked": "Flipp",
        "Competitors_Linked_Count": "1",
        "Has_Dead_GroceryIQ_Link": "No",
        "Has_Dead_ZipList_Link": "No",
    })
    assert "Hi Lifewire Team," in body_flp and "Flipp" in body_flp

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
            contact="test@customdomain.org",
            is_direct_email=True,
            target_urls=["https://customdomain.org/post"],
            subject="Hello there",
            body=f"Hi {placeholder_text}, check out https://www.storehop.app/",
        )
        try:
            validate_no_placeholders([fake_item])
            raise AssertionError(f"validate_no_placeholders should have raised ValueError on {placeholder_text}")
        except ValueError:
            pass

    # Verify sent-log deduplication (by ID, by recipient email, and by domain)
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_log = Path(tmpdir) / "outreach-sent-log.json"
        empty_log = load_sent_log(tmp_log)

        assert select_candidates(all_emails, empty_log, target_id=None, limit=0) == []
        assert select_candidates(all_emails, empty_log, target_id=None, limit=-5) == []

        expected_unique_md = 36
        expected_total_unique = expected_unique_md + len(csv_emails)
        all_unique = select_candidates(all_emails, empty_log, target_id=None, limit=5000)
        assert len(all_unique) == expected_total_unique, (
            f"Expected {expected_total_unique} unique selectable recipients, got {len(all_unique)}"
        )

        # Record Email #9 (tips@thekitchn.com) as sent
        msg_9 = build_email_message(email_9.contact, email_9.subject, email_9.body)
        assert msg_9["From"] == "Derek <info@storehop.app>"
        assert msg_9["Reply-To"] == "info@storehop.app"
        assert "@storehop.app>" in msg_9["Message-ID"]

        record_sent_email(tmp_log, email_9, str(msg_9["Message-ID"]), str(msg_9["From"]))
        updated_log = load_sent_log(tmp_log)

        rows, counts = compute_list_counts(all_emails, updated_log)
        assert counts["sent"] == 1, f"Expected 1 sent in list summary after sending #9, got {counts['sent']}"
        assert counts["pending"] == expected_total_unique - 1, (
            f"Expected {expected_total_unique - 1} pending after sending #9, got {counts['pending']}"
        )
        status_by_id = {item.id: st for item, st in rows}
        assert status_by_id["9"] == "SENT"
        assert status_by_id["10"] == "DUP_SENT (#9)"

        assert select_candidates(all_emails, updated_log, target_id="9", limit=1) == []
        assert select_candidates(all_emails, updated_log, target_id="10", limit=1) == []
        assert len(select_candidates(all_emails, updated_log, target_id="10", limit=1, allow_same_recipient=True)) == 1

    print(
        f"ALL SELF-TESTS PASSED: {len(all_emails)} total pitches loaded "
        f"({len(md_direct)} markdown direct + {len(csv_emails)} CSV direct + {len(md_forms)} web forms), "
        f"0 placeholders, headers & domain/email dedup verified."
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="StoreHop Outreach CLI Sender via ForwardEmail.net SMTP (smtp.forwardemail.net:465)"
    )
    mode_group = parser.add_mutually_exclusive_group()
    mode_group.add_argument(
        "--list",
        action="store_true",
        help="List all parsed outreach emails (Batch 1 #1-#24 + Ahrefs A-Z + CSV R1-R1081) and their status.",
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
        help="Run automated verification tests on markdown + CSV parsing, placeholders, headers, and deduplication.",
    )
    mode_group.add_argument(
        "--enrich-csv",
        action="store_true",
        help="Auto-discover and populate verified Contact_Email values across docs/ahrefs-competitor-intersect-tracker.csv.",
    )

    parser.add_argument(
        "--id",
        metavar="ID",
        help="Target a specific email ID (e.g. '1', '#1', 'A', 'Z', 'R3', 'CSV-3').",
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
        help="Allow sending a second distinct pitch to a general tips@ inbox or domain that was already emailed.",
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
    if args.enrich_csv:
        enrich_tracker_csv(TRACKER_CSV)
        return

    if args.self_test:
        run_self_tests()
        return

    dotenv_vars = load_dotenv(ENV_PATH)

    smtp_host = args.smtp_host or get_config_value("FORWARDEMAIL_SMTP_HOST", dotenv_vars, DEFAULT_SMTP_HOST)
    smtp_port = args.smtp_port or int(get_config_value("FORWARDEMAIL_SMTP_PORT", dotenv_vars, str(DEFAULT_SMTP_PORT)))
    from_email = args.from_email or get_config_value("FORWARDEMAIL_USER", dotenv_vars, DEFAULT_FROM_EMAIL)
    from_name = args.from_name or get_config_value("FORWARDEMAIL_FROM_NAME", dotenv_vars, DEFAULT_FROM_NAME)
    smtp_password = get_config_value("FORWARDEMAIL_PASSWORD", dotenv_vars, "")

    emails = load_all_outreach_emails(include_csv=True)
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
