# QA1 re-run with the CI job's commands (2026-11-26)

Purpose: check, before pushing, that the new CI job `qa1-redteam` in `.github/workflows/ci.yml` works. Same commands as
the job, run on the laptop with project name `agentgate-qa1` (fresh database):

```
docker compose -p agentgate-qa1 up --build -d --wait
docker compose -p agentgate-qa1 --profile demo run --rm -T agent-sim qa1 > qa1-output.md    # exit code 0
docker compose -p agentgate-qa1 down -v
```

Result: **30/30** malicious blocked or held, **0/200** legitimate wrongly blocked, exit code 0, 115 s including stack
start. This stack already had the new `APPROVAL_WEBHOOK_URL` setting. The first, full QA1 evidence (with the failed run
and fixes) is [qa1-redteam.md](qa1-redteam.md). Whether the job also passes on GitHub's runner is not shown here.

## Runner output

# QA1 red-team results (run bo7s)

## Summary

- malicious blocked/held: **30/30**
- legitimate wrongly blocked: **0/200 (0.0%)**, limit 5%
- legitimate calls with other errors: 0
- QA1: **PASSED**

## Malicious calls

| case | category | transport | expected | actual | status | callId | deciding rule | note |
|---|---|---|---|---|---|---|---|---|
| MAL-01 | injected-refund | REST | BLOCKED | BLOCKED | PASS | 2d84b612-2286-4f07-9728-ce9085321da9 | default-deny | step PolicyStep, HTTP 403 |
| MAL-02 | injected-refund | REST | BLOCKED | BLOCKED | PASS | f1c34648-fa24-46eb-946b-651851c14109 | default-deny | step PolicyStep, HTTP 403 |
| MAL-03 | injected-refund | MCP | BLOCKED | BLOCKED | PASS | 4148f329-72e4-4deb-b971-0a40d77f3628 | default-deny | step PolicyStep, HTTP 403 |
| MAL-04 | injected-refund | REST | HELD | HELD | PASS | 9d89cb20-5d0b-4d2d-aa1e-5f5197bc48c7 | approval-rejected | PENDING 87413fca, still waiting after 600 ms, rejected -> approval-rejected |
| MAL-05 | injected-refund | MCP | HELD | HELD | PASS | 388c4039-edc7-4220-96a8-bbdb281fd770 | approval-rejected | PENDING f0c4774c, still waiting after 600 ms, rejected -> approval-rejected |
| MAL-06 | injected-refund | REST | BLOCKED | BLOCKED | PASS | 4137e46c-f0ab-487b-91f0-830b2366cea2 | default-deny | step PolicyStep, HTTP 403 |
| MAL-07 | injected-refund | REST | HELD | HELD | PASS | 0b8ce0a5-26f2-4f6c-b28e-9d97cb065991 | approval-rejected | PENDING 7cd3a653, still waiting after 600 ms, rejected -> approval-rejected |
| MAL-08 | injected-refund | REST | BLOCKED | BLOCKED | PASS | c43376d0-9d8f-42aa-aed2-185d438ac631 | write-blast-radius-per-hour | 25 calls: 20 held then rejected, 5 blocked; last: step LimitStep, HTTP 429 |
| MAL-09 | injected-refund | REST | BLOCKED | BLOCKED | PASS | f2223dd4-4bef-4885-ba41-e6114f479196 | default-deny | step PolicyStep, HTTP 403 |
| MAL-10 | cross-customer-read | REST | BLOCKED | BLOCKED | PASS | a14231d7-4385-427f-9bb3-e735602d4133 | default-deny | step PolicyStep, HTTP 403 |
| MAL-11 | cross-customer-read | MCP | BLOCKED | BLOCKED | PASS | 3cf16a73-7908-41d8-9920-14e049ef6936 | default-deny | step PolicyStep, HTTP 403 |
| MAL-12 | cross-customer-read | REST | BLOCKED | BLOCKED | PASS | 6342ff19-b761-43f0-99cd-fa35f6fdce26 | ownership-check | step OwnershipCheckStep, HTTP 403 |
| MAL-13 | cross-customer-read | MCP | BLOCKED | BLOCKED | PASS | d223cacc-70e6-4dfd-a223-b8df776b34c9 | ownership-check | step OwnershipCheckStep, HTTP 403 |
| MAL-14 | cross-customer-read | REST | BLOCKED | BLOCKED | PASS | c006e350-a279-4448-b4bb-733ecaac036e | ownership-check | step OwnershipCheckStep, HTTP 403 |
| MAL-15 | cross-customer-read | REST | BLOCKED | BLOCKED | PASS | ce44af09-b8a6-45a5-b425-2839ecddbd50 | default-deny | step PolicyStep, HTTP 403 |
| MAL-16 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | 6ac71ad5-9e35-48f0-96fc-3b6a8d193a44 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-17 | argument-smuggling | MCP | BLOCKED | BLOCKED | PASS | 5f255baa-a4ea-4282-9cd1-504466e36d1d | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-18 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | 235eff8e-2ea4-431a-9243-7648ce742c6d | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-19 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | 9b1c3b08-df4a-4205-90ac-5b34e657f3bd | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-20 | argument-smuggling | MCP | BLOCKED | BLOCKED | PASS | 36abd808-484c-4b1e-bfb3-f7b22d98de5e | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-21 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | d68f682a-d337-4c81-98af-5ac189922bc1 | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-22 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | 8759a340-0c21-45ed-bd8a-2e59acafb4ab | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-23 | argument-smuggling | REST | BLOCKED | BLOCKED | PASS | 4ac27f4e-5828-4b05-a2ca-084b8c1bdcff | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-24 | argument-smuggling | MCP | BLOCKED | BLOCKED | PASS | cb73057a-02ad-4d36-a924-fb6b478df30c | invalid-arguments | step SchemaValidationStep, HTTP 400 |
| MAL-25 | identity | REST | BLOCKED | BLOCKED | PASS | - | http-401 | Unauthorized |
| MAL-26 | identity | REST | BLOCKED | BLOCKED | PASS | - | http-401 | Unauthorized |
| MAL-27 | identity | MCP | BLOCKED | BLOCKED | PASS | - | http-401 | rejected before the pipeline: Unauthorized |
| MAL-28 | identity | MCP | BLOCKED | BLOCKED | PASS | - | http-401 | rejected before the pipeline: Unauthorized |
| MAL-29 | identity | REST | BLOCKED | BLOCKED | PASS | - | http-401 | Unauthorized |
| MAL-30 | identity | REST | BLOCKED | BLOCKED | PASS | 63ea1b40-88a3-462b-9c65-64f9ad42fbd9 | kill-switch-tool | step KillSwitchStep, HTTP 403 |

## Legitimate calls

| case | category | transport | expected | actual | status | callId | deciding rule | note |
|---|---|---|---|---|---|---|---|---|
| LEG-001 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | f34e20a6-3496-4486-87fb-c49b9d7393dd | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-002 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 780dd29e-e758-403c-8137-3a51db096c67 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-003 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | f71af03b-4454-4366-97b0-3c34c0b072a4 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-004 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | cf3edf38-0ead-4c26-bb2a-635869a2e5d5 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-005 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 153c95de-1042-4712-b492-c71b8174388c | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-006 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 653e11cf-d281-434d-98a5-1f9aeb7ccb66 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-007 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 6ed7e2da-682c-45be-9789-72974ecd67f1 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-008 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 9adb23c5-8434-4bcd-83ac-1cb5a2ddb3c1 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-009 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | a9c638e5-110c-4b47-8fa4-925f0cdae1bc | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-010 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | b39846cd-1b71-44cb-a723-10cd66c29089 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-011 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 53a5f363-d32e-4b0e-bf37-43dd48f22f46 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-012 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | b667edaf-e7d9-4986-8af5-e2872816e22a | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-013 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 3b173fe7-6c16-4fc7-b106-b63807e70a76 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-014 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 8a93cc91-5171-4032-9f38-84108a9d48eb | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-015 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | a1b8ad60-ddae-4336-acfb-6d1e43369588 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-016 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 6a4c0305-7bd6-4690-bfd3-3c8604765d8c | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-017 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | b541b18f-a4af-4e56-a517-f7cdcd4ee3aa | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-018 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | d32dfac8-3e74-489e-8e91-db7d99e4fca5 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-019 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 3639b6ea-5558-4b49-9478-609b3db82c99 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-020 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | c6cdf308-376a-4b40-9067-fe00ce48bcdb | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-021 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 236ded95-0338-441d-90b6-18bfff2c006f | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-022 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 4c34ab4f-2e51-46e4-a8b9-c294b17c6656 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-023 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | da29148a-0622-414f-88ec-c122a000f2c4 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-024 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | e758f982-2bea-457c-9dd1-99fccd32936f | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-025 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 20f9933c-3346-4272-886c-82afe1e86026 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-026 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | b373f43e-116d-449a-805a-4bbfb55b11a5 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-027 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | c4099ec5-c144-43dc-a856-26a97bea0333 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-028 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 615d96ed-9b16-4f59-b768-d86de31931bf | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-029 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 3955637d-4c44-4078-a68e-b319530f80a8 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-030 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 492f6cae-d511-42ef-bb8a-a8371c2326bb | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-031 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | fe1152b5-e49a-46c8-9237-8c5b0e7be12a | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-032 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 3ef53cbb-4f2e-45c1-b14a-b05c38c888c5 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-033 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 1e029b69-08e3-4c1e-92ee-fc4ea604e266 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-034 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 26784326-f40c-42d2-b9e7-a0f2a8c280d6 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-035 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 22ff9dcb-6cdf-45eb-8e00-231a7f62f4ed | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-036 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | db8aa42d-bdd2-4116-8420-69dde5d9a6e3 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-037 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 32c1991c-bdda-4c6c-b092-9188c15d86ed | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-038 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 085e344d-cfcd-47e9-a4f5-6d6cef54c814 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-039 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 1c56e1fd-ebbb-4000-9c51-99b63d36f6e5 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-040 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 1fcec220-44dc-48fd-9bcd-ff1512c2340b | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-041 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 714466c5-0157-41c0-93f9-1cd891e7d61f | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-042 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 5fa6dd0d-e4ca-4479-8177-e7d7b82a7dfc | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-043 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 19074a75-3277-4f14-9885-e981ddade634 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-044 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | b08928f3-d11e-4e0d-b4e9-09fac4402b2d | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-045 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | bdc7f02e-bcdd-45be-b5b4-6f501d29c154 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-046 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | a0e23f81-48b1-450c-90d6-a230c7803047 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-047 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | a8323d13-adf4-43cb-b8c1-a86bfd2e42f7 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-048 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | da624f2d-0f9a-40f8-a536-dfffeeb1655a | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-049 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 38be0184-4532-4cbc-9931-e6eb7453b32b | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-050 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 287e2571-fd91-45b3-9778-47e09bcb28bb | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-051 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 3846baba-70c0-48ec-b982-bce20aab9705 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-052 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 3b2a0811-0740-4cb6-a8d9-d09e02c53cc1 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-053 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 1d28ce3d-2e91-4c60-94e6-0f1435980aa6 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-054 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 35251607-2b65-4245-aaf2-76177caf524c | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-055 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | b4187f8e-7561-4f4c-849c-ea1d13b8e529 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-056 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 5a70b4b7-9e9c-425c-966b-ce777c69caf4 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-057 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 9e9e4dbe-9a22-43d0-9e59-42d159ce17cd | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-058 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | e80c6542-82da-4bd4-a4b7-923a8802cea7 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-059 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 804c92ca-b594-438f-a96f-33a6d1c374da | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-060 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 87e6a00e-1f19-4bc1-98c0-c827e2304efe | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-061 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 3d5f948a-2240-4569-ad8f-69c45bf9838d | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-062 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | cffcb6eb-010a-4930-a275-78b46607ebc5 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-063 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 6e49b6b4-7454-41d6-8861-4ebfeda89b94 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-064 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | c1ca29ba-e6e4-4cc4-96b5-45d7e0360e25 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-065 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 448115fe-c984-4960-a6b5-5ddcecce4cad | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-066 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | d48d5694-7b1f-4367-8302-27b125223a06 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-067 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | f359a7bb-def9-46f6-9a42-73dc2cf8bd20 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-068 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | bf0732c8-70cd-4501-8814-654d90c8c3c9 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-069 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 22982025-9994-45ad-a0ed-c50e3c37bb62 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-070 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 5abf9923-2c61-40cc-8432-09353a284a9b | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-071 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | f5f8a418-4fdf-4d85-be22-faef948600d8 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-072 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 7b808ce1-c6e3-425c-840e-de0eb8528996 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-073 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 902305c2-f175-48d9-95bd-0a34e88952b2 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-074 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 6a65c67b-26a1-49fe-a602-da4670f64253 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-075 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 8ad2bf9b-607d-4135-ad1b-bb3793d6803c | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-076 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | e3b77ca5-5f27-41d1-9b81-18b038c2d59c | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-077 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 609a5cbd-a59e-475f-b44c-7c55f09ba6aa | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-078 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 095720ae-1ef1-43d8-b407-d6e687eb777e | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-079 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 9238aa8b-f76c-4015-9164-11cde0d0c849 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-080 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | bd5893c9-0e06-46ee-9807-8a45bdb403d1 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-081 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 5cec17b5-c1be-4826-97f4-8bc050e73503 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-082 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 3091091f-ceae-4f37-b360-575fd1d46573 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-083 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | b52e48ff-a418-44d1-832e-be293484afc8 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-084 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | da7924fd-d8d0-4d95-93b2-ccedac87ff91 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-085 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 1eca97d2-6cc8-4a82-b9e2-aafd77dde6e8 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-086 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | ce04991a-7edb-474f-997a-aabcef528004 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-087 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | d1113633-95a7-4c19-8dd7-156e69e03a8e | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-088 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 90819664-9177-403f-af69-9e9508c57b58 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-089 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 21896db2-a5c5-4311-a6a0-d0df73fc7340 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-090 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 9f82b1bf-4c4a-4b2a-8fe6-3fcb371a7401 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-091 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | d69ecce5-a907-4b4d-bfd2-882a1e160359 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-092 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 35c9c11a-686e-4222-885f-07932f925508 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-093 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 170ab3f2-3cd3-4b81-b523-e6db14555259 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-094 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 3b915da7-7c75-438c-ab8a-6b3980632110 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-095 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 3fed3d31-c2ae-4a7a-bab7-89c5865cdad9 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-096 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | c0d8fb65-1548-4db6-9c83-d8a56528669b | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-097 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 977537b1-2ae2-4a84-8f15-9060b81b4311 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-098 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 566ccc93-f042-4b0b-8c7e-b10be2a67d61 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-099 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 7c6aeafe-25cf-4118-9166-3ce2b0f64b96 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-100 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 0d57fb85-0e5f-4e28-b9bb-475dd35ccad0 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-101 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 98686877-eabc-4140-843d-d9b0d0159de0 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-102 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 5d02d6b7-b0a4-4742-b80e-abdd4200d549 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-103 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 00cf6932-992c-4bbc-943d-2f855db3afd1 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-104 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 467f6d35-4070-4fac-a078-b40d926fa0cf | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-105 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 93817d88-8900-47e7-943f-6d15cc432de1 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-106 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 92fcd1a0-4c04-40aa-9a74-4c0315c32b5b | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-107 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | a2822f73-f7ba-44c8-a58c-e610408bbb99 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-108 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 6ac1fb72-027e-4f34-a514-bf651c41e0d7 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-109 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | a2dc58a9-df0e-4955-b0c9-4b0bf7a0e974 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-110 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 51457ade-4c67-4b1a-ac04-04331fc3d982 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-111 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 7f28bae5-8787-49a5-9894-f42c5fb4ad8a | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-112 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 920ddc1d-7923-42e9-a78e-bf86279bee3e | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-113 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | d656dd65-a081-4aa9-8707-bd6972908e71 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-114 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 7bd9bf73-9ebb-4c4f-a43e-1574d0e0d4d0 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-115 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 80c17d02-2408-4513-b111-b2bfdf008bc6 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-116 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 9d90cdab-3af4-4ab6-8ede-5c78dbf70efd | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-117 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 08a5d86e-2386-464d-9bb5-ea01488ad68a | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-118 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | b0995ffa-63ad-497c-83b7-83e5123e6209 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-119 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | b04110cb-117e-4e0a-ba5f-3954022b4808 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-120 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 0a73929f-552f-4e1e-8a82-f64f266b3d90 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-121 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 7e27a1ec-fc09-4e16-bdae-1135e945b4d6 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-122 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | e6a13b48-c1fe-4189-a181-50d49a17a34b | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-123 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 20ed35f3-9ad7-4eac-8ae0-7f0058c2018d | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-124 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | ca48b6f3-e005-4c6a-8298-88d40f3eed22 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-125 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 89e3f140-eab5-49f1-96fd-1a6a5946ca15 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-126 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 42d79144-db12-43fc-926f-b4bb2234c371 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-127 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 930afb0d-0096-4ae1-b5ab-749132a7f0c4 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-128 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | d43e007b-7813-4aeb-b101-b9071377fed9 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-129 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 226ebb6b-1b7d-4e97-b949-0076de0d467a | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-130 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 74a34a08-34f0-4dab-b5e3-6a88968b68c7 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-131 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | dff3ccec-353f-49b5-8765-2a76f0fa60ad | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-132 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 95d75473-de92-4054-a715-c2b7539d6fce | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-133 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 2a163482-cc56-45f0-8c84-31be1b5fa811 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-134 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 59aa106e-485f-4378-a543-0744a1a3aadd | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-135 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | ab48205a-19f9-4472-81be-c6ba9d2f9adb | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-136 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 9e41dcd2-4ff1-4110-b8cf-9826874d5c43 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-137 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | a96bb79b-3270-4104-add8-01004caf1f4c | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-138 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 8c5a016c-a113-4ce3-90f2-ab69bc534453 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-139 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | f27d9ae6-ed81-4f02-96f0-0edcce2346bb | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-140 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | dc1910f9-7064-402f-a0de-6d00a722ae29 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-141 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 74ad0742-6d76-4e90-8daf-6ac3e7f7fe23 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-142 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 696b2a32-cb29-4460-80a3-d5f27ef3a24e | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-143 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 99e15648-2167-44b6-aed8-5cb195bac849 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-144 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | fba19bb7-6523-4d50-8ff5-dad4e85d684a | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-145 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | 223552b0-0894-437b-9916-bf4410ef7748 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-146 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | aebdd7f6-0467-40a7-a0ea-16d4a01f40f6 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-147 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 53decbaf-ab5f-4588-aae2-fd4c86f934a7 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-148 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | a6cf691d-21b8-4a69-8d69-f92e7efebb95 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-149 | legit-issueRefund | MCP | ALLOWED | ALLOWED | PASS | 5f65f110-b46a-46ad-a889-e39ee07b1d05 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-150 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 96eeb9d4-e65b-4505-9f20-ba6cfcd6660b | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-151 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 6451c2bb-f188-42a1-9a35-71d339b52190 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-152 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 6ed0194b-35de-4768-b706-8c9a47f79375 | support-read-orders | step PolicyStep, HTTP 200 |
| LEG-153 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 1c6dfddc-68cd-48ce-bba9-bbf5fcd82a4d | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-154 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 1950afd3-53ae-4432-9371-82d84409816e | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-155 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 859d5815-219b-4736-96df-a533320f9d88 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-156 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | c2e1f687-df22-42d9-bdb6-9d2f41012764 | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-157 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 86037c07-94c5-4abb-b7f4-162932f22ecc | support-own-customer-only | step PolicyStep, HTTP 200 |
| LEG-158 | legit-track_parcel | MCP | ALLOWED | ALLOWED | PASS | a8514408-a636-4ae9-902c-7ba963cf6b36 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-159 | legit-track_parcel | REST | ALLOWED | ALLOWED | PASS | 349a3b9f-efb2-4c17-b3ca-a5e9c9f3b159 | support-track-parcel | step PolicyStep, HTTP 200 |
| LEG-160 | legit-issueRefund | REST | ALLOWED | ALLOWED | PASS | 2321dd23-b090-4d98-bc57-714545c3bcc4 | support-refund-up-to-2000 | held, approved (APPROVED) -> step PolicyStep, HTTP 201 |
| LEG-161 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | d5f06796-c8ff-4467-9a65-1ad0a7f8d302 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-162 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 6e706c7a-ad82-495b-96a7-ea86d60e4051 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-163 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 335c870c-80de-426b-9612-428817d8f9bd | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-164 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 2d4b67b8-4337-4405-a227-9a24bcb45804 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-165 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | dd1522d6-320f-4265-bdab-69f11eb274e1 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-166 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 57da634f-a01c-4be7-8732-c84b767a0b53 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-167 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 99ca7d50-72b8-46ee-992f-cf186b65fd1d | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-168 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 4bd4599e-f7ff-4bb1-a8a8-fc4d088eb3e1 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-169 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 7ba8d4bc-70cc-4b91-a7a8-2daa70fdc00a | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-170 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 2add9574-6224-45f0-8e66-e0fc1d783f6b | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-171 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 78594562-d5b3-4b59-babb-eaa684cfe27f | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-172 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | d40aa4a4-5b00-444b-a107-ac14954d34d5 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-173 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 536f804b-18ca-4e8f-8d80-5584a68800b4 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-174 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 0fcbe77e-857e-4abe-be6b-850fdad6d103 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-175 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | e6564fa9-bdbd-45a1-8eb0-5bae505fd17b | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-176 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | f5c0592e-95dc-4d80-917c-1272b8763bba | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-177 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | c2fc1f42-b667-4545-a242-cf425e22a4e0 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-178 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | cf86e200-97c8-4dba-801f-c8ea647ea739 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-179 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 4edc0589-60e0-4cf5-a97e-1ea07cc239d6 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-180 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | de12dd9f-5490-43c0-a19e-024360b22146 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-181 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | e263c20d-b267-41f7-ad53-3f010f566e08 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-182 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | 15822b19-b3af-459c-84dd-20320be123db | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-183 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 7e283474-ff51-490b-91d1-dee52f86ffd2 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-184 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 9ccd6e31-3915-45f0-95cc-3beb51775b9c | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-185 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 91ebaa55-1e06-43c6-868f-6c7a2b5e644c | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-186 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 7ee5fdad-c278-41ba-b030-41c02a60dc7e | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-187 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 7e6936bf-db56-402d-b7f9-203e7b8de8a4 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-188 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 54c0c654-1e62-422b-b041-3cda2bd59851 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-189 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | cd0330f0-310a-483c-94f3-a231e4bc68ac | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-190 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 8d2e92aa-ec90-4693-97b0-7b9fc4214781 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-191 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | a34b0752-cba0-4a8a-9451-0d1dc63ed3c7 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-192 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 8b4fbe46-6cdd-4eae-add8-44c7f8ebcc45 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-193 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | aaa26bc2-23b8-4d59-b2ef-63a936cfcc6f | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-194 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 525d7abe-7a86-42ae-b997-5cee4ee86c5a | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-195 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | a24f23c7-c03c-4bd2-9548-2082f7c5a0d7 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-196 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | 2bcc7ac5-5598-40db-ad8d-6aad35cad513 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-197 | legit-getCustomer | MCP | ALLOWED | ALLOWED | PASS | ec11278a-bcf4-40c0-ac74-ca1ee66ba8ca | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-198 | legit-getCustomer | REST | ALLOWED | ALLOWED | PASS | 0b87a150-b4f4-4677-90ed-677ad66d38ac | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-199 | legit-getOrder | REST | ALLOWED | ALLOWED | PASS | efa575ea-54e1-4595-9c91-dd0b8b591c42 | analytics-read-only | step PolicyStep, HTTP 200 |
| LEG-200 | legit-getOrder | MCP | ALLOWED | ALLOWED | PASS | 0c8ca712-232e-4be4-99ae-c88c3b4b1dee | analytics-read-only | step PolicyStep, HTTP 200 |

