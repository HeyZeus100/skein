# Aviation UI probe and citation navigation

Following normal owner biometric unlock after the supplied-evidence diagnostic, the sole runner imported the six staged aviation and mycology notes once through the document picker. All six rendered titles and complete bodies matched their committed fictional sources after whitespace/bold normalization. The original three construction imports were retained. No owner draft or note was edited.

On the unchanged `28354dd25` app, a fresh empty chat was verified before one Send at **17:47:17.736876 UTC**. Search Knowledge was enabled, and the exact plain question contained no attachments:

> Which log is missing from DEMO-A?

No assistant text was visible through **164.685 seconds**. The first observed text appeared by **172.853 seconds** while generation continued. It incorrectly claimed unknown/no supporting information and rambled, despite having the correct note among its sources. This is a failed positive answer.

The observer detected the 180-second limit at **181.932 seconds**. Normal Stop was actually tapped at **17:50:23.775894 UTC**, **186.039 seconds** after Send; idle was verified at **17:50:29.681 UTC**. Preserve the observation/control overhead: this was not an exact 180-second stop. There was no resend.

The actual inline citation **[1]** was then tapped. It opened **DEMO-A Records Packet** in the source pane; the complete rendered note matched its committed body, including the statement that the propeller log is missing. This proves one real citation-navigation path even though the generated answer was wrong. It must not be described as a successful cited answer.

Context contained eight rows, all from the nine known fictional notes, with zero unknown rows: Records Packet, Records Request, Cedar Cabinet Order, OYS-014 Observation Log, Planning Meeting, OYS-014 Sample Provenance, Cedar Delivery Coordination and Cedar RFI-017. This broad live context differs from the one-source supplied-evidence diagnostic; neither result replaces the other.

The exact-window numeric trace recorded **1218 prompt tokens**, context capacity **16384**, and three prefill batches. Prefill began 5.973 seconds after Send; batch durations were 40812, 50923 and 27832 milliseconds. The last batch completed at 125.541 seconds after Send—approximately **119.567 seconds processing the prompt**. The terminal reason was CANCELLED, with zeroed token/TTFT fields that do not establish how much sampling occurred.

The source and Context views were closed and the UI was idle at **17:53:27 UTC**. No model, sampling, retrieval threshold, source note or application version was changed during this probe. Full demo readiness, answer quality, retrieval breadth and repeated rehearsal remain open.

| Preserved local file | SHA256 |
|---|---|
| `aviation-ui-review.json` | `ee6807806d486a5e6590fbae5222e6f12676e110d1c9e4b4e055f4fa26e07e29` |
| `aviation-numeric.json` | `4c99d18e14a5063dfc799a78b9bd85f1a5efcdecc42a29c88bceb06214b65bcb` |
| `citation-body-review-demo-a-packet.md.json` | `dea0098f170fe1b1d50cab3de95e86c57a71e5b6c1850c052c39f8729811ba00` |
| Actual stopped-answer capture `301-private-screen.png` | `955c3f19eb7883e64f4b377533da70d5600da8662e1e8dfce185dc69084f0c7d` |
| Actual citation/source capture `307-private-screen.png` | `4046f9ec6ddea429e2354f76065127b0a353bb801fef1c34534b1843c2c096b3` |

The original files remain in the ignored local directory `build/agent-logs/fold/ui-28354dd-20260929T164502Z` of the conference-demo worktree. Screenshots contain the fictional probe/source; owner previews were not exposed.
