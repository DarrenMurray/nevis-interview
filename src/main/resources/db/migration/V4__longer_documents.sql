-- Fuller document text.
--
-- V2's documents were a few lines each — too short for a summary to say anything the title
-- did not. Rewritten here rather than edited in place: V2 has already run in production and
-- Flyway validates its checksum.
--
-- Clearing embedding and summary makes the backfill regenerate both, which also replaces the
-- passage rows keyed on each document.

UPDATE documents SET content =
'Thames Water. Account 8891234. Service address: 12 Acacia Avenue, London N1 4TG. Billing period 01-31 March 2026.
Opening balance GBP 61.40. Payment received 14 March, thank you. Charges for the period GBP 22.70. Balance carried forward GBP 42.10.
Your supply is metered. The meter at this property was read on 29 March 2026 and the reading was 04417 cubic metres, an increase of 12 on the previous quarter, which is typical for a two-person household.
This statement confirms the occupier of record at the address shown above for the whole of the billing period. It may be presented where confirmation of residence is required.
Wastewater and surface water drainage are included in the standing charge. If the property has been disconnected from surface water drainage you may be entitled to a rebate.
Payment is due within 21 days. To spread payments across the year, contact us about a monthly plan.'
WHERE id = 'a0000001-0000-4000-8000-000000000001';

UPDATE documents SET content =
'United Kingdom of Great Britain and Northern Ireland. Passport number 533••••21. Type P. Code GBR.
Surname DOE. Given names JOHN ALBERT. Nationality BRITISH CITIZEN. Date of birth 14 JUN 1958. Place of birth SHEFFIELD. Sex M.
Date of issue 02 SEP 2021. Date of expiry 02 SEP 2031. Authority HM PASSPORT OFFICE.
The holder is entitled to enter and remain in the United Kingdom without let or hindrance. The document establishes the identity and nationality of the person named above.
This is a certified scan of pages 2 and 3 taken during the client onboarding meeting on 4 February 2026. The original was inspected in person and returned to the client.
The machine readable zone has been redacted from this copy. Endorsements: none.'
WHERE id = 'a0000002-0000-4000-8000-000000000002';

UPDATE documents SET content =
'Projected income from a self-invested personal pension held in flexi-access drawdown.
Current fund value GBP 412,000 as at 31 March 2026, invested 45 percent global equities, 35 percent index-linked gilts, 20 percent short-dated corporate bonds.
Assumed growth 4.5 percent per annum net of charges. Annual management charge 0.42 percent. Adviser charge 0.50 percent.
Annual withdrawal GBP 18,000, increasing each year in line with the Consumer Prices Index. Twenty-five percent of the fund was taken as a tax-free lump sum at crystallisation in 2023.
Under the central projection the fund is exhausted at age 91. Under the lower projection of 2.5 percent growth it is exhausted at age 84, which is within the client stated planning horizon and was discussed at the annual review.
Sequence of returns risk is material in the early years of drawdown. A cash buffer of two years expenditure is recommended to avoid crystallising losses in a falling market.'
WHERE id = 'a0000003-0000-4000-8000-000000000003';

UPDATE documents SET content =
'London Borough of Islington. Council tax demand for the financial year 2026/27. Property reference 4471902. Valuation band E.
Annual charge GBP 2,148.66, comprising the borough precept, the Greater London Authority precept and the adult social care precept.
Payable in ten monthly instalments of GBP 214.87 commencing 01 April 2026. Direct debit is the preferred method.
The liable person named on this demand is recorded as resident at the dwelling from 06 April 2026 and is solely liable for the charge.
A single person discount of 25 percent has not been applied. If you are the only adult resident at this address you may be entitled to claim it.
Appeals against the banding of the property are made to the Valuation Office Agency, not to the billing authority.'
WHERE id = 'a0000004-0000-4000-8000-000000000004';

UPDATE documents SET content =
'Meridian Capital Ltd. Business current account. Sort code 20-••-••. Account ending 4471. Statement period 1 February to 29 February 2026.
Opening balance GBP 88,204.12. Total credits GBP 141,880.00 across 11 receipts. Total debits GBP 96,340.55 across 34 payments. Closing balance GBP 133,743.57.
Largest receipt GBP 64,200.00 from Harbour Logistics on 12 February, being settlement of invoices 2291 and 2294.
Recurring debits include payroll of GBP 41,880.00 on 25 February, a commercial lease of GBP 6,400.00 on the first working day, and corporation tax on account.
The account holder has held this account since March 2019 and the registered address on file matches the correspondence address for the company.
Arranged overdraft facility GBP 25,000, unused throughout the period. Interest paid on credit balances at 1.75 percent AER.'
WHERE id = 'a0000005-0000-4000-8000-000000000005';

UPDATE documents SET content =
'Subscription for ordinary shares under the Enterprise Investment Scheme.
Amount subscribed GBP 50,000 for 25,000 ordinary shares of GBP 0.01 each, representing 3.1 percent of the issued share capital following the round.
The company has received advance assurance from HM Revenue and Customs that it expects to be a qualifying company. Advance assurance is not a guarantee that relief will be given.
Income tax relief of 30 percent is available on the amount subscribed, subject to the investor having sufficient liability. Relief may be carried back to the previous tax year.
Shares must be held for three years from the date of issue, or from the commencement of trade if later, otherwise relief is withdrawn.
Disposal after the qualifying period is free of capital gains tax. Loss relief is available against income if the company fails. The investment is illiquid and there is no secondary market.'
WHERE id = 'a0000006-0000-4000-8000-000000000006';

UPDATE documents SET content =
'Driver and Vehicle Licensing Agency. Licence number MACLE••••9AJ. Surname MACLEOD. First names ALASDAIR JAMES.
Date of birth 03 NOV 1971. Place of birth PERTH. Issued 11 JAN 2019 by DVLA Swansea. Valid until 11 JAN 2029.
Entitlement categories B, BE, F, K and provisional entitlement for category A.
The residential address recorded against this licence is Glenbank Farm, Perthshire PH2 9QR, and has been unchanged since issue.
There are no current endorsements or disqualifications recorded. The photocard must be renewed every ten years even where the entitlement runs longer.
A copy was taken at the review meeting and verified against the original. The holder is required to notify the agency of any change of address or of a relevant medical condition.'
WHERE id = 'a0000007-0000-4000-8000-000000000007';

UPDATE documents SET content =
'Advice note on the inheritance tax treatment of working farmland held by the client.
Agricultural property relief is available at 100 percent where the land has been occupied by the transferor for agricultural purposes for two years prior to transfer, or owned for seven years where occupied by another.
The land in question extends to 214 acres of arable and permitted grazing and has been farmed in hand since 1998, so the two year occupation test is comfortably met.
The farmhouse qualifies only if it is of a character appropriate to the land. Recent case law has narrowed this considerably and the ratio of house value to land value is the usual point of challenge.
Relief applies to agricultural value only. Any development or amenity value above that is not covered and may instead qualify for business property relief if the trade tests are met.
Succession is complicated by the tenancy granted to the client nephew in 2011, which may restrict vacant possession and therefore the valuation basis.'
WHERE id = 'a0000008-0000-4000-8000-000000000008';

UPDATE documents SET content =
'Pension savings statement for the tax year 2025/26, issued because the pension input amount exceeded the standard annual allowance.
Pension input amount GBP 68,400, comprising employer contributions and the deemed growth in the defined benefit section.
Threshold income GBP 214,000 and adjusted income GBP 289,000, which reduces the annual allowance by GBP 1 for every GBP 2 above GBP 260,000, giving a tapered allowance of GBP 14,000.
Unused allowance carried forward from the three previous tax years totals GBP 21,500 and is applied against the excess in order, oldest first.
An annual allowance charge arises on the remaining excess and is payable at the member marginal rate. Where the charge exceeds GBP 2,000 the scheme may be required to pay it under mandatory scheme pays.
Electing for scheme pays reduces the eventual benefits and the election must be made by 31 July following the tax year.'
WHERE id = 'a0000009-0000-4000-8000-000000000009';

UPDATE documents SET content =
'Assured shorthold tenancy agreement for the property at Flat 4, 88 Gower Street, London WC1E 6AA.
Term of twelve months commencing 01 October 2025 and ending 30 September 2026, after which the tenancy continues on a monthly periodic basis unless terminated.
Monthly rent GBP 2,350 payable in advance on the first day of each month. Deposit of GBP 2,712 held in a government approved protection scheme.
The tenant named in this agreement is recorded as occupying the premises from the commencement date and is responsible for council tax and all utility accounts.
The landlord covenants to keep the structure and exterior in repair and to maintain the installations for the supply of water, gas, electricity and sanitation.
Subletting is prohibited without written consent. An inventory was agreed at check-in and forms part of this agreement.'
WHERE id = 'a000000a-0000-4000-8000-00000000000a';

UPDATE documents SET content =
'Completed questionnaire on domicile and residence for the tax year 2025/26.
The client arrived in the United Kingdom on 14 November 2025 and has been resident for part of one tax year only. Prior to arrival the client was resident in Poland for eleven consecutive years.
Under the statutory residence test the client meets the third automatic UK test through full time work. Days spent in the United Kingdom during the period were 138, recorded in the schedule overleaf.
The client retains a domicile of origin outside the United Kingdom and has not formed an intention to remain permanently or indefinitely.
Foreign income and gains for the period total the equivalent of GBP 96,000 and remain unremitted. A separate nominated account has been opened to avoid mixed fund problems.
The remittance basis may be claimed without charge for the first seven years of residence. Claiming it forfeits the personal allowance and the capital gains annual exempt amount, which is not worthwhile at current income levels.'
WHERE id = 'a000000b-0000-4000-8000-00000000000b';

UPDATE documents SET content =
'Minutes of the quarterly investment committee held on 18 March 2026. Present: four members, quorate. Apologies received from one member.
The committee reviewed performance for the twelve months to 29 February. The portfolio returned 6.4 percent against a composite benchmark of 5.9 percent, with the majority of the excess attributable to the underweight in long duration gilts.
Resolved to reduce the allocation to open ended property funds from 8 percent to 3 percent following continued redemption pressure across the sector and the resulting risk of dealing suspension.
Direct equity holdings are to be reviewed against the concentration policy, which limits any single holding to 5 percent of the portfolio. Two holdings currently breach this and are to be trimmed over the next two quarters.
The committee noted the revised charging structure proposed by the custodian and asked for a comparison against two alternative providers before the June meeting.
Next meeting scheduled for 17 June 2026.'
WHERE id = 'a000000c-0000-4000-8000-00000000000c';

UPDATE documents SET content =
'This is the last will and testament of the testator, made this eleventh day of January two thousand and twenty six.
I revoke all former wills and testamentary dispositions made by me and declare this to be my last will.
I appoint my daughter and the partners at the date of my death in the firm named below to be the executors and trustees of this my will.
I give free of tax the sum of twenty thousand pounds to each of my grandchildren living at my death and attaining the age of twenty five years.
I give my residuary estate to my children in equal shares absolutely, and if any child predeceases me leaving issue then that share passes to that issue per stirpes.
I declare that my trustees shall have power to appropriate any asset in or towards satisfaction of any share without the consent of any beneficiary.
A letter of wishes accompanies this will and is not legally binding upon my trustees.'
WHERE id = 'a000000d-0000-4000-8000-00000000000d';

-- Force regeneration of vectors, passages and summaries.
UPDATE documents SET embedding = NULL, summary = NULL;
DELETE FROM document_chunks;
