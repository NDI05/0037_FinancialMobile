#!/usr/bin/env python3
"""Revise the complete v0.4 DOCX in place-preserving XML; never overwrite its source.

Run with the document runtime reported by Codex load_workspace_dependencies.
All existing media, relationships, sections and requirement IDs are retained.
"""
from argparse import ArgumentParser
from copy import deepcopy
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import re
from lxml import etree as E

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NS = {"w": W}


def text(p):
    return "".join(p.xpath(".//w:t/text()", namespaces=NS))


def replace_text(p, value):
    assert not p.xpath(".//w:drawing", namespaces=NS), "refuse to remove a drawing"
    properties = p.find(f"{{{W}}}pPr")
    run_properties = p.find(f"{{{W}}}r/{{{W}}}rPr")
    keep = deepcopy(run_properties) if run_properties is not None else None
    for child in list(p):
        if child is not properties:
            p.remove(child)
    run = E.SubElement(p, f"{{{W}}}r")
    if keep is not None:
        run.append(keep)
    E.SubElement(run, f"{{{W}}}t").text = value


def main():
    ap = ArgumentParser()
    ap.add_argument("--source", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    assert args.source.resolve() != args.output.resolve()
    with ZipFile(args.source) as z:
        contents = {n: z.read(n) for n in z.namelist()}
    doc = E.fromstring(contents["word/document.xml"])
    paras = doc.xpath("//w:p", namespaces=NS)

    def update(prefix, value):
        matches = [p for p in paras if text(p).startswith(prefix)
                   and not re.match(r"^(?:FR|NFR)-\d+ (?:to|through)\b", text(p))]
        assert len(matches) == 1, (prefix, len(matches))
        replace_text(matches[0], value)

    changes = {
        "0.4 Consolidated Revision for Review": "0.5 Azure LLM Revision",
        "November 21, 2026": "November 22, 2026",
        "This specification consolidates PAM requirements": "This specification consolidates the complete PAM Android, Go REST and Turso requirements from v0.4, incorporates CR-003 owner-scoped Gmail persistence and CR-004 Azure LLM categorization, and preserves prior financial, privacy, CRUDS and assessment obligations. Version 0.4 remains an archived baseline; this is the current target specification.",
        "Document status is version 0.4": "Document status: v0.5 target requirements for review, issued November 22, 2026. The owner requested Azure for Students as the cloud LLM route and LLM-assisted transaction categorization. AWS was corrected to Microsoft Azure before any AWS resource was provisioned. Provider infrastructure setup, application implementation, benchmark results and formal academic acceptance are separate evidence; this document does not claim they all pass.",
        "Implementation snapshot is design": "Implementation snapshot at the start of this revision: branch feat/c2-android, commit 74eab38. The latest worker handoff records real Gmail import/Go readback and reconnect lifecycle evidence. Existing runtime still contains transitional import/storage paths. Azure CLI authentication and subscription checks are separate from category API implementation. Historical test counts do not become fresh runs, and neither a model deployment nor a synthetic smoke proves category accuracy or completion of S2-S4.",
        "This register distinguishes historical execution": "This register retains historical v0.4 execution entries below. The current revision starts from 74eab38, whose handoff records real Gmail/Go readback and reconnect lifecycle observations. Azure setup evidence is recorded separately in docs/evidence/azure_classification_synthetic_smoke.json and docs/AZURE_CLASSIFIER_SETUP.md. A deployed model and synthetic smoke do not establish full Android classification, privacy, cost-control or accuracy acceptance.",
        "R3 CR-001 v0.3": "R3 Project change records CR-001 v0.3, CR-002 v0.4, CR-003 Gmail persistence and CR-004 Azure categorization; current review November 22, 2026. Reference implementation baseline at revision start: 74eab38. Requirement decisions and execution evidence remain distinct.",
        "PAM combines an Android presentation": "PAM combines Android presentation and transient IMAP parsing with a Go REST backend and Turso cloud persistence. Validated normalized transactions enter owner-scoped storage. Separate purpose-gated model boundaries process either allow-listed aggregate analysis data or the minimal sanitized merchant classification input in FR-68. The analysis route does not inherit permission to receive merchant labels.",
        "USER manages the financial data and files": "USER manages finance and files within their own workspace; ADMIN additionally manages account roles/status without access to another owner's finance. Reviewers use synthetic identities and operational maintenance uses protected configuration and safe logs. Model access is purpose-scoped: approved aggregates for analysis and the separately consented FR-68 business-merchant projection for Azure classification.",
        "Validated read-only mailbox connection with independent AI decision": "Validated read-only mailbox connection with independent analysis and classification consent decisions",
        "The backend uses Go with versioned REST JSON": "The backend uses Go REST JSON and a libSQL-compatible driver for Turso Cloud Free. Azure Microsoft Foundry supplies the selected cloud classification model through a backend-only OpenAI-compatible API. Initial account preflight identifies GPT-OSS 120B version 1 in Japan West as a deployable candidate; record the actual deployment, SKU and tests in the setup evidence. GlobalStandard inference can process outside the resource region. Android never connects to the model provider directly.",
        "A5 The baseline model adapter": "A5 Azure Microsoft Foundry is the selected primary transaction-classification processor, funded by the owner's Azure for Students credit where eligible. Student credit is finite, expires and excludes Marketplace offers. Subscription access, allowed region, model quota, deployment capability, current pricing and credit eligibility must be verified. Local/fake adapters support development; no automatic provider switch or credit-limit removal is authorized.",
        "FR-03 ": "FR-03 The system shall record purpose, notice version, processor disclosure, decision and timestamp independently for GMAIL_READ, AI_PROCESSING (aggregate analysis), and AI_CATEGORIZATION (merchant classification). New cloud-classification permission shall not be inferred from legacy aggregate-only AI consent. Declining one purpose preserves the others and deterministic/manual finance remains usable.",
        "FR-04 ": "FR-04 The user shall be able to withdraw each AI purpose independently. Withdrawal blocks new requests for that purpose, cancels or discards late responses, and retains legitimate transaction data and user corrections. Existing analysis reports follow historical-state rules; classification provenance is retained without granting future processing.",
        "FR-05 ": "FR-05 Logout shall revoke/clear the PAM session, stop active work and close IMAP transport and volatile owner state. Owner-scoped encrypted Gmail connection credentials may remain on the same device; a later authenticated login by the same PAM owner can reconnect after current consent and credential validation. No signed-out or different-owner access is permitted.",
        "FR-09 ": "FR-09 Mailbox metadata and wrapped credentials shall be bound to the authenticated PAM owner and backend namespace. Account switching clears accessible previous-owner UI, cached transport and search state, without exposing or deleting another owner's stored credential. Ambiguous legacy global credentials require explicit reauthorization rather than ownership guessing.",
        "FR-54 ": "FR-54 Android shall validate an owner-entered Gmail App Password over read-only TLS IMAP before committing a Keystore-wrapped, owner-bound vault record. Failed validation or persistence shall not report connected. Ordinary logout preserves the wrapped record on this device; explicit disconnect, Gmail withdrawal and owner deletion remove it. Revocation, corrupt storage or lost Keystore material require reauthorization; temporary network failure does not erase valid credentials.",
        "Priority is High. Users create and review transactions": "Priority is High. Users create and review transactions and correct categories. With optional AI_CATEGORIZATION consent, eligible merchant labels can be categorized by the Azure-hosted LLM through Go. Explicit USER choices and confirmed owner mappings take precedence; bounded local rules/cache reduce repeated calls. Missing consent, unclear input or provider failure preserves the transaction as uncategorized/reviewable. LLMs do not alter source money, timestamps, verified direction or refund/transfer facts.",
        "FR-23 ": "FR-23 The system shall provide income, expense, transfer and uncategorized classifications, including food, transport, housing, utilities, shopping, health, education and other expenses. Eligible new or unresolved transactions shall pass a category pipeline with USER override protection, owner-scoped cache/mapping, bounded rules and optional Azure LLM classification. A QRIS payment method or success subject alone shall not imply a food category.",
        "FR-24 ": "FR-24 Category assignments shall retain their semantic origin (USER, RULE or LLM), update time, rule/prompt/taxonomy/model version and calibrated review decision where available. Parser confidence and category confidence are distinct. A model's self-reported confidence shall not be treated as a measured probability. Unknown or ambiguous results shall remain reviewable and may abstain as UNCATEGORIZED.",
        "FR-25 ": "FR-25 Users shall be able to correct categories and allowed annotations without altering immutable imported amount, event time or verified direction. USER corrections shall survive reimport, cache refresh and model/rule upgrades. Owner-scoped reclassification of eligible non-USER rows shall use expected revisions, preserve identity/fingerprint and invalidate affected category totals and analysis dependencies.",
        "UC05 The user creates a manual transaction": "UC05 A manual or imported transaction is committed independently of the model. Eligible unresolved categories can enter a durable Azure job after AI_CATEGORIZATION consent. The user sees pending, classified or review/error state and can correct the category. Revision-checked application refreshes affected lists and summaries; USER choices and immutable imported fields remain protected.",
        "FR-34 ": "FR-34 Before every aggregate-analysis transmission, the backend shall verify current AI_PROCESSING consent and disclose the configured provider, model route and data categories. Merchant classification separately requires AI_CATEGORIZATION under FR-66 to FR-71. Both purposes are optional and shall not authorize each other's payload.",
        "FR-35 ": "FR-35 The aggregate-analysis provider shall receive only versioned allow-listed monthly totals and safe built-in category aggregates. It shall exclude raw email, credentials, source IDs, account/card identifiers, names, merchant descriptions, notes, arbitrary attributes and custom category labels. A separate classifier adapter may receive only the minimal sanitized business-merchant payload explicitly defined by FR-66/68 after AI_CATEGORIZATION consent; this exception shall not widen the aggregate-analysis allowlist.",
        "The Go backend uses a database/sql driver": "The Go backend uses database/sql with Turso Cloud libSQL. Aggregate-analysis and merchant-classification adapters have separate typed input/output schemas and purpose gates. Azure Microsoft Foundry classification uses a backend-only HTTPS OpenAI-compatible endpoint, an allow-listed deployment ID and backend-held credentials or managed identity. Local/fake providers remain available for tests. Deployment/region/quota/pricing are verified operational settings, not constants embedded in Android.",
        "NFR-12 ": "NFR-12 All monetary arithmetic and ledger membership shall be deterministic and tested independently of LLMs. Neither model route may alter source amount/time/direction, compute authoritative totals or overwrite a USER-approved category. Automatic category updates shall preserve owner scope, revision semantics and exact gross cash flow.",
        "NFR-14 ": "NFR-14 Turso tokens, JWT keys and Azure model credentials shall remain backend-only; managed identity/Entra credentials are preferred for hosted workloads. Development secrets are stored in a private file outside Git with owner-only permissions. Android holds no Azure key. IMAP/refresh credentials use Keystore and the owner-aware CR-003 lifecycle; logs, APKs, URLs and crash reports exclude secrets.",
        "NFR-19 ": "NFR-19 Each outbound model request shall pass the validator for its declared purpose immediately before transmission. Aggregate analysis uses FR-35; classification uses FR-68 only. Both reject forbidden nested fields, enforce current processor/region disclosure and consent, and disable provider invocation logging of sensitive prompt bodies. Azure abuse-monitoring retention and GlobalStandard processing location shall not be misrepresented as guaranteed zero retention or Indonesia-only inference.",
        "DR-05 ": "DR-05 Raw emails, source headers/IDs and source attachments remain transient on Android. Normalized owner-scoped records persist in Turso, and user file bytes stay device-private. Aggregate models receive FR-35 data only. The separate Azure classifier receives a bounded sanitized merchant label, compatible direction/method and safe taxonomy codes under FR-68; payload/credential minimization applies independently of database encryption.",
        "BR-06 ": "BR-06 Aggregate-analysis recommendations and budget suggestions shall identify supporting summary metrics and disclose limited coverage when history is insufficient. Merchant classification instead relies on the bounded FR-68 input and may abstain; it shall not invent summary evidence or purchase details. Multi-month analysis patterns require the referenced months to be present.",
        "Go backend -> current AI consent -> aggregate allowlist -> configured model": "Go -> AI_PROCESSING -> aggregate allowlist -> analysis model; separately Go -> AI_CATEGORIZATION -> minimal merchant allowlist -> Azure Microsoft Foundry classifier -> revision-checked category result",
        "TB1 is authenticated Gmail TLS access": "TB1 is Gmail TLS on Android; TB2 is authenticated Android-to-Go; TB3 is Go-to-Turso; TB4 is purpose-gated aggregate model processing; TB5 is distinct Azure classification with merchant minimization, independent consent and no model-side financial mutation. GlobalStandard processing is not restricted to Japan West solely because the resource is located there. ADMIN cannot read another owner's finance.",
        "Related requirements FR-02, FR-06, FR-35": "Related requirements FR-02, FR-06, FR-35, FR-54 to FR-71. The original figure remains a baseline overview; the current text adds the separate TB5 Azure classifier path. Raw email/App Password stay on-device; server-side category jobs hold only necessary owner-scoped normalized inputs and provenance.",
        "Local Ollama selected as baseline adapter.": "Azure Microsoft Foundry selected for classification (CR-004). Account-specific deployment/model, usable student credit, region policy, quality benchmark, processor notice and purpose consent must be evidenced. Initial GPT-OSS 120B candidate in Japan West does not imply a completed accuracy benchmark.",
        "Related requirements FR-02, FR-06, FR-35, FR-54": "Related requirements FR-02, FR-06, FR-35 and FR-54 to FR-71. Separate aggregate and classifier purposes are authoritative; earlier aggregate-only illustrations are historical baseline context.",
    }
    # Some versions have one shared paragraph for the last two context prefixes.
    changes.pop("Related requirements FR-02, FR-06, FR-35, FR-54", None)
    for prefix, value in changes.items():
        update(prefix, value)

    # Extend revision history by cloning its final row, preserving table geometry.
    for table in doc.xpath("//w:tbl", namespaces=NS):
        if "Revision History" in text(table):
            continue
        rows = table.findall(f"{{{W}}}tr")
        if any("Consolidated Go backend, Turso cloud" in text(row) for row in rows):
            row = deepcopy(rows[-1]); cells = row.findall(f"{{{W}}}tc")
            assert len(cells) == 4
            for cell, value in zip(cells, ["2026-11-22", "0.5", "CR-003 owner-scoped Gmail persistence and CR-004 Azure LLM categorization, independent consent, bounded payload, spending controls and T13 acceptance.", "Andhika Pratama / PM revision"]):
                ps = cell.findall(f"{{{W}}}p")
                replace_text(ps[0], value)
                for extra in ps[1:]: cell.remove(extra)
            table.append(row)
            break
    else:
        raise AssertionError("revision history table not found")

    def make(value, reference):
        p = deepcopy(reference); replace_text(p, value); return p

    body_template = next(p for p in paras if text(p).startswith("FR-23 "))
    references_anchor = [p for p in paras if text(p).startswith('1.6 Acronyms')][-1]
    references_anchor.addprevious(make('R6 Microsoft Azure documentation, checked November 22, 2026: Azure for Students and Marketplace exclusions (learn.microsoft.com/azure/education-hub/faq); Foundry models sold directly by Azure, including GPT-OSS (learn.microsoft.com/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure); OpenAI-compatible v1 API (learn.microsoft.com/azure/foundry/openai/api-version-lifecycle). Account-specific setup and synthetic evidence are in CR-004 and docs/AZURE_CLASSIFIER_SETUP.md.', body_template))
    heading_template = next(p for p in paras if text(p) == "3.5 Transaction Categorization and Review")
    anchor = [p for p in paras if text(p).startswith("3.6 ")][-1]
    new = [
        ("3.5A Azure LLM Categorization", True),
        ("FR-66 With independently recorded AI_CATEGORIZATION consent, Go shall classify eligible normalized transactions through Microsoft Foundry. USER assignments take precedence, and verified transfer/refund semantics remain deterministic. USER/RULE/LLM/UNCATEGORIZED provenance shall be supported across API, database and UI.", False),
        ("FR-67 Acknowledged financial imports shall not wait for model availability. Durable owner-scoped classification jobs expose PENDING, RUNNING, CLASSIFIED, NEEDS_REVIEW, FAILED and CANCELLED, preserve idempotency and resume after restart. Revoked consent, deleted rows, changed revisions or account switches invalidate late results.", False),
        ("FR-68 Classifier input shall be an explicit bounded schema: sanitized business merchant label up to 120 characters, verified direction, payment-method code where known, safe built-in category codes and taxonomy version. Amount, transaction date, source email/ID, credentials, account/card data, personal counterparty names, notes, attributes and custom category labels are excluded. Sanitation failure yields review, not wider sharing.", False),
        ("FR-69 Model output shall contain only an allow-listed categoryCode, needsReview and bounded reasonCode under the documented schema. Apply only a compatible validated result to the intended owner/transaction/revision. Preserve source fields and USER overrides, write model/prompt/taxonomy provenance, and invalidate affected list/detail/summary/analysis state after acknowledgement. JSON/schema validity alone is not semantic accuracy.", False),
        ("FR-70 Backend configuration shall pin Azure endpoint, deployment/model version, deployment SKU and privacy disclosure. Application request/token/cost budgets and concurrency bounds shall reserve estimated worst-case cost atomically and reconcile actual usage; uncertainty is accounted conservatively. Student credit expiry/exhaustion, 429, unavailable provider or budget exhaustion leaves the financial transaction intact and classification reviewable. No automatic subscription upgrade, Marketplace purchase or paid fallback is permitted.", False),
        ("FR-71 Consent and category cache entries shall be owner-scoped and versioned by taxonomy, prompt/rule/model policy and normalized merchant identity. Repeated merchants need not trigger repeated model calls. Manual corrections always take precedence. Before enabling automatic classification, execute T13; a small synthetic smoke is connectivity evidence only.", False),
        ("AC05A Without AI_CATEGORIZATION consent, zero merchant model requests occur. A synthetic food merchant can classify through Android-Go-Azure-Go-database-GET-UI. Ambiguous names can abstain. Duplicate requests, restart, late responses, logout/owner switch, USER correction, deleted row and credit/provider errors preserve ledger correctness and privacy.", False),
        ("Notice (Indonesian): Kategorisasi AI bersifat opsional. Nama merchant usaha yang telah diminimalkan, arah transaksi, metode pembayaran dan kode kategori dapat diproses Microsoft Azure untuk menyarankan kategori. Email mentah, nomor rekening/kartu, nominal, tanggal transaksi dan catatan tidak dikirim untuk tujuan ini. Hasil dapat salah dan dapat Anda koreksi. Persetujuan ini terpisah dari analisis agregat. Lokasi pemrosesan mengikuti jenis deployment yang diinformasikan.", False),
    ]
    for value, heading in new:
        anchor.addprevious(make(value, heading_template if heading else body_template))

    nfr_anchor = next(p for p in paras if text(p) == "6 Other Requirements")
    for value in [
        "NFR-29 Category quality shall be measured on a human-labeled representative held-out dataset with merchant-disjoint evaluation and explicit ambiguous cases. Report per-class precision/recall/F1, automatic-assignment precision, abstention/review coverage, unknown-merchant errors and request failure rate. Proposed release target: at least 95% precision for auto-applied classifications at separately reported coverage. Self-rated model confidence is not a substitute; this target is not a measured result.",
        "NFR-30 Classification requests shall have bounded input/output tokens, timeout, at most one bounded retry honoring Retry-After, and a finite durable queue. Proposed pilot configuration is at most two concurrent calls, 20-second request timeout, USD0.10 daily and USD2 monthly application ceilings, configurable after verified model pricing. Budgets are application controls to implement; Azure budget alerts are not hard stops and no exact remaining credit is inferred from a subscription label.",
    ]:
        nfr_anchor.addprevious(make(value, body_template))

    # Add a traceability row matching the existing three-column table.
    for table in doc.xpath("//w:tbl", namespaces=NS):
        rows = table.findall(f"{{{W}}}tr")
        if any("T12 Go migration" in text(row) for row in rows):
            row = deepcopy(rows[-1]); cells = row.findall(f"{{{W}}}tc")
            assert len(cells) == 3
            vals = ["T13 Azure category classification", "FR-23 to FR-25, FR-66 to FR-71, NFR-12 to NFR-19, NFR-29/30", "Purpose isolation, minimal outbound schema, Azure synthetic smoke and held-out quality, credit/429/timeout failure, owner/revision/USER protection, cache and durable resume; Android-to-Go-to-model-to-UI evidence."]
            for cell, value in zip(cells, vals):
                ps = cell.findall(f"{{{W}}}p"); replace_text(ps[0], value)
                for extra in ps[1:]: cell.remove(extra)
            table.append(row)
            break
    else:
        raise AssertionError("traceability table not found")

    # TOC is static in the supplied document. Add a matching section-index entry;
    # a later PDF-based refresh sets each TOC page reference after pagination.
    toc = next(p for p in paras if p.xpath('./w:pPr/w:pStyle/@w:val', namespaces=NS) == ['TOC2'] and text(p).startswith('3.5 '))
    toc_row = deepcopy(toc)
    runs = toc_row.xpath('.//w:t', namespaces=NS)
    runs[0].text = '3.5A Azure LLM Categorization'
    runs[-1].text = '18'
    toc.addnext(toc_row)

    for name in [n for n in contents if re.match(r'word/(?:header|footer)\d+\.xml$', n)] + ["docProps/core.xml"]:
        raw = contents[name].decode('utf-8')
        raw = raw.replace('v0.4 Go and Turso', 'v0.5 Go, Turso and Azure LLM')
        if name.startswith(('word/header', 'word/footer')):
            raw = raw.replace('v0.4', 'v0.5')
        contents[name] = raw.encode('utf-8')
    contents['word/document.xml'] = E.tostring(doc, xml_declaration=True, encoding='UTF-8', standalone=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.output, 'w', compression=ZIP_DEFLATED) as z:
        for name, data in contents.items(): z.writestr(name, data)
    complete = '\n'.join(text(p) for p in doc.xpath('//w:p', namespaces=NS))
    for req in [f'FR-{n:02}' for n in range(1,72) if n != 8]:
        assert req in complete, req
    assert len([n for n in contents if n.startswith('word/media/')]) == 6
    print(f'Created full SRS: {args.output}; original media retained; FR-01..71 present.')


if __name__ == '__main__':
    main()
