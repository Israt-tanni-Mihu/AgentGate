# AI usage log (C11)

## Tools used

| Tool | Used for |
|---|---|
| Claude Code (Claude Pro) | Writing and running code, tests, debugging |
| Claude (chat) | Planning, explanations, documentation review |

## Key prompts

| Date | Task | Prompt (short) |
|---|---|---|
| | | |

## Cases where the AI was wrong or incomplete (need at least 3)

### Case 1
- - **Date:** 2026-11-23
- **What I asked:** Build mock-tools endpoints and give me commands to test them
- **What the AI produced:** A PowerShell error-check command using $_.ErrorDetails.Message
- **What was wrong / missing:** In Windows PowerShell 5.1 it returns empty, so the error body was not shown
- **How I found it:** Running the command against the real running server
- **How it was fixed:** Read the HTTP response stream with a StreamReader instead
- **What I learned:** AI-written commands must be tested on my own system before trusting them

### Case 2
- **Date:**
- **What I asked:**
- **What the AI produced:**
- **What was wrong / missing:**
- **How I found it:**
- **How it was fixed:**
- **What I learned:**

### Case 3
- **Date:**
- **What I asked:**
- **What the AI produced:**
- **What was wrong / missing:**
- **How I found it:**
- **How it was fixed:**
- **What I learned:**
