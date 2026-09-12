-- Demo data, applied everywhere including production: this deployment is a demo and is
-- expected to have content.
--
-- Fixed UUIDs and ON CONFLICT DO NOTHING so the file is safe to re-apply by hand.
-- Flyway also wraps and records it, so it runs once per database.
--
-- embedding is left NULL — nothing embeds documents yet. Lexical client search works
-- immediately; semantic document search stays empty until embedding lands.

INSERT INTO clients (id, first_name, last_name, email, description, social_links) VALUES
  ('11111111-1111-4111-8111-111111111111', 'John', 'Doe',
   'john.doe@neviswealth.com',
   'Retired mechanical engineer. Cautious, income-focused. Drawing down a SIPP and wants to keep capital intact for his grandchildren.',
   ARRAY['https://www.linkedin.com/in/johndoe']),

  ('22222222-2222-4222-8222-222222222222', 'Priya', 'Raghunathan',
   'priya.r@meridiancapital.co.uk',
   'Founder of a logistics business, mid-forties. High earner, irregular income, interested in VCT and EIS allowances.',
   ARRAY['https://www.linkedin.com/in/priyaraghunathan']),

  ('33333333-3333-4333-8333-333333333333', 'Alasdair', 'MacLeod',
   'a.macleod@neviswealth.com',
   'Farm owner in Perthshire. Land-rich and cash-poor; succession planning and agricultural property relief are the live questions.',
   ARRAY[]::text[]),

  ('44444444-4444-4444-8444-444444444444', 'Beatrice', 'Okonkwo',
   'beatrice.okonkwo@harbourtrust.com',
   'NHS consultant approaching the annual allowance taper. Needs pension input period modelling and carry-forward advice.',
   ARRAY['https://www.linkedin.com/in/bokonkwo']),

  ('55555555-5555-4555-8555-555555555555', 'Tomas', 'Nowak',
   'tomas.nowak@example.org',
   'Recently relocated from Krakow. Non-domiciled, holds property abroad, first meeting scheduled.',
   ARRAY[]::text[]),

  ('66666666-6666-4666-8666-666666666666', 'Eleanor', 'Whitfield',
   'eleanor@whitfield-family-office.com',
   'Second-generation family office. Sits on the investment committee and prefers direct equities over funds.',
   ARRAY['https://www.linkedin.com/in/ewhitfield'])
ON CONFLICT (id) DO NOTHING;

-- Documents. Deliberately worded the way real paperwork is: none of them contains the
-- phrase "address proof" or "identity verification", so those queries only ever succeed
-- through semantic similarity rather than by accident.
INSERT INTO documents (id, client_id, title, content) VALUES
  ('a0000001-0000-4000-8000-000000000001', '11111111-1111-4111-8111-111111111111',
   'Utility Bill - March 2026',
   'Thames Water. Account 8891234. Service address: 12 Acacia Avenue, London N1 4TG. Billing period 01-31 March 2026. Balance carried forward GBP 42.10. This statement confirms the occupier at the property above.'),

  ('a0000002-0000-4000-8000-000000000002', '11111111-1111-4111-8111-111111111111',
   'Passport Scan',
   'United Kingdom of Great Britain and Northern Ireland. Passport number 533••••21. Surname DOE, given names JOHN ALBERT. Date of birth 14 JUN 1958. Place of birth SHEFFIELD. Date of expiry 02 SEP 2031. Machine readable zone omitted.'),

  ('a0000003-0000-4000-8000-000000000003', '11111111-1111-4111-8111-111111111111',
   'SIPP Drawdown Illustration',
   'Projected income from a self-invested personal pension in flexi-access drawdown. Fund value GBP 412,000. Assumed growth 4.5 percent net of charges. Annual withdrawal GBP 18,000 rising with CPI. Fund exhaustion projected at age 91 under the central scenario.'),

  ('a0000004-0000-4000-8000-000000000004', '22222222-2222-4222-8222-222222222222',
   'Council Tax Bill 2026/27',
   'London Borough of Islington. Council tax demand for the year 2026/27. Property band E. Annual charge GBP 2,148.66 payable in ten instalments. Liable person named below is recorded as resident at this dwelling from 06 April 2026.'),

  ('a0000005-0000-4000-8000-000000000005', '22222222-2222-4222-8222-222222222222',
   'Bank Statement - Business Current Account',
   'Meridian Capital Ltd. Sort code 20-••-••, account ending 4471. Statement period 1 February to 29 February 2026. Opening balance GBP 88,204.12. Credits GBP 141,880.00. Debits GBP 96,340.55. Closing balance GBP 133,743.57.'),

  ('a0000006-0000-4000-8000-000000000006', '22222222-2222-4222-8222-222222222222',
   'EIS Subscription Agreement',
   'Subscription for ordinary shares under the Enterprise Investment Scheme. Amount subscribed GBP 50,000. The company has received advance assurance from HMRC. Investor acknowledges shares must be held three years for relief to be retained.'),

  ('a0000007-0000-4000-8000-000000000007', '33333333-3333-4333-8333-333333333333',
   'Driving Licence',
   'DVLA. Licence number MACLE••••9AJ. Surname MACLEOD, first name ALASDAIR JAMES. Issued 11 JAN 2019, valid to 11 JAN 2029. Entitlement categories B, BE, F, K. Residential address recorded as Glenbank Farm, Perthshire PH2 9QR.'),

  ('a0000008-0000-4000-8000-000000000008', '33333333-3333-4333-8333-333333333333',
   'Agricultural Property Relief Note',
   'Advice note on inheritance tax treatment of working farmland. Relief at 100 percent is available where the land has been occupied for agricultural purposes for two years prior to transfer. The farmhouse qualifies only if it is of a character appropriate to the land.'),

  ('a0000009-0000-4000-8000-000000000009', '44444444-4444-4444-8444-444444444444',
   'Annual Allowance Statement',
   'Pension savings statement for the tax year 2025/26. Pension input amount GBP 68,400 against a tapered annual allowance of GBP 14,000. Unused allowance carried forward from the previous three years totals GBP 21,500. An annual allowance charge may arise.'),

  ('a000000a-0000-4000-8000-00000000000a', '44444444-4444-4444-8444-444444444444',
   'Tenancy Agreement',
   'Assured shorthold tenancy for the property at Flat 4, 88 Gower Street, London WC1E 6AA. Term twelve months commencing 01 October 2025. Monthly rent GBP 2,350. The tenant named in this agreement is recorded as occupying the premises from the commencement date.'),

  ('a000000b-0000-4000-8000-00000000000b', '55555555-5555-4555-8555-555555555555',
   'Remittance Basis Questionnaire',
   'Completed questionnaire on domicile and residence. Client arrived in the United Kingdom in November 2025 and has been resident for part of one tax year. Foreign income and gains remain unremitted. Statutory residence test working days recorded overleaf.'),

  ('a000000c-0000-4000-8000-00000000000c', '66666666-6666-4666-8666-666666666666',
   'Investment Committee Minutes - Q1 2026',
   'Minutes of the quarterly investment committee. Resolved to reduce the allocation to open-ended property funds following continued redemption pressure. Direct equity holdings to be reviewed against the concentration policy. Next meeting scheduled for June 2026.'),

  ('a000000d-0000-4000-8000-00000000000d', '66666666-6666-4666-8666-666666666666',
   'Last Will and Testament',
   'I revoke all former wills and testamentary dispositions. I appoint my daughter and the partners of the firm named below to be my executors and trustees. I give my residuary estate to my children in equal shares absolutely.')
ON CONFLICT (id) DO NOTHING;

