# Remote Play QA Matrix

| Test | Expected result |
|---|---|
| Valid User ID, first device | Account is bound to that Android installation; token is returned once and stored securely |
| Same User ID, same device and token | Dashboard login succeeds |
| Same User ID from another installation | Rejected with device-already-bound |
| Balance 0 | Dashboard and Topup available; all PC connect actions blocked |
| Rental expired or disabled | Dashboard and Topup available; all PC connect actions blocked |
| User cooldown active | Dashboard visible; connect blocked until server cooldown ends |
| Session > 3 hours | Host stream stopped; timer closed; account cooldown set to configured 5–10 minutes |
| User balance shorter than 3 hours | Session stops at the balance deadline; no 3-hour cooldown |
| Two clients start same host simultaneously | Only one session lease succeeds |
| Same rental tries two hosts | Only one session lease succeeds |
| PC Billing has active session | Remote Play refuses host |
| Remote Play active | Conflicting PC Billing session insert is blocked |
| Host Agent heartbeat missing >35 seconds | Watchdog finalizes session and charges elapsed time once |
| Android force-stop | Host/DB watchdog ends session; next dashboard refresh sees no live session |
| Sunshine API unreachable | Pairing/stream setup fails closed; no public API exposure |
| Host offline or Agent stale | Host displayed offline; cannot start |
| Duplicate session-start retry | Same source event must return existing session (idempotency test required) |
| P2P candidate unreachable / CGNAT | Direct attempt fails safely; relay fallback is used or PC shown unavailable |
| QRIS not configured | Topup screen shows actionable “QRIS belum dikonfigurasi”, never a fake QR |
| QRIS configured | Exact merchant QR is displayed; user is directed to WA 082298316902 |
| Existing PC Billing regression | Rental CRUD, heartbeat, local session, Admin reports and WoL remain unchanged |

The database and Edge Function deployment are only the control-plane foundation; the complete matrix must pass on at least one Android phone and two Windows hosts before release.
