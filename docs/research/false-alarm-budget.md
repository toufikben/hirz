# The false-alarm budget

> **بالعربية:** حِرز يَعِد بأقل إنذار كاذب ممكن، **بميزانية مقيسة ومعلنة**. هذه هي الأرقام. التجربة الميدانية (P8) تنجح فقط إذا حققت كل بند. البند الذي ليست له بيانات كافية **راسب**.

Every enrolled asset-day of a trial counts. An asset-day is one asset with a live source for at least 12 h of that day.
An alarm's outcome is given by the family after it closes. A guided test is never a false alarm, and "not sure" counts
neither way.

| Line | id | Limit |
|---|---|---|
| False P0 | `false_p0` | ≤ 1 per 300 asset-days |
| False P1 | `false_p1` | ≤ 1 per 30 asset-days |
| Missed real events in guided tests | `missed_real` | 0 missed guided tests |
| P0 push delivered | `p0_delivery` | p95 ≤ 30 s |
| P0 acknowledged (first claim) | `p0_ack` | median ≤ 3 min |
| Fresh-fix coverage | `coverage` | ≥ 90% of asset-hours |
| Digest usefulness | `digest_useful` | ≥ 50% of rated lines |
| Outcome coverage | `outcome_coverage` | ≥ 80% of closed P0/P1 |

Not enough data: fewer than 100 asset-days (for the rate lines), fewer than 5 P0 guided tests (for missed events),
or nothing measured on a line. Such a line fails.

The numbers live in `shared/.../rules/FalseAlarmBudget.kt`; `BudgetDocTest` fails CI if this table drifts from them.
