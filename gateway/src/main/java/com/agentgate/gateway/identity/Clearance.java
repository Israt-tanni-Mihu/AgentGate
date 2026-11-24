package com.agentgate.gateway.identity;

/** How much sensitive data an agent may see. FR5 redaction will hide emails/phones/NIDs from LOW agents. */
public enum Clearance {
    LOW,
    HIGH
}
