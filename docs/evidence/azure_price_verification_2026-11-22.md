# Azure price verification — `gpt-oss-120B` GlobalStandard

Date: 22 November 2026. Purpose: replace the fail-closed "prices unverified" state with
prices read from a **primary Microsoft source**, so the runtime USD ledger multiplies measured
tokens by a defensible number instead of a guessed one.

## Source

Azure Retail Prices API — the official retail price feed that backs the Azure pricing pages.

```
GET https://prices.azure.com/api/retail/prices
    ?$filter=contains(meterName,'gpt-oss-120B Inp glbl')
    ?$filter=contains(meterName,'gpt-oss-120B Outp glbl')
```

Reproduce:

```bash
curl -s -G 'https://prices.azure.com/api/retail/prices' \
  --data-urlencode "\$filter=contains(meterName,'gpt-oss-120B Inp glbl')" \
  --data-urlencode '$top=200'
```

## Result (USD, `type=Consumption`, `unitOfMeasure=1K`)

| field | input | output |
|---|---|---|
| `productName` | Azure OpenAI OSS Models | Azure OpenAI OSS Models |
| `serviceName` | Foundry Models | Foundry Models |
| `skuName` | gpt-oss-120B Inp glbl | gpt-oss-120B Outp glbl |
| `meterName` | gpt-oss-120B Inp glbl Tokens | gpt-oss-120B Outp glbl Tokens |
| `meterId` | `0b0a7c6f-1eee-548f-a3cd-880708e42661` | `057494d7-8846-5ffc-b683-904f45505d61` |
| `retailPrice` | **0.00015** | **0.0006** |
| `currencyCode` | USD | USD |
| `effectiveStartDate` | 2026-03-01T00:00:00Z | 2026-03-01T00:00:00Z |

40 rows returned per meter (one per region). The `glbl` (Global) meters are uniform at
0.00015 / 0.0006 across commercial regions, including `indonesiacentral`, `eastus2`,
`japaneast`, `westus2`. Two outliers exist only in sovereign/Gov regions
(`usgovarizona`: 0.00019 input, 0.00075 output) and do not apply to this tenant.

`DZone` (data-zone) meters are a different SKU and are **not** used:
`gpt-oss-120B Inp DZone` 0.000165, `gpt-oss-120B Outp DZone` 0.00066.

## Mapping to runtime config

Deployment `pam-category-gptoss120b` is `GlobalStandard`, i.e. the `glbl` meters:

```
CATEGORY_LLM_PRICE_PROMPT_PER_1K_USD=0.00015
CATEGORY_LLM_PRICE_COMPLETION_PER_1K_USD=0.0006
```

Both are `> 0`, which satisfies the fail-closed config check in
`internal/config/config.go` (`enabled requires azure endpoint/key and verified prices`).

## What this does and does not prove

- **Proves:** the unit price used to convert measured tokens into an estimated USD amount.
- **Does not prove:** that Azure billed that amount. A billed figure is only an invoice /
  Cost Management number. Token×price is an **estimate**, never an invoice.
- **Does not prove:** coverage of the outer cloud spend guard (Foundry cost control
  `pam-monthly-10usd`, Cost Management budget `pam-foundry-monthly-10usd`). The cloud
  USD10/month ceiling is an outer layer; it does not widen the runtime caps
  (USD0.10/day, USD2/month, 20 attempts/owner/day, 100 attempts/installation/day).

## Note on the pre-flight input estimate

`MaxInputTokens = 1024` is enforced with a deterministic conservative byte-based estimate
(`ceil(bodyBytes / 2)`), not a formal tokenizer proof. The **exact** ledger value is the
provider-reported `prompt_tokens` when usage is present; when the provider omits usage the
reservation is settled as `usageKnown=false` at the conservative reserved value and can be
reconciled `UNKNOWN → SETTLED` exactly once.