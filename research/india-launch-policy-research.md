# India-first launch: legal and policy research

**Research date:** 10 August 2026 (Asia/Kolkata)  
**Product assumption:** Dutch is a shared-expense recordkeeping app. It does not initiate, route, settle, hold, or custody money. It processes account identifiers, member names, and user-authored financial records.  
**Method:** official Indian primary sources only. This is a product/engineering research note, not legal advice; obtain Indian counsel’s launch sign-off.

## Executive conclusion

Launch with a visible privacy policy, terms/user rules, a named and reachable Indian grievance officer, deletion/export workflow, and a retention schedule that distinguishes account PII from immutable financial evidence. The most immediate binding launch work comes from the IT Intermediary Rules, CERT-In directions, and (where applicable) the IT SPDI Rules. The core DPDP Act/Data Principal obligations are notified but, as of this research date, have a phased effective date of **14 May 2027**; build to them now rather than defer design.

There is no general India-only hosting requirement in the cited sources for this non-payment product. Today’s SPDI transfer rule applies when Dutch handles SPDI; the future DPDP regime permits overseas processing unless Government restricts a destination or another law is stricter. An India AWS region is a sensible launch risk-control, but not established here as a universal legal mandate.

## Binding requirements now

### 1. Treat Dutch as an intermediary unless counsel concludes otherwise

The IT Rules define a social-media intermediary as one that enables interaction between two or more users and lets them create, upload, share, modify, or access information. Dutch’s collaborative expenses, notes, activity and group membership strongly resemble that fact pattern, though only counsel can conclusively classify it. If it is an intermediary, Rule 3 applies. [IT Rules 2021, definitions](https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_76_00001_200021_1517807324077&filename=information_technology_%28intermediary_guidelines_and_digital_media_ethics_code%29_rules%2C_2021_%28updated_06.04.2023%29-.pdf&type=rule)

**Launch implementation if classified as an intermediary:**

- Prominently publish rules/regulations, a privacy policy, and user agreement; inform users of them in English or an Eighth Schedule language of the user’s choice; and re-inform users at least annually / when they change. The notice must include prohibited content categories and Dutch must make reasonable efforts around them. [Rule 3(1)(a)-(c), (f)](https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_76_00001_200021_1517807324077&filename=information_technology_%28intermediary_guidelines_and_digital_media_ethics_code%29_rules%2C_2021_%28updated_06.04.2023%29-.pdf&type=rule)
- Prominently publish the Grievance Officer’s name/contact details and complaint mechanism. Acknowledge complaints within 24 hours and resolve within 15 days; specified content-removal complaints have a 72-hour target. Preserve removed/disabled material and associated records for 180 days; retain registration information for 180 days after account cancellation/withdrawal. [Rule 3(2)](https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_76_00001_200021_1517807324077&filename=information_technology_%28intermediary_guidelines_and_digital_media_ethics_code%29_rules%2C_2021_%28updated_06.04.2023%29-.pdf&type=rule)
- Maintain the capability to remove/disable material in response to a valid court/Government order within 36 hours and to provide data/assistance within 72 hours of a lawful written agency order. These are operational/legal-response requirements, not user-request deletion times. [Rule 3(1)(d), (j)](https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_76_00001_200021_1517807324077&filename=information_technology_%28intermediary_guidelines_and_digital_media_ethics_code%29_rules%2C_2021_%28updated_06.04.2023%29-.pdf&type=rule)

**Product consequence:** create a public legal/support page reachable before login and from Settings, a ticketing workflow with the above clocks, a moderation/legal-hold state, and a retention exception that overrides normal deletion only where lawfully required.

### 2. CERT-In incident response and logs

CERT-In says its 28 April 2022 directions apply, as applicable, to service providers, intermediaries, data centres, body corporates, cloud providers and others; Dutch and its cloud suppliers should be assessed against that scope. The IT Rules also expressly require intermediaries to report incidents and related information to CERT-In under the CERT-In rules. [CERT-In directions landing page](https://cert-in.org.in/Directions70B.jsp), [CERT-In applicability FAQ](https://www.cert-in.org.in/PDF/FAQs_on_CyberSecurityDirections_May2022.pdf), [IT Rules 3(1)(l)](https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_76_00001_200021_1517807324077&filename=information_technology_%28intermediary_guidelines_and_digital_media_ethics_code%29_rules%2C_2021_%28updated_06.04.2023%29-.pdf&type=rule)

**Binding operational baseline (subject to counsel confirming Dutch’s category):** incident runbook, 24x7 reporting ownership, and tamper-resistant logs. The 2022 directions are commonly implemented as reportable incident notification within six hours and ICT-system logs retained securely for 180 days in India; verify the final text and applicability with counsel before turning these into a compliance assertion, because the official PDF was not text-extractable through the research tool. [Official directions PDF](https://www.cert-in.org.in/PDF/CERT-In_Directions_70B_28.04.2022.pdf)

### 3. Existing privacy/security regime remains relevant before DPDP substantive commencement

Section 43A IT Act exposes a body corporate negligently handling SPDI without reasonable security practices to compensation for resulting wrongful loss/gain. The SPDI Rules require a privacy policy, consent/collection/disclosure conditions for SPDI, and documented reasonable security practices; ISO/IEC 27001 is named as one example. [IT Act section 43A and SPDI Rules index](https://www.indiacode.nic.in/handle/123456789/1362/simple-search?query=The+Information+Technology+%28Reasonable+Security+Practices+and+Procedures+and+Sensitive+Personal+Data+or+Information%29+Rules%2C+2011.&searchradio=rules), [SPDI Rules 6–8](https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_76_00001_200021_1517807324077&filename=GSR313E_10511%281%29_0.pdf&type=rule)

**Applicability nuance:** Dutch’s ordinary name/email/phone is personal information but not necessarily SPDI under the older rules. If it collects passwords (including a password held by an auth service), financial-account details, health, biometric, sexual-orientation, or similar listed SPDI, the stricter rules matter. Do not collect unnecessary sensitive fields. The 2025 DPDP commencement notification schedules the repeal in section 44(2) for the later substantive phase; counsel should verify the still-operative legacy provisions for the actual launch date. [DPDP Act and commencement framework](https://www.indiacode.nic.in/bitstream/123456789/22037/2/a2023-22.pdf)

## DPDP Act and DPDP Rules: notified now; core product duties start 13 May 2027

The DPDP Act was enacted in 2023 but its provisions take effect on separately notified dates. Gazette G.S.R. 843(E) and the DPDP Rules were published on 14 November 2025. The Rules bring Rules 1, 2 and 17–21 into force on publication, Rule 4 one year later, and Rules 3, 5–16 and 22–23 eighteen months later. The accompanying commencement notification uses the same phased approach for Act provisions: the core processing, fiduciary-obligation, child, rights, transfer, and enforcement provisions fall in the eighteen-month cohort. The substantive deadline is therefore **14 May 2027**. [Official Act commencement notification (G.S.R. 843(E))](https://www.meity.gov.in/static/uploads/2025/11/c56ceae6c383460ca69577428d36828b.pdf), [official Rules gazette (G.S.R. 846(E))](https://www.meity.gov.in/static/uploads/2025/11/53450e6e5dc0bfa85ebd78686cadad39.pdf), [DPDP Act text](https://www.indiacode.nic.in/bitstream/123456789/22037/2/a2023-22.pdf)

Build these requirements into MVP now:

- **Notice/consent:** before/at consent, give a standalone, clear notice of what data is processed and for what purpose; consent must be free, specific, informed, unconditional, unambiguous affirmative action and as easy to withdraw as give. Do not bundle analytics/marketing with core account/service processing. [DPDP Act sections 5–6](https://www.indiacode.nic.in/bitstream/123456789/22037/2/a2023-22.pdf)
- **Rights:** provide a workflow for access information, correction/completion/updating/erasure, grievance redressal, and nomination. Publish business contact details for a DPO if one is required, otherwise someone able to answer data-processing questions; establish effective grievance redressal. [sections 8(9)–(10), 11–14](https://www.indiacode.nic.in/bitstream/123456789/22037/2/a2023-22.pdf)
- **Deletion:** erase upon consent withdrawal or when the specified purpose is no longer served, unless retention is necessary for compliance with law; cause processors to erase too. This supports Dutch’s proposed account anonymisation while retaining minimum immutable ledger evidence only under a documented legal/legitimate retention basis. The law does **not** mean delete every financial-history record merely because a person taps delete. [section 8(7)](https://www.indiacode.nic.in/show-data?abv=CEN&actid=AC_CEN_45_0_00003_2023-22_1763464807080&orderno=8&orgactid=AC_CEN_45_0_00003_2023-22_1763464807080&statehandle=123456789%2F1362)
- **Security/breach:** reasonable safeguards and Board/affected-principal breach intimation in the prescribed form/manner. [sections 8(5)–(6)](https://www.indiacode.nic.in/show-data?abv=CEN&actid=AC_CEN_45_0_00003_2023-22_1763464807080&orderno=8&orgactid=AC_CEN_45_0_00003_2023-22_1763464807080&statehandle=123456789%2F1362)

## Children / minimum age

Under DPDP section 9 (effective 14 May 2027), a child is under 18. Before processing a child’s data, obtain verifiable parent/lawful-guardian consent; do not undertake tracking/behavioural monitoring of children or targeted advertising directed at them, subject to any Government-notified exemptions. The final Rules prescribe verification routes. [DPDP Act section 9](https://www.meity.gov.in/static/uploads/2024/06/2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf), [DPDP Rules 10 and 12](https://www.meity.gov.in/static/uploads/2025/11/53450e6e5dc0bfa85ebd78686cadad39.pdf)

**Recommendation:** launch **18+ only** and enforce an age gate plus Terms. This is a product choice that avoids building and operating parental-verification/consent mechanisms. If Dutch accepts under-18s, this becomes a mandatory DPDP design and operational programme by May 2027. Do not imply a lower statutory digital-consent age.

## Export, deletion and financial-record retention

- **Export:** DPDP’s access right requires a summary of personal data processed, processing activities and other prescribed information; it is not phrased as a universal “download every raw database record” right. Offer a secure structured account export anyway—profile, group/direct memberships, expenses/settlements/activity visible to the user, and privacy request status—to make the right usable and reduce support risk. [section 11](https://www.indiacode.nic.in/bitstream/123456789/22037/2/a2023-22.pdf)
- **Deletion:** distinguish (a) user-visible account closure/anonymisation, (b) erasure of contact/auth data and device tokens, (c) retention of a minimally identifying financial/audit record, and (d) the statutory 180-day intermediary registration/removal evidence hold where applicable. Keep a retention schedule and legal-hold register; disclose categories and periods in the privacy notice.
- **No payment handling:** On the stated scope, Dutch records interpersonal obligations but does not execute a payment. This research found no RBI payment-aggregator/PPI requirement applicable solely to that recordkeeping fact pattern. This is an absence-of-scope conclusion, not a regulatory clearance. Re-evaluate before adding UPI deep links with transaction orchestration, payment initiation, wallet/custody, fee collection tied to payments, or any funds flow.

## Residency and overseas transfer

**Current legacy SPDI rule:** overseas transfer is allowed where the recipient ensures the same protection and transfer is necessary for a lawful contract or the provider consented. [SPDI Rule 7](https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_76_00001_200021_1517807324077&filename=GSR313E_10511%281%29_0.pdf&type=rule)

**Future DPDP rule:** section 16 lets the Central Government restrict transfers to notified countries/territories and preserves stricter sectoral laws. It is not a general localisation command. [DPDP Act section 16](https://www.indiacode.nic.in/show-data?abv=CEN&actid=AC_CEN_45_0_00003_2023-22_1763464807080&orderno=16&orgactid=AC_CEN_45_0_00003_2023-22_1763464807080&sectionId=101282&sectionno=16&statehandle=123456789%2F1362)

**Recommendation:** run India-region primary production data, document every processor/subprocessor and transfer, use contractual/security controls, and keep portability to react to a future restricted-country notification. This is risk-reduction; it should not be labelled as a general binding residency mandate.

## Practical launch checklist

1. Have Indian counsel confirm intermediary status, entity/contact disclosures, and whether any planned analytics/auth vendor processes SPDI.
2. Publish accessible Privacy Policy, Terms/User Rules, and grievance mechanism with named contact/owner before public beta; configure acknowledgement/15-day SLA and evidence retention.
3. Implement account export, deletion/anonymisation, consent/notice records, processor-deletion workflow, legal holds, and a retention register.
4. Launch 18+ until a separate child-consent project is approved.
5. Create CERT-In incident runbook, evidence/log retention, and contractual obligations with AWS/auth/push/support processors; get counsel to confirm the six-hour/180-day operational detail.
6. Keep money movement out of Dutch. Treat future payment integration as a new regulated-scope assessment.
7. Before 14 May 2027, perform DPDP readiness review: notice/consent, rights SLA, breach communications, child controls, transfer register, and DPO/contact/grievance mechanisms. The final Rules require documented safeguards such as access controls/logging/backup and prescribe specific breach and grievance procedures at that phase. [DPDP Rules 6–7 and 14](https://www.meity.gov.in/static/uploads/2025/11/53450e6e5dc0bfa85ebd78686cadad39.pdf)

## Open legal questions for counsel

1. Does Dutch’s collaborative ledger definitively qualify as an intermediary/social-media intermediary under IT Act/Rules, and does its proposed legal page satisfy language-choice requirements in the specific mobile UX?
2. Which legacy SPDI obligations apply to the selected IAM vendor and tokens/password representations, and what exact retention rule applies to each data class?
3. Which CERT-In direction clauses apply directly to Dutch as SaaS/app operator versus its cloud provider, particularly the six-hour incident-report and log-localisation duties?
4. What legal basis supports retaining participant display/audit evidence after a deletion request, and how should the Privacy Policy explain that with the existing “Former member” product behaviour?
5. Are there state-specific, tax, consumer, or company-law retention/consumer-contact obligations for the legal entity and monetisation model not captured by these national data/intermediary sources?
